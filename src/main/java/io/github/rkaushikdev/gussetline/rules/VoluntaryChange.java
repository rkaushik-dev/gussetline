package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import java.util.Objects;

/**
 * Category 31, voluntary changes, as it is filed: whether a ticket may be reissued, which day's
 * tariff the new ticket is priced from, and what becomes of the difference when the new fare is
 * lower.
 *
 * <p>The provision only speaks on a reissue; on a first sale it does not apply. What a particular
 * reissue costs or returns is a {@link Reissue}, which reads this filing together with the category
 * 16 fee.
 *
 * @param permitted whether the fare may be changed at all
 * @param basis which day's tariff the reissue is priced from
 * @param residual what happens to the difference when the new fare is lower
 * @param keepFareBreaks whether the reissue has to keep the ticket's fare break points
 * @param sameCarrierOnly whether the reissue has to stay on the filing carrier
 * @param onceOnly whether the ticket may be changed only once
 */
public record VoluntaryChange(
        boolean permitted,
        RepricingBasis basis,
        Residual residual,
        boolean keepFareBreaks,
        boolean sameCarrierOnly,
        boolean onceOnly)
        implements Provision {

    public VoluntaryChange {
        Objects.requireNonNull(basis, "basis");
        Objects.requireNonNull(residual, "residual");
    }

    /** Changes permitted, priced from the current tariff, the residual forfeited. */
    public VoluntaryChange() {
        this(RepricingBasis.CURRENT, Residual.FORFEITED);
    }

    /** Changes permitted, priced on a basis, with the residual handled one way, and nothing else. */
    public VoluntaryChange(RepricingBasis basis, Residual residual) {
        this(true, basis, residual, false, false, false);
    }

    /** A fare that may not be changed at all. */
    public static VoluntaryChange notPermitted() {
        return builder().permitted(false).build();
    }

    /** A builder starting from {@link #VoluntaryChange()}. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.VOLUNTARY_CHANGES;
    }

    /**
     * Does not apply unless the ticket is being reissued. Then fails when changes are not permitted,
     * and otherwise passes naming the {@code basis} and the {@code residual}.
     */
    @Override
    public Outcome evaluate(Context context) {
        if (!context.reissue()) {
            return silent(category(), "not a reissue");
        }
        if (!permitted) {
            return fails(category(), "the fare does not permit a voluntary change");
        }
        return passes(
                category(),
                "reissue priced " + basis.value() + ", residual " + residual.value(),
                Details.of("basis", basis.value(), "residual", residual.value()));
    }

    /**
     * The filing in one line: {@code changes permitted, current pricing, residual forfeited}, or
     * {@code changes not permitted}.
     */
    @Override
    public String toString() {
        if (!permitted) {
            return "changes not permitted";
        }
        return "changes permitted, " + basis.value() + " pricing, residual " + residual.value();
    }

    /** Collects a filing, starting from changes permitted at the current tariff, forfeited. */
    public static final class Builder {

        private boolean permitted = true;
        private RepricingBasis basis = RepricingBasis.CURRENT;
        private Residual residual = Residual.FORFEITED;
        private boolean keepFareBreaks;
        private boolean sameCarrierOnly;
        private boolean onceOnly;

        private Builder() {}

        public Builder permitted(boolean value) {
            permitted = value;
            return this;
        }

        public Builder basis(RepricingBasis value) {
            basis = value;
            return this;
        }

        public Builder residual(Residual value) {
            residual = value;
            return this;
        }

        public Builder keepFareBreaks(boolean value) {
            keepFareBreaks = value;
            return this;
        }

        public Builder sameCarrierOnly(boolean value) {
            sameCarrierOnly = value;
            return this;
        }

        public Builder onceOnly(boolean value) {
            onceOnly = value;
            return this;
        }

        /** The filing. */
        public VoluntaryChange build() {
            return new VoluntaryChange(permitted, basis, residual, keepFareBreaks, sameCarrierOnly, onceOnly);
        }
    }
}
