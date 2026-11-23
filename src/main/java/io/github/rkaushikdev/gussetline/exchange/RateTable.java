package io.github.rkaushikdev.gussetline.exchange;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Currency;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
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

    /** The heading a rate file opens with, after any comment lines. */
    public static final String HEADING = "effective,code,units_per_nuc";

    /**
     * A table read from a rate file: a heading of {@link #HEADING}, then one rate per line as {@code
     * 2025-01-01,EUR,0.951}. Blank lines and lines starting with {@code #} are skipped, and white
     * space around a field is ignored.
     *
     * <p>Refused whole rather than read in part, because a table that silently lost one currency
     * prices every fare in it at whatever rate came before, which is a quarter out of date and looks
     * right.
     *
     * @throws ValidationError naming the {@code line} when the heading is missing, a line does not
     *     have three fields, a date or a rate does not read, a code is not a currency the desk sells
     *     in, or a currency is listed twice for one date
     */
    public static RateTable read(String name, String text) {
        RateTable table = new RateTable(name);
        boolean headed = false;
        String[] lines = text.split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = Text.strip(lines[index]);
            int number = index + 1;
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (!headed) {
                if (!line.equals(HEADING)) {
                    throw new ValidationError("a rate file opens with its heading", Details.of("line", number));
                }
                headed = true;
                continue;
            }
            table.addLine(line, number);
        }
        if (!headed) {
            throw new ValidationError("a rate file opens with its heading", Details.of("line", lines.length));
        }
        return table;
    }

    private void addLine(String line, int number) {
        List<String> fields =
                Arrays.stream(line.split(",", -1)).map(Text::strip).toList();
        if (fields.size() != 3) {
            throw new ValidationError("a rate line has three fields", Details.of("line", number));
        }
        LocalDate effective;
        try {
            effective = LocalDate.parse(fields.get(0));
        } catch (DateTimeParseException unreadable) {
            throw new ValidationError("not a date", Details.of("line", number, "value", fields.get(0)));
        }
        String code = fields.get(1).toUpperCase(Locale.ROOT);
        NavigableMap<LocalDate, Rate> held = byCode.get(code);
        if (held != null && held.containsKey(effective)) {
            throw new ValidationError(
                    "a currency is listed twice for one date", Details.of("line", number, "code", code));
        }
        try {
            add(code, effective, fields.get(2));
        } catch (NumberFormatException unreadable) {
            throw new ValidationError("not a rate", Details.of("line", number, "value", fields.get(2)));
        } catch (ValidationError refused) {
            throw new ValidationError(refused.message(), Details.of("line", number, "code", code));
        }
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
