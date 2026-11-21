package io.github.rkaushikdev.gussetline.reports;

import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Which suppliers are actually answering, read off the run reports.
 *
 * <p>The number everybody asks for is a success rate, and the naive one is wrong in three separate
 * ways that all flatter the supplier.
 *
 * <p>It is wrong in the denominator, because a task that never left the building is not evidence about
 * the far end. A supplier whose breaker is open contributes a row to every run and no information at
 * all, and counting those rows makes an outage look like a slow decline instead of a stop. So the rate
 * is measured against the calls that went out.
 *
 * <p>It is wrong about throttling, which is our own pacing and not their fault. A throttled task means
 * the limiter did its job late. It belongs in the report, because somebody has to widen the budget,
 * but it does not belong in the supplier's rate or the desk spends a week tuning the wrong end.
 *
 * <p>It is wrong about malformed payloads, because those arrive with a 200 on them. A feed that quietly
 * changed its date format answers every call perfectly and returns nothing usable, and an answer rate
 * that counts a 200 as an answer will sit at a hundred percent through the whole outage. So malformed
 * is counted separately and it is the reading that grades a supplier down fastest.
 *
 * <p>There is a fourth thing that no status code shows at all. A supplier can serve the same bytes back
 * for a week, which the archive notices because the digest stops changing. That is repeated rather
 * than failed, and it is reported on its own because the fix is different: the call is fine, the cache
 * behind it is not.
 *
 * @param first when the earliest run read started, or empty when there were none
 * @param last when the latest run read started, or empty when there were none
 * @param runs how many runs were read
 * @param suppliers one reading per supplier, in order of name
 */
