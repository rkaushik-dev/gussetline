package io.github.rkaushikdev.gussetline.util;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Reading and writing numbers the one way the desk does everywhere.
 *
 * <p>Amounts are exact decimals. Arithmetic on them is exact, except division, which runs at
 * {@link #CONTEXT}: 28 significant digits with ties to the even neighbour. Text is read leniently
 * about white space at the ends and about single underscores between digits ({@code 1_000}), and
 * strictly about everything else.
 */
public final class Numbers {

    /** Twenty-eight significant digits, ties to the even neighbour, for every decimal division. */
    public static final MathContext CONTEXT = new MathContext(28, RoundingMode.HALF_EVEN);

    private static final String DIGITS = "\\d(?:_?\\d)*";

    private static final Pattern INTEGER = Pattern.compile("[+-]?" + DIGITS, Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(?:" + DIGITS + "(?:\\.(?:" + DIGITS + ")?)?|\\." + DIGITS + ")(?:[eE][+-]?" + DIGITS + ")?",
            Pattern.UNICODE_CHARACTER_CLASS);

    private static final Pattern NON_FINITE = Pattern.compile("[+-]?(?:inf|infinity|nan)", Pattern.CASE_INSENSITIVE);

    private Numbers() {}

    /** {@code dividend / divisor} at {@link #CONTEXT}. A zero divisor throws {@link ArithmeticException}. */
    public static BigDecimal divide(BigDecimal dividend, BigDecimal divisor) {
        return dividend.divide(divisor, CONTEXT);
    }

    /**
     * A whole number written in decimal digits, with an optional sign.
     *
     * @throws NumberFormatException when the text is anything else, or does not fit an {@code int}
     */
    public static int parseInt(String text) {
        long value = parseLong(text);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new NumberFormatException("too large for a whole number: " + Text.quote(text));
        }
        return (int) value;
    }

    /**
     * A whole number written in decimal digits, with an optional sign.
     *
     * @throws NumberFormatException when the text is anything else, or does not fit a {@code long}
     */
    public static long parseLong(String text) {
        String stripped = Text.strip(text);
        if (!INTEGER.matcher(stripped).matches()) {
            throw new NumberFormatException("not a whole number: " + Text.quote(text));
        }
        return Long.parseLong(ascii(stripped));
    }

    /**
     * An exact decimal: digits with an optional fraction and exponent, such as {@code 12.50},
     * {@code .5}, {@code 3.} or {@code 1E+3}. Not-a-number and infinity are refused, because no
     * amount is either.
     *
     * @throws NumberFormatException when the text is not a finite decimal
     */
    public static BigDecimal parseDecimal(String text) {
        String stripped = Text.strip(text);
        if (!DECIMAL.matcher(stripped).matches()) {
            throw new NumberFormatException("not a decimal: " + Text.quote(text));
        }
        return new BigDecimal(ascii(stripped));
    }

    /**
     * A binary floating point number: a decimal as {@link #parseDecimal} reads it, or {@code inf},
     * {@code infinity} or {@code nan} in any case, with an optional sign.
     *
     * @throws NumberFormatException when the text is neither
     */
    public static double parseFloat(String text) {
        String stripped = Text.strip(text);
        if (NON_FINITE.matcher(stripped).matches()) {
            boolean negative = stripped.startsWith("-");
            if (stripped.toLowerCase(Locale.ROOT).endsWith("nan")) {
                return Double.NaN;
            }
            return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
        }
        if (!DECIMAL.matcher(stripped).matches()) {
            throw new NumberFormatException("not a number: " + Text.quote(text));
        }
        return Double.parseDouble(ascii(stripped));
    }

    /**
     * The shortest text that reads back as the same {@code double}: positional between 1e-4 and
     * 1e16 and always with a fraction ({@code 1.0}, {@code 0.0001}), scientific outside that range
     * with a signed exponent of at least two digits ({@code 1e+16}, {@code 1e-05}).
     */
    public static String floatText(double value) {
        if (Double.isNaN(value)) {
            return "nan";
        }
        if (Double.isInfinite(value)) {
            return value > 0 ? "inf" : "-inf";
        }
        String sign = value < 0 || (value == 0 && 1 / value < 0) ? "-" : "";
        if (value == 0) {
            return sign + "0.0";
        }
        BigDecimal shortest = new BigDecimal(Double.toString(Math.abs(value))).stripTrailingZeros();
        String digits = shortest.unscaledValue().toString();
        int exponent = digits.length() - 1 - shortest.scale();
        if (exponent < -4 || exponent >= 16) {
            String mantissa = digits.length() == 1 ? digits : digits.charAt(0) + "." + digits.substring(1);
            return sign + mantissa + "e" + (exponent < 0 ? "-" : "+")
                    + String.format(Locale.ROOT, "%02d", Math.abs(exponent));
        }
        String plain = shortest.toPlainString();
        return sign + (plain.indexOf('.') < 0 ? plain + ".0" : plain);
    }

    /**
     * A {@code double} to a fixed number of decimal places, rounding the exact binary value with
     * ties to the even neighbour. A negative value that rounds to zero keeps its minus sign.
     */
    public static String fixed(double value, int places) {
        BigDecimal rounded = new BigDecimal(value).setScale(places, RoundingMode.HALF_EVEN);
        boolean negative = value < 0 || (value == 0 && 1 / value < 0);
        return signed(rounded, negative);
    }

    /**
     * A decimal to a fixed number of places, ties to the even neighbour, never in scientific form.
     * A negative value that rounds to zero keeps its minus sign.
     */
    public static String fixed(BigDecimal value, int places) {
        return signed(value.setScale(places, RoundingMode.HALF_EVEN), value.signum() < 0);
    }

    /** Whether a decimal is a whole number, whatever its scale. */
    public static boolean isWhole(BigDecimal value) {
        return value.signum() == 0 || value.stripTrailingZeros().scale() <= 0;
    }

    private static String signed(BigDecimal rounded, boolean negative) {
        String plain = rounded.toPlainString();
        return negative && rounded.signum() == 0 ? "-" + plain : plain;
    }

    /** The text with underscores removed and every decimal digit in ASCII. */
    private static String ascii(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '_') {
                continue;
            }
            int digit = Character.digit(character, 10);
            out.append(Character.isDigit(character) && digit >= 0 ? (char) ('0' + digit) : character);
        }
        return out.toString();
    }
}
