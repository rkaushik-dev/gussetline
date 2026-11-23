package io.github.rkaushikdev.gussetline.surcharges;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Tariff;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One filed Q surcharge and the market it attaches to.
 *
 * <p>The carrier is normalised and both ends fold to the cities the tariff files, so a filing given
 * as FRA to EWR is held as FRA to NYC. Unless a filing says otherwise it is charged once per
 * component, under the reason {@code Q}, in either direction, with a travel window that opens on
 * 1900-01-01 and closes on {@link Tariff#FAR_FUTURE}. {@link #builder()} sets any of those.
 *
 * @param carrier the carrier designator, upper case
 * @param origin the city the market starts at
 * @param destination the city the market ends at
 * @param amount what one charge costs, in the filed currency
 * @param scope whether it is charged once per component or once per matching sector
 * @param reason the note it is charged under, such as {@code Q} or {@code QS}
 * @param directional whether it applies only from the origin to the destination
 * @param firstTravel the first day of travel it applies to
 * @param lastTravel the last day of travel it applies to
 * @param days the weekdays a sector has to leave on for the filing to read it; empty for every day
 * @param bookingClasses the booking classes a sector has to be sold in, upper case; empty for every
 *     class
 */
public record QSurcharge(
        String carrier,
        String origin,
        String destination,
        Money amount,
        Scope scope,
        String reason,
        boolean directional,
        LocalDate firstTravel,
        LocalDate lastTravel,
        Set<DayOfWeek> days,
        Set<String> bookingClasses) {

    /** Where the travel window opens when nobody says otherwise. */
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    /**
     * Normalises the carrier and folds both ends to cities.
     *
     * @throws ValidationError when the carrier is malformed, an end is unknown, both ends are one
     *     city (naming the {@code point}), the amount is not positive (naming the {@code amount}), or
     *     the travel window closes before it opens
     */
    public QSurcharge {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(firstTravel, "firstTravel");
        Objects.requireNonNull(lastTravel, "lastTravel");
        days = Set.copyOf(days);
        bookingClasses = bookingClasses.stream().map(Text::code).collect(Collectors.toUnmodifiableSet());

        carrier = Carriers.normaliseCode(carrier);
        origin = Geography.cityOf(origin);
        destination = Geography.cityOf(destination);
        if (origin.equals(destination)) {
            throw new ValidationError("a surcharge covers a market", Details.of("point", origin));
        }
        if (amount.amount().signum() <= 0) {
            throw new ValidationError("a surcharge is positive", Details.of("amount", amount.toString()));
        }
        if (lastTravel.isBefore(firstTravel)) {
            throw new ValidationError("the travel window closes before it opens");
        }
    }

    /** A component surcharge under {@code Q}, either way round, with no travel window to speak of. */
    public QSurcharge(String carrier, String origin, String destination, Money amount) {
        this(
                carrier,
                origin,
                destination,
                amount,
                Scope.COMPONENT,
                "Q",
                false,
                EARLIEST,
                Tariff.FAR_FUTURE,
                Set.of(),
                Set.of());
    }

    /** An empty builder: the carrier, both ends and the amount have to be set before it builds. */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Whether the filing attaches to a market, given as airports or cities. A filing that is not
     * directional reads either way round.
     *
     * @throws ValidationError when a point is unknown
     */
    public boolean covers(String from, String to) {
        String left = Geography.cityOf(from);
        String right = Geography.cityOf(to);
        if (left.equals(origin) && right.equals(destination)) {
            return true;
        }
        if (directional) {
            return false;
        }
        return right.equals(origin) && left.equals(destination);
    }

    /** Whether a departure date falls in the travel window, both ends included. */
    public boolean flyableOn(LocalDate departure) {
        return !departure.isBefore(firstTravel) && !departure.isAfter(lastTravel);
    }

    /**
     * Whether a sector is one the filing reads: it leaves on one of the filed weekdays and is sold in
     * one of the filed booking classes, either of which may be left open. A weekend surcharge filed
     * for Friday and Sunday reads the Friday outbound and leaves the Tuesday one alone.
     */
    public boolean readsSector(Segment sector) {
        return (days.isEmpty() || days.contains(sector.departure().getDayOfWeek()))
                && (bookingClasses.isEmpty() || bookingClasses.contains(sector.bookingClass()));
    }

    /** Whether the filing names a weekday or a booking class, which a surface sector never has. */
    public boolean isRestricted() {
        return !days.isEmpty() || !bookingClasses.isEmpty();
    }

    /** The reason, the market and the amount: {@code Q FRANYC 30.00 EUR}. */
    @Override
    public String toString() {
        return reason + " " + origin + destination + " " + amount;
    }

    /**
     * Collects a filing's fields. The carrier, both ends and the amount have to be set; the rest
     * start at the defaults {@link QSurcharge} describes. Every setter refuses null and returns the
     * builder.
     */
    public static final class Builder {

        private String carrier;
        private String origin;
        private String destination;
        private Money amount;
        private Scope scope = Scope.COMPONENT;
        private String reason = "Q";
        private boolean directional;
        private LocalDate firstTravel = EARLIEST;
        private LocalDate lastTravel = Tariff.FAR_FUTURE;
        private Set<DayOfWeek> days = Set.of();
        private Set<String> bookingClasses = Set.of();

        private Builder() {}

        public Builder carrier(String carrier) {
            this.carrier = Objects.requireNonNull(carrier, "carrier");
            return this;
        }

        public Builder origin(String origin) {
            this.origin = Objects.requireNonNull(origin, "origin");
            return this;
        }

        public Builder destination(String destination) {
            this.destination = Objects.requireNonNull(destination, "destination");
            return this;
        }

        public Builder amount(Money amount) {
            this.amount = Objects.requireNonNull(amount, "amount");
            return this;
        }

        public Builder scope(Scope scope) {
            this.scope = Objects.requireNonNull(scope, "scope");
            return this;
        }

        public Builder reason(String reason) {
            this.reason = Objects.requireNonNull(reason, "reason");
            return this;
        }

        public Builder directional(boolean directional) {
            this.directional = directional;
            return this;
        }

        public Builder firstTravel(LocalDate firstTravel) {
            this.firstTravel = Objects.requireNonNull(firstTravel, "firstTravel");
            return this;
        }

        public Builder lastTravel(LocalDate lastTravel) {
            this.lastTravel = Objects.requireNonNull(lastTravel, "lastTravel");
            return this;
        }

        public Builder days(DayOfWeek... days) {
            this.days = Set.of(days);
            return this;
        }

        public Builder bookingClasses(String... classes) {
            this.bookingClasses = Set.of(classes);
            return this;
        }

        /**
         * The filing.
         *
         * @throws NullPointerException naming the first of the four required fields left unset
         * @throws ValidationError for anything the filing itself refuses
         */
        public QSurcharge build() {
            return new QSurcharge(
                    carrier,
                    origin,
                    destination,
                    amount,
                    scope,
                    reason,
                    directional,
                    firstTravel,
                    lastTravel,
                    days,
                    bookingClasses);
        }
    }
}
