package io.github.rkaushikdev.gussetline.reports;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Capability;
import io.github.rkaushikdev.gussetline.collect.Registry;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Schedule;
import io.github.rkaushikdev.gussetline.collect.Series;
import io.github.rkaushikdev.gussetline.collect.Supplier;
import io.github.rkaushikdev.gussetline.reports.Layout.Figure;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * What the desk is actually watching, as opposed to what it meant to watch.
 *
 * <p>The schedule says nine thousand series are followed. The run reports say how many of them a
 * supplier answered for. Coverage is the difference, and it is the report that catches the failures
 * nobody gets paged about, because a series that is quietly never polled looks exactly like a series
 * where the fare never moved.
 *
 * <p>Two decisions here and both of them are the reason a global staleness number was thrown away.
 *
 * <p>The first is that lateness is measured against each series' own cadence. A departure in March is
 * polled fortnightly and one next week is polled daily, so a single number for how old an observation
 * may be either pages on the March ones every morning or misses the ones next week entirely. The
 * schedule already works out the interval; this asks it rather than picking a number.
 *
 * <p>The second is that a quote is not a price. The calendar answers for markets the other two do not
 * cover, and its answers cannot be repriced, audited or turned into a recommendation. A series only
 * the calendar has answered for is covered in the sense that somebody looked and uncovered in the
 * sense that matters, so it gets its own grade rather than being counted either way.
 *
 * <p>A departure that has gone is neither. It is finished, and counting finished series as gaps is how
 * a coverage figure ends up falling every week no matter what the collectors do.
 *
 * @param asOf the day the series are graded on
 * @param series every followed series, graded, in order of market and then departure
 */
public record Coverage(LocalDate asOf, List<Watched> series) {

    /** How many of a series' own intervals may pass before it counts as late. */
    public static final int LATE_AFTER_INTERVALS = 2;

    /** How many gaps a rendered report lists unless told otherwise. */
    private static final int SHOWN = 20;

    /** Dark before late, and nearer departures first inside each. */
    private static final Comparator<Watched> WORST_FIRST = Comparator.comparingInt(
                    (Watched watched) -> watched.cover() == Cover.DARK ? 0 : 1)
            .thenComparingInt(Watched::daysOut);

    public Coverage {
        Objects.requireNonNull(asOf, "asOf");
        series = List.copyOf(series);
    }

    /** How well one series is being watched. */
    public enum Cover {
        PRICED("priced"),
        QUOTED("quoted"),
        LATE("late"),
        DARK("dark"),
        FLOWN("flown");

        private final String value;

        Cover(String value) {
            this.value = value;
        }

        /** The word the report prints for the grade. */
        public String value() {
            return value;
        }

        /** Whether somebody should do something about it: a late series or a dark one. */
        public boolean isAGap() {
            return this == LATE || this == DARK;
        }

        /**
         * The grade a word stands for.
         *
         * @throws IllegalArgumentException when the word is no grade
         */
        public static Cover of(String value) {
            for (Cover cover : values()) {
                if (cover.value.equals(value)) {
                    return cover;
                }
            }
            throw new IllegalArgumentException("'" + value + "' is not a valid Cover");
        }
    }

