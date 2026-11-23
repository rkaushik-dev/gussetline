package io.github.rkaushikdev.gussetline.reports;

import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.State;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.reports.Layout.Figure;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.StreamSupport;

/**
 * The savings report, which is the one the desk is measured on.
 *
 * <p>Three numbers and they are deliberately different questions.
 *
 * <p>What was found is the benefit of every recommendation raised. It measures the collectors and the
 * tariff, and it goes up when the desk polls more.
 *
 * <p>What was taken is the benefit of the ones somebody acted on. It measures the desk, and it is the
 * number that goes on the invoice.
 *
 * <p>What was missed is the benefit of the ones that expired. It measures the staffing, and it is the
 * only one of the three that anybody argues about, which is why it is reported at all rather than
 * being quietly folded into the others.
 *
 * <p>A killed recommendation is in none of them past the first. Somebody looked and said no, usually
 * because the customer wanted the flight they had, and counting that as a miss blames the desk for a
 * decision that was correct.
 *
 * <p>A superseded one is in none of them either, and for a different reason: it was never anybody's to
 * act on. A better fare turned up on the same series and the desk quoted that instead. It is counted
 * so the found total still adds up, and it is kept out of the take rate, because a run of good
 * collecting would otherwise read as a desk that stopped answering its queue.
 *
 * @param currency the currency every amount is in
 * @param overall every recommendation in the window
 * @param byMonth one line per month raised, in month order
 * @param byAgent one line per person who settled something, in name order
 * @param byMarket one line per city pair watched, in name order
 */
public record Savings(String currency, Line overall, List<Line> byMonth, List<Line> byAgent, List<Line> byMarket) {

    /** How wide the label column of a line is, in characters. */
    private static final int LABEL_WIDTH = 16;

    public Savings {
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(overall, "overall");
        byMonth = List.copyOf(byMonth);
        byAgent = List.copyOf(byAgent);
        byMarket = List.copyOf(byMarket);
    }

    /** The overall line, which is what the report reads back as. */
    @Override
    public String toString() {
        return overall.toString();
    }

    /**
     * One row of the report, for one grouping.
     *
     * @param label what the row groups: {@code overall}, a month, or who settled them
     * @param raised how many recommendations were raised
     * @param taken how many were taken
     * @param killed how many were killed
     * @param expired how many expired
     * @param superseded how many were withdrawn for a better fare
     * @param stillOpen how many are still open
     * @param found the benefit of every one raised
     * @param realised the benefit of the taken ones
     * @param missed the benefit of the expired ones
     */
    public record Line(
            String label,
            int raised,
            int taken,
            int killed,
            int expired,
            int superseded,
            int stillOpen,
            Money found,
            Money realised,
            Money missed) {

        public Line {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(found, "found");
            Objects.requireNonNull(realised, "realised");
            Objects.requireNonNull(missed, "missed");
        }

        /** How many have left the open state, for whatever reason. */
        public int settled() {
            return taken + killed + expired + superseded;
        }

        /** The ones a person settled, which is the only fair denominator. */
        public int decided() {
            return taken + killed;
        }

        /** Taken over decided, to the hundredth, half to even; zero when nobody decided any. */
        public BigDecimal takeRate() {
            if (decided() == 0) {
                return BigDecimal.ZERO;
            }
            return Numbers.divide(BigDecimal.valueOf(taken), BigDecimal.valueOf(decided()))
                    .setScale(2, RoundingMode.HALF_EVEN);
        }

        /**
         * {@code overall          raised    1 taken    1 worth 90.00 EUR}: the label padded to sixteen
         * characters, then the two counts right-aligned in four.
         */
        @Override
        public String toString() {
            return Text.padEnd(label, LABEL_WIDTH)
                    + String.format(Locale.ROOT, " raised %4d taken %4d worth %s", raised, taken, realised);
        }
    }

    /** {@link #build(Iterable, String, Optional, Optional)} over every recommendation given. */
    public static Savings build(Iterable<Recommendation> recommendations, String currency) {
        return build(recommendations, currency, Optional.empty(), Optional.empty());
    }

