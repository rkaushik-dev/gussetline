package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.YearMonth;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Values read out of the text a caller sent, as leniently as the service promises and no more.
 *
 * <p>A whole number may carry surrounding white space and a fraction of nothing but zeros ({@code
 * " 12.0 "}). A day is {@code 2025-08-04}, or a moment at midnight. A moment is a day, a {@code T},
 * {@code t}, {@code _} or space, and {@code HH:MM}, optionally with seconds, a fraction of a second
 * (kept to the microsecond) and an offset, which is read past and not applied: a sector time is a
 * wall clock at the airport. A day on its own reads as a moment at midnight.
 *
 * <p>Every refusal is an {@link Unfit} naming what is wrong in the same words whichever route asked.
 */
final class WireText {

    private static final Pattern WHOLE = Pattern.compile("([+-]?[0-9]+)(?:\\.0*)?");
    private static final int MICROSECOND_DIGITS = 6;
    private static final int HOURS_IN_A_DAY = 24;

    private WireText() {}

    /** A whole number, held to the range of an {@code int}: anything further out reads as the end it passed. */
    static int integer(String text) {
        long whole = whole(text);
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, whole));
    }

    /** A whole number, held to the range of a {@code long}. */
    static long whole(String text) {
        Matcher matched = WHOLE.matcher(Text.strip(text));
        if (!matched.matches()) {
            throw new Unfit("int_parsing", "Input should be a valid integer, unable to parse string as an integer");
        }
        BigInteger read = new BigInteger(matched.group(1));
        return read.max(BigInteger.valueOf(Long.MIN_VALUE))
                .min(BigInteger.valueOf(Long.MAX_VALUE))
                .longValue();
    }

    /** A day, or a moment at exactly midnight. */
    static LocalDate date(String text) {
        try {
            return new Reader(text).day();
        } catch (Reason notADay) {
            LocalDateTime moment;
            try {
                moment = new Reader(text).moment();
            } catch (Reason why) {
                throw new Unfit(
                        "date_from_datetime_parsing",
                        "Input should be a valid date or datetime, " + why.getMessage(),
                        Map.of("error", why.getMessage()));
            }
            if (!moment.toLocalTime().equals(LocalTime.MIDNIGHT)) {
                throw new Unfit(
                        "date_from_datetime_inexact",
                        "Datetimes provided to dates should have zero time - e.g. be exact dates");
            }
            return moment.toLocalDate();
        }
    }

    /** A moment, or a day read as its midnight. */
    static LocalDateTime dateTime(String text) {
        try {
            return new Reader(text).moment();
        } catch (Reason notAMoment) {
            try {
                return new Reader(text).day().atStartOfDay();
            } catch (Reason why) {
                throw new Unfit(
                        "datetime_from_date_parsing",
                        "Input should be a valid datetime or date, " + why.getMessage(),
                        Map.of("error", why.getMessage()));
            }
        }
    }

    /** Why a text is not a day or a moment, in the reader's own words. */
    private static final class Reason extends Exception {

        Reason(String why) {
            super(why, null, false, false);
        }
    }

    /** One pass over one text, front to back. */
    private static final class Reader {

        private final String text;
        private int at;

        Reader(String text) {
            this.text = text;
        }

        LocalDate day() throws Reason {
            LocalDate day = datePart();
            if (at < text.length()) {
                throw new Reason("unexpected extra characters at the end of the input");
            }
            return day;
        }

        LocalDateTime moment() throws Reason {
            LocalDate day = datePart();
            if (at >= text.length() || "Tt _".indexOf(text.charAt(at)) < 0) {
                throw new Reason("invalid datetime separator, expected `T`, `t`, `_` or space");
            }
            at++;
            LocalTime time = timePart();
            offset();
            if (at < text.length()) {
                throw new Reason("unexpected extra characters at the end of the input");
            }
            return day.atTime(time);
        }

        private LocalDate datePart() throws Reason {
            if (text.length() - at < 10) {
                throw new Reason("input is too short");
            }
            int year = digits(4, "invalid character in year");
            separator('-', "invalid date separator, expected `-`");
            int month = digits(2, "invalid character in month");
            separator('-', "invalid date separator, expected `-`");
            int day = digits(2, "invalid character in day");
            if (month < 1 || month > 12) {
                throw new Reason("month value is outside expected range of 1-12");
            }
            if (day < 1 || day > YearMonth.of(year, month).lengthOfMonth()) {
                throw new Reason("day value is outside expected range");
            }
            return LocalDate.of(year, month, day);
        }

        private LocalTime timePart() throws Reason {
            if (text.length() - at < 5) {
                throw new Reason("input is too short");
            }
            int hour = digits(2, "invalid character in hour");
            separator(':', "invalid time separator, expected `:`");
            int minute = digits(2, "invalid character in minute");
            int second = 0;
            int nanos = 0;
            if (at < text.length() && text.charAt(at) == ':') {
                at++;
                if (text.length() - at < 2) {
                    throw new Reason("input is too short");
                }
                second = digits(2, "invalid character in second");
                if (at < text.length() && (text.charAt(at) == '.' || text.charAt(at) == ',')) {
                    at++;
                    nanos = fraction();
                }
            }
            if (hour > 23) {
                throw new Reason("hour value is outside expected range of 0-23");
            }
            if (minute > 59) {
                throw new Reason("minute value is outside expected range of 0-59");
            }
            if (second > 59) {
                throw new Reason("second value is outside expected range of 0-59");
            }
            return LocalTime.of(hour, minute, second, nanos);
        }

        /** The fraction of a second in nanoseconds, cut to the microsecond. */
        private int fraction() throws Reason {
            int start = at;
            while (at < text.length() && isDigit(text.charAt(at))) {
                at++;
            }
            if (at == start) {
                throw new Reason("invalid character in second fraction");
            }
            String kept = text.substring(start, Math.min(at, start + MICROSECOND_DIGITS));
            String padded = (kept + "000000").substring(0, MICROSECOND_DIGITS);
            return Integer.parseInt(padded) * 1_000;
        }

        /** Reads past an offset, {@code Z} or {@code +HH:MM}, when there is one. */
        private void offset() throws Reason {
            if (at >= text.length()) {
                return;
            }
            char sign = text.charAt(at);
            if (sign == 'Z' || sign == 'z') {
                at++;
                return;
            }
            if (sign != '+' && sign != '-') {
                throw new Reason("invalid timezone sign");
            }
            at++;
            if (text.length() - at < 2) {
                throw new Reason("input is too short");
            }
            int hours = digits(2, "invalid timezone hour");
            if (at < text.length() && text.charAt(at) == ':') {
                at++;
            }
            if (at < text.length()) {
                if (text.length() - at < 2) {
                    throw new Reason("input is too short");
                }
                digits(2, "invalid timezone minute");
            }
            if (hours >= HOURS_IN_A_DAY) {
                throw new Reason("timezone offset must be less than 24 hours");
            }
        }

        private int digits(int count, String otherwise) throws Reason {
            int value = 0;
            for (int index = 0; index < count; index++) {
                char next = text.charAt(at + index);
                if (!isDigit(next)) {
                    throw new Reason(otherwise);
                }
                value = value * 10 + (next - '0');
            }
            at += count;
            return value;
        }

        private void separator(char expected, String otherwise) throws Reason {
            if (text.charAt(at) != expected) {
                throw new Reason(otherwise);
            }
            at++;
        }

        private static boolean isDigit(char character) {
            return character >= '0' && character <= '9';
        }
    }
}
