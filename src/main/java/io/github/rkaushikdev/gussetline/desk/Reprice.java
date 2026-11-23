package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.baggage.Allowances;
import io.github.rkaushikdev.gussetline.baggage.Change;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Changes;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalties;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * The reprice decision, which is where five separate rules meet.
 *
 * <p>A held drop is not a saving. It becomes one only if all of these agree: the fare permits a
 * voluntary change at all, which is category 31; the change reprices off today rather than off the
 * day the ticket was first issued, which is also category 31 and is the one that quietly kills most of
 * them; the residual comes back as something, which is category 31 again and decides whether the
 * difference is money, a voucher or nothing; the fee is small enough that something is left, which is
 * category 16 and moves depending on whether the passenger has flown yet; and the ticket still has
 * coupons open, which is a reading of the ticket against today.
 *
 * <p>Any one of them can turn a four hundred euro drop into nothing, and four of the five say so
 * without looking at the price at all. So {@link #decide} runs them in the order that costs least to
 * fail and stops at the first refusal, and it says which one it was, because the desk gets asked.
 */
public final class Reprice {

    private Reprice() {}

    /** {@link #decide(Watch, Terms, LocalDate, BigDecimal)} at the desk's credit discount. */
    public static Decision decide(
            Watch watch, VoluntaryChange changeRule, List<Penalty> penalties, Context context, LocalDate today) {
        return decide(watch, new Terms(changeRule, penalties, context), today, Changes.CREDIT_DISCOUNT);
    }

    /** {@link #decide(Watch, Terms, LocalDate, BigDecimal)} with no allowance recorded on the ticket. */
    public static Decision decide(
            Watch watch,
            VoluntaryChange changeRule,
            List<Penalty> penalties,
            Context context,
            LocalDate today,
            BigDecimal creditDiscount) {
        return decide(watch, new Terms(changeRule, penalties, context), today, creditDiscount);
    }

    /** {@link #decide(Watch, Terms, LocalDate, BigDecimal)} at the desk's credit discount. */
    public static Decision decide(Watch watch, Terms terms, LocalDate today) {
        return decide(watch, terms, today, Changes.CREDIT_DISCOUNT);
    }

    /**
     * Run the five checks in the order that costs least to fail.
     *
     * <ol>
     *   <li>A ticket with every coupon flown has {@link Verdict#NOTHING_OPEN}.
     *   <li>A fare that does not permit a change is {@link Verdict#NOT_CHANGEABLE}.
     *   <li>A fare that reprices off any day but today is {@link Verdict#PRICED_HISTORICALLY}.
     *   <li>A forfeited residual is {@link Verdict#RESIDUAL_FORFEITED}, however big the drop.
     *   <li>Then the price: no {@linkplain Watch#heldDrop() held drop}, a drop that does not more than
     *       cover the category 16 change fee, or a benefit under the watch's threshold are each {@link
     *       Verdict#NOT_WORTH_IT}; anything else is a {@link Verdict#REISSUE}.
     * </ol>
     *
     * <p>The fee is every change filing added up across the ticket's coupons on {@code today}, a fee
     * filed as a percentage read off what the watch paid, nothing when none is chargeable. The benefit is the drop less the fee; a retained residual comes back as a
     * credit and counts at {@code creditDiscount} of that, to the cent, half to even.
     *
     * <p>Then the hold. When the terms record what the ticket held carries, the fare offered is
     * compared with it ({@link Allowances#compare}): checked pieces lost come off the benefit at the
     * terms' rate per piece, and any other change the passenger would have to be told about is noted.
     * A hundred euro drop onto a fare with one fewer bag is a saving of about forty, and quoting the
     * hundred is how a recommendation becomes a complaint.
     *
     * @param creditDiscount what a credit is worth against cash
     * @throws ValidationError when the fee and the fare are in different currencies, naming the {@code
     *     fee} and the {@code fare} currency, or the rate per piece is in another currency than the
     *     fare
     */
    public static Decision decide(Watch watch, Terms terms, LocalDate today, BigDecimal creditDiscount) {
        VoluntaryChange changeRule = terms.changeRule();
        if (watch.ticket().isFinished(today)) {
            return new Decision(Verdict.NOTHING_OPEN, watch, Residual.FORFEITED, "every coupon has been used");
        }
        if (!changeRule.permitted()) {
            return new Decision(
                    Verdict.NOT_CHANGEABLE,
                    watch,
                    Residual.FORFEITED,
                    "category 31 does not permit a voluntary change");
        }
        if (changeRule.basis() != RepricingBasis.CURRENT) {
            return new Decision(
                    Verdict.PRICED_HISTORICALLY,
                    watch,
                    Residual.FORFEITED,
                    "the reissue prices off the original ticketing date");
        }
        if (changeRule.residual() == Residual.FORFEITED) {
            return new Decision(
                    Verdict.RESIDUAL_FORFEITED, watch, changeRule.residual(), "any difference is kept by the carrier");
        }

        Optional<Observation> found = watch.heldDrop();
        if (found.isEmpty()) {
            return new Decision(Verdict.NOT_WORTH_IT, watch, Residual.FORFEITED, "no drop has held long enough");
        }
        Observation held = found.get();

        String currency = watch.paid().currency();
        List<Penalty> changes = terms.penalties().stream()
                .filter(penalty -> penalty.action() == Action.CHANGE)
                .toList();
        Money fee = Penalties.totalPenalty(
                        changes,
                        terms.context(),
                        watch.ticket().coupons().size(),
                        Optional.of(today),
                        Optional.of(watch.paid()))
                .orElseGet(() -> Money.zero(currency));
        if (!fee.currency().equals(currency)) {
            throw new ValidationError(
                    "the fee and the fare are in different currencies",
                    Details.of("fee", fee.currency(), "fare", currency));
        }

        Money drop = watch.dropAgainst(held);
        Money gross = drop.minus(fee);
        if (gross.isNegative() || gross.isZero()) {
            return priced(
                    Verdict.NOT_WORTH_IT,
                    watch,
                    held,
                    drop,
                    fee,
                    Money.zero(currency),
                    changeRule.residual(),
                    "the drop of " + drop + " does not cover the " + fee + " fee",
                    List.of());
        }

        Money benefit = gross;
        List<String> notes = new ArrayList<>();
        if (changeRule.residual() == Residual.RETAINED) {
            benefit = new Money(
                    gross.amount().multiply(creditDiscount, Numbers.CONTEXT).setScale(2, RoundingMode.HALF_EVEN),
                    gross.currency());
            notes.add("the residual comes back as a credit and is counted at a discount");
        }
        if (terms.carried().isStated()) {
            Change change = Allowances.compare(terms.carried(), held.baggage());
            if (change.lostPieces() > 0 && terms.perPiece().isPresent()) {
                Money lost = Allowances.worthOf(change, terms.perPiece().get());
                benefit =
                        Allowances.netBenefit(benefit, change, terms.perPiece().get());
                notes.add(change + ", worth " + lost);
            } else if (change.worthSaying()) {
                notes.add(change.toString());
            }
        }

        if (benefit.isLessThan(watch.threshold())) {
            return priced(
                    Verdict.NOT_WORTH_IT,
                    watch,
                    held,
                    drop,
                    fee,
                    benefit,
                    changeRule.residual(),
                    benefit + " is under the " + watch.threshold() + " the desk needs",
                    notes);
        }
        return priced(
                Verdict.REISSUE,
                watch,
                held,
                drop,
                fee,
                benefit,
                changeRule.residual(),
                "a drop of " + drop + " against a " + fee + " fee",
                notes);
    }

    /** The actionable decisions, the most valuable first; of equal worth, the first given first. */
    public static List<Decision> worthReporting(List<Decision> decisions) {
        return decisions.stream()
                .filter(Decision::actionable)
                .sorted(Comparator.comparing(
                                (Decision decision) -> decision.worth().amount())
                        .reversed())
                .toList();
    }

    /** What the actionable decisions are worth together. */
    public static Money totalBenefit(List<Decision> decisions, String currency) {
        return decisions.stream()
                .filter(Decision::actionable)
                .map(Decision::worth)
                .reduce(Money.zero(currency), Money::plus);
    }

    /** Every watch a decision said will never become actionable, with the reason, in order. */
    public static List<Retirement> toRetire(List<Decision> decisions) {
        List<Retirement> found = new ArrayList<>();
        for (Decision decision : decisions) {
            decision.verdict()
                    .retiresTheWatch()
                    .ifPresent(reason -> found.add(new Retirement(decision.watch(), reason)));
        }
        return List.copyOf(found);
    }

    private static Decision priced(
            Verdict verdict,
            Watch watch,
            Observation held,
            Money drop,
            Money fee,
            Money benefit,
            Residual residual,
            String reason,
            List<String> notes) {
        return new Decision(
                verdict,
                watch,
                Optional.of(held),
                Optional.of(drop),
                Optional.of(fee),
                Optional.of(benefit),
                residual,
                reason,
                notes);
    }
}
