package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Everything a run did, including the nothings.
 *
 * <p>The reliability report and the coverage report are both built out of this and out of nothing
 * else, so a run that silently drops a task takes a hole in both with it. Every task planned has a
 * result here, and the tasks skipped on the breaker or the budget are counted as well as recorded.
 *
 * <p>A report is filled as a run goes, and is not safe to fill from several threads at once.
 */
public final class RunReport {

    private final LocalDateTime started;
    private final List<Result> results;
    private int skippedBreaker;
    private int skippedBudget;

    /** A run that has done nothing yet. */
    public RunReport(LocalDateTime started) {
        this(started, List.of());
    }

    /** A run with these results and nothing skipped. */
    public RunReport(LocalDateTime started, List<Result> results) {
        this(started, results, 0, 0);
    }

    /** A run with these results, of which so many were skipped on the breaker and on the budget. */
    public RunReport(LocalDateTime started, List<Result> results, int skippedBreaker, int skippedBudget) {
        this.started = Objects.requireNonNull(started, "started");
        this.results = new ArrayList<>(results);
        this.skippedBreaker = skippedBreaker;
        this.skippedBudget = skippedBudget;
    }

    /** When the run started. */
    public LocalDateTime started() {
        return started;
    }

    /** What every task did, in the order the plan asked for them. */
    public List<Result> results() {
        return List.copyOf(results);
    }

    /** How many tasks were skipped because the supplier's breaker was open. */
    public int skippedBreaker() {
        return skippedBreaker;
    }

    /** How many tasks were skipped because the host's budget would not allow a call. */
    public int skippedBudget() {
        return skippedBudget;
    }

    /** Record what a task did. */
    public void add(Result result) {
        results.add(Objects.requireNonNull(result, "result"));
    }

    /** Record a task skipped because its supplier's breaker was open, and count it. */
    public void skipOnBreaker(Result result) {
        add(result);
        skippedBreaker++;
    }

    /** Record a task skipped because its host's budget would not allow a call, and count it. */
    public void skipOnBudget(Result result) {
        add(result);
        skippedBudget++;
    }

    /** How many things the payloads gave up, offers and quotes together. */
    public int offers() {
        return results.stream().mapToInt(Result::offers).sum();
    }

    /** How many tasks sent at least one call. */
    public int calls() {
        return (int) results.stream().filter(result -> result.attempts() > 0).count();
    }

    /** How many tasks came to each outcome, by the outcome's word, in the order each first came up. */
    public Map<String, Integer> byOutcome() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Result result : results) {
            counts.merge(result.outcome().value(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** The results that did not succeed, in order. */
    public List<Result> failures() {
        return results.stream().filter(result -> !result.ok()).toList();
    }

    /** How many parts of the payloads could not be read, which used to be dropped on the floor. */
    public int problems() {
        return results.stream().mapToInt(Result::problems).sum();
    }

    /**
     * What the run spent, counting only the calls that went out.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a result names a supplier the
     *     registry does not hold
     */
    public BigDecimal cost(Registry registry) {
        BigDecimal total = BigDecimal.ZERO;
        for (Result result : results) {
            if (result.attempts() > 0) {
                total = total.add(registry.get(result.task().supplier()).costPerCall(), Numbers.CONTEXT);
            }
        }
        return total;
    }

    /** How many tasks the run has a result for. */
    public int size() {
        return results.size();
    }

    /** {@code 2 tasks, 2 offers, 0 skipped on the breaker, 0 on the budget}. */
    @Override
    public String toString() {
        return results.size() + " tasks, " + offers() + " offers, " + skippedBreaker + " skipped on the breaker, "
                + skippedBudget + " on the budget";
    }
}