    /**
     * One series, and what the runs managed to get for it.
     *
     * @param key the market and the departure, {@code FRA-NYC 2025-08-20}, which is how it is looked up
     * @param market the market's key, {@code FRA-NYC}
     * @param departure the departure date
     * @param daysOut days from the report's day to the departure; negative once it has gone
     * @param interval days between polls the schedule wants for this series
     * @param asOf the day the series is graded on
     * @param polls how many results the runs had for it, answered or not
     * @param answers how many of those answered
     * @param pricedAnswers how many answers came from a supplier whose answers can be priced
     * @param lastAnswer the day of the latest answer, if any came
     * @param lastPriced the day of the latest answer that can be priced, if any came
     */
    public record Watched(
            String key,
            String market,
            LocalDate departure,
            int daysOut,
            int interval,
            LocalDate asOf,
            int polls,
            int answers,
            int pricedAnswers,
            Optional<LocalDate> lastAnswer,
            Optional<LocalDate> lastPriced) {

        public Watched {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(departure, "departure");
            Objects.requireNonNull(asOf, "asOf");
            Objects.requireNonNull(lastAnswer, "lastAnswer");
            Objects.requireNonNull(lastPriced, "lastPriced");
        }

        /** Days since anything answered, or empty when nothing ever did. */
        public Optional<Integer> age() {
            return lastAnswer.map(day -> daysBetween(day, asOf));
        }

        /** How old an answer may be before this series is late: {@link #LATE_AFTER_INTERVALS} intervals. */
        public int allowance() {
            return interval * LATE_AFTER_INTERVALS;
        }

        /**
         * The grade, and the order is the argument.
         *
         * <p>Flown first, because a departure that has gone is finished and not a gap. Dark next, because
         * never having been answered for is worse than being late. Then lateness, which is measured on
         * the priced answers, because a stream of calendar quotes does not keep a series fresh for
         * anything the desk can act on.
         */
        public Cover cover() {
            if (daysOut < 0) {
                return Cover.FLOWN;
            }
            if (lastAnswer.isEmpty()) {
                return Cover.DARK;
            }
            if (lastPriced.isEmpty()) {
                return Cover.QUOTED;
            }
            if (daysBetween(lastPriced.get(), asOf) > allowance()) {
                return Cover.LATE;
            }
            return Cover.PRICED;
        }

        /**
         * One line saying what the grade is about. The days given for a late or a priced series are the
         * {@linkplain #age() days since anything answered}.
         */
        public String why() {
            return switch (cover()) {
                case FLOWN -> "flown";
                case DARK -> polls + " polls and nothing answered";
                case QUOTED -> "quotes only, nothing that can be repriced";
                case LATE -> "last priced " + ageText() + " days ago against an allowance of " + allowance();
                case PRICED -> "priced " + ageText() + " days ago";
            };
        }

        /** {@code FRA-NYC 2025-08-20 priced priced 0 days ago}: the market, the departure, the grade, why. */
        @Override
        public String toString() {
            return market + " " + departure + " " + cover().value() + " " + why();
        }

        private String ageText() {
            return Text.plain(age().orElse(null));
        }
    }

    /** The ones still ahead of their departure, which is the denominator. */
    public List<Watched> live() {
        return series.stream().filter(watched -> watched.cover() != Cover.FLOWN).toList();
    }

    /** The series with one grade, in order. */
    public List<Watched> withCover(Cover cover) {
        return series.stream().filter(watched -> watched.cover() == cover).toList();
    }

    /** The ones somebody should look at, worst first: dark before late, then the nearest departure. */
    public List<Watched> gaps() {
        return series.stream()
                .filter(watched -> watched.cover().isAGap())
                .sorted(WORST_FIRST)
                .toList();
    }

