package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Residual;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a held drop is actually worth, and why.
 *
 * <p>A decision that stopped before the price was read carries no reading, drop, fee or benefit. One
 * that read the price carries all four, and a {@link Verdict#REISSUE} always does.
 *
 * @param verdict what the desk should do
 * @param watch the watch the drop was held on
 * @param observation the held reading, when the decision got as far as the price
 * @param drop how far the reading sits below what was paid, when read
 * @param penalty the change fee, when read
 * @param benefit what the reissue is worth to the customer, when worked out
 * @param residual what the fare does with a difference
 * @param reason why, in one phrase, for the desk to read out
 * @param notes anything else the desk should know, in the order found
 */
public record Decision(
        Verdict verdict,
        Watch watch,
        Optional<Observation> observation,
        Optional<Money> drop,
        Optional<Money> penalty,
        Optional<Money> benefit,
        Residual residual,
        String reason,
        List<String> notes) {

    public Decision {
        Objects.requireNonNull(verdict, "verdict");
        Objects.requireNonNull(watch, "watch");
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(drop, "drop");
        Objects.requireNonNull(penalty, "penalty");
        Objects.requireNonNull(benefit, "benefit");
        Objects.requireNonNull(residual, "residual");
        Objects.requireNonNull(reason, "reason");
        notes = List.copyOf(notes);
    }

    /** A verdict reached without reading the price, with a forfeited residual and no reason given. */
    public Decision(Verdict verdict, Watch watch) {
        this(verdict, watch, Residual.FORFEITED, "");
    }

    /** A verdict reached without reading the price, for a reason. */
    public Decision(Verdict verdict, Watch watch, Residual residual, String reason) {
        this(
                verdict,
                watch,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                residual,
                reason,
                List.of());
    }

    /** Whether the desk should act on it. */
    public boolean actionable() {
        return verdict.isActionable();
    }

    /** The benefit, or nothing in the currency paid when none was worked out. Never empty. */
    public Money worth() {
        return benefit.orElseGet(() -> Money.zero(watch.paid().currency()));
    }

    /**
     * {@code FRA-NYC-2025-07-04-Y reissue worth 90.00 EUR}, or the verdict and the reason: {@code
     * FRA-NYC-2025-07-04-Y not_changeable, category 31 does not permit a voluntary change}.
     */
    @Override
    public String toString() {
        if (actionable()) {
            return watch.seriesKey() + " reissue worth " + worth();
        }
        return watch.seriesKey() + " " + verdict.value() + ", " + reason;
    }
}
