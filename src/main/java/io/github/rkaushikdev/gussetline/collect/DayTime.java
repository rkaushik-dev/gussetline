package io.github.rkaushikdev.gussetline.collect;

/**
 * A day of the year and a time of day with no year, the way the pricing gateway sends a departure:
 * {@code 03041025} is the fourth of March at ten twenty five.
 *
 * <p>The numbers are as sent and are not checked against a calendar. Only a reader that knows which
 * year the gateway meant can say whether the day exists.
 *
 * @param month the month, one to twelve when the gateway is right
 * @param day the day of the month
 * @param hour the hour, on a twenty four hour clock
 * @param minute the minute
 */
public record DayTime(int month, int day, int hour, int minute) {}
