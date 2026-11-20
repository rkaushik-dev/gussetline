package io.github.rkaushikdev.gussetline.money;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A selling currency and how amounts in it are rounded.
 *
 * <p>{@code exponent} is the number of minor unit digits. {@code fareUnit} is the step a constructed
 * fare is rounded up to, expressed in major units, and it is frequently coarser than the minor unit.
 * Japan publishes fares to the hundred yen even though the yen has no minor unit at all.
 *
 * @param code the ISO 4217 code
 * @param exponent the number of minor unit digits
 * @param fareUnit the step a constructed fare rounds up to, in major units
 * @param name the currency's name
 */
public record Currency(String code, int exponent, BigDecimal fareUnit, String name) {

    /**
     * The neutral unit fares are constructed in. It is not a currency anybody sells in, it always
     * carries two decimals, and it truncates rather than rounds, which is why it is not in {@link
     * #SELLING}.
     */
    public static final Currency NUC = new Currency("NUC", 2, new BigDecimal("0.01"), "Neutral Unit of Construction");

    /** The currencies the desk sells in, by code, in a settled order. */
    public static final Map<String, Currency> SELLING = table(
            row("EUR", 2, "1", "Euro"),
            row("USD", 2, "1", "US Dollar"),
            row("GBP", 2, "1", "Pound Sterling"),
            row("CHF", 2, "1", "Swiss Franc"),
            row("SEK", 2, "5", "Swedish Krona"),
            row("NOK", 2, "5", "Norwegian Krone"),
            row("DKK", 2, "5", "Danish Krone"),
            row("PLN", 2, "1", "Zloty"),
            row("CZK", 2, "10", "Czech Koruna"),
            row("HUF", 2, "100", "Forint"),
            row("JPY", 0, "100", "Yen"),
            row("KRW", 0, "100", "Won"),
            row("CNY", 2, "1", "Yuan Renminbi"),
            row("INR", 2, "5", "Indian Rupee"),
            row("BRL", 2, "1", "Brazilian Real"),
            row("ZAR", 2, "1", "Rand"),
            row("AED", 2, "5", "UAE Dirham"),
            row("KWD", 3, "0.5", "Kuwaiti Dinar"),
            row("BHD", 3, "0.5", "Bahraini Dinar"),
            row("TND", 3, "0.5", "Tunisian Dinar"),
            row("CAD", 2, "1", "Canadian Dollar"),
            row("AUD", 2, "1", "Australian Dollar"),
            row("NZD", 2, "1", "New Zealand Dollar"),
            row("SGD", 2, "1", "Singapore Dollar"),
            row("HKD", 2, "1", "Hong Kong Dollar"),
            row("THB", 2, "5", "Baht"),
            row("TRY", 2, "1", "Turkish Lira"),
            row("MXN", 2, "1", "Mexican Peso"));

    /** One minor unit: 0.01 for the euro, 1 for the yen, 0.001 for the dinar. */
    public BigDecimal minor() {
        return BigDecimal.ONE.scaleByPowerOfTen(-exponent);
    }

    /** The step a tax or a refund is rounded to, which is the minor unit. */
    public BigDecimal quantum() {
        return minor();
    }

    /**
     * Look a currency up, ignoring case and surrounding white space, refusing anything the desk
     * cannot sell in. The neutral unit answers to {@code NUC}.
     *
     * @throws ValidationError when the code is not one of ours
     */
    public static Currency of(String code) {
        String key = Text.strip(code).toUpperCase(Locale.ROOT);
        if (key.equals(NUC.code())) {
            return NUC;
        }
        Currency found = SELLING.get(key);
        if (found == null) {
            throw new ValidationError("unknown currency", Details.of("code", code));
        }
        return found;
    }

    private static Currency row(String code, int exponent, String unit, String name) {
        return new Currency(code, exponent, new BigDecimal(unit), name);
    }

    private static Map<String, Currency> table(Currency... rows) {
        Map<String, Currency> byCode = new LinkedHashMap<>();
        for (Currency row : rows) {
            byCode.put(row.code(), row);
        }
        return Collections.unmodifiableMap(byCode);
    }
}
