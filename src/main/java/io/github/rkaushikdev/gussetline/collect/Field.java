package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;

/**
 * One field of a fixed width record: where it sits on the line, and how to read and write it.
 *
 * <p>Offsets count characters from the start of the line, the record tag included.
 *
 * @param name the name the value is kept under
 * @param start the offset of its first character
 * @param length how many characters it takes
 * @param kind how its characters turn into a value and back
 * @param optional whether a field of nothing but spaces is simply absent rather than a problem
 */
public record Field(String name, int start, int length, Kind kind, boolean optional) {

    /** A required text field. */
    public Field(String name, int start, int length) {
        this(name, start, length, Kind.TEXT, false);
    }

    /** A required field of a kind. */
    public Field(String name, int start, int length, Kind kind) {
        this(name, start, length, kind, false);
    }

    /** The offset just past its last character. */
    public int end() {
        return start + length;
    }

    /** Its characters on a line, exactly as they stand: as many as the line has, spaces kept. */
    public String sliceFrom(String line) {
        return Text.slice(line, start, end());
    }

    /** How the characters of a field turn into a value, and how a value is written back. */
    public enum Kind {
        /** Text without its surrounding white space. A blank field reads as empty text. */
        TEXT,
        /** The characters exactly as sent, spaces and all. A blank field reads as itself. */
        RAW,
        /** A whole number, read as an {@link Integer}. Written zero padded on the left. */
        INT,
        /**
         * An amount with two implied decimal places, read as a {@link BigDecimal}: {@code 000042000}
         * is four hundred and twenty. The decimal point is not in the payload and never has been. A
         * negative amount carries a leading minus.
         */
        AMOUNT,
        /** An amount with no decimal places, as for yen. */
        AMOUNT0,
        /** An amount with three implied decimal places, as for dinar. */
        AMOUNT3,
        /** A date written {@code yyyymmdd}, read as a {@link LocalDate}. */
        DATE,
        /** A day and a time written {@code mmddhhmm}, read as a {@link DayTime}. */
        DAYTIME;

        /** Whether a field of this kind that is nothing but spaces is a value rather than missing. */
        boolean readsBlank() {
            return this == TEXT || this == RAW;
        }

        /**
         * The value a field's characters stand for.
         *
         * @throws IllegalArgumentException when the characters are not one
         */
        Object read(String raw) {
            return switch (this) {
                case TEXT -> Text.strip(raw);
                case RAW -> raw;
                case INT -> Numbers.parseInt(Text.strip(raw));
                case AMOUNT -> readImplied(raw, 2);
                case AMOUNT0 -> readImplied(raw, 0);
                case AMOUNT3 -> readImplied(raw, 3);
                case DATE -> readDate(raw);
                case DAYTIME -> readDayTime(raw);
            };
        }

        /**
         * A value written into a field {@code width} wide. Text is cut or padded to fit exactly;
         * anything else comes back at its natural width, and the caller refuses one that does not fit.
         *
         * @throws IllegalArgumentException when the value is not one this kind can write
         */
        String write(Object value, int width) {
            return switch (this) {
                case TEXT, RAW -> padRight(Text.slice(Text.plain(value), 0, width), width, ' ');
                case INT -> padLeft(wholeNumber(value), width, '0');
                case AMOUNT -> writeImplied(value, width, 2);
                case AMOUNT0 -> writeImplied(value, width, 0);
                case AMOUNT3 -> writeImplied(value, width, 3);
                case DATE -> padLeft(writeDate(value), width, ' ');
                case DAYTIME -> padLeft(writeDayTime(value), width, ' ');
            };
        }

