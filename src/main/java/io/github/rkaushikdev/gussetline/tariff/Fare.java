package io.github.rkaushikdev.gussetline.tariff;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.farebasis.FareBasis;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One published fare, keyed the way the tariff keys it.
 *
 * <p>A fare is not valid or invalid. It has an effective date and a discontinue date, which say when
 * it may be sold, and a first and last travel date, which say when it may be flown. A ticket sold in
 * February for travel in June is checked against the sale window on the ticketing date and the
 * travel window on the departure date, and confusing the two is how a desk ends up arguing that a
 * fare which expired in March priced a June flight.
 *
 * <p>The carrier, both ends and the fare basis are normalised on the way in. The ends fold to the
 * cities the tariff files, so a fare given as FRA to EWR is held as FRA to NYC.
 *
 * <p>The nine fields up to the routing number are always given. The rest default to tariff 1, no
 * footnote, economy, a published fare, and sale and travel windows that open on 1900-01-01 and close
 * on {@link Tariff#FAR_FUTURE}. Each {@code with} method gives a copy with one field changed; {@link
 * #toBuilder()} changes several at once, which is what moving a window that is already set needs,
 * since a copy whose window closes before it opens is refused on the way.
 *
 * @param carrier the carrier designator, upper case
 * @param origin the city the fare is filed from
 * @param destination the city the fare is filed to
 * @param fareBasis the fare basis, upper case without surrounding white space
 * @param amount the filed amount; for a {@link OwRt#ROUND_TRIP_HALF} it is half of a round trip
 * @param owrt what the filed amount means
 * @param globalIndicator the global indicator the fare is filed under, which is part of its key
 * @param ruleNumber the rule the fare's conditions are filed under
 * @param routingNumber the routing it may travel, {@code 0000} for the mileage principle
 * @param tariffNumber the tariff it is filed in
 * @param footnote the footnote that narrows its rule, or empty
 * @param cabin the cabin it prices
 * @param fareType who it is filed for
 * @param effective the first day it may be sold
 * @param discontinue the last day it may be sold
 * @param firstTravel the first day it may be flown
 * @param lastTravel the last day it may be flown
 */
public record Fare(
        String carrier,
        String origin,
        String destination,
        String fareBasis,
        Money amount,
        OwRt owrt,
        GlobalIndicator globalIndicator,
        String ruleNumber,
        String routingNumber,
        String tariffNumber,
        String footnote,
        Cabin cabin,
        FareType fareType,
        LocalDate effective,
        LocalDate discontinue,
        LocalDate firstTravel,
        LocalDate lastTravel) {

    /** Where both windows open when nobody says otherwise. */
    private static final LocalDate EARLIEST = LocalDate.of(1900, 1, 1);

    /**
     * Normalises the carrier, folds both ends to cities and upper cases the fare basis.
     *
     * @throws ValidationError when the carrier is malformed, an end is unknown, both ends are one
     *     city (naming the {@code point}), the amount is not positive (naming the {@code amount}),
     *     the sale window closes before it opens (naming {@code effective} and {@code discontinue}),
     *     or the travel window closes before it opens
     */
    public Fare {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(fareBasis, "fareBasis");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(owrt, "owrt");
        Objects.requireNonNull(globalIndicator, "globalIndicator");
        Objects.requireNonNull(ruleNumber, "ruleNumber");
        Objects.requireNonNull(routingNumber, "routingNumber");
        Objects.requireNonNull(tariffNumber, "tariffNumber");
        Objects.requireNonNull(footnote, "footnote");
        Objects.requireNonNull(cabin, "cabin");
        Objects.requireNonNull(fareType, "fareType");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(discontinue, "discontinue");
        Objects.requireNonNull(firstTravel, "firstTravel");
        Objects.requireNonNull(lastTravel, "lastTravel");

        carrier = Carriers.normaliseCode(carrier);
        origin = Geography.cityOf(origin);
        destination = Geography.cityOf(destination);
        fareBasis = Text.code(fareBasis);
        if (origin.equals(destination)) {
            throw new ValidationError("a fare goes between two places", Details.of("point", origin));
        }
        if (amount.amount().signum() <= 0) {
            throw new ValidationError("a published fare is positive", Details.of("amount", amount.toString()));
        }
        if (discontinue.isBefore(effective)) {
            throw new ValidationError(
                    "the sale window closes before it opens",
                    Details.of("effective", effective.toString(), "discontinue", discontinue.toString()));
        }
        if (lastTravel.isBefore(firstTravel)) {
            throw new ValidationError("the travel window closes before it opens");
        }
    }

    /** A fare with the nine fields every filing gives, and the defaults for the rest. */
    public Fare(
            String carrier,
            String origin,
            String destination,
            String fareBasis,
            Money amount,
            OwRt owrt,
            GlobalIndicator globalIndicator,
            String ruleNumber,
            String routingNumber) {
        this(
                carrier,
                origin,
                destination,
                fareBasis,
                amount,
                owrt,
                globalIndicator,
                ruleNumber,
                routingNumber,
                "1",
                "",
                Cabin.ECONOMY,
                FareType.PUBLISHED,
                EARLIEST,
                Tariff.FAR_FUTURE,
                EARLIEST,
                Tariff.FAR_FUTURE);
    }

    /** An empty builder: the nine fields up to the routing number have to be set before it builds. */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding every field of this fare, for a copy that changes several of them. */
    public Builder toBuilder() {
        return new Builder()
                .carrier(carrier)
                .origin(origin)
                .destination(destination)
                .fareBasis(fareBasis)
                .amount(amount)
                .owrt(owrt)
                .globalIndicator(globalIndicator)
                .ruleNumber(ruleNumber)
                .routingNumber(routingNumber)
                .tariffNumber(tariffNumber)
                .footnote(footnote)
                .cabin(cabin)
                .fareType(fareType)
                .effective(effective)
                .discontinue(discontinue)
                .firstTravel(firstTravel)
                .lastTravel(lastTravel);
    }

    /** The direction it is filed in, between two cities. */
    public Market market() {
        return new Market(origin, destination);
    }

    /** The fare basis read into its parts, so a caller does not parse it again. */
    public FareBasis parsedBasis() {
        return FareBasis.parse(fareBasis);
    }

    /** The booking class it prices in: the first letter of the fare basis. */
    public String bookingClass() {
        return fareBasis.substring(0, fareBasis.offsetByCodePoints(0, 1));
    }

    /** Whether it is filed as half of a round trip. */
    public boolean isHalfRoundTrip() {
        return owrt == OwRt.ROUND_TRIP_HALF;
    }

    /** Whether it may be sold on a ticketing date, both ends of the sale window included. */
    public boolean sellableOn(LocalDate ticketing) {
        return !ticketing.isBefore(effective) && !ticketing.isAfter(discontinue);
    }

    /** Whether it may be flown on a departure date, both ends of the travel window included. */
    public boolean flyableOn(LocalDate departure) {
        return !departure.isBefore(firstTravel) && !departure.isAfter(lastTravel);
    }

    /**
     * What a single direction costs on this filing.
     *
     * <p>A round trip half cannot be sold on its own, so asking for its one way amount is a mistake
     * worth refusing rather than answering with half a round trip and letting it reach an invoice.
     *
     * @throws ValidationError naming the {@code fare_basis} when the fare is a round trip half
     */
    public Money oneWayAmount() {
        if (owrt == OwRt.ROUND_TRIP_HALF) {
            throw new ValidationError(
                    "a round trip half does not have a one way amount", Details.of("fare_basis", fareBasis));
        }
        return amount;
    }

    /**
     * What a round trip costs on this filing: the filed amount doubled.
     *
     * @throws ValidationError naming the {@code fare_basis} when the fare is one way only
     */
    public Money roundTripAmount() {
        if (owrt == OwRt.ONE_WAY_ONLY) {
            throw new ValidationError("this fare may not be doubled", Details.of("fare_basis", fareBasis));
        }
        return amount.times(2);
    }

    /** What tells this filing from another one in the same tariff. */
    public Key key() {
        return new Key(carrier, origin, destination, fareBasis, globalIndicator);
    }

    public Fare withCarrier(String code) {
        return toBuilder().carrier(code).build();
    }

    /** A copy filed from another point. Change both ends at once through {@link #toBuilder()}. */
    public Fare withOrigin(String point) {
        return toBuilder().origin(point).build();
    }

    /** A copy filed to another point. Change both ends at once through {@link #toBuilder()}. */
    public Fare withDestination(String point) {
        return toBuilder().destination(point).build();
    }

    public Fare withFareBasis(String basis) {
        return toBuilder().fareBasis(basis).build();
    }

    public Fare withAmount(Money filed) {
        return toBuilder().amount(filed).build();
    }

    public Fare withOwrt(OwRt indicator) {
        return toBuilder().owrt(indicator).build();
    }

    public Fare withGlobalIndicator(GlobalIndicator indicator) {
        return toBuilder().globalIndicator(indicator).build();
    }

    public Fare withRuleNumber(String number) {
        return toBuilder().ruleNumber(number).build();
    }

    public Fare withRoutingNumber(String number) {
        return toBuilder().routingNumber(number).build();
    }

    public Fare withTariffNumber(String number) {
        return toBuilder().tariffNumber(number).build();
    }

    public Fare withFootnote(String note) {
        return toBuilder().footnote(note).build();
    }

    public Fare withCabin(Cabin priced) {
        return toBuilder().cabin(priced).build();
    }

    public Fare withFareType(FareType type) {
        return toBuilder().fareType(type).build();
    }

    public Fare withEffective(LocalDate day) {
        return toBuilder().effective(day).build();
    }

    public Fare withDiscontinue(LocalDate day) {
        return toBuilder().discontinue(day).build();
    }

    public Fare withFirstTravel(LocalDate day) {
        return toBuilder().firstTravel(day).build();
    }

    public Fare withLastTravel(LocalDate day) {
        return toBuilder().lastTravel(day).build();
    }

    /** The fare as a desk reads it: {@code LH FRANYC KLWAP7RT 420.00 EUR}. */
    @Override
    public String toString() {
        return carrier + " " + origin + destination + " " + fareBasis + " " + amount;
    }

    /**
     * The identity of a filing: carrier, market, fare basis and global indicator. The indicator is
     * part of it because the fare is filed under one, and the same basis under another indicator is
     * another fare.
     *
     * @param carrier the carrier designator
     * @param origin the city the fare is filed from
     * @param destination the city the fare is filed to
     * @param fareBasis the fare basis
     * @param globalIndicator the indicator it is filed under
     */
    public record Key(
            String carrier, String origin, String destination, String fareBasis, GlobalIndicator globalIndicator) {}

    /**
     * Collects a fare's fields. The nine up to the routing number have to be set; the rest start at
     * the defaults {@link Fare} describes. Every setter refuses null and returns the builder.
     */
    public static final class Builder {

        private String carrier;
        private String origin;
        private String destination;
        private String fareBasis;
        private Money amount;
        private OwRt owrt;
        private GlobalIndicator globalIndicator;
        private String ruleNumber;
        private String routingNumber;
        private String tariffNumber = "1";
        private String footnote = "";
        private Cabin cabin = Cabin.ECONOMY;
        private FareType fareType = FareType.PUBLISHED;
        private LocalDate effective = EARLIEST;
        private LocalDate discontinue = Tariff.FAR_FUTURE;
        private LocalDate firstTravel = EARLIEST;
        private LocalDate lastTravel = Tariff.FAR_FUTURE;

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

        public Builder fareBasis(String fareBasis) {
            this.fareBasis = Objects.requireNonNull(fareBasis, "fareBasis");
            return this;
        }

        public Builder amount(Money amount) {
            this.amount = Objects.requireNonNull(amount, "amount");
            return this;
        }

        public Builder owrt(OwRt owrt) {
            this.owrt = Objects.requireNonNull(owrt, "owrt");
            return this;
        }

        public Builder globalIndicator(GlobalIndicator globalIndicator) {
            this.globalIndicator = Objects.requireNonNull(globalIndicator, "globalIndicator");
            return this;
        }

        public Builder ruleNumber(String ruleNumber) {
            this.ruleNumber = Objects.requireNonNull(ruleNumber, "ruleNumber");
            return this;
        }

        public Builder routingNumber(String routingNumber) {
            this.routingNumber = Objects.requireNonNull(routingNumber, "routingNumber");
            return this;
        }

        public Builder tariffNumber(String tariffNumber) {
            this.tariffNumber = Objects.requireNonNull(tariffNumber, "tariffNumber");
            return this;
        }

        public Builder footnote(String footnote) {
            this.footnote = Objects.requireNonNull(footnote, "footnote");
            return this;
        }

        public Builder cabin(Cabin cabin) {
            this.cabin = Objects.requireNonNull(cabin, "cabin");
            return this;
        }

        public Builder fareType(FareType fareType) {
            this.fareType = Objects.requireNonNull(fareType, "fareType");
            return this;
        }

        public Builder effective(LocalDate effective) {
            this.effective = Objects.requireNonNull(effective, "effective");
            return this;
        }

        public Builder discontinue(LocalDate discontinue) {
            this.discontinue = Objects.requireNonNull(discontinue, "discontinue");
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

        /**
         * The fare.
         *
         * @throws NullPointerException naming the first of the nine required fields left unset
         * @throws ValidationError for anything the fare itself refuses
         */
        public Fare build() {
            return new Fare(
                    carrier,
                    origin,
                    destination,
                    fareBasis,
                    amount,
                    owrt,
                    globalIndicator,
                    ruleNumber,
                    routingNumber,
                    tariffNumber,
                    footnote,
                    cabin,
                    fareType,
                    effective,
                    discontinue,
                    firstTravel,
                    lastTravel);
        }
    }
}
