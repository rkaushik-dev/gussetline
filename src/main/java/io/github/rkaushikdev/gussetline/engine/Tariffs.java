package io.github.rkaushikdev.gussetline.engine;

import io.github.rkaushikdev.gussetline.combinability.CombinabilityStore;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.mileage.ExtraMileageAllowance;
import io.github.rkaushikdev.gussetline.routing.RoutingStore;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.surcharges.SurchargeTable;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import java.util.Objects;

/**
 * Everything the engine reads, passed as one thing so it cannot go stale.
 *
 * <p>A run that reads the fare store from one snapshot and the routing store from another produces
 * a price nobody can reproduce, so they travel together and the loaders replace the whole object.
 * Every part but the fares defaults to an empty store of its kind; {@link #builder(TariffStore)}
 * sets the ones a caller holds.
 *
 * @param fares the published fares
 * @param routings the routing maps the fares name
 * @param miles the ticketed point mileages
 * @param surcharges the Q surcharges filed
 * @param combinability what each fare may be combined with
 * @param allowances the extra mileage allowances filed
 * @param rules the fare rules
 */
public record Tariffs(
        TariffStore fares,
        RoutingStore routings,
        MileageTable miles,
        SurchargeTable surcharges,
        CombinabilityStore combinability,
        ExtraMileageAllowance allowances,
        RuleStore rules) {

    public Tariffs {
        Objects.requireNonNull(fares, "fares");
        Objects.requireNonNull(routings, "routings");
        Objects.requireNonNull(miles, "miles");
        Objects.requireNonNull(surcharges, "surcharges");
        Objects.requireNonNull(combinability, "combinability");
        Objects.requireNonNull(allowances, "allowances");
        Objects.requireNonNull(rules, "rules");
    }

    /** The fares alone, with every other part empty. */
    public Tariffs(TariffStore fares) {
        this(
                fares,
                new RoutingStore(),
                new MileageTable(),
                new SurchargeTable(),
                new CombinabilityStore(),
                new ExtraMileageAllowance(),
                new RuleStore());
    }

    /** A builder holding the fares, every other part starting empty. */
    public static Builder builder(TariffStore fares) {
        return new Builder(fares);
    }

    /** Collects the parts of a {@link Tariffs}. Every setter refuses null and returns the builder. */
    public static final class Builder {

        private final TariffStore fares;
        private RoutingStore routings = new RoutingStore();
        private MileageTable miles = new MileageTable();
        private SurchargeTable surcharges = new SurchargeTable();
        private CombinabilityStore combinability = new CombinabilityStore();
        private ExtraMileageAllowance allowances = new ExtraMileageAllowance();
        private RuleStore rules = new RuleStore();

        private Builder(TariffStore fares) {
            this.fares = Objects.requireNonNull(fares, "fares");
        }

        public Builder routings(RoutingStore routings) {
            this.routings = Objects.requireNonNull(routings, "routings");
            return this;
        }

        public Builder miles(MileageTable miles) {
            this.miles = Objects.requireNonNull(miles, "miles");
            return this;
        }

        public Builder surcharges(SurchargeTable surcharges) {
            this.surcharges = Objects.requireNonNull(surcharges, "surcharges");
            return this;
        }

        public Builder combinability(CombinabilityStore combinability) {
            this.combinability = Objects.requireNonNull(combinability, "combinability");
            return this;
        }

        public Builder allowances(ExtraMileageAllowance allowances) {
            this.allowances = Objects.requireNonNull(allowances, "allowances");
            return this;
        }

        public Builder rules(RuleStore rules) {
            this.rules = Objects.requireNonNull(rules, "rules");
            return this;
        }

        public Tariffs build() {
            return new Tariffs(fares, routings, miles, surcharges, combinability, allowances, rules);
        }
    }
}
