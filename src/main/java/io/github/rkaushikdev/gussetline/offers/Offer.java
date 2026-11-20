package io.github.rkaushikdev.gussetline.offers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.util.Text;
import io.github.rkaushikdev.gussetline.util.Times;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * One priced itinerary as one supplier described it at one moment.
 *
 * <p>This is what every supplier payload turns into. One shape, three sources, and the shape is the
 * contract: everything that reads offers has never seen a supplier payload, which is the only reason
 * a fourth supplier can be added in an afternoon. The breakdown is the part worth being strict
 * about; see {@link PriceBreakdown}.
 *
 * <p>Five fields are required and have a constructor of their own; the rest default to economy, no
 * seat count, an unstated allowance, unknown refundability, no validating carrier, no payload digest
 * and no notes, and are set through {@link #builder()}.
 *
 * @param source the supplier that sent it, lower case
 * @param itinerary the journey priced
 * @param breakdown the price, part by part
 * @param fareBases the fare basis of each component, at least one
 * @param observedAt when the supplier said it
 * @param cabin the cabin priced
 * @param seats how many seats the supplier said were left, if it said
 * @param baggage the hold allowance
 * @param refundable whether the fare is refundable, if the supplier said
 * @param validatingCarrier the carrier the ticket would be issued on, if the supplier said
 * @param rawDigest the digest of the payload the offer was read from, if it was kept
 * @param notes remarks made about the offer on the way in, oldest first
 */
public record Offer(
        String source,
        Itinerary itinerary,
        PriceBreakdown breakdown,
        List<String> fareBases,
        LocalDateTime observedAt,
        Cabin cabin,
        Optional<Integer> seats,
        Baggage baggage,
        Optional<Boolean> refundable,
        Optional<String> validatingCarrier,
        Optional<String> rawDigest,
        List<String> notes) {

    /** How many hexadecimal characters of the digest a fingerprint keeps. */
    private static final int FINGERPRINT_LENGTH = 32;

    /**
     * Normalises the source to lower case without surrounding white space.
     *
     * @throws ValidationError when the source is blank, there is no fare basis, or the seat count is
     *     negative, naming the {@code seats}
     */
    public Offer {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(itinerary, "itinerary");
        Objects.requireNonNull(breakdown, "breakdown");
        Objects.requireNonNull(fareBases, "fareBases");
        Objects.requireNonNull(observedAt, "observedAt");
        Objects.requireNonNull(cabin, "cabin");
        Objects.requireNonNull(seats, "seats");
        Objects.requireNonNull(baggage, "baggage");
        Objects.requireNonNull(refundable, "refundable");
        Objects.requireNonNull(validatingCarrier, "validatingCarrier");
        Objects.requireNonNull(rawDigest, "rawDigest");
        Objects.requireNonNull(notes, "notes");
        if (Text.isBlank(source)) {
            throw new ValidationError("an offer names its source");
        }
        source = Text.strip(source).toLowerCase(Locale.ROOT);
        fareBases = List.copyOf(fareBases);
        if (fareBases.isEmpty()) {
            throw new ValidationError("an offer carries at least one fare basis");
        }
        if (seats.isPresent() && seats.get() < 0) {
            throw new ValidationError("a seat count is not negative", Details.of("seats", seats.get()));
        }
        notes = List.copyOf(notes);
    }

    /** An economy offer with nothing optional known about it. */
    public Offer(
            String source,
            Itinerary itinerary,
            PriceBreakdown breakdown,
            List<String> fareBases,
            LocalDateTime observedAt) {
        this(
                source,
                itinerary,
                breakdown,
                fareBases,
                observedAt,
                Cabin.ECONOMY,
                Optional.empty(),
                new Baggage(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of());
    }

    /** An offer built field by field, the optional ones starting at their defaults. */
    public static Builder builder() {
        return new Builder();
    }

    /** Where the journey starts. */
    public String origin() {
        return itinerary.origin();
    }

    /** Where the journey ends. */
    public String destination() {
        return itinerary.destination();
    }

    /** What the supplier says the offer costs, or what its parts add to when it did not say. */
    public Money total() {
        return breakdown.statedTotal();
    }

    /** The currency the offer is priced in. */
    public String currency() {
        return breakdown.currency();
    }

    /** The marketing carriers, each once, in the order they first fly. */
    public List<String> carriers() {
        return itinerary.carriers();
    }

    /** Whether the journey is one element long. */
    public boolean isDirect() {
        return itinerary.elements().size() == 1;
    }

    /**
     * A stable identity for the priced thing, ignoring when it was seen: thirty two hexadecimal
     * characters.
     *
     * <p>Two observations of the same offer an hour apart have to collapse to one series, and the
     * same itinerary at a different price must not. So the digest covers the sectors, the fare bases
     * and the amount, and deliberately leaves out the source, because two suppliers selling the same
     * fare at the same price is a duplicate and not a finding.
     *
     * <p>The digest is SHA-256 over UTF-8 text: each sector as {@code LH400:FRAJFK:2025-03-04T10:25:00}
     * joined with {@code |}, the fare bases joined with {@code |}, the total's amount as written and
     * its currency, the four joined with {@code ~}. Stored fingerprints depend on that text staying as
     * it is.
     */
    public String fingerprint() {
        String sectors = itinerary.segments().stream().map(Offer::sectorText).collect(Collectors.joining("|"));
        String priced = String.join(
                "~", sectors, String.join("|", fareBases), total().amount().toString(), total().currency());
        return HexFormat.of().formatHex(sha256(priced)).substring(0, FINGERPRINT_LENGTH);
    }

    /** What groups observations into one watchable price series: the market, the day and the cabin. */
    public Series.Parts seriesKey() {
        return Series.parts(itinerary.origin(), itinerary.destination(), itinerary.departure(), cabin);
    }

    /**
     * Whether this offer costs less than another.
     *
     * @throws ValidationError naming the {@code left} and {@code right} currencies when they differ
     */
    public boolean cheaperThan(Offer other) {
        if (!currency().equals(other.currency())) {
            throw new ValidationError(
                    "two offers in different currencies do not compare",
                    Details.of("left", currency(), "right", other.currency()));
        }
        return total().isLessThan(other.total());
    }

    /**
     * This offer's total minus another's.
     *
     * @throws ValidationError when the currencies differ
     */
    public Money differenceFrom(Offer other) {
        if (!currency().equals(other.currency())) {
            throw new ValidationError("two offers in different currencies do not subtract");
        }
        return total().minus(other.total());
    }

    /** A copy with one more note, after the ones it already has. This offer is left alone. */
    public Offer withNote(String note) {
        List<String> more = new ArrayList<>(notes);
        more.add(note);
        return new Offer(
                source,
                itinerary,
                breakdown,
                fareBases,
                observedAt,
                cabin,
                seats,
                baggage,
                refundable,
                validatingCarrier,
                rawDigest,
                more);
    }

    /**
     * The source, the route walked point by point, the fare bases and the total: {@code gds FRAJFK
     * KLWAP7RT 490.83 EUR}.
     */
    @Override
    public String toString() {
        StringBuilder route = new StringBuilder(origin());
        for (Element element : itinerary.elements()) {
            route.append(element.destination());
        }
        return source + " " + route + " " + String.join("/", fareBases) + " " + total();
    }

    private static String sectorText(Segment segment) {
        return segment.designator() + ":" + segment.origin() + segment.destination() + ":"
                + Times.iso(segment.departure());
    }

    private static byte[] sha256(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException missing) {
            throw new IllegalStateException("every Java platform provides SHA-256", missing);
        }
    }

    /**
     * Builds an offer one field at a time. The five required fields have no default and {@link
     * #build()} refuses a missing one; the optional ones start at the defaults the five field
     * constructor uses.
     */
    public static final class Builder {

        private String source;
        private Itinerary itinerary;
        private PriceBreakdown breakdown;
        private List<String> fareBases;
        private LocalDateTime observedAt;
        private Cabin cabin = Cabin.ECONOMY;
        private Optional<Integer> seats = Optional.empty();
        private Baggage baggage = new Baggage();
        private Optional<Boolean> refundable = Optional.empty();
        private Optional<String> validatingCarrier = Optional.empty();
        private Optional<String> rawDigest = Optional.empty();
        private List<String> notes = List.of();

        private Builder() {}

        public Builder source(String value) {
            source = value;
            return this;
        }

        public Builder itinerary(Itinerary value) {
            itinerary = value;
            return this;
        }

        public Builder breakdown(PriceBreakdown value) {
            breakdown = value;
            return this;
        }

        public Builder fareBases(List<String> value) {
            fareBases = value;
            return this;
        }

        public Builder observedAt(LocalDateTime value) {
            observedAt = value;
            return this;
        }

        public Builder cabin(Cabin value) {
            cabin = value;
            return this;
        }

        public Builder seats(int value) {
            return seats(Optional.of(value));
        }

        /** The seat count, or that the supplier did not send one. */
        public Builder seats(Optional<Integer> value) {
            seats = value;
            return this;
        }

        public Builder baggage(Baggage value) {
            baggage = value;
            return this;
        }

        public Builder refundable(boolean value) {
            return refundable(Optional.of(value));
        }

        /** Whether the fare is refundable, or that the supplier did not say. */
        public Builder refundable(Optional<Boolean> value) {
            refundable = value;
            return this;
        }

        public Builder validatingCarrier(String value) {
            return validatingCarrier(Optional.of(value));
        }

        /** The validating carrier, or that the supplier did not name one. */
        public Builder validatingCarrier(Optional<String> value) {
            validatingCarrier = value;
            return this;
        }

        public Builder rawDigest(String value) {
            return rawDigest(Optional.of(value));
        }

        /** The payload digest, or that none was kept. */
        public Builder rawDigest(Optional<String> value) {
            rawDigest = value;
            return this;
        }

        public Builder notes(List<String> value) {
            notes = value;
            return this;
        }

        /**
         * The offer.
         *
         * @throws NullPointerException when a required field was never set
         * @throws ValidationError whatever the offer itself refuses
         */
        public Offer build() {
            return new Offer(
                    source,
                    itinerary,
                    breakdown,
                    fareBases,
                    observedAt,
                    cabin,
                    seats,
                    baggage,
                    refundable,
                    validatingCarrier,
                    rawDigest,
                    notes);
        }
    }
}
