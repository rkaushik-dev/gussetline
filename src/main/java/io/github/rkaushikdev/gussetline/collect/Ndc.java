package io.github.rkaushikdev.gussetline.collect;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.github.rkaushikdev.gussetline.taxes.Taxes;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import io.github.rkaushikdev.gussetline.util.Times;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The offer feed, which is JSON and is not as tidy as that sounds.
 *
 * <p>Four habits of this supplier's payloads, all of them normal in the industry and all of them
 * fatal to a reader that assumes otherwise.
 *
 * <p>A field that holds a list holds a bare object when there is one of them. One segment is an
 * object and two segments are a list of objects, and the shape changes with the data rather than
 * with the schema ({@link #asList}).
 *
 * <p>Amounts arrive as strings on one endpoint and as JSON numbers on another. A JSON number is read
 * as a binary floating point number and a binary floating point number is not an amount, so numbers
 * are read through their shortest exact text and never through their binary value ({@link
 * #amountOf}).
 *
 * <p>Keys moved from camel case to snake case in a version that both endpoints still serve, so every
 * lookup takes both spellings ({@link #pick}).
 *
 * <p>And null is used for absent, for zero and for unknown, so a null is treated as absent everywhere
 * and the caller decides what absent means for that field.
 *
 * <p>The helpers here work on a document as the JSON reader gives it: a {@code Map} for an object, a
 * {@code List} for an array, and a {@code String}, {@code Integer}, {@code Long}, {@code BigInteger},
 * {@code Double} or {@code Boolean} for a value. A field the offer needs as text, a yes or no, or a
 * whole number that holds a value of another kind makes that offer a problem.
 */
public final class Ndc {

    /** The name the harvests and offers from this reader carry. */
    public static final String SOURCE = "ndc";

    /**
     * Reads a payload as one JSON document: one value with nothing after it, the non-finite numbers
     * allowed, every object as a map in the order its keys were written.
     */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private Ndc() {}

    /**
     * A camel case key in snake case: each capital becomes an underscore and its lower case, and
     * underscores at the start are dropped. {@code marketingCarrier} is {@code marketing_carrier},
     * and {@code HTTPStatus} is {@code h_t_t_p_status}.
     */
    public static String snake(String name) {
        StringBuilder out = new StringBuilder(name.length() + 4);
        name.codePoints().forEach(character -> {
            if (Character.isUpperCase(character)) {
                out.append('_').append(Character.toString(character).toLowerCase(Locale.ROOT));
            } else {
                out.appendCodePoint(character);
            }
        });
        int start = 0;
        while (start < out.length() && out.charAt(start) == '_') {
            start++;
        }
        return out.substring(start);
    }

    /**
     * The first of the names an object holds a value under, each name tried as written and then in
     * snake case. A null is absent, and anything that is not an object holds nothing.
     */
    public static Optional<Object> pick(Object document, String... names) {
        if (!(document instanceof Map<?, ?> fields)) {
            return Optional.empty();
        }
        for (String name : names) {
            for (String candidate : List.of(name, snake(name))) {
                Object value = fields.get(candidate);
                if (value != null) {
                    return Optional.of(value);
                }
            }
        }
        return Optional.empty();
    }

    /** A list, whatever shape the supplier used for one of something: nothing for null, one for a value. */
    public static List<Object> asList(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> many) {
            return Collections.unmodifiableList(many);
        }
        return Collections.singletonList(value);
    }

    /**
     * Read an amount without ever going through a binary fraction.
     *
     * <p>A JSON number with a fraction has already been through a binary floating point number by
     * the time it reaches here, so it is rendered back to its shortest exact text and read from that:
     * {@code 420.10} reads as {@code 420.1}. Text is read as a decimal, white space at the ends
     * allowed, and a whole number as itself.
     *
     * @throws MalformedPayload naming the {@code value} when it is not a number at all, rather than
     *     reading it as zero: a yes or no, a null, text that is not a decimal, or a number that is not
     *     finite
     */
    public static BigDecimal amountOf(Object value) {
        return switch (value) {
            case BigDecimal exact -> exact;
            case Integer whole -> BigDecimal.valueOf(whole);
            case Long whole -> BigDecimal.valueOf(whole);
            case BigInteger whole -> new BigDecimal(whole);
            case Double binary when Double.isFinite(binary) -> Numbers.parseDecimal(Numbers.floatText(binary));
            case String text -> {
                try {
                    yield Numbers.parseDecimal(Text.strip(text));
                } catch (NumberFormatException unreadable) {
                    MalformedPayload refusal = new MalformedPayload("not an amount", Details.of("value", text));
                    refusal.initCause(unreadable);
                    throw refusal;
                }
            }
            case null, default -> throw new MalformedPayload("not an amount", Details.of("value", Text.repr(value)));
        };
    }

    /** {@link #moneyOf(Object, String)} with no currency to fall back on. */
    public static Optional<Money> moneyOf(Object document) {
        return moneyOf(document, "");
    }

    /**
     * Read the money shape, which is an amount and a code in one object: the amount under {@code
     * amount} or {@code value}, the code under {@code currencyCode}, {@code code} or {@code currency}.
     * None when it is not an object, has no amount, or has no code and there is none to fall back on.
     *
     * @throws MalformedPayload when the amount is not one ({@link #amountOf})
     * @throws ValidationError when the code is not a currency the desk sells in
     */
    public static Optional<Money> moneyOf(Object document, String fallbackCurrency) {
        if (!(document instanceof Map<?, ?>)) {
            return Optional.empty();
        }
        Optional<Object> raw = pick(document, "amount", "value");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        Object code = pick(document, "currencyCode", "code", "currency").orElse(fallbackCurrency);
        if (!truthy(code)) {
            return Optional.empty();
        }
        return Optional.of(new Money(amountOf(raw.get()), Text.plain(code)));
    }

    /**
     * Read a timestamp, dropping the offset because sectors are wall clock.
     *
     * <p>A trailing {@code Z} goes, and an offset is cut for either sign, but only after the date,
     * because a date has two hyphens in it and a naive search for one takes the text apart at the
     * year. A western departure carries a minus offset, and a reader that only cut the plus brought
     * every American arrival back with its offset still on it. None for anything that is not text
     * or does not then read as a date and time.
     */
    public static Optional<LocalDateTime> when(Object value) {
        if (!(value instanceof String raw) || Text.isBlank(raw)) {
            return Optional.empty();
        }
        String text = Text.strip(raw);
        if (text.endsWith("Z")) {
            text = text.substring(0, text.length() - 1);
        }
        int dateEnd = text.offsetByCodePoints(0, Math.min(10, text.codePointCount(0, text.length())));
        String tail = text.substring(dateEnd);
        for (char sign : new char[] {'+', '-'}) {
            int found = tail.indexOf(sign);
            if (found >= 0) {
                text = text.substring(0, dateEnd + found);
                break;
            }
        }
        try {
            return Optional.of(Times.parseIso(text));
        } catch (DateTimeParseException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * Read an offer feed response sent as bytes, in whichever Unicode encoding JSON allows, a byte
     * order mark included.
     */
    public static Harvest parse(byte[] payload, LocalDateTime observedAt) {
        Object document;
        try {
            document = JSON.readValue(payload, Object.class);
        } catch (IOException unreadable) {
            throw notJson(unreadable);
        }
        return read(document, observedAt);
    }

    /**
     * Read an offer feed response, keeping what it could not use.
     *
     * <p>The offers sit under {@code offers} or {@code offerList}, inside an {@code offerResponse}
     * envelope or with none. An offer that cannot be read is a problem on the harvest, naming its
     * place in the list, and the others still read.
     *
     * @throws MalformedPayload when the payload is not JSON or carries no offers
     * @throws ValidationError when an offer names a point, a carrier or a currency the desk does not
     *     know, or cannot describe a real offer
     */
    public static Harvest parse(String payload, LocalDateTime observedAt) {
        Object document;
        try {
            document = JSON.readValue(payload, Object.class);
        } catch (IOException unreadable) {
            throw notJson(unreadable);
        }
        return read(document, observedAt);
    }

    private static Harvest read(Object document, LocalDateTime observedAt) {
        Object envelope = pick(document, "offerResponse", "OfferResponse").orElse(document);
        List<Object> entries = asList(pick(envelope, "offers", "offerList").orElse(null));
        if (entries.isEmpty()) {
            throw new MalformedPayload("the payload carries no offers");
        }

        Harvest harvest = new Harvest(SOURCE);
        for (int index = 0; index < entries.size(); index++) {
            Optional<Offer> offer;
            try {
                offer = offer(entries.get(index), observedAt);
            } catch (MalformedPayload refused) {
                harvest.addProblem("offer " + index + ": " + refused.message());
                continue;
            }
            if (offer.isEmpty()) {
                harvest.addProblem("offer " + index + ": could not be assembled");
                continue;
            }
            harvest.addOffer(offer.get());
        }
        return harvest;
    }

    /** One offer, or none when it has no segments at all. */
    private static Optional<Offer> offer(Object entry, LocalDateTime observedAt) {
        Object price = pick(entry, "priceDetail", "price").orElse(Map.of());
        Money base = moneyOf(pick(price, "baseAmount", "base").orElse(null))
                .orElseThrow(() -> new MalformedPayload("no base amount on the offer"));

        List<Segment> segments = new ArrayList<>();
        for (Object journey : asList(pick(entry, "paxJourneys", "journeys").orElse(null))) {
            for (Object sector : asList(pick(journey, "segments", "segmentList").orElse(null))) {
                segments.add(segment(sector).orElseThrow(() -> new MalformedPayload("a segment was missing a field")));
            }
        }
        if (segments.isEmpty()) {
            return Optional.empty();
        }

        Itinerary itinerary;
        try {
            itinerary = new Itinerary(segments);
        } catch (ValidationError disjoint) {
            MalformedPayload refusal = new MalformedPayload("the segments do not join up");
            refusal.initCause(disjoint);
            throw refusal;
        }

        Object bags = pick(entry, "baggageAllowance", "bags").orElse(null);
        Object pieces = bags instanceof Map<?, ?> ? pick(bags, "pieces").orElse(null) : bags;

        return Optional.of(Offer.builder()
                .source(SOURCE)
                .itinerary(itinerary)
                .breakdown(new PriceBreakdown(
                        base,
                        taxes(price, base.currency()),
                        moneyOf(pick(price, "totalAmount", "total").orElse(null), base.currency())))
                .fareBases(asList(pick(entry, "fareBasis", "fareBasisCodes").orElse(null)).stream()
                        .map(Text::plain)
                        .toList())
                .observedAt(observedAt)
                .cabin(itinerary.segments().get(0).cabin())
                .seats(seats(entry))
                .baggage(whole(pieces).map(Baggage::ofPieces).orElseGet(Baggage::new))
                .refundable(typed(entry, Boolean.class, "refundable"))
                .validatingCarrier(typed(entry, String.class, "validatingCarrier"))
                .build());
    }

    /** A segment, or none when any field it cannot do without is missing or empty. */
    private static Optional<Segment> segment(Object sector) {
        Optional<LocalDateTime> departure =
                when(pick(sector, "departureTime", "departure").orElse(null));
        Optional<LocalDateTime> arrival =
                when(pick(sector, "arrivalTime", "arrival").orElse(null));
        Object origin = pick(sector, "origin", "departureAirport").orElse(null);
        Object destination = pick(sector, "destination", "arrivalAirport").orElse(null);
        Object carrier = pick(sector, "marketingCarrier", "carrier").orElse(null);
        Object number = pick(sector, "flightNumber", "number").orElse(null);
        Object bookingClass = pick(sector, "bookingClass", "rbd").orElse(null);
        if (departure.isEmpty()
                || arrival.isEmpty()
                || !truthy(origin)
                || !truthy(destination)
                || !truthy(carrier)
                || !truthy(number)
                || !truthy(bookingClass)) {
            return Optional.empty();
        }
        LocalDateTime leaves = departure.get();
        LocalDateTime lands = arrival.get();
        String cabin = Text.plain(pick(sector, "cabin").orElse("Y"));
        long days = ChronoUnit.DAYS.between(leaves.toLocalDate(), lands.toLocalDate());
        return Optional.of(new Segment(
                Text.plain(carrier),
                Text.plain(number),
                Text.plain(origin),
                Text.plain(destination),
                leaves,
                lands,
                Text.plain(bookingClass),
                Gds.cabinOf(cabin),
                typed(sector, String.class, "operatingCarrier"),
                Optional.empty(),
                (int) Math.max(0, days),
                0,
                Optional.empty(),
                false));
    }

    /** The taxes an offer's price lists; one with no code or no money is dropped. */
    private static List<AppliedTax> taxes(Object price, String fallbackCurrency) {
        List<AppliedTax> found = new ArrayList<>();
        for (Object entry : asList(pick(price, "taxes", "taxBreakdown").orElse(null))) {
            Optional<Object> code = pick(entry, "code", "taxCode");
            Optional<Money> amount = moneyOf(entry, fallbackCurrency);
            if (code.isEmpty() || amount.isEmpty()) {
                continue;
            }
            String text = Text.plain(code.get());
            found.add(new AppliedTax(
                    text,
                    amount.get(),
                    Text.plain(pick(entry, "point").orElse("")),
                    Taxes.CARRIER_IMPOSED.contains(text)));
        }
        return found;
    }

    /** The seats left, a yes or no counting as one or nought. */
    private static Optional<Integer> seats(Object entry) {
        Optional<Object> found = pick(entry, "seatsRemaining", "availableSeats");
        if (found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(whole(found.get()).orElseThrow(() -> wrongKind("seatsRemaining", found.get())));
    }

    /** A field's value when it is of the kind wanted, or none when it is absent. */
    private static <T> Optional<T> typed(Object entry, Class<T> kind, String name) {
        Optional<Object> found = pick(entry, name);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (!kind.isInstance(found.get())) {
            throw wrongKind(name, found.get());
        }
        return Optional.of(kind.cast(found.get()));
    }

    /** A whole number that fits an {@code int}, a yes or no counting as one or nought. */
    private static Optional<Integer> whole(Object value) {
        return switch (value) {
            case Boolean flag -> Optional.of(flag ? 1 : 0);
            case Integer number -> Optional.of(number);
            case Long number when number == number.intValue() -> Optional.of(number.intValue());
            case BigInteger number when number.bitLength() < Integer.SIZE -> Optional.of(number.intValue());
            case null, default -> Optional.empty();
        };
    }

    /**
     * Whether a value says anything: not null, not false, not zero, and not empty text, list or
     * object.
     */
    private static boolean truthy(Object value) {
        return switch (value) {
            case null -> false;
            case Boolean flag -> flag;
            case CharSequence text -> !text.isEmpty();
            case Collection<?> many -> !many.isEmpty();
            case Map<?, ?> fields -> !fields.isEmpty();
            case BigDecimal exact -> exact.signum() != 0;
            case BigInteger whole -> whole.signum() != 0;
            case Double binary -> binary != 0.0;
            case Number number -> number.longValue() != 0;
            default -> true;
        };
    }

    private static MalformedPayload wrongKind(String field, Object value) {
        return new MalformedPayload(
                "a field holds the wrong kind of value", Details.of("field", field, "value", Text.repr(value)));
    }

    private static MalformedPayload notJson(IOException unreadable) {
        MalformedPayload refusal = new MalformedPayload("response was not json");
        refusal.initCause(unreadable);
        return refusal;
    }
}