        private static BigDecimal readImplied(String raw, int scale) {
            String digits = Text.strip(raw);
            String unsigned = stripLeadingMinus(digits);
            if (!Text.isDigits(unsigned)) {
                throw new IllegalArgumentException("not a number: " + Text.repr(raw));
            }
            BigDecimal sign = digits.startsWith("-") ? BigDecimal.ONE.negate() : BigDecimal.ONE;
            String written = unsigned;
            if (scale > 0) {
                String padded = padLeft(unsigned, scale + 1, '0');
                int point = padded.length() - scale;
                written = padded.substring(0, point) + "." + padded.substring(point);
            }
            return Numbers.parseDecimal(written).multiply(sign, Numbers.CONTEXT);
        }

        private static LocalDate readDate(String raw) {
            String text = Text.strip(raw);
            if (Text.length(text) == 8) {
                try {
                    return LocalDate.parse(text, DateTimeFormatter.BASIC_ISO_DATE);
                } catch (DateTimeParseException unreadable) {
                    throw new IllegalArgumentException("not a date: " + Text.repr(raw), unreadable);
                }
            }
            throw new IllegalArgumentException("not a date: " + Text.repr(raw));
        }

        private static DayTime readDayTime(String raw) {
            String text = Text.strip(raw);
            if (text.length() != 8 || !Text.isDigits(text)) {
                throw new IllegalArgumentException("not a day and time: " + Text.repr(raw));
            }
            return new DayTime(
                    Numbers.parseInt(text.substring(0, 2)),
                    Numbers.parseInt(text.substring(2, 4)),
                    Numbers.parseInt(text.substring(4, 6)),
                    Numbers.parseInt(text.substring(6, 8)));
        }

        /** The digits of a whole number, a fraction cut off toward zero. */
        private static String wholeNumber(Object value) {
            if (value instanceof BigDecimal amount) {
                return amount.toBigInteger().toString();
            }
            if (value instanceof BigInteger whole) {
                return whole.toString();
            }
            if (value instanceof Double || value instanceof Float) {
                return new BigDecimal(((Number) value).doubleValue())
                        .toBigInteger()
                        .toString();
            }
            if (value instanceof Number whole) {
                return Long.toString(whole.longValue());
            }
            if (value instanceof CharSequence text) {
                return Long.toString(Numbers.parseLong(text.toString()));
            }
            throw new IllegalArgumentException("not a whole number: " + Text.repr(value));
        }

        /** An implied decimal written back out: rounded to its places, sign first and zero padded. */
        private static String writeImplied(Object value, int width, int scale) {
            BigDecimal amount = Numbers.parseDecimal(Text.plain(value)).setScale(scale, RoundingMode.HALF_EVEN);
            boolean negative = amount.signum() < 0;
            String digits = amount.abs().toPlainString().replace(".", "");
            String body = padLeft(digits, negative ? width - 1 : width, '0');
            return negative ? "-" + body : body;
        }

        private static String writeDate(Object value) {
            if (value instanceof LocalDate day) {
                return DateTimeFormatter.BASIC_ISO_DATE.format(day);
            }
            if (value instanceof LocalDateTime moment) {
                return DateTimeFormatter.BASIC_ISO_DATE.format(moment.toLocalDate());
            }
            throw new IllegalArgumentException("not a date: " + Text.repr(value));
        }

        private static String writeDayTime(Object value) {
            if (value instanceof DayTime when) {
                return String.format(
                        Locale.ROOT, "%02d%02d%02d%02d", when.month(), when.day(), when.hour(), when.minute());
            }
            throw new IllegalArgumentException("not a day and time: " + Text.repr(value));
        }

        private static String stripLeadingMinus(String text) {
            int start = 0;
            while (start < text.length() && text.charAt(start) == '-') {
                start++;
            }
            return text.substring(start);
        }

        private static String padLeft(String text, int width, char pad) {
            int missing = width - Text.length(text);
            return missing > 0 ? String.valueOf(pad).repeat(missing) + text : text;
        }

        private static String padRight(String text, int width, char pad) {
            int missing = width - Text.length(text);
            return missing > 0 ? text + String.valueOf(pad).repeat(missing) : text;
        }
    }
}
