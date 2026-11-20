package io.github.rkaushikdev.gussetline.exchange;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Currency;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Effective dated rates for many currencies, queried by date.
 *
 * <p>Rates are added out of order often enough that the table keeps them sorted itself rather than
 * trusting the caller. A lookup takes the latest rate on or before the date asked for, which is
 * what an effective dated table means, and refuses rather than guessing when the date is before the
 * first rate held.
 */
public final class RateTable {

    private final String name;
    private final Map<String, NavigableMap<LocalDate, Rate>> byCode = new TreeMap<>();

    /** An empty table, named for the complaints it raises, such as {@code roe} or {@code bsr}. */
    public RateTable(String name) {
        this.name = Objects.requireNonNull(name, "name");
    }

    public String name() {
        return name;
    }

    /**
     * Hold a rate written as decimal text, such as {@code "0.951"}.
     *
     * @throws NumberFormatException when the text is not a decimal
     * @throws ValidationError when the rate is not positive or the code is not a currency we sell in
     * @see #add(String, LocalDate, BigDecimal)
     */
    public Rate add(String code, LocalDate effective, String unitsPerNuc) {
        return add(code, effective, Numbers.parseDecimal(unitsPerNuc));
    }

    /**
     * Hold a rate from a date. The code is read in upper case. A second rate for a currency on a
     * date already held replaces the first.
     *
     * @throws ValidationError when the rate is not positive or the code is not a currency we sell in
     */
    public Rate add(String code, LocalDate effective, BigDecimal unitsPerNuc) {
        Rate rate = new Rate(code.toUpperCase(Locale.ROOT), effective, unitsPerNuc);
        Currency.of(rate.code());
        byCode.computeIfAbsent(rate.code(), held -> new TreeMap<>()).put(rate.effective(), rate);
        return rate;
    }

    /** Hold one quarter's rates for several currencies, each written as decimal text. */
    public void addMany(LocalDate effective, Map<String, String> rates) {
        rates.forEach((code, value) -> add(code, effective, value));
    }

    /** The codes the table holds a rate for, sorted. */
    public List<String> codes() {
        return List.copyOf(byCode.keySet());
    }

    /**
     * The rate in force on a date: the latest one on or before it. The code is read in upper case.
     *
     * @throws ValidationError when the table holds no rate for the currency, or its first rate comes
     *     after the date
     */
    public Rate lookup(String code, LocalDate on) {
        String key = code.toUpperCase(Locale.ROOT);
        NavigableMap<LocalDate, Rate> rows = byCode.get(key);
        if (rows == null) {
            throw new ValidationError("no rate held", Details.of("table", name, "code", key));
        }
        Map.Entry<LocalDate, Rate> inForce = rows.floorEntry(on);
        if (inForce == null) {
            throw new ValidationError(
                    "rate table starts after the date asked for",
                    Details.of(
                            "table",
                            name,
                            "code",
                            key,
                            "on",
                            on.toString(),
                            "first",
                            rows.firstKey().toString()));
        }
        return inForce.getValue();
    }

    /**
     * The units of a currency one neutral unit buys on a date.
     *
     * @throws ValidationError as {@link #lookup} does
     */
    public BigDecimal rateFor(String code, LocalDate on) {
        return lookup(code, on).unitsPerNuc();
    }
}
