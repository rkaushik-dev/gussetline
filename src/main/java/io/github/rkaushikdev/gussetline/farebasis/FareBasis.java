package io.github.rkaushikdev.gussetline.farebasis;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A fare basis code, read as far as it goes, with everything it could not decode kept whole.
 *
 * <p>Fare basis codes are a filing convention and not a grammar. {@code KLWAP7RT/CH25} is a real
 * code. The first letter is the booking class it prices in, {@code L} is the low season, {@code W}
 * is the weekend variant, {@code AP7} means seven days advance purchase, {@code RT} says it is half
 * of a round trip, and everything after the slash is a ticket designator that discounts it by a
 * quarter for a child.
 *
 * <p>None of that is guaranteed. Carriers file whatever fits in the field and the only part the
 * tariff itself relies on is the prime code and the designator. So {@link #parse} reads what it
 * recognises, records the rest verbatim, and never refuses a code it cannot decompose. A reader
 * that refused here would stop the desk pricing perfectly good tickets because a carrier used a
 * letter we had not seen.
 *
 * @param raw the code as filed, in upper case, designator included
 * @param prime the booking class it prices in, its first letter
 * @param designator what follows the slash, when anything does
 * @param season the season its letter names: high, shoulder, low, off peak, peak or basic
 * @param weekend whether it is the weekend ({@code W}) or the weekday ({@code X}) variant, when it
 *     says
 * @param advanceDays the days of advance purchase it asks for, when it says
 * @param maxStayMonths the longest stay in months, when it says
 * @param minStay the shortest stay, when it says
 * @param roundTrip whether it is half of a round trip ({@code RT}) or a one way ({@code OW}), when
 *     it says
 * @param unparsed the letters and digits nothing recognised, in the order filed
 * @param notes what the reader noticed on the way, such as an unrecognised tail
 */
public record FareBasis(
        String raw,
        String prime,
        Optional<TicketDesignator> designator,
        Optional<String> season,
        Optional<Boolean> weekend,
        Optional<Integer> advanceDays,
        Optional<Integer> maxStayMonths,
        Optional<MinimumStay> minStay,
        Optional<Boolean> roundTrip,
        String unparsed,
        List<String> notes) {

    private static final Map<Character, String> SEASONS = Map.of(
            'H', "high",
            'K', "shoulder",
            'L', "low",
            'O', "off peak",
            'J', "peak",
            'T', "basic");

    /**
     * Up to fifteen letters and digits. The end anchor also takes a line feed that ends the body, so
     * a body written {@code "Y\n"} before its slash reads, and the line feed is kept as unparsed.
     */
    private static final Pattern CODE = Pattern.compile("[A-Z0-9]{1,15}$", Pattern.UNIX_LINES);

    private static final Pattern ADVANCE = Pattern.compile("AP(\\d{1,3})");
    private static final Pattern MIN_STAY = Pattern.compile("MIN(\\d{1,3})([DM])");
    private static final Pattern MAX_STAY = Pattern.compile("(\\d{1,2})M(?![A-Z])");

    public FareBasis {
        Objects.requireNonNull(raw, "raw");
        Objects.requireNonNull(prime, "prime");
        Objects.requireNonNull(designator, "designator");
        Objects.requireNonNull(season, "season");
        Objects.requireNonNull(weekend, "weekend");
        Objects.requireNonNull(advanceDays, "advanceDays");
        Objects.requireNonNull(maxStayMonths, "maxStayMonths");
        Objects.requireNonNull(minStay, "minStay");
        Objects.requireNonNull(roundTrip, "roundTrip");
        Objects.requireNonNull(unparsed, "unparsed");
        notes = List.copyOf(notes);
    }

    /** A code with only its raw text and prime known; everything else is set on the builder. */
    public static Builder builder(String raw, String prime) {
        return new Builder(raw, prime);
    }

    /**
     * Decompose a fare basis as far as it goes and keep the remainder. Case and surrounding white
     * space are ignored.
     *
     * @throws ValidationError when the text is empty, is not up to fifteen letters and digits before
     *     an optional slash, does not start with a letter, or carries a designator that does not read
     */
    public static FareBasis parse(String text) {
        String raw = Text.code(text);
        if (raw.isEmpty()) {
            throw new ValidationError("empty fare basis");
        }

        int slash = raw.indexOf('/');
        String body = slash < 0 ? raw : raw.substring(0, slash);
        String designatorText = slash < 0 ? "" : raw.substring(slash + 1);
        if (!CODE.matcher(body).lookingAt()) {
            throw new ValidationError("not a fare basis", Details.of("value", text));
        }

        Builder parsed = builder(raw, body.substring(0, 1));
        if (!designatorText.isEmpty()) {
            parsed.designator(TicketDesignator.parse(designatorText));
        }

        if (!Character.isLetter(body.charAt(0))) {
            throw new ValidationError("a fare basis starts with its booking class", Details.of("value", text));
        }

        String rest = body.substring(1);

        Matcher advance = ADVANCE.matcher(rest);
        if (advance.find()) {
            parsed.advanceDays(Numbers.parseInt(advance.group(1)));
            rest = without(rest, advance);
        }

        Matcher minimum = MIN_STAY.matcher(rest);
        if (minimum.find()) {
            String unit = minimum.group(2).equals("D") ? "days" : "months";
            parsed.minStay(new MinimumStay(Numbers.parseInt(minimum.group(1)), unit));
            rest = without(rest, minimum);
        }

        Matcher maximum = MAX_STAY.matcher(rest);
        if (maximum.find()) {
            parsed.maxStayMonths(Numbers.parseInt(maximum.group(1)));
            rest = without(rest, maximum);
        }

        if (rest.contains("RT")) {
            parsed.roundTrip(true);
            rest = rest.replaceFirst("RT", "");
        } else if (rest.contains("OW")) {
            parsed.roundTrip(false);
            rest = rest.replaceFirst("OW", "");
        }

        String season = null;
        Boolean weekend = null;
        StringBuilder leftover = new StringBuilder();
        for (char each : rest.toCharArray()) {
            String named = SEASONS.get(each);
            if (season == null && named != null) {
                season = named;
            } else if (weekend == null && (each == 'W' || each == 'X')) {
                weekend = each == 'W';
            } else {
                leftover.append(each);
            }
        }
        if (season != null) {
            parsed.season(season);
        }
        if (weekend != null) {
            parsed.weekend(weekend);
        }

        String unparsed = leftover.toString();
        if (!unparsed.isEmpty()) {
            parsed.unparsed(unparsed).notes(List.of("unrecognised " + unparsed));
        }
        return parsed.build();
    }

    /**
     * Whether two codes are the same fare wearing different designators.
     *
     * <p>Two codes that differ only after the slash are the same published fare sold to a child and
     * to an adult, and the watch has to treat them as one or it will report a drop every time a
     * family books.
     */
    public static boolean sameFareFamily(FareBasis left, FareBasis right) {
        return left.body().equals(right.body());
    }

    /** The code without its designator, which is what the tariff keys on. */
    public String body() {
        int slash = raw.indexOf('/');
        return slash < 0 ? raw : raw.substring(0, slash);
    }

    /** Whether its designator takes anything off the fare. */
    public boolean isDiscounted() {
        return designator.filter(TicketDesignator::isDiscount).isPresent();
    }

    /** Whether it prices in a booking class, given in any case and with surrounding white space. */
    public boolean matchesClass(String bookingClass) {
        return prime.equals(Text.code(bookingClass));
    }

    /** The code as filed, designator included. */
    @Override
    public String toString() {
        return raw;
    }

    private static String without(String text, Matcher found) {
        return text.substring(0, found.start()) + text.substring(found.end());
    }

    /** Collects the parts of a fare basis, leaving unset what the code does not say. */
    public static final class Builder {

        private final String raw;
        private final String prime;
        private TicketDesignator designator;
        private String season;
        private Boolean weekend;
        private Integer advanceDays;
        private Integer maxStayMonths;
        private MinimumStay minStay;
        private Boolean roundTrip;
        private String unparsed = "";
        private List<String> notes = List.of();

        private Builder(String raw, String prime) {
            this.raw = Objects.requireNonNull(raw, "raw");
            this.prime = Objects.requireNonNull(prime, "prime");
        }

        public Builder designator(TicketDesignator designator) {
            this.designator = Objects.requireNonNull(designator, "designator");
            return this;
        }

        public Builder season(String season) {
            this.season = Objects.requireNonNull(season, "season");
            return this;
        }

        public Builder weekend(boolean weekend) {
            this.weekend = weekend;
            return this;
        }

        public Builder advanceDays(int days) {
            this.advanceDays = days;
            return this;
        }

        public Builder maxStayMonths(int months) {
            this.maxStayMonths = months;
            return this;
        }

        public Builder minStay(MinimumStay stay) {
            this.minStay = Objects.requireNonNull(stay, "stay");
            return this;
        }

        public Builder roundTrip(boolean roundTrip) {
            this.roundTrip = roundTrip;
            return this;
        }

        public Builder unparsed(String unparsed) {
            this.unparsed = Objects.requireNonNull(unparsed, "unparsed");
            return this;
        }

        public Builder notes(List<String> notes) {
            this.notes = List.copyOf(notes);
            return this;
        }

        public FareBasis build() {
            return new FareBasis(
                    raw,
                    prime,
                    Optional.ofNullable(designator),
                    Optional.ofNullable(season),
                    Optional.ofNullable(weekend),
                    Optional.ofNullable(advanceDays),
                    Optional.ofNullable(maxStayMonths),
                    Optional.ofNullable(minStay),
                    Optional.ofNullable(roundTrip),
                    unparsed,
                    notes);
        }
    }
}
