package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Break;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.Duration;
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
     * at a point and the same country can be entered twice on one journey and owe twice. Departure
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
        Charges owed = new Charges(base, itinerary.turnaroundIndex());
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
                boolean due =
                        switch (rule.applies()) {
                            case PER_DEPARTURE -> true;
                            case PER_INTERNATIONAL_DEPARTURE -> !Geography.sameCountry(departing, arriving);
                            default -> false;
                        };
                if (due && !inTransit(rule, before)) {
                    owed.charge(rule, departing, index);
                }
            }

            for (TaxRule rule : table.forCountry(Geography.airportOrCityCountry(arriving))) {
                if (rule.applies() == Applies.PER_ARRIVAL && !inTransit(rule, after)) {
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
        Money running = Money.zero(currency);
        for (AppliedTax tax : taxes) {
            if (!tax.carrierImposed()) {
                running = running.plus(tax.amount());
            }
        }
        return running;
    }

    /** Everything the carrier keeps through the tax box. Nothing owed is zero. */
    public static Money carrierTotal(Iterable<AppliedTax> taxes, String currency) {
        Money running = Money.zero(currency);
        for (AppliedTax tax : taxes) {
            if (tax.carrierImposed()) {
                running = running.plus(tax.amount());
            }
        }
        return running;
    }

    /** The taxes the desk sees on the markets it actually sells. A new table each call. */
    public static TaxTable defaultTaxTable() {
        TaxTable table = new TaxTable();
        table.addMany(List.of(
                new TaxRule(
                        "DE",
                        "DE",
                        "German air transport tax",
                        Basis.FLAT,
                        new BigDecimal("70.83"),
                        "EUR",
                        Applies.PER_INTERNATIONAL_DEPARTURE,
                        true),
                new TaxRule(
                        "RA",
                        "DE",
                        "German security charge",
                        Basis.FLAT,
                        new BigDecimal("10.00"),
                        "EUR",
                        Applies.PER_DEPARTURE,
                        true),
                new TaxRule(
                        "GB",
                        "GB",
                        "UK air passenger duty",
                        Basis.FLAT,
                        new BigDecimal("88.00"),
                        "GBP",
                        Applies.PER_INTERNATIONAL_DEPARTURE,
                        true),
                new TaxRule(
                        "UB",
                        "GB",
                        "UK passenger service charge",
                        Basis.FLAT,
                        new BigDecimal("31.30"),
                        "GBP",
                        Applies.PER_DEPARTURE,
                        true),
                new TaxRule(
                        "US",
                        "US",
                        "US transportation tax",
                        Basis.PERCENT_OF_BASE,
                        new BigDecimal("7.5"),
                        "USD",
                        Applies.PER_TICKET),
                new TaxRule(
                        "ZP",
                        "US",
                        "US flight segment tax",
                        Basis.FLAT,
                        new BigDecimal("5.00"),
                        "USD",
                        Applies.PER_COUPON),
                new TaxRule(
                                "XF",
                                "US",
                                "US passenger facility charge",
                                Basis.FLAT,
                                new BigDecimal("4.50"),
                                "USD",
                                Applies.PER_DEPARTURE,
                                true,
                                PFC_TRANSIT_HOURS)
                        .withMaxPerDirection(PFC_PER_DIRECTION),
                new TaxRule(
                        "FR",
                        "FR",
                        "French civil aviation tax",
                        Basis.FLAT,
                        new BigDecimal("14.34"),
                        "EUR",
                        Applies.PER_INTERNATIONAL_DEPARTURE,
                        true),
                new TaxRule(
                        "JP",
                        "JP",
                        "Japan international tourist tax",
                        Basis.FLAT,
                        new BigDecimal("1000"),
                        "JPY",
                        Applies.PER_INTERNATIONAL_DEPARTURE),
                new TaxRule(
                        "AE",
                        "AE",
                        "UAE passenger service charge",
                        Basis.FLAT,
                        new BigDecimal("75.00"),
                        "AED",
                        Applies.PER_DEPARTURE,
                        true),
                new TaxRule(
                        "YQ",
                        "ZZ",
                        "Carrier imposed fuel surcharge",
                        Basis.FLAT,
                        new BigDecimal("180.00"),
                        "EUR",
                        Applies.PER_TICKET)));
        return table;
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
        private final List<AppliedTax> owed = new ArrayList<>();
        private final Map<TaxRule, int[]> counted = new HashMap<>();

        Charges(Money base, int turnaround) {
            this.base = base;
            this.turnaround = turnaround;
        }

        void charge(TaxRule rule, String point, int element) {
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