public record Reliability(
        Optional<LocalDateTime> first, Optional<LocalDateTime> last, int runs, List<Health> suppliers) {

    /** Below this share of answered calls a supplier is not worth planning against. */
    public static final BigDecimal UNFIT_ANSWER_RATE = new BigDecimal("0.50");

    /** Above this it is fine. Between the two somebody should be told. */
    public static final BigDecimal SOUND_ANSWER_RATE = new BigDecimal("0.90");

    /** A supplier serving this share of its bodies unchanged is caching at us. */
    public static final BigDecimal REPEAT_LIMIT = new BigDecimal("0.80");

    private static final DateTimeFormatter MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT);

    /** Unfit before watch before sound, and the lower answer rate first inside each. */
    private static final Comparator<Health> WORST_FIRST = Comparator.comparingInt(
                    (Health health) -> switch (health.grade()) {
                        case UNFIT -> 0;
                        case WATCH -> 1;
                        case SOUND -> 2;
                    })
            .thenComparing(Health::answerRate);

    public Reliability {
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(last, "last");
        suppliers = List.copyOf(suppliers);
    }

    /** What to do about a supplier, which is the point of the report. */
    public enum Grade {
        SOUND("sound"),
        WATCH("watch"),
        UNFIT("unfit");

        private final String value;

        Grade(String value) {
            this.value = value;
        }

        /** The word the report prints for the grade. */
        public String value() {
            return value;
        }

        /** Whether anybody needs telling: every grade but sound. */
        public boolean wantsAHuman() {
            return this != SOUND;
        }

        /**
         * The grade a word stands for.
         *
         * @throws IllegalArgumentException when the word is no grade
         */
        public static Grade of(String value) {
            for (Grade grade : values()) {
                if (grade.value.equals(value)) {
                    return grade;
                }
            }
            throw new IllegalArgumentException("'" + value + "' is not a valid Grade");
        }
    }

    /**
     * One supplier, over however many runs were handed in.
     *
     * @param name the supplier, as the tasks named it
     * @param planned how many tasks the runs had for it
     * @param attempted how many of those sent at least one call
     * @param answered how many came back answered
     * @param transients how many failed in a way worth trying again later
     * @param throttled how many our own limiter held back
     * @param blocked how many the supplier refused to serve at all
     * @param malformed how many answered with a payload that would not parse
     * @param budget how many were skipped on the budget
     * @param refused how many were refused for any other reason
     * @param repeats how many answered calls brought back bytes the archive already held
     * @param offers how many offers and quotes the payloads gave up
     * @param problems how many offers in the payloads did not parse
     * @param runs how many runs the supplier had at least one task in
     */
    public record Health(
            String name,
            int planned,
            int attempted,
            int answered,
            int transients,
            int throttled,
            int blocked,
            int malformed,
            int budget,
            int refused,
            int repeats,
            int offers,
            int problems,
            int runs) {

        /** How wide the name column of a reading is, in characters. */
        private static final int NAME_WIDTH = 10;

        /** How wide the grade column of a reading is, in characters. */
        private static final int GRADE_WIDTH = 6;

        public Health {
            Objects.requireNonNull(name, "name");
        }

        /** A reading for a supplier built a count at a time, every count starting at nought. */
        public static Builder builder(String name) {
            return new Builder(name);
        }

        /** Tasks that never left the building, for whatever reason. */
        public int skipped() {
            return planned - attempted;
        }

        /**
         * Answered calls over calls made, to the hundredth, half to even. No calls means no reading,
         * which is zero.
         */
        public BigDecimal answerRate() {
            return attempted == 0 ? BigDecimal.ZERO : share(answered, attempted);
        }

        /** How much of the plan got out of the door at all, to the hundredth; zero when nothing was planned. */
        public BigDecimal reachRate() {
            return planned == 0 ? BigDecimal.ZERO : share(attempted, planned);
        }

        /** Share of answered calls that brought back bytes we already had; zero when none answered. */
        public BigDecimal repeatRate() {
            return answered == 0 ? BigDecimal.ZERO : share(repeats, answered);
        }

        /** Whether anything here is worth grading: at least one call went out. */
        public boolean hasEvidence() {
            return attempted > 0;
        }

        /**
         * The verdict, and the order of these tests is the whole argument.
         *
         * <p>No calls at all is watched, since there is nothing to grade. Blocked comes first because it
         * is the one outcome that means stop asking. Malformed next, before the answer rate, because
         * malformed calls are counted as answered by every layer below this one and a supplier that
         * returns unusable 200s would otherwise grade sound: as many malformed as answered is unfit, fewer
         * is watched. Then the answer rate against {@link #UNFIT_ANSWER_RATE} and {@link
         * #SOUND_ANSWER_RATE}, and last a supplier serving the same bytes back, at least {@link
         * #REPEAT_LIMIT} of more than two answers, is watched.
         */
        public Grade grade() {
            if (!hasEvidence()) {
                return Grade.WATCH;
            }
            if (blocked > 0) {
                return Grade.UNFIT;
            }
            if (malformed > 0) {
                return malformed >= answered ? Grade.UNFIT : Grade.WATCH;
            }
            if (answerRate().compareTo(UNFIT_ANSWER_RATE) < 0) {
                return Grade.UNFIT;
            }
            if (answerRate().compareTo(SOUND_ANSWER_RATE) < 0) {
                return Grade.WATCH;
            }
            if (servingRepeats()) {
                return Grade.WATCH;
            }
            return Grade.SOUND;
        }

        /** One line saying what the grade is about, for the pager. */
        public String why() {
            if (!hasEvidence()) {
                return "nothing went out";
            }
            if (blocked > 0) {
                return "blocked";
            }
            if (malformed > 0) {
                return malformed + " payloads did not parse";
            }
            if (answerRate().compareTo(SOUND_ANSWER_RATE) < 0) {
                return "answered " + answered + " of " + attempted;
            }
            if (servingRepeats()) {
                return "serving the same bytes back";
            }
            return "answering";
        }

        /**
         * {@code gds        sound  1.00 answering}: the name padded to ten characters, the grade to six,
         * the answer rate and why.
         */
        @Override
        public String toString() {
            return Text.padEnd(name, NAME_WIDTH) + " " + Text.padEnd(grade().value(), GRADE_WIDTH) + " " + answerRate()
                    + " " + why();
        }

        private boolean servingRepeats() {
            return repeatRate().compareTo(REPEAT_LIMIT) >= 0 && answered > 2;
        }

        private static BigDecimal share(int part, int whole) {
            return Numbers.divide(BigDecimal.valueOf(part), BigDecimal.valueOf(whole))
                    .setScale(2, RoundingMode.HALF_EVEN);
        }

        /** The counts of a reading named one at a time, each starting at nought. */
        public static final class Builder {

            private final String name;
            private int planned;
            private int attempted;
            private int answered;
            private int transients;
            private int throttled;
            private int blocked;
            private int malformed;
            private int budget;
            private int refused;
            private int repeats;
            private int offers;
            private int problems;
            private int runs;

            private Builder(String name) {
                this.name = Objects.requireNonNull(name, "name");
            }

            public Builder planned(int value) {
                planned = value;
                return this;
            }

            public Builder attempted(int value) {
                attempted = value;
                return this;
            }

            public Builder answered(int value) {
                answered = value;
                return this;
            }

            public Builder transients(int value) {
                transients = value;
                return this;
            }

            public Builder throttled(int value) {
                throttled = value;
                return this;
            }

            public Builder blocked(int value) {
                blocked = value;
                return this;
            }

            public Builder malformed(int value) {
                malformed = value;
                return this;
            }

            public Builder budget(int value) {
                budget = value;
                return this;
            }

            public Builder refused(int value) {
                refused = value;
                return this;
            }

            public Builder repeats(int value) {
                repeats = value;
                return this;
            }

            public Builder offers(int value) {
                offers = value;
                return this;
            }

            public Builder problems(int value) {
                problems = value;
                return this;
            }

            public Builder runs(int value) {
                runs = value;
                return this;
            }

            public Health build() {
                return new Health(
                        name,
                        planned,
                        attempted,
                        answered,
                        transients,
                        throttled,
                        blocked,
                        malformed,
                        budget,
                        refused,
                        repeats,
                        offers,
                        problems,
                        runs);
            }

            /** Add one task's result to the counts. */
            private void fold(Result result) {
                planned += 1;
                offers += result.offers();
                problems += result.problems();
                if (result.attempts() > 0) {
                    attempted += 1;
                }
                switch (result.outcome()) {
                    case OK -> answered += 1;
                    case TRANSIENT -> transients += 1;
                    case THROTTLED -> throttled += 1;
                    case BLOCKED -> blocked += 1;
                    case MALFORMED -> malformed += 1;
                    case BUDGET -> budget += 1;
                    case REFUSED -> refused += 1;
                }
                if (result.attempts() > 0 && result.outcome() == Outcome.OK && !result.fresh()) {
                    repeats += 1;
                }
            }
        }
    }

    /** The reading for a supplier, or empty when it had no tasks in the runs read. */
    public Optional<Health> get(String name) {
        return suppliers.stream().filter(health -> health.name().equals(name)).findFirst();
    }

    /** The suppliers that are not sound, in order of name. */
    public List<Health> needingAHuman() {
        return suppliers.stream().filter(health -> health.grade().wantsAHuman()).toList();
    }

    /**
     * The one to look at first: unfit before watch before sound, then the lowest answer rate, then the
     * first by name. Empty when no supplier had a task.
     */
    public Optional<Health> worst() {
        return suppliers.stream().sorted(WORST_FIRST).findFirst();
    }

    /** How many suppliers had a task in the runs read. */
    public int size() {
        return suppliers.size();
    }

    /** {@code 1 runs, 2 suppliers, 1 wanting a look}. */
    @Override
    public String toString() {
        return runs + " runs, " + suppliers.size() + " suppliers, "
                + needingAHuman().size() + " wanting a look";
    }

    /**
     * Fold a window of runs into one reading per supplier.
     *
     * <p>Every result counts towards its supplier's plan; one with a call is an attempt; an answered
     * call that brought back bytes the archive already held is a repeat. A supplier's runs are the runs
     * it had at least one task in, and the window runs from the earliest start to the latest.
     */
    public static Reliability build(Iterable<RunReport> reports) {
        Map<String, Health.Builder> seen = new HashMap<>();
        Map<String, Integer> runsIn = new HashMap<>();
        List<LocalDateTime> stamps = new ArrayList<>();

        for (RunReport report : reports) {
            stamps.add(report.started());
            Set<String> touched = new HashSet<>();
            for (Result result : report.results()) {
                String name = result.task().supplier();
                seen.computeIfAbsent(name, Health::builder).fold(result);
                touched.add(name);
            }
            touched.forEach(name -> runsIn.merge(name, 1, Integer::sum));
        }

        return new Reliability(
                stamps.stream().min(Comparator.naturalOrder()),
                stamps.stream().max(Comparator.naturalOrder()),
                stamps.size(),
                seen.keySet().stream()
                        .sorted(Text.CODE_POINT_ORDER)
                        .map(name -> seen.get(name).runs(runsIn.get(name)).build())
                        .toList());
    }

    /**
     * The report as the operator reads it at eight in the morning: the heading with the window, then
     * each supplier's reading with the counts that explain it underneath, or {@code nothing ran}.
     *
     * <p>Per supplier and not aggregated, because the aggregate of one supplier refusing everything and
     * three answering everything is a reassuring number that describes nobody.
     */
    public static String render(Reliability report) {
        String title = "reliability over " + report.runs() + " runs";
        if (report.suppliers().isEmpty()) {
            return Layout.page(Layout.heading(title), List.of("nothing ran"));
        }
        List<String> heading = report.first().isPresent() && report.last().isPresent()
                ? Layout.heading(
                        title,
                        MINUTE.format(report.first().get()) + " to "
                                + MINUTE.format(report.last().get()))
                : Layout.heading(title);
        List<String> lines = new ArrayList<>(heading);
        report.suppliers().forEach(health -> lines.addAll(supplier(health)));
        return Layout.page(lines);
    }

    /** One supplier, with the counts that explain the grade above them. */
    private static List<String> supplier(Health health) {
        List<String> lines = new ArrayList<>();
        lines.add(health.toString());
        lines.add("  planned " + health.planned() + " called " + health.attempted() + " answered " + health.answered()
                + " repeats " + health.repeats());
        if (health.throttled() > 0) {
            lines.add("  throttled " + health.throttled() + ", which is our pacing and not theirs");
        }
        if (health.budget() > 0) {
            lines.add("  " + health.budget() + " skipped on the budget");
        }
        if (health.problems() > 0) {
            lines.add("  " + health.problems() + " offers in the payloads did not parse");
        }
        return lines;
    }
}
