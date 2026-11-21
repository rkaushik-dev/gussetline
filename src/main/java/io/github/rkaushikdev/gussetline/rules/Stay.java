package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;

/**
 * The calendar readings categories 6 and 7 share: which weekday a filing names, and whether a stay
 * covers one.
 *
 * <p>Both categories are measured from a point in the journey and the points are different. {@link
 * MinimumStay} runs from the arrival at the turnaround, because that is when the stay starts. {@link
 * MaximumStay} runs from the departure from origin, because that is when the ticket starts. Measuring
 * both from the same end is the mistake, and it is invisible on a short trip and wrong by a day on a
 * long one.
 *
 * <p>The Sunday rule is the other thing worth being careful about. A minimum stay filed as Sunday
 * does not mean seven days, it means the passenger has to be away over a Sunday, and a Friday to
 * Monday trip satisfies it while a Monday to Friday trip four days longer does not.
 */
public final class Stay {

    /** The weekdays as a filing spells them, Monday first, so a weekday's position is its number. */
    public static final List<String> WEEKDAYS = List.of("mon", "tue", "wed", "thu", "fri", "sat", "sun");

    private Stay() {}

    /**
     * Whether a stay from one date to another covers a given weekday, Monday being 0 and Sunday 6.
     *
     * <p>Inclusive of the start and exclusive of the end, because the passenger is away on the night
     * of the start day and has gone home on the end day. A stay of a week or more always covers every
     * weekday, and a stay that ends on or before the day it starts covers none.
     */
    public static boolean crossesWeekday(LocalDate start, LocalDate end, int weekday) {
        if (!end.isAfter(start)) {
            return false;
        }
        if (ChronoUnit.DAYS.between(start, end) >= 7) {
            return true;
        }
        for (LocalDate current = start; current.isBefore(end); current = current.plusDays(1)) {
            if (weekdayOf(current) == weekday) {
                return true;
            }
        }
        return false;
    }

    /**
     * The number of a weekday written by name, read from its first three letters in any case and
     * with surrounding white space: {@code " SUNDAY "} is 6.
     *
     * @throws ValidationError when the name is not a day of the week, naming the {@code value} given
     */
    static int weekdayIndex(String name) {
        String key = Text.slice(Text.strip(name).toLowerCase(Locale.ROOT), 0, 3);
        int index = WEEKDAYS.indexOf(key);
        if (index < 0) {
            throw new ValidationError("not a day of the week", Details.of("value", name));
        }
        return index;
    }

    /** The weekday number of a date, Monday being 0. */
    static int weekdayOf(LocalDate day) {
        return day.getDayOfWeek().getValue() - 1;
    }
}
