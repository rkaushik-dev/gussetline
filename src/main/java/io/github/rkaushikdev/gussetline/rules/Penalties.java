package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Category 16 read across several filings: what an action costs in total, whether a ticket can be
 * refunded at all, and what is left of it after the fee.
 *
 * <p>Each {@link Penalty} files one action. A ticket usually carries several, and the multipliers
 * differ between them, which is why the total is its own calculation and not a sum.
 */
public final class Penalties {

    private Penalties() {}

    /**
     * The dearest of several component fees, for the highest of filing, or empty when there are none.
     * Of equal amounts the first one given wins.
     *
     * @throws ValidationError when the amounts are in more than one currency, naming the {@code
     *     currencies}, sorted
     */
    public static Optional<Money> highestOf(List<Money> amounts) {
        if (amounts.isEmpty()) {
            return Optional.empty();
        }
        refuseMixedCurrencies(amounts);
        Money dearest = amounts.get(0);
        for (Money amount : amounts) {
            if (amount.amount().compareTo(dearest.amount()) > 0) {
                dearest = amount;
            }
        }
        return Optional.of(dearest);
    }

    /** {@link #totalPenalty(List, Context, int, Optional)} for one component on the context's own day. */
    public static Optional<Money> totalPenalty(List<Penalty> penalties, Context context) {
        return totalPenalty(penalties, context, 1, Optional.empty());
    }

    /** {@link #totalPenalty(List, Context, int, Optional)} on the context's own day. */
    public static Optional<Money> totalPenalty(List<Penalty> penalties, Context context, int components) {
        return totalPenalty(penalties, context, components, Optional.empty());
    }

    /** {@link #totalPenalty(List, Context, int, Optional)} on a given day. */
    public static Optional<Money> totalPenalty(List<Penalty> penalties, Context context, int components, LocalDate on) {
        return totalPenalty(penalties, context, components, Optional.of(on));
    }

    /**
     * Add up every filed penalty for one action, respecting each multiplier, on a ticket of that many
     * components. Empty when nothing is chargeable.
     *
     * <p>A highest of filing does not add to the others, it replaces the per component arithmetic for
     * its own filing only: the dearest of its amount times one, two, and so on up to the number of
     * components, charged once. That is why this cannot be a sum with a special case bolted on. A
     * highest of filing is charged from its amount alone, whether or not it permits the action.
     *
     * @throws ValidationError when the charges are in more than one currency, naming the {@code
     *     currencies}, sorted
     */
    public static Optional<Money> totalPenalty(
            List<Penalty> penalties, Context context, int components, Optional<LocalDate> on) {
        List<Money> charges = new ArrayList<>();
        for (Penalty penalty : penalties) {
            if (penalty.applies() == Applies.HIGHEST_OF) {
                penalty.amountOn(context, on)
                        .flatMap(base -> highestOf(multiples(base, components)))
                        .ifPresent(charges::add);
                continue;
            }
            penalty.charge(context, components, on).ifPresent(charges::add);
        }
        if (charges.isEmpty()) {
            return Optional.empty();
        }
        refuseMixedCurrencies(charges);
        Money running = Money.zero(charges.get(0).currency());
        for (Money charge : charges) {
            running = running.plus(charge);
        }
        return Optional.of(running);
    }

    /** Whether any filing permits a cancellation at all. A fare with no cancel filing is refundable. */
    public static boolean isRefundable(List<Penalty> penalties) {
        List<Penalty> cancels = penalties.stream()
                .filter(penalty -> penalty.action() == Action.CANCEL)
                .toList();
        if (cancels.isEmpty()) {
            return true;
        }
        return cancels.stream().anyMatch(Penalty::permitted);
    }

    /** {@link #residualValue(Money, Optional)} for a fee that applies. */
    public static Money residualValue(Money paid, Money penalty) {
        return residualValue(paid, Optional.of(penalty));
    }

    /**
     * What is left of a ticket after the fee, floored at nothing. With no fee at all, which means the
     * cancellation is not permitted, nothing is left.
     *
     * <p>Floored because a fee larger than the fare does not leave the passenger owing money on a
     * refund, it leaves them with nothing, and a negative residual would flow into the savings report
     * as a gain.
     */
    public static Money residualValue(Money paid, Optional<Money> penalty) {
        if (penalty.isEmpty()) {
            return Money.zero(paid.currency());
        }
        Money left = paid.minus(penalty.get());
        return left.isNegative() ? Money.zero(paid.currency()) : left;
    }

    /** The amount times one, two, and so on up to {@code count}, in that order. */
    private static List<Money> multiples(Money base, int count) {
        List<Money> multiples = new ArrayList<>();
        for (int times = 1; times <= count; times++) {
            multiples.add(base.times(times));
        }
        return multiples;
    }

    private static void refuseMixedCurrencies(List<Money> amounts) {
        TreeSet<String> currencies =
                amounts.stream().map(Money::currency).collect(Collectors.toCollection(TreeSet::new));
        if (currencies.size() > 1) {
            throw new ValidationError(
                    "penalties in more than one currency", Details.of("currencies", List.copyOf(currencies)));
        }
    }
}
