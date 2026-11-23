package io.github.rkaushikdev.gussetline.involuntary;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.reissue.TaxReissue;
import io.github.rkaushikdev.gussetline.rules.Residual;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * When the carrier breaks the contract, which is a different arithmetic: what an involuntary reissue
 * actually costs, which is usually nothing.
 *
 * <p>A voluntary change is the passenger deciding to travel differently and paying for the
 * privilege. An involuntary one is the carrier cancelling the flight, and almost everything the desk
 * knows about repricing is wrong for it.
 *
 * <p>The fare does not reprice. The passenger bought a fare and the carrier failed to fly it, so the
 * passenger keeps the fare they bought whatever it costs today. Repricing an involuntary reissue at
 * today's number is the single most common way a desk overcharges somebody who has already been let
 * down.
 *
 * <p>There is no change fee. Category 16 is about a passenger changing their mind and nobody changed
 * their mind here.
 *
 * <p>The advance purchase and the minimum stay do not apply. Those conditions are promises the
 * passenger made about how they would buy and travel, and they kept them; the carrier is the one who
 * moved. What does still apply is the carrier's own limit on what it will reaccommodate to ({@link
 * Reaccommodation}).
 *
 * <p>The distinction that pays for this module is that an involuntary reissue looks identical to a
 * voluntary one in every system the desk touches. Both are a reissue against a ticket, both have a
 * new itinerary, and only the reason tells them apart. So the reason is required here and there is no
 * default.
 *
 * @param paid what the ticket held cost
 * @param cause why the carrier is reaccommodating
 * @param taxes what the reissue does to the taxes, when that was worked out
 * @param downgradeRefund what the passenger is owed for a lower cabin
 * @param notes why the reissue costs what it does, in the order the reasons were found
 * @param compensation what Regulation 261 owes on top, when the journey was given to assess it on
 */
