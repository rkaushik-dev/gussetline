package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Category 2: which days of the week and which hours of the day the sectors may depart on.
 *
 * <p>It is filed per direction, so a fare can be a weekday fare outbound and an any day fare home.
 * A filing that is outbound only checks the sectors of the component being priced; any other filing
 * checks every sector of the journey. Days are named by their first three letters in any case
 * ({@code tue}, {@code SAT}, {@code Sunday}).
 *
 * @param days the days of the week a sector may depart on; none filed means any day
 * @param window the hours a sector may depart in, when filed
 * @param outboundOnly whether only the component's own sectors are checked
 */
public record DayAndTime(List<String> days, Optional<TimeWindow> window, boolean outboundOnly) implements Provision {

    private static final DateTimeFormatter TIMETABLE = DateTimeFormatter.ofPattern("HHmm", Locale.ENGLISH);

    /**
     * Checks the days.
     *
     * @throws ValidationError naming the {@code value} when a day is not a day of the week
     */
    public DayAndTime {
        days = List.copyOf(days);
        Objects.requireNonNull(window, "window");
        days.forEach(Stay::weekdayIndex);
    }

    /** Nothing filed. */
    public DayAndTime() {
        this(List.of(), Optional.empty(), false);
    }

    /** Days alone, checked on every sector of the journey. */
    public DayAndTime(List<String> days) {
        this(days, Optional.empty(), false);
    }

    /** Hours alone, checked on every sector of the journey. */
    public DayAndTime(TimeWindow window) {
        this(List.of(), Optional.of(window), false);
    }

    /** Days and hours, checked on every sector of the journey. */
    public DayAndTime(List<String> days, TimeWindow window) {
        this(days, Optional.of(window), false);
    }

    @Override
    public Category category() {
        return Category.DAY_TIME;
    }

    /** Whether neither days nor hours are filed. */
    public boolean isEmpty() {
        return days.isEmpty() && window.isEmpty();
    }

    /**
     * Fails on the first sector that departs on a day not filed, naming the {@code sector} and the
     * {@code day}, or outside the hours, naming the {@code sector} and the {@code window}.
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }

        Set<Integer> wanted = days.stream().map(Stay::weekdayIndex).collect(Collectors.toSet());
        for (Segment segment : sectors(context)) {
            LocalDateTime when = segment.departure();
            int weekday = Stay.weekdayOf(when.toLocalDate());
            if (!wanted.isEmpty() && !wanted.contains(weekday)) {
                String day = Stay.WEEKDAYS.get(weekday);
                return fails(
                        category(),
                        segment.designator() + " departs a " + day + " and the fare wants " + String.join("/", days),
                        Details.of("sector", segment.designator(), "day", day));
            }
            if (window.isPresent() && !window.get().covers(when.toLocalTime())) {
                return fails(
                        category(),
                        segment.designator() + " departs " + TIMETABLE.format(when) + " outside " + window.get(),
                        Details.of(
                                "sector",
                                segment.designator(),
                                "window",
                                window.get().toString()));
            }
        }
        return passes(category(), "every sector departs inside the filed days and hours");
    }

    private List<Segment> sectors(Context context) {
        if (outboundOnly) {
            return context.component().segments();
        }
        return context.itinerary().segments();
    }
}
