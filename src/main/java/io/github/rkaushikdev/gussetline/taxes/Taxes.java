package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.discounts.Passenger;
import io.github.rkaushikdev.gussetline.discounts.PassengerType;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Break;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Taxes, fees and the two carrier surcharges that pretend to be taxes.
 *
 * <p>YQ and YR are not taxes. They are money the carrier keeps, filed in the tax box because that is
 * where there was room. They are the first thing to check when a supplier total is high and the
 * base fare looks right, and they are the first thing a rival drops when it wants to look cheap, so
 * the desk breaks them out separately and never lets them into the tax subtotal.
 *
 * <p>The rule that produces most of the work here is the transit exemption. A departure tax is owed
 * by a passenger who departs, and a passenger who is only connecting is not departing in the sense
 * the tax means. So whether a country's tax applies depends on how long the passenger was on the
 * ground at that point, read against the transit break the country filed ({@link
 * TaxRule#transitHours()}) rather than against the stopover line the fare uses. The two often
 * disagree: a journey can stop over for the fare and still be in transit for the tax. One rule
 * deciding what another rule measures is the normal state of this class.
 */
public final class Taxes {

    /** The two codes that are carrier money rather than government money. */
    public static final Set<String> CARRIER_IMPOSED = Set.of("YQ", "YR");

    /**
     * The longest wait at a United States airport that is still a connection for the facility charge.
     * Longer than this and the passenger enplanes there again and pays it again.
     */
    public static final int PFC_TRANSIT_HOURS = 4;

    /**
     * How many facility charges one direction of a journey pays, however many United States airports
     * it enplanes at: two on a one way, four on a round trip.
     */
    public static final int PFC_PER_DIRECTION = 2;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Taxes() {}

    /**
     * Work out every tax and surcharge owed on a journey.
     *
     * <p>The itinerary is walked point by point rather than country by country, because a tax is owed
     * at a point and the same country can be entered twice on one journey and owe twice. A rule held
     * to domestic or international journeys ({@link TaxRule#journeys()}) is not read on the other
     * kind at all. Departure
     * and arrival taxes come first, flown sector by flown sector, because nobody boards or lands on a
     * surface sector; then the taxes owed once per ticket and once
     * per coupon in the country the journey starts in; then the carrier surcharges, at the origin.
     *
     * <p>A rule capped per direction ({@link TaxRule#maxPerDirection()}) is charged at the first
     * points that owe it, up to the cap, on the way out and again on the way home.
     *
     * <p>A flat tax is charged in its filed currency and a percentage tax in the base fare's, each
     * rounded to the minor unit with halves away from zero ({@link Money#roundTax}).
     *
     * @param itinerary the journey
     * @param base the base fare a percentage tax reads
     * @param table the filed taxes
     * @throws ValidationError when the itinerary's breaks cannot be read
     */
    public static List<AppliedTax> assess(Itinerary itinerary, Money base, TaxTable table) {
        return assess(itinerary, base, table, new Passenger(PassengerType.ADULT));
    }

    /**
     * {@link #assess(Itinerary, Money, TaxTable)} for one traveller, leaving out every tax that
     * spares their passenger type ({@link TaxRule#spares}). An infant on a lap enplanes nowhere in the
     * sense a facility charge means and owes none of it, however many airports the parent passes.
     */
    public static List<AppliedTax> assess(Itinerary itinerary, Money base, TaxTable table, Passenger passenger) {
        Charges owed = new Charges(base, itinerary.turnaroundIndex(), passenger.kind(), itinerary.isInternational());
        List<Element> elements = itinerary.elements();
        List<Break> breaks = itinerary.breaks();

        for (int index = 0; index < elements.size(); index++) {
            Element element = elements.get(index);
            if (!(element instanceof Segment)) {
                continue;
            }
            String departing = element.origin();
            String arriving = element.destination();
            Optional<Break> before = index == 0 ? Optional.empty() : flownBreak(elements, breaks, index - 1);
            Optional<Break> after =
                    index == elements.size() - 1 ? Optional.empty() : flownBreak(elements, breaks, index);

            for (TaxRule rule : table.forCountry(Geography.airportOrCityCountry(departing))) {
                if (dueOn(rule, element, true) && !inTransit(rule, before)) {
                    owed.charge(rule, departing, index);
                }
            }
            for (TaxRule rule : table.forCountry(Geography.airportOrCityCountry(arriving))) {
                if (dueOn(rule, element, false) && !inTransit(rule, after)) {
                    owed.charge(rule, arriving, index);
                }
            }
        }

        String origin = itinerary.origin();
        List<TaxRule> atOrigin = table.forCountry(Geography.airportOrCityCountry(origin));
        atOrigin.stream()
                .filter(rule -> rule.applies() == Applies.PER_TICKET)
                .forEach(rule -> owed.charge(rule, origin, 0));
        for (TaxRule rule : atOrigin) {
            if (rule.applies() == Applies.PER_COUPON) {
                for (int index = 0; index < elements.size(); index++) {
                    owed.charge(rule, elements.get(index).origin(), index);
                }
            }
        }
        table.carrierRules().forEach(rule -> owed.charge(rule, origin, 0));
        return owed.list();
    }

    /** Everything the government gets. Carrier surcharges are not in it. Nothing owed is zero. */
    public static Money governmentTotal(Iterable<AppliedTax> taxes, String currency) {
        return subtotal(taxes, currency, false);
    }

    /** Everything the carrier keeps through the tax box. Nothing owed is zero. */
    public static Money carrierTotal(Iterable<AppliedTax> taxes, String currency) {
        return subtotal(taxes, currency, true);
    }

    /**
     * Every tax restated in one currency on the bankers rate, in the order given, each keeping its
     * code, point and description.
     *
     * <p>A journey from Frankfurt over London owes euro at one end and sterling at the other, and the
     * passenger pays both in whatever the ticket is sold in. A tax goes through the bankers selling
     * rate and never through the rate of exchange, because the rate of exchange is for fares; using it
     * here is a one unit difference on every multi country ticket that nobody can reproduce. A tax
     * already in the currency is kept as it is.
     *
     * @throws ValidationError when the bankers table holds no rate for a currency on the date
     */
    public static List<AppliedTax> inCurrency(
            List<AppliedTax> taxes, String currency, Converter converter, LocalDate on) {
        return taxes.stream()
                .map(tax -> tax.withAmount(converter.convertTax(tax.amount(), currency, on)))
                .toList();
    }

    /**
     * The fare and everything owed on it as one breakdown in the fare's currency: the taxes assessed
     * for the traveller, then restated on the bankers rate ({@link #inCurrency}).
     *
     * <p>This is the breakdown the desk compares a supplier's against. A supplier that charges the
     * sterling duty at the fare's rate of exchange shows up as a discrepancy here rather than as a
     * refusal to add sterling to euro.
     */
    public static PriceBreakdown quote(
            Itinerary itinerary, Money base, TaxTable table, Passenger passenger, Converter converter, LocalDate on) {
        List<AppliedTax> owed = assess(itinerary, base, table, passenger);
        return new PriceBreakdown(base, inCurrency(owed, base.currency(), converter, on));
    }

    private static Money subtotal(Iterable<AppliedTax> taxes, String currency, boolean carrier) {
        Money running = Money.zero(currency);
        for (AppliedTax tax : taxes) {
            if (tax.carrierImposed() == carrier) {
                running = running.plus(tax.amount());
            }
        }
        return running;
    }

    /** The taxes the desk sees on the markets it actually sells. A new table each call. */
    public static TaxTable defaultTaxTable() {
        TaxTable table = new TaxTable();
        table.addMany(List.of(
                TaxRule.builder("DE", "DE")
                        .description("German air transport tax")
                        .flat("70.83", "EUR")
                        .applies(Applies.PER_INTERNATIONAL_DEPARTURE)
                        .exemptTransit(true)
                        .spares(PassengerType.INFANT)
                        .build(),
                TaxRule.builder("RA", "DE")
                        .description("German security charge")
                        .flat("10.00", "EUR")
                        .applies(Applies.PER_DEPARTURE)
                        .exemptTransit(true)
                        .build(),
                TaxRule.builder("GB", "GB")
                        .description("UK air passenger duty")
                        .flat("88.00", "GBP")
                        .applies(Applies.PER_INTERNATIONAL_DEPARTURE)
                        .exemptTransit(true)
                        .spares(PassengerType.CHILD, PassengerType.INFANT, PassengerType.INFANT_WITH_SEAT)
                        .build(),
                TaxRule.builder("UB", "GB")
                        .description("UK passenger service charge")
                        .flat("31.30", "GBP")
                        .applies(Applies.PER_DEPARTURE)
                        .exemptTransit(true)
                        .build(),
                TaxRule.builder("US", "US")
                        .description("US transportation tax")
                        .percentOfBase("7.5", "USD")
                        .journeys(Journeys.DOMESTIC)
                        .build(),
                TaxRule.builder("US", "US")
                        .description("US international departure tax")
                        .flat("22.90", "USD")
                        .applies(Applies.PER_INTERNATIONAL_DEPARTURE)
                        .journeys(Journeys.INTERNATIONAL)
                        .build(),
                TaxRule.builder("US", "US")
                        .description("US international arrival tax")
                        .flat("22.90", "USD")
                        .applies(Applies.PER_INTERNATIONAL_ARRIVAL)
                        .journeys(Journeys.INTERNATIONAL)
                        .build(),
                TaxRule.builder("ZP", "US")
                        .description("US flight segment tax")
                        .flat("5.00", "USD")
                        .applies(Applies.PER_COUPON)
                        .spares(PassengerType.INFANT)
                        .build(),
                TaxRule.builder("XF", "US")
                        .description("US passenger facility charge")
                        .flat("4.50", "USD")
                        .applies(Applies.PER_DEPARTURE)
                        .exemptTransit(PFC_TRANSIT_HOURS)
                        .maxPerDirection(PFC_PER_DIRECTION)
                        .spares(PassengerType.INFANT)
                        .build(),
                TaxRule.builder("FR", "FR")
                        .description("French civil aviation tax")
                        .flat("14.34", "EUR")
                        .applies(Applies.PER_INTERNATIONAL_DEPARTURE)
                        .exemptTransit(true)
                        .build(),
                TaxRule.builder("JP", "JP")
                        .description("Japan international tourist tax")
                        .flat("1000", "JPY")
                        .applies(Applies.PER_INTERNATIONAL_DEPARTURE)
                        .spares(PassengerType.INFANT, PassengerType.INFANT_WITH_SEAT)
                        .build(),
                TaxRule.builder("AE", "AE")
                        .description("UAE passenger service charge")
                        .flat("75.00", "AED")
                        .applies(Applies.PER_DEPARTURE)
                        .exemptTransit(true)
                        .build(),
                TaxRule.builder("YQ", "ZZ")
                        .description("Carrier imposed fuel surcharge")
                        .flat("180.00", "EUR")
                        .build()));
        return table;
    }

    /**
     * Whether a sector owes a rule at one of its ends: on leaving for the departure kinds, on landing
     * for the arrival kinds, and for the international ones only when the sector crosses a border.
     */
    private static boolean dueOn(TaxRule rule, Element sector, boolean leaving) {
        boolean crossing = !Geography.sameCountry(sector.origin(), sector.destination());
        return switch (rule.applies()) {
            case PER_DEPARTURE -> leaving;
            case PER_INTERNATIONAL_DEPARTURE -> leaving && crossing;
            case PER_ARRIVAL -> !leaving;
            case PER_INTERNATIONAL_ARRIVAL -> !leaving && crossing;
            case PER_TICKET, PER_COUPON -> false;
        };
    }

    /**
     * Whether the passenger is only passing through under this rule's own transit break.
     *
     * <p>A connecting passenger does not depart or arrive in the sense the tax means. What counts as
     * connecting is the hours the country filed, not the journey's stopover line: a tax that spares
     * a twelve hour connection charges the same passenger on a fourteen hour one, even though the
     * fare reads both as connections.
     */
    private static boolean inTransit(TaxRule rule, Optional<Break> ground) {
        return rule.exemptTransit()
                && ground.isPresent()
                && ground.get().duration().compareTo(Duration.ofHours(rule.transitHours())) <= 0;
    }

    /**
     * The break between an element and the next one, when both are flown. A surface sector is never a
     * transit, whatever the clock says, so a break next to one comes back empty.
     */
    private static Optional<Break> flownBreak(List<Element> elements, List<Break> breaks, int index) {
        if (elements.get(index) instanceof Segment && elements.get(index + 1) instanceof Segment) {
            return Optional.of(breaks.get(index));
        }
        return Optional.empty();
    }

    /**
     * A flat amount in its own currency, or the percentage of the base fare: the base times the rate,
     * then divided by a hundred. Rounded as a tax either way.
     */
    private static Money charge(TaxRule rule, Money base) {
        if (rule.basis() == Basis.FLAT) {
            return Money.roundTax(new Money(rule.amount(), rule.currency()));
        }
        BigDecimal product = base.amount().multiply(rule.amount(), Numbers.CONTEXT);
        return Money.roundTax(new Money(Numbers.divide(product, HUNDRED), base.currency()));
    }

    /**
     * What a journey owes so far, counting each rule's charges in each direction so a capped rule
     * stops being charged once it has been charged enough.
     *
     * <p>The direction is read off the turnaround: an element that ends at or before it is on the way
     * out and anything after it is on the way home.
     */
    private static final class Charges {

        private final Money base;
        private final int turnaround;
        private final PassengerType passenger;
        private final boolean international;
        private final List<AppliedTax> owed = new ArrayList<>();
        private final Map<TaxRule, int[]> counted = new HashMap<>();

        Charges(Money base, int turnaround, PassengerType passenger, boolean international) {
            this.base = base;
            this.turnaround = turnaround;
            this.passenger = passenger;
            this.international = international;
        }

        void charge(TaxRule rule, String point, int element) {
            if (rule.spares(passenger) || !rule.journeys().reads(international)) {
                return;
            }
            int[] times = counted.computeIfAbsent(rule, unused -> new int[2]);
            int direction = element < turnaround ? 0 : 1;
            if (rule.maxPerDirection().isPresent()
                    && times[direction] >= rule.maxPerDirection().get()) {
                return;
            }
            times[direction]++;
            owed.add(new AppliedTax(
                    rule.code(), Taxes.charge(rule, base), point, rule.description(), rule.isCarrierImposed()));
        }

        List<AppliedTax> list() {
            return List.copyOf(owed);
        }
    }
}
