package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * What to poll and when, which is where the budget is actually spent.
 *
 * <p>A thousand shopping requests a day and nine thousand ticketed itineraries on the books. Polling
 * everything daily is off by a factor of nine, so the schedule has to decide, and the two things it
 * decides on are how close the departure is and whether the price has been moving.
 *
 * <p>Closeness matters because fares move most in the last three weeks and a drop found the day
 * before departure is worth as much as one found in March, while a drop found after departure is
 * worth nothing at all. So the cadence tightens as the date approaches ({@link #CADENCE_BANDS}) and
 * stops dead once it has passed.
 *
 * <p>Movement matters because a market that has sat at the same number for six weeks will probably
 * sit there for a seventh. Every poll that finds no change widens the interval a little, and the
 * first poll that finds one resets it. The widening is capped ({@link #MAX_INTERVAL_DAYS}), because
 * a market that has been quiet for a year can still drop the week before departure and a schedule
 * that has backed off to monthly will not see it.
 *
 * <p>Both of those are readings of history rather than settings, which is why this takes the last
 * observation rather than a configuration file.
 */
public final class Schedule {

    /**
     * One band of the cadence.
     *
     * @param daysOut the furthest a departure can be and still fall in this band, in days
     * @param interval how many days go between polls in this band
     */
    public record Band(int daysOut, int interval) {}

    /** Days to departure, and how many days between polls at that distance, nearest band first. */
    public static final List<Band> CADENCE_BANDS =
            List.of(new Band(3, 1), new Band(21, 2), new Band(60, 4), new Band(120, 7), new Band(365, 14));

    /** However quiet a market has been, never go longer than this between polls. */
    public static final int MAX_INTERVAL_DAYS = 21;

    private final Map<Series.Key, Series> followed = new TreeMap<>();

    /** A schedule that follows nothing yet. */
    public Schedule() {}

    /**
     * Days between polls at a given distance from departure. A departure further out than the last
     * band takes the last band.
     */
    public static int baseInterval(int daysOut) {
        for (Band band : CADENCE_BANDS) {
            if (daysOut <= band.daysOut()) {
                return band.interval();
            }
        }
        return CADENCE_BANDS.get(CADENCE_BANDS.size() - 1).interval();
    }

    /**
     * The cadence, widened by a run of polls that found nothing.
     *
     * <p>The widening is one extra day per two quiet polls, which is gentle enough that a market has
     * to be genuinely still before it slows much, and capped so nothing ever falls off the schedule.
     *
     * @throws ValidationError naming the count when the quiet count is negative
     */
    public static int intervalFor(int daysOut, int quietPolls) {
        if (quietPolls < 0) {
            throw new ValidationError("a quiet count is not negative", Details.of("quiet", quietPolls));
        }
        return Math.min(baseInterval(daysOut) + quietPolls / 2, MAX_INTERVAL_DAYS);
    }

    /**
     * Start following a market and departure, from no history at all. A series already followed for
     * the same pair is replaced.
     */
    public Series follow(Market market, LocalDate departure) {
        Series series = new Series(market, departure);
        followed.put(series.key(), series);
        return series;
    }

    /** Every series followed, in order of market and then departure. */
    public List<Series> series() {
        return List.copyOf(followed.values());
    }

    /** The series for a market and departure, or empty when it is not followed. */
    public Optional<Series> get(Market market, LocalDate departure) {
        return Optional.ofNullable(followed.get(new Series.Key(market.key(), departure.toString())));
    }

    /** The series that want polling today, in order of market and then departure. */
    public List<Series> due(LocalDate today) {
        return followed.values().stream().filter(series -> series.dueOn(today)).toList();
    }

    /** Drop the series whose departures have gone. Returns how many. */
    public int retireFlown(LocalDate today) {
        int before = followed.size();
        followed.values().removeIf(series -> series.daysOut(today) < 0);
        return before - followed.size();
    }

    /**
     * The tasks to run today, closest departures first, then by market, inside the budget.
     *
     * <p>Sorted on days out and not on how long it has been since the last poll. A budget that runs
     * out should run out on the March departures rather than on the ones next week.
     *
     * @param budget how many tasks there is room for; never negative
     * @throws ValidationError naming the budget when it is negative
     */
    public List<Task> plan(LocalDate today, String supplier, int budget) {
        if (budget < 0) {
            throw new ValidationError("a budget is not negative", Details.of("budget", budget));
        }
        List<Series> wanted = due(today).stream()
                .sorted(Comparator.<Series>comparingInt(series -> series.daysOut(today))
                        .thenComparing(series -> series.market().key()))
                .limit(budget)
                .toList();
        return IntStream.range(0, wanted.size())
                .mapToObj(index -> {
                    Series series = wanted.get(index);
                    return new Task(supplier, series.market(), series.departure(), series.daysOut(today), index);
                })
                .toList();
    }

    /** How many series are followed. */
    public int size() {
        return followed.size();
    }
}
