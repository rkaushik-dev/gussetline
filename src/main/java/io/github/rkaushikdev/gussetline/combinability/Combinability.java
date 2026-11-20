package io.github.rkaushikdev.gussetline.combinability;

import io.github.rkaushikdev.gussetline.CombinabilityError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What one fare permits itself to be joined to, and the checks that read it.
 *
 * <p>Every published fare says what it may be joined to. A fare filed for round trip combination
 * only cannot be half of a circle trip. A fare that permits end on end combination may be joined to
 * a second pricing unit but not necessarily to one on another carrier. A fare with no combinability
 * filed at all combines with nothing and has to stand as a one way, which is the case most systems
 * get wrong because absent reads as permissive if nobody looks.
 *
 * <p>Everything defaults to refused. A filing the loader could not read produces a record that
 * combines with nothing, which shows up as a refusal the desk can look at rather than as a price
 * that is quietly a hundred euro light.
 *
 * <p>The checks run on the assembled pricing unit and not on the fares in isolation ({@link
 * #checkUnit}, {@link #checkEndOnEnd}). A pair of fares that are each combinable can still be an
 * illegal pair, because both sides have to permit the other and the tariff files them separately.
 *
 * @param carrier the carrier designator, upper case
 * @param fareBasis the fare basis it is filed for, upper case without surrounding white space
 * @param roundTrip whether the fare may be half of a round trip
 * @param circleTrip whether the fare may be part of a circle trip
 * @param openJaw whether the fare may be part of an open jaw
 * @param doubleOpenJaw whether the fare may be part of an open jaw at both ends
 * @param endOnEnd whether the fare's pricing unit may be joined to another one on the same ticket
 * @param sameCarrierOnly whether the fare combines only with fares of its own carrier
 * @param sameRuleOnly whether the fare combines only with fares filed under its own basis
 * @param sideTrip whether the fare permits a side trip
 */
public record Combinability(
        String carrier,
        String fareBasis,
        boolean roundTrip,
        boolean circleTrip,
        boolean openJaw,
        boolean doubleOpenJaw,
        boolean endOnEnd,
        boolean sameCarrierOnly,
        boolean sameRuleOnly,
        boolean sideTrip) {

    /**
     * Normalises the carrier and upper cases the fare basis.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public Combinability {
        carrier = Carriers.normaliseCode(Objects.requireNonNull(carrier, "carrier"));
        fareBasis = Text.code(Objects.requireNonNull(fareBasis, "fareBasis"));
    }

    /** A record that permits nothing and insists on its own carrier. */
    public Combinability(String carrier, String fareBasis) {
        this(carrier, fareBasis, false, false, false, false, false, true, false, false);
    }

    /** A builder starting from a record that permits nothing and insists on its own carrier. */
    public static Builder builder(String carrier, String fareBasis) {
        return new Builder(carrier, fareBasis);
    }

    /**
     * Whether the record lets its fare sit in a unit of this shape. A one way always may, because
     * there is nothing in it to combine with.
     */
    public boolean permits(JourneyType kind) {
        return switch (kind) {
            case ONE_WAY -> true;
            case ROUND_TRIP -> roundTrip;
            case CIRCLE_TRIP -> circleTrip;
            case OPEN_JAW -> openJaw;
        };
    }

    /** The carrier, the basis and what it permits: {@code LH KLWAP7RT RT/EOE}, or {@code nothing}. */
    @Override
    public String toString() {
        List<String> permitted = new ArrayList<>();
        if (roundTrip) {
            permitted.add("RT");
        }
        if (circleTrip) {
            permitted.add("CT");
        }
        if (openJaw) {
            permitted.add("OJ");
        }
        if (endOnEnd) {
            permitted.add("EOE");
        }
        return carrier + " " + fareBasis + " " + (permitted.isEmpty() ? "nothing" : String.join("/", permitted));
    }

    /** {@link #checkUnit(PricingUnit, CombinabilityStore, Trace)} with nothing recorded. */
    public static List<String> checkUnit(PricingUnit unit, CombinabilityStore store) {
        return checkUnit(unit, store, new Trace());
    }

    /**
     * Every reason this unit may not be combined the way it has been. An empty list is the pass.
     *
     * <p>A one way unit passes without a lookup, because there is nothing to combine it with yet, and
     * whether it may be joined to a second unit is the end on end question and not this one.
     * Otherwise each fare needs a record that permits the unit's shape, and when two or more records
     * are found, a record filed same carrier only refuses a unit on several carriers and a record
     * filed same rule only refuses a unit on several bases.
     *
     * <p>Each refusal is recorded as a failed combinability step; a unit with none records one pass.
     *
     * @throws CombinabilityError when a component has not been priced yet
     */
    public static List<String> checkUnit(PricingUnit unit, CombinabilityStore store, Trace trace) {
        if (unit.kind() == JourneyType.ONE_WAY) {
            trace.passed(Stage.COMBINABILITY, "a one way unit combines with itself");
            return List.of();
        }

        List<String> refusals = new ArrayList<>();
        List<Combinability> records = new ArrayList<>();
        for (FareComponent component : unit.components()) {
            Fare fare = pricedFare(component);
            Optional<Combinability> found = store.get(fare.carrier(), fare.fareBasis());
            if (found.isEmpty()) {
                refusals.add(fare.carrier() + " " + fare.fareBasis() + " has no combinability filed");
                continue;
            }
            Combinability filed = found.get();
            records.add(filed);
            if (!filed.permits(unit.kind())) {
                refusals.add(filed.carrier + " " + filed.fareBasis + " does not permit "
                        + unit.kind().value());
            }
        }

        if (records.size() > 1) {
            Set<String> carriers = new TreeSet<>();
            Set<String> bases = new TreeSet<>();
            records.forEach(filed -> {
                carriers.add(filed.carrier);
                bases.add(filed.fareBasis);
            });
            if (carriers.size() > 1 && records.stream().anyMatch(Combinability::sameCarrierOnly)) {
                refusals.add("a fare filed same carrier only is combined with " + String.join("/", carriers));
            }
            if (bases.size() > 1 && records.stream().anyMatch(Combinability::sameRuleOnly)) {
                refusals.add("a fare filed same rule only is combined with " + String.join("/", bases));
            }
        }

        report(trace, refusals, unit.kind().value() + " combination permitted");
        return List.copyOf(refusals);
    }

    /** {@link #checkEndOnEnd(List, CombinabilityStore, Trace)} with nothing recorded. */
    public static List<String> checkEndOnEnd(List<PricingUnit> units, CombinabilityStore store) {
        return checkEndOnEnd(units, store, new Trace());
    }

    /**
     * Whether several pricing units may sit on one ticket: every reason they may not. An empty list
     * is the pass.
     *
     * <p>A single unit needs no permission. Two or more do, and every fare in every unit has to carry
     * it, not just the first one, because a passenger only has to find one fare that refuses for the
     * whole ticket to be unissuable. A fare with nothing filed refuses too.
     *
     * <p>Each refusal is recorded as a failed combinability step; units with none record one pass.
     *
     * @throws CombinabilityError when a component has not been priced yet
     */
    public static List<String> checkEndOnEnd(List<PricingUnit> units, CombinabilityStore store, Trace trace) {
        if (units.size() < 2) {
            trace.passed(Stage.COMBINABILITY, "one pricing unit, nothing to join");
            return List.of();
        }

        List<String> refusals = new ArrayList<>();
        for (PricingUnit unit : units) {
            for (FareComponent component : unit.components()) {
                Fare fare = pricedFare(component);
                Optional<Combinability> found = store.get(fare.carrier(), fare.fareBasis());
                if (found.isEmpty()) {
                    refusals.add(fare.carrier() + " " + fare.fareBasis() + " has no combinability filed");
                } else if (!found.get().endOnEnd()) {
                    refusals.add(found.get().carrier + " " + found.get().fareBasis + " does not permit end on end");
                }
            }
        }

        report(trace, refusals, units.size() + " units joined end on end");
        return List.copyOf(refusals);
    }

    private static Fare pricedFare(FareComponent component) {
        return component
                .fare()
                .orElseThrow(() -> new CombinabilityError("combinability runs after the unit is priced"));
    }

    private static void report(Trace trace, List<String> refusals, String passed) {
        refusals.forEach(refusal -> trace.failedStep(Stage.COMBINABILITY, refusal));
        if (refusals.isEmpty()) {
            trace.passed(Stage.COMBINABILITY, passed);
        }
    }

    /**
     * Collects what a record permits, starting from nothing permitted and its own carrier insisted
     * on. Every setter returns the builder.
     */
    public static final class Builder {

        private final String carrier;
        private final String fareBasis;
        private boolean roundTrip;
        private boolean circleTrip;
        private boolean openJaw;
        private boolean doubleOpenJaw;
        private boolean endOnEnd;
        private boolean sameCarrierOnly = true;
        private boolean sameRuleOnly;
        private boolean sideTrip;

        private Builder(String carrier, String fareBasis) {
            this.carrier = Objects.requireNonNull(carrier, "carrier");
            this.fareBasis = Objects.requireNonNull(fareBasis, "fareBasis");
        }

        public Builder roundTrip(boolean permitted) {
            this.roundTrip = permitted;
            return this;
        }

        public Builder circleTrip(boolean permitted) {
            this.circleTrip = permitted;
            return this;
        }

        public Builder openJaw(boolean permitted) {
            this.openJaw = permitted;
            return this;
        }

        public Builder doubleOpenJaw(boolean permitted) {
            this.doubleOpenJaw = permitted;
            return this;
        }

        public Builder endOnEnd(boolean permitted) {
            this.endOnEnd = permitted;
            return this;
        }

        public Builder sameCarrierOnly(boolean insisted) {
            this.sameCarrierOnly = insisted;
            return this;
        }

        public Builder sameRuleOnly(boolean insisted) {
            this.sameRuleOnly = insisted;
            return this;
        }

        public Builder sideTrip(boolean permitted) {
            this.sideTrip = permitted;
            return this;
        }

        /**
         * The record.
         *
         * @throws ValidationError when the carrier designator is malformed
         */
        public Combinability build() {
            return new Combinability(
                    carrier,
                    fareBasis,
                    roundTrip,
                    circleTrip,
                    openJaw,
                    doubleOpenJaw,
                    endOnEnd,
                    sameCarrierOnly,
                    sameRuleOnly,
                    sideTrip);
        }
    }
}
