package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
 * the tax means. So whether a country's tax applies depends on whether the break at that point was a
 * connection or a stopover, which is a reading of the clock made in the {@link Itinerary}. One rule
 * deciding what another rule measures is the normal state of this class.
 */
public final class Taxes {

    /** The two codes that are carrier money rather than government money. */
    public static final Set<String> CARRIER_IMPOSED = Set.of("YQ", "YR");

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private Taxes() {}

    /**
     * Work out every tax and surcharge owed on a journey.
     *
     * <p>The itinerary is walked point by point rather than country by country, because a tax is owed
     * at a point and the same country can be entered twice on one journey and owe twice. Departure
     * and arrival taxes come first, element by element; then the taxes owed once per ticket and once
     * per coupon in the country the journey starts in; then the carrier surcharges, at the origin.
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
        List<AppliedTax> owed = new ArrayList<>();
        Set<String> stopovers = Set.copyOf(itinerary.stopovers());
        Set<String> connections = Set.copyOf(itinerary.connections());
        List<Element> elements = itinerary.elements();

        for (int index = 0; index < elements.size(); index++) {
            Element element = elements.get(index);
            String departing = element.origin();
            String arriving = element.destination();
            boolean first = index == 0;
            boolean last = index == elements.size() - 1;

            for (TaxRule rule : table.forCountry(Geography.airportOrCityCountry(departing))) {
                if (rule.applies() == Applies.PER_DEPARTURE) {
                    if (!exempt(rule, departing, first, connections)) {
                        owed.add(apply(rule, base, departing));
                    }
                } else if (rule.applies() == Applies.PER_INTERNATIONAL_DEPARTURE) {
                    if (!Geography.sameCountry(departing, arriving) && !exempt(rule, departing, first, connections)) {
                        owed.add(apply(rule, base, departing));
                    }
                }
            }

            for (TaxRule rule : table.forCountry(Geography.airportOrCityCountry(arriving))) {
                if (rule.applies() == Applies.PER_ARRIVAL) {
                    boolean transiting = rule.exemptTransit() && !last && !stopovers.contains(arriving);
                    if (!transiting) {
                        owed.add(apply(rule, base, arriving));
                    }
                }
            }
        }

        List<TaxRule> atOrigin = table.forCountry(Geography.airportOrCityCountry(itinerary.origin()));
        for (TaxRule rule : atOrigin) {
            if (rule.applies() == Applies.PER_TICKET) {
                owed.add(apply(rule, base, itinerary.origin()));
            }
        }
        for (TaxRule rule : atOrigin) {
            if (rule.applies() == Applies.PER_COUPON) {
                for (Element element : elements) {
                    owed.add(apply(rule, base, element.origin()));
                }
            }
        }

        for (TaxRule rule : table.carrierRules()) {
            owed.add(apply(rule, base, itinerary.origin()));
        }
        return List.copyOf(owed);
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
                        true),
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

    /** A connecting passenger does not depart in the sense a departure tax means. */
    private static boolean exempt(TaxRule rule, String point, boolean first, Set<String> connections) {
        if (!rule.exemptTransit()) {
            return false;
        }
        if (first) {
            return false;
        }
        return connections.contains(point);
    }

    private static AppliedTax apply(TaxRule rule, Money base, String point) {
        return new AppliedTax(rule.code(), charge(rule, base), point, rule.description(), rule.isCarrierImposed());
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
}
