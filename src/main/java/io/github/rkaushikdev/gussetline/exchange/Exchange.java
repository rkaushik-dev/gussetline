package io.github.rkaushikdev.gussetline.exchange;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

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

    /** Where the shipped rates of exchange are kept, next to this class. */
    public static final String SHIPPED_ROE = "roe.csv";

    /**
     * The rates the desk ships with, one quarter per block, named {@code roe}, read from {@link
     * #SHIPPED_ROE} ({@link RateTable#read}).
     *
     * <p>These are the published IATA rates for the currencies the desk sells in. They ship with the
     * build rather than being fetched because a quarter that fails to download must not silently
     * price at last quarter's rate, and they are kept as the rate file IATA publishes rather than as
     * code so a new quarter is a new block of rows and nothing else.
     *
     * @throws IllegalStateException when the build was made without the file
     */
    public static RateTable defaultRoeTable() {
        try (InputStream shipped = Exchange.class.getResourceAsStream(SHIPPED_ROE)) {
            if (shipped == null) {
                throw new IllegalStateException("the shipped rates of exchange are missing from the build");
            }
            return RateTable.read("roe", new String(shipped.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }
}
