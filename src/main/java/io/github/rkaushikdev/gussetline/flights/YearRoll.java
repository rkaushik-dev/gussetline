package io.github.rkaushikdev.gussetline.flights;

import java.time.DateTimeException;
import java.time.LocalDate;

/**
 * Carries the year forward across dates that only give a month and a day.
 *
 * <p>Two feeds and one command line all send dates with no year on them, and none of the three can
 * be read one date at a time. The year comes from the order: it rolls forward the moment the month
 * goes backwards, and it never rolls back, because a journey does not.
 *
 * <p>A roll holds state, so one roll reads one journey, in order.
 */
public final class YearRoll {

    private int year;
    private int lastMonth;

    /** A roll that reads its first date in the given year, whatever month it names. */
    public YearRoll(int start) {
        this.year = start;
    }

    /**
     * A roll that starts from a day rather than a year.
     *
     * <p>The difference matters on the first date. Starting from a year alone reads 04JAN as this
     * January even when the entry is being priced in December, which puts an outbound eleven months
     * in the past.
     */
    public static YearRoll fromDate(LocalDate on) {
        YearRoll made = new YearRoll(on.getYear());
        made.lastMonth = on.getMonthValue();
        return made;
    }

    /** The year the next date reads in unless its month goes backwards. */
    public int year() {
        return year;
    }

    /**
     * The next date of the journey. The year moves on first if the month is earlier than the last
     * one read, and the month is remembered even when the day turns out not to exist.
     *
     * @throws IllegalArgumentException when the month and day name no date in the year reached
     */
    public LocalDate resolve(int month, int day) {
        if (lastMonth != 0 && month < lastMonth) {
            year++;
        }
        lastMonth = month;
        try {
            return LocalDate.of(year, month, day);
        } catch (DateTimeException noSuchDay) {
            throw new IllegalArgumentException(noSuchDay.getMessage(), noSuchDay);
        }
    }
}