    /**
     * Every number, over a window, grouped the three ways the desk asks for.
     *
     * <p>The window takes the recommendations raised on or after {@code first} and on or before {@code
     * last}, either end open when absent. They are grouped by the month they were raised, by whoever
     * settled them, and by the market watched. Whoever settled them is a person, or {@code the clock}
     * for the expired ones; an open one belongs to no agent, and neither does a superseded one,
     * because nobody decided it. The market is the two cities of the series, such as {@code FRA-NYC},
     * which is the line a carrier's account manager asks about first.
     */
    public static Savings build(
            Iterable<Recommendation> recommendations,
            String currency,
            Optional<LocalDate> first,
            Optional<LocalDate> last) {
        List<Recommendation> rows = StreamSupport.stream(recommendations.spliterator(), false)
                .filter(made -> first.isEmpty() || !made.raisedOn().isBefore(first.get()))
                .filter(made -> last.isEmpty() || !made.raisedOn().isAfter(last.get()))
                .toList();

        return new Savings(
                currency,
                line("overall", rows, currency),
                grouped(rows, row -> Optional.of(month(row.raisedOn())), currency),
                grouped(rows, Savings::settledBy, currency),
                grouped(rows, row -> Optional.of(market(row.seriesKey())), currency));
    }

    /** Who settled a recommendation, or nobody when it is open, superseded, or settled unnamed. */
    private static Optional<String> settledBy(Recommendation row) {
        if (row.state() == State.SUPERSEDED
                || !row.state().isSettled()
                || row.decidedBy().isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(row.decidedBy());
    }

    /** The two cities of a series: {@code FRA-NYC}. */
    private static String market(String seriesKey) {
        Series.Reading read = Series.read(seriesKey);
        return read.origin() + Series.SEPARATOR + read.destination();
    }

    /**
     * The report as the desk prints it: the heading, the figures, then a line per month, per agent and
     * per market, each section left out when it has nothing in it.
     */
    public static String render(Savings report) {
        Line overall = report.overall();
        return Layout.page(
                Layout.heading("savings in " + report.currency()),
                Layout.figures(
                        new Figure("found", overall.found()),
                        new Figure("taken", overall.realised()),
                        new Figure("missed", overall.missed()),
                        new Figure("raised", overall.raised()),
                        new Figure("settled", overall.settled()),
                        new Figure("replaced", overall.superseded()),
                        new Figure("open", overall.stillOpen()),
                        new Figure("take rate", overall.takeRate() + " over " + overall.decided() + " decided")),
                Layout.section("by month", report.byMonth()),
                Layout.section("by agent", report.byAgent()),
                Layout.section("by market", report.byMarket()));
    }

    private static List<Line> grouped(
            List<Recommendation> rows, Function<Recommendation, Optional<String>> by, String currency) {
        Map<String, List<Recommendation>> groups = new LinkedHashMap<>();
        for (Recommendation row : rows) {
            by.apply(row).ifPresent(found -> groups.computeIfAbsent(found, unused -> new ArrayList<>())
                    .add(row));
        }
        return groups.keySet().stream()
                .sorted(Text.CODE_POINT_ORDER)
                .map(key -> line(key, groups.get(key), currency))
                .toList();
    }

    private static Line line(String label, List<Recommendation> rows, String currency) {
        Money found = Money.zero(currency);
        Money realised = Money.zero(currency);
        Money missed = Money.zero(currency);
        int taken = 0;
        int killed = 0;
        int expired = 0;
        int superseded = 0;
        int stillOpen = 0;

        for (Recommendation row : rows) {
            found = found.plus(row.benefit());
            switch (row.state()) {
                case TAKEN -> {
                    taken += 1;
                    realised = realised.plus(row.benefit());
                }
                case KILLED -> killed += 1;
                case EXPIRED -> {
                    expired += 1;
                    missed = missed.plus(row.benefit());
                }
                case SUPERSEDED -> superseded += 1;
                case OPEN -> stillOpen += 1;
            }
        }
        return new Line(label, rows.size(), taken, killed, expired, superseded, stillOpen, found, realised, missed);
    }

    /** The month a day falls in, as {@code 2025-06}: the year as it is written, the month in two digits. */
    private static String month(LocalDate day) {
        return String.format(Locale.ROOT, "%d-%02d", day.getYear(), day.getMonthValue());
    }
}
