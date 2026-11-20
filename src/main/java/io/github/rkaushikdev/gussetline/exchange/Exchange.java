package io.github.rkaushikdev.gussetline.exchange;

import static java.util.Map.entry;

import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Rates of exchange, and the two of them that are not the same rate.
 *
 * <p>A fare is published in the currency of the country of commencement and is converted into
 * neutral units with the rate of exchange, which is published quarterly and is deliberately stale.
 * A tax is converted with the bankers selling rate, which moves daily. Using the rate of exchange
 * for a tax is the single most common cause of a supplier quote and our reconstruction differing by
 * a few units, and it looks like a rounding argument rather than the wrong table.
 *
 * <p>Both tables are effective dated ({@link RateTable}). A lookup asks for the date of ticketing,
 * never today, because a reconstruction of a ticket sold in March has to use the rate that was in
 * force in March or it will not reproduce the number on the coupon. {@link Converter} does the
 * conversions.
 */
public final class Exchange {

    private Exchange() {}

    /** The first day of the IATA quarter a date falls in. */
    public static LocalDate quarterStart(LocalDate on) {
        int month = (on.getMonthValue() - 1) / 3 * 3 + 1;
        return LocalDate.of(on.getYear(), month, 1);
    }

    /**
     * The rates the desk ships with, one quarter per block, named {@code roe}.
     *
     * <p>These are the published IATA rates for the currencies the desk sells in. They are kept in
     * the code rather than fetched because a quarter that fails to download must not silently price
     * at last quarter's rate.
     */
    public static RateTable defaultRoeTable() {
        RateTable table = new RateTable("roe");
        table.addMany(
                LocalDate.of(2024, 10, 1),
                rates(
                        entry("EUR", "0.913"),
                        entry("USD", "1.000"),
                        entry("GBP", "0.766"),
                        entry("CHF", "0.859"),
                        entry("SEK", "10.401"),
                        entry("NOK", "10.686"),
                        entry("DKK", "6.812"),
                        entry("PLN", "3.926"),
                        entry("CZK", "22.938"),
                        entry("HUF", "364.42"),
                        entry("JPY", "146.86"),
                        entry("KRW", "1329.62"),
                        entry("CNY", "7.099"),
                        entry("INR", "83.92"),
                        entry("CAD", "1.354"),
                        entry("AUD", "1.478"),
                        entry("SGD", "1.301"),
                        entry("HKD", "7.783"),
                        entry("AED", "3.673"),
                        entry("THB", "33.42"),
                        entry("TRY", "34.11")));
        table.addMany(
                LocalDate.of(2025, 1, 1),
                rates(
                        entry("EUR", "0.951"),
                        entry("USD", "1.000"),
                        entry("GBP", "0.789"),
                        entry("CHF", "0.896"),
                        entry("SEK", "10.968"),
                        entry("NOK", "11.163"),
                        entry("DKK", "7.093"),
                        entry("PLN", "4.070"),
                        entry("CZK", "23.902"),
                        entry("HUF", "391.66"),
                        entry("JPY", "152.44"),
                        entry("KRW", "1421.09"),
                        entry("CNY", "7.264"),
                        entry("INR", "84.71"),
                        entry("CAD", "1.416"),
                        entry("AUD", "1.575"),
                        entry("SGD", "1.348"),
                        entry("HKD", "7.775"),
                        entry("AED", "3.673"),
                        entry("THB", "34.16"),
                        entry("TRY", "35.29")));
        table.addMany(
                LocalDate.of(2025, 4, 1),
                rates(
                        entry("EUR", "0.924"),
                        entry("USD", "1.000"),
                        entry("GBP", "0.774"),
                        entry("CHF", "0.883"),
                        entry("SEK", "10.045"),
                        entry("NOK", "10.501"),
                        entry("DKK", "6.892"),
                        entry("PLN", "3.869"),
                        entry("CZK", "23.117"),
                        entry("HUF", "371.05"),
                        entry("JPY", "149.51"),
                        entry("KRW", "1461.32"),
                        entry("CNY", "7.257"),
                        entry("INR", "85.52"),
                        entry("CAD", "1.437"),
                        entry("AUD", "1.594"),
                        entry("SGD", "1.343"),
                        entry("HKD", "7.777"),
                        entry("AED", "3.673"),
                        entry("THB", "34.03"),
                        entry("TRY", "37.92")));
        return table;
    }

    /** Codes and their rates, kept in the order written. */
    @SafeVarargs
    private static Map<String, String> rates(Map.Entry<String, String>... rows) {
        Map<String, String> quarter = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            quarter.put(row.getKey(), row.getValue());
        }
        return Collections.unmodifiableMap(quarter);
    }
}