public record Involuntary(
        Money paid,
        Cause cause,
        Optional<TaxReissue> taxes,
        Money downgradeRefund,
        List<String> notes,
        Optional<Compensation> compensation) {

    /** How many days either side of the booked date still count as the same journey. */
    public static final int REACCOMMODATION_DAYS = 14;

    /**
     * Checks the refund and the cause.
     *
     * @throws ValidationError when the downgrade refund is negative, naming the {@code amount}, or the
     *     cause is not the carrier's doing, naming the {@code cause}
     */
    public Involuntary {
        Objects.requireNonNull(paid, "paid");
        Objects.requireNonNull(cause, "cause");
        Objects.requireNonNull(taxes, "taxes");
        Objects.requireNonNull(downgradeRefund, "downgradeRefund");
        Objects.requireNonNull(compensation, "compensation");
        notes = List.copyOf(notes);
        if (downgradeRefund.isNegative()) {
            throw new ValidationError("a refund is not negative", Details.of("amount", downgradeRefund.toString()));
        }
        if (!cause.isTheCarriersDoing()) {
            throw new ValidationError("that is not an involuntary cause", Details.of("cause", cause.value()));
        }
    }

    /** A reissue with no taxes worked out, no downgrade owed in euro, no notes and no compensation. */
    public Involuntary(Money paid, Cause cause) {
        this(paid, cause, Optional.empty(), Money.zero("EUR"), List.of(), Optional.empty());
    }

    /**
     * The fare the passenger keeps, which is the one they bought.
     *
     * <p>Not today's number. The passenger bought a fare and the carrier failed to fly it, and
     * repricing here is the most common way a desk overcharges somebody who has already been let down.
     */
    public Money newFare() {
        return paid;
    }

    /** Nothing. Category 16 is about changing your mind. */
    public Money penalty() {
        return Money.zero(paid.currency());
    }

    /** What the passenger pays, which is the tax difference and nothing else. */
    public Money addCollect() {
        return taxes.map(TaxReissue::collect).orElse(Money.zero(paid.currency()));
    }

    /** What comes back: the tax difference, plus any downgrade owed. */
    public Money refund() {
        return taxes.map(report -> downgradeRefund.plus(report.refund())).orElse(downgradeRefund);
    }

    /** Collect minus refund. Negative means the passenger is owed money. */
    public Money net() {
        return addCollect().minus(refund());
    }

    /** {@code cancelled: collect 0 EUR, refund 0 EUR}. */
    @Override
    public String toString() {
        return cause.value() + ": collect " + addCollect() + ", refund " + refund();
    }

    /** An involuntary reissue with nothing known but its cause. */
    public static Involuntary assess(Money paid, Cause cause) {
        return assess(paid, Disruption.of(cause));
    }

    /**
     * Work out an involuntary reissue, which is mostly working out what is not owed.
     *
     * <p>A move to a lower cabin owes the difference between the two only when the cause owes a
     * downgrade refund; otherwise it is noted and owes nothing. A move up, or no move, owes nothing
     * either way. When the journey is known, the compensation the regulation owes is assessed on it
     * ({@link Eu261#assess}) and noted when anything is owed.
     *
     * @throws ValidationError when a downgrade is owed and no difference was priced, naming {@code was}
     *     and {@code now}
     */
    public static Involuntary assess(Money paid, Disruption disruption) {
        Cause cause = disruption.cause();
        Cabin was = disruption.booked();
        List<String> notes = new ArrayList<>(List.of(
                "the fare is the one the passenger bought, because the carrier did not fly it",
                "no change fee, because nobody changed their mind"));

        Money owed = Money.zero(paid.currency());
        Optional<Cabin> now = disruption.flown();
        if (now.isPresent() && now.get().rank() < was.rank()) {
            Cabin lower = now.get();
            if (!cause.owesADowngradeRefund()) {
                notes.add("flown in " + lower.value() + " rather than " + was.value() + " without a downgrade owed");
            } else if (disruption.cabinDifference().isEmpty()) {
                throw new ValidationError(
                        "a downgrade needs the difference between the two cabins",
                        Details.of("was", was.value(), "now", lower.value()));
            } else {
                owed = disruption.cabinDifference().get();
                notes.add("downgraded from " + was.value() + " to " + lower.value());
            }
        }
        Optional<Compensation> compensation = disruption.journey().map(journey -> Eu261.assess(journey, disruption));
        compensation.filter(Compensation::owed).ifPresent(found -> notes.add("compensation of " + found));
        return new Involuntary(paid, cause, disruption.taxes(), owed, notes, compensation);
    }

    /**
     * Which conditions an involuntary reissue does not have to satisfy.
     *
     * <p>Written out rather than checked, because the ones that are waived are waived by the reason and
     * not by anything on the ticket, and a caller that has this list can say so on the screen next to
     * the price.
     */
    public static List<String> waives(Cause cause) {
        return List.of("advance purchase", "minimum stay", "maximum stay", "day and time", "seasonality", "penalties");
    }

    /**
     * What an involuntary reissue still has to satisfy.
     *
     * <p>Shorter than {@link #waives} and every entry is the carrier's own limit on what it will put
     * the passenger on, rather than a condition the passenger agreed to.
     */
    public static List<String> stillApplies(Cause cause) {
        return List.of("eligibility", "flight application", "ticket endorsement");
    }

    /**
     * What happens to a difference on an involuntary reissue: refundable, always.
     *
     * <p>A residual that is forfeited or retained is a term the passenger accepted in exchange for a
     * cheaper fare, and none of that applies when the carrier is the one who did not fly.
     */
    public static Residual residualFor(Cause cause) {
        return Residual.REFUNDABLE;
    }

    /** {@link #isWithinReaccommodation(Cause, LocalDate, LocalDate, int)} for {@link #REACCOMMODATION_DAYS}. */
    public static boolean isWithinReaccommodation(Cause cause, LocalDate original, LocalDate offered) {
        return isWithinReaccommodation(cause, original, offered, REACCOMMODATION_DAYS);
    }

    /**
     * Whether the offered date is close enough to count as the same journey: no more than {@code days}
     * before or after the booked one.
     *
     * <p>A carrier reaccommodates onto a nearby flight. Two months later is not a reaccommodation, it
     * is a new journey the passenger asked for, and pricing it as involuntary hands them a fare they
     * did not buy.
     *
     * @throws ValidationError when the window is negative, naming the {@code days}
     */
    public static boolean isWithinReaccommodation(Cause cause, LocalDate original, LocalDate offered, int days) {
        if (days < 0) {
            throw new ValidationError("a reaccommodation window is not negative", Details.of("days", days));
        }
        return Math.abs(ChronoUnit.DAYS.between(original, offered)) <= days;
    }
}