    /**
     * Share of live series with a priced answer inside their allowance, to the hundredth, half to even;
     * zero when nothing is live.
     */
    public BigDecimal rate() {
        List<Watched> live = live();
        if (live.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long priced =
                live.stream().filter(watched -> watched.cover() == Cover.PRICED).count();
        return Numbers.divide(BigDecimal.valueOf(priced), BigDecimal.valueOf(live.size()))
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    /** The series under a key, {@code FRA-NYC 2025-08-20}, or empty when none is followed under it. */
    public Optional<Watched> get(String key) {
        return series.stream().filter(watched -> watched.key().equals(key)).findFirst();
    }

    /** How many series are followed. */
    public int size() {
        return series.size();
    }

    /** {@code 1 live series, 0.00 priced, 1 gaps}. */
    @Override
    public String toString() {
        return live().size() + " live series, " + rate() + " priced, " + gaps().size() + " gaps";
    }

    /**
     * Join what was followed to what the runs got, one row per series.
     *
     * <p>Every result for a followed series is a poll; an answered one is an answer, dated the day its
     * run started, and it is a priced answer when its supplier can shop or reprice. A supplier the
     * registry does not hold can do neither. Results for series nobody follows are dropped, and the
     * latest day wins whatever order the runs arrive in.
     */
    public static Coverage build(Schedule schedule, Iterable<RunReport> reports, Registry registry, LocalDate today) {
        Map<String, Tally> watched = new HashMap<>();
        for (Series followed : schedule.series()) {
            Tally blank = new Tally(followed, today);
            watched.put(blank.key, blank);
        }

        for (RunReport report : reports) {
            LocalDate day = report.started().toLocalDate();
            for (Result result : report.results()) {
                Tally row = watched.get(
                        result.task().market().key() + " " + result.task().departure());
                if (row == null) {
                    continue;
                }
                row.polls += 1;
                if (result.ok()) {
                    row.answer(day, canPrice(registry, result.task().supplier()));
                }
            }
        }

        return new Coverage(
                today,
                watched.keySet().stream()
                        .sorted(Text.CODE_POINT_ORDER)
                        .map(key -> watched.get(key).watched())
                        .toList());
    }

    /** {@link #render(Coverage, int)} listing twenty gaps. */
    public static String render(Coverage report) {
        return render(report, SHOWN);
    }

    /**
     * The report as the operator reads it, gaps first and the rest counted: the heading, a figure for
     * every grade and the rate, then the gaps, worst first, cut at {@code limit} and left out when there
     * are none.
     */
    public static String render(Coverage report, int limit) {
        return Layout.page(
                Layout.heading("coverage on " + report.asOf()),
                Layout.figures(
                        new Figure("followed", report.size()),
                        new Figure("live", report.live().size()),
                        new Figure("priced", report.withCover(Cover.PRICED).size()),
                        new Figure("quoted", report.withCover(Cover.QUOTED).size()),
                        new Figure("late", report.withCover(Cover.LATE).size()),
                        new Figure("dark", report.withCover(Cover.DARK).size()),
                        new Figure("flown", report.withCover(Cover.FLOWN).size()),
                        new Figure("rate", report.rate())),
                Layout.section("gaps, worst first", report.gaps(), limit));
    }

    /** Whether an answer from this supplier is one the desk can act on. */
    private static boolean canPrice(Registry registry, String supplier) {
        try {
            Supplier found = registry.get(supplier);
            return found.can(Capability.SHOP) || found.can(Capability.REPRICE);
        } catch (ValidationError unknown) {
            return false;
        }
    }

    private static int daysBetween(LocalDate first, LocalDate last) {
        return Math.toIntExact(ChronoUnit.DAYS.between(first, last));
    }

    /** One series while the runs are read into it. */
    private static final class Tally {

        private final String key;
        private final String market;
        private final LocalDate departure;
        private final int daysOut;
        private final int interval;
        private final LocalDate asOf;
        private int polls;
        private int answers;
        private int pricedAnswers;
        private LocalDate lastAnswer;
        private LocalDate lastPriced;

        Tally(Series series, LocalDate today) {
            this.key = series.key().market() + " " + series.key().departure();
            this.market = series.market().key();
            this.departure = series.departure();
            this.daysOut = series.daysOut(today);
            this.interval = Schedule.intervalFor(Math.max(daysOut, 0), series.quietPolls());
            this.asOf = today;
        }

        void answer(LocalDate day, boolean priced) {
            answers += 1;
            lastAnswer = later(lastAnswer, day);
            if (priced) {
                pricedAnswers += 1;
                lastPriced = later(lastPriced, day);
            }
        }

        Watched watched() {
            return new Watched(
                    key,
                    market,
                    departure,
                    daysOut,
                    interval,
                    asOf,
                    polls,
                    answers,
                    pricedAnswers,
                    Optional.ofNullable(lastAnswer),
                    Optional.ofNullable(lastPriced));
        }

        private static LocalDate later(LocalDate current, LocalDate seen) {
            return current == null || seen.isAfter(current) ? seen : current;
        }
    }
}
