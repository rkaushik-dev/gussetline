package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

/**
 * One night's work, which is the only place the whole desk runs in order.
 *
 * <p>Everything below this can be tested on its own and is. What cannot be tested anywhere else is the
 * order, and the order is where the two bugs that cost real money both lived.
 *
 * <p>Expiry comes first. A recommendation raised on Monday against a fare that has gone is still
 * sitting open on Thursday, and if the run raises a fresh one for the same ticket before closing that,
 * the desk holds two open numbers for one phone call. Closing first means every series starts the
 * night with at most one open recommendation, and the rest of the run can then assume it.
 *
 * <p>Retiring comes before deciding, not after. A watch on a ticket that has been flown, or on a fare
 * category 31 will never allow a change to, will otherwise be priced first and retired afterwards,
 * which is a shopping request spent on an answer nobody can use. The cheap check goes first and the
 * expensive one only runs on what survives it.
 *
 * <p>The last rule is the one that is not obvious. When the market drops again while a recommendation
 * is still open, the old one is withdrawn and a new one raised, but only if the improvement is worth
 * the churn. An agent working a queue does not want the same ticket appearing twice in a morning
 * because the fare moved four euros, so a better number under the margin is simply not raised and the
 * run says how many it held back.
 */
public final class Session {

    /**
     * How much better a fresh recommendation has to be before it is worth replacing one an agent may
     * already have in front of them.
     */
    public static final BigDecimal SUPERSEDE_MARGIN = new BigDecimal("10.00");

    private Session() {}

    /**
     * {@link #run(Iterable, List, Function, LocalDate, String, int, BigDecimal)} raising recommendations
     * that last the default lifetime, superseding at {@link #SUPERSEDE_MARGIN}.
     */
    public static Night run(
            Iterable<Watch> watches,
            List<Recommendation> recommendations,
            Function<Watch, Terms> rules,
            LocalDate today,
            String currency) {
        return run(
                watches,
                recommendations,
                rules,
                today,
                currency,
                Recommendation.DEFAULT_LIFETIME_DAYS,
                SUPERSEDE_MARGIN);
    }

    /**
     * Work through every watch once, in the order that wastes least.
     *
     * <p>Stale recommendations are expired first. Then, watch by watch: a retired watch is skipped and
     * counted; a watch that {@linkplain Watch#shouldRetire should retire} is retired without being
     * priced; anything else is {@linkplain Reprice#decide decided} on the terms {@code rules} gives for
     * it, and retired when the verdict says it never will be actionable. An actionable decision raises a
     * recommendation, unless the series already has an open one that the new benefit does not beat by
     * at least {@code margin}, in which case it is held back; when it does beat it, or the two are in
     * different currencies, the open one is withdrawn in favour of the new one.
     *
     * <p>{@code recommendations} is the desk's whole open book and it is written to: stale ones are
     * expired and replaced ones are withdrawn in place. The ones raised tonight are appended, so a
     * second watch on the same series inside one run sees them. It has to be a list that can be
     * appended to.
     *
     * @param rules what the tariff says about each watch
     * @param currency the currency the night's totals are added up in
     * @param lifetime how many days each recommendation raised tonight lasts
     * @param margin how much better a new benefit has to be to replace an open one
     */
    public static Night run(
            Iterable<Watch> watches,
            List<Recommendation> recommendations,
            Function<Watch, Terms> rules,
            LocalDate today,
            String currency,
            int lifetime,
            BigDecimal margin) {
        Night night = new Night(today, currency);
        night.countExpired(Recommendation.expireStale(recommendations, today));

        for (Watch watch : watches) {
            if (!watch.isActive()) {
                night.countSkipped();
                continue;
            }

            Optional<Retired> gone = watch.shouldRetire(today);
            if (gone.isPresent()) {
                watch.retire(gone.get());
                night.addRetired(watch.seriesKey(), gone.get());
                continue;
            }

            Terms terms = rules.apply(watch);
            Decision decision = Reprice.decide(watch, terms.changeRule(), terms.penalties(), terms.context(), today);
            night.addDecision(decision);

            Optional<Retired> finished = decision.verdict().retiresTheWatch();
            if (finished.isPresent()) {
                watch.retire(finished.get());
                night.addRetired(watch.seriesKey(), finished.get());
                continue;
            }

            if (decision.actionable()) {
                offer(night, recommendations, decision, today, lifetime, margin);
            }
        }
        return night;
    }

    /** Raise a recommendation, unless one already open beats it. */
    private static void offer(
            Night night,
            List<Recommendation> recommendations,
            Decision decision,
            LocalDate today,
            int lifetime,
            BigDecimal margin) {
        Optional<Recommendation> standing =
                Recommendation.openOn(recommendations, decision.watch().seriesKey());
        if (standing.isPresent() && !worthReplacing(standing.get(), decision, margin)) {
            night.countHeldBack();
            return;
        }

        Recommendation made = Recommendation.raiseFrom(decision, today, lifetime);
        if (standing.isPresent()) {
            standing.get().withdraw(today, made.fingerprint());
            night.addWithdrawn(standing.get());
        }

        recommendations.add(made);
        night.addRaised(made);
    }

    /**
     * Whether the new number beats the open one by enough to be worth it.
     *
     * <p>Currency is checked rather than assumed. A ticket repriced into another currency is a different
     * conversation and the comparison here would be meaningless, so it always supersedes and lets a
     * person look at both.
     */
    private static boolean worthReplacing(Recommendation standing, Decision decision, BigDecimal margin) {
        if (!standing.benefit().currency().equals(decision.worth().currency())) {
            return true;
        }
        BigDecimal better =
                decision.worth().amount().subtract(standing.benefit().amount(), Numbers.CONTEXT);
        return better.compareTo(margin) >= 0;
    }
}
