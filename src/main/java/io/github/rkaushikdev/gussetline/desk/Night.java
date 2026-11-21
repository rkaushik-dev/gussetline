package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one night's run did, in the terms the morning report is written in.
 *
 * <p>{@link Session#run} fills it in as it goes; everything a reader gets back is read only.
 */
public final class Night {

    private final LocalDate on;
    private final String currency;
    private int lookedAt;
    private int skippedInactive;
    private int expired;
    private int heldBack;
    private final List<RetiredSeries> retired = new ArrayList<>();
    private final List<Recommendation> raised = new ArrayList<>();
    private final List<Recommendation> withdrawn = new ArrayList<>();
    private final List<Decision> decisions = new ArrayList<>();

    /** A night on a day, in the currency its totals are added up in, with nothing done yet. */
    public Night(LocalDate on, String currency) {
        this.on = Objects.requireNonNull(on, "on");
        this.currency = Objects.requireNonNull(currency, "currency");
    }

    /** The day the run was for. */
    public LocalDate on() {
        return on;
    }

    /** The currency the night's totals are in. */
    public String currency() {
        return currency;
    }

    /** How many live watches were priced. */
    public int lookedAt() {
        return lookedAt;
    }

    /** How many watches were already retired and passed over. */
    public int skippedInactive() {
        return skippedInactive;
    }

    /** How many open recommendations were expired before anything else happened. */
    public int expired() {
        return expired;
    }

    /** How many better numbers were not raised because they did not beat the open one by the margin. */
    public int heldBack() {
        return heldBack;
    }

    /** The watches closed tonight, by series and reason, in the order closed. */
    public List<RetiredSeries> retired() {
        return Collections.unmodifiableList(retired);
    }

    /** The recommendations raised tonight, in order. */
    public List<Recommendation> raised() {
        return Collections.unmodifiableList(raised);
    }

    /** The open recommendations withdrawn tonight in favour of a better one, in order. */
    public List<Recommendation> withdrawn() {
        return Collections.unmodifiableList(withdrawn);
    }

    /** Every decision reached tonight, one per watch priced, in order. */
    public List<Decision> decisions() {
        return Collections.unmodifiableList(decisions);
    }

    /** The decisions the desk should act on, in order. */
    public List<Decision> actionable() {
        return decisions.stream().filter(Decision::actionable).toList();
    }

    /** What the recommendations raised tonight are worth together. */
    public Money benefit() {
        Money running = Money.zero(currency);
        for (Recommendation made : raised) {
            running = running.plus(made.benefit());
        }
        return running;
    }

    /** The verdicts that were not actionable, each word with its count, in the order first met. */
    public Map<String, Integer> whyNot() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Decision decision : decisions) {
            if (!decision.actionable()) {
                counts.merge(decision.verdict().value(), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /** {@code 2025-06-24: looked at 1, raised 1 worth 90.00 EUR, retired 0, expired 0}. */
    @Override
    public String toString() {
        return on + ": looked at " + lookedAt + ", raised " + raised.size() + " worth " + benefit() + ", retired "
                + retired.size() + ", expired " + expired;
    }

    void countExpired(int count) {
        expired = count;
    }

    void countSkipped() {
        skippedInactive += 1;
    }

    void addDecision(Decision decision) {
        lookedAt += 1;
        decisions.add(decision);
    }

    void addRetired(String seriesKey, Retired reason) {
        retired.add(new RetiredSeries(seriesKey, reason));
    }

    void countHeldBack() {
        heldBack += 1;
    }

    void addRaised(Recommendation made) {
        raised.add(made);
    }

    void addWithdrawn(Recommendation standing) {
        withdrawn.add(standing);
    }
}
