package io.github.rkaushikdev.gussetline.baggage;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * What a passenger may carry, which is a comparison and not a lookup.
 *
 * <p>The desk quotes a reissue as a saving, and a passenger who loses a checked bag in the process
 * has not saved anything. A hundred euro drop onto a fare with one fewer bag is a loss of about
 * forty, and it arrives as a complaint rather than as a number, because nothing in the price shows
 * it. So an allowance is compared ({@link #compare}) and not merely reported, and the comparison is
 * awkward for three reasons.
 *
 * <p>Pieces and weight do not convert. A fare filed as two pieces and one filed as thirty kilos are
 * not orderable: whether the second is worse depends on what the passenger packed, and a desk that
 * invents a conversion is inventing an answer. They come back as {@link Verdict#NOT_COMPARABLE},
 * which is a real verdict and not a failure.
 *
 * <p>The allowance is per component and the journey is not. A ticket with two pieces across the
 * Atlantic and one on the domestic connection carries the smaller of them in practice ({@link
 * #smallest}), because the bag has to be checked through, and reporting the larger is how a
 * passenger arrives at a desk being asked for money.
 *
 * <p>And the most restrictive wins even when the carriers differ. An interline journey applies the
 * allowance of the carrier whose sector it is, and the passenger meets the strictest of them at the
 * first check in desk.
 */
public final class Allowances {

    private Allowances() {}

    /** What changing from one fare to another does to the allowance. */
    public static Change compare(Baggage was, Baggage now) {
        if (!was.isStated() || !now.isStated()) {
            // Not nothing. A fare with no allowance filed may include a bag or
            // may not, and quoting a saving against it without saying so is the
            // complaint this whole comparison exists to prevent.
            return new Change(was, now, Verdict.UNKNOWN);
        }

        if (was.pieces().isPresent() && now.pieces().isPresent()) {
            return numbers(was, now, was.pieces().get(), now.pieces().get(), true);
        }
        if (was.kilos().isPresent() && now.kilos().isPresent()) {
            return numbers(was, now, was.kilos().get(), now.kilos().get(), false);
        }

        // One of each. Whether thirty kilos beats two pieces depends on what the
        // passenger packed, and a desk that invents a conversion invents an answer.
        return new Change(was, now, Verdict.NOT_COMPARABLE);
    }

    /**
     * What a journey actually carries, which is the least of its sectors.
     *
     * <p>The bag is checked through, so the passenger meets the strictest sector's allowance at the
     * first desk. Reporting the largest is how somebody arrives at check in being asked for money. A
     * sector with nothing stated is left out rather than dragging the journey down.
     *
     * <p>Two kinds together give nothing stated, because the honest answer to what a two piece and a
     * thirty kilo journey carries is that somebody has to look. Nothing stated anywhere gives nothing
     * stated too.
     */
    public static Baggage smallest(List<Baggage> allowances) {
        List<Baggage> stated = allowances.stream().filter(Baggage::isStated).toList();
        if (stated.isEmpty()) {
            return new Baggage();
        }

        List<Integer> pieces =
                stated.stream().flatMap(one -> one.pieces().stream()).toList();
        List<Integer> kilos =
                stated.stream().flatMap(one -> one.kilos().stream()).toList();
        if (!pieces.isEmpty() && !kilos.isEmpty()) {
            return new Baggage();
        }
        if (!pieces.isEmpty()) {
            return Baggage.ofPieces(Collections.min(pieces));
        }
        return Baggage.ofKilos(Collections.min(kilos));
    }

    /**
     * What a lost allowance costs the passenger, at the desk's own rate per piece.
     *
     * @throws ValidationError naming the {@code kilos} when the allowance lost is weight, which needs
     *     a rate per kilo
     * @see #worthOf(Change, Money, Money)
     */
    public static Money worthOf(Change change, Money perPiece) {
        return worth(change, perPiece, Optional.empty());
    }

    /**
     * What a lost allowance costs the passenger, at the desk's own rates.
     *
     * <p>Only what is lost. A better allowance is not a saving to be added to a recommendation: the
     * passenger was not going to be paid for the bag they did not check, and counting it makes every
     * recommendation look larger than the money that actually moves. Anything but a {@link
     * Verdict#WORSE} costs nothing, in the currency of the rate per piece.
     */
    public static Money worthOf(Change change, Money perPiece, Money perKilo) {
        return worth(change, perPiece, Optional.of(perKilo));
    }

    /**
     * A saving with the lost allowance taken off it, at the desk's own rate per piece.
     *
     * @throws ValidationError as {@link #netBenefit(Money, Change, Money, Money)} does, and naming the
     *     {@code kilos} when the allowance lost is weight
     */
    public static Money netBenefit(Money saving, Change change, Money perPiece) {
        return benefit(saving, change, perPiece, Optional.empty());
    }

    /**
     * A saving with the lost allowance taken off it.
     *
     * <p>This is the number the desk should be quoting. A hundred euro drop onto a fare with one fewer
     * bag is a saving of about forty, and quoting the hundred is how a recommendation becomes a
     * complaint. It goes negative when the saving does not cover what is lost.
     *
     * @throws ValidationError naming the {@code saving} and the {@code cost} currency when they
     *     differ
     */
    public static Money netBenefit(Money saving, Change change, Money perPiece, Money perKilo) {
        return benefit(saving, change, perPiece, Optional.of(perKilo));
    }

    private static Change numbers(Baggage was, Baggage now, int before, int after, boolean pieces) {
        if (after == before) {
            return new Change(was, now, Verdict.SAME);
        }
        if (after > before) {
            return new Change(was, now, Verdict.BETTER);
        }
        int lost = before - after;
        return new Change(was, now, Verdict.WORSE, pieces ? lost : 0, pieces ? 0 : lost);
    }

    private static Money worth(Change change, Money perPiece, Optional<Money> perKilo) {
        if (change.verdict() != Verdict.WORSE) {
            return Money.zero(perPiece.currency());
        }
        if (change.lostPieces() != 0) {
            return perPiece.times(change.lostPieces());
        }
        return perKilo.orElseThrow(() -> new ValidationError(
                        "a weight allowance needs a rate per kilo", Details.of("kilos", change.lostKilos())))
                .times(change.lostKilos());
    }

    private static Money benefit(Money saving, Change change, Money perPiece, Optional<Money> perKilo) {
        Money cost = worth(change, perPiece, perKilo);
        if (!cost.currency().equals(saving.currency())) {
            throw new ValidationError(
                    "a benefit is worked out in one currency",
                    Details.of("saving", saving.currency(), "cost", cost.currency()));
        }
        return saving.minus(cost);
    }
}
