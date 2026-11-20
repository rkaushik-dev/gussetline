package io.github.rkaushikdev.gussetline.components;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The components that get priced and minimum checked together.
 *
 * <p>It is the pricing unit that decides whether a round trip minimum applies, not the component. A
 * round trip unit is exactly two components, a one way unit exactly one, and a circle trip unit
 * three or more.
 *
 * @param components the components, in journey order
 * @param kind the shape of journey the unit is priced as
 */
public record PricingUnit(List<FareComponent> components, JourneyType kind) {

    /**
     * Keeps a copy of the components.
     *
     * @throws ValidationError when there is no component, or the number of components does not fit
     *     the kind (naming the {@code count})
     */
    public PricingUnit {
        components = List.copyOf(components);
        Objects.requireNonNull(kind, "kind");
        if (components.isEmpty()) {
            throw new ValidationError("a pricing unit needs a component");
        }
        int count = components.size();
        if (kind == JourneyType.ROUND_TRIP && count != 2) {
            throw new ValidationError(
                    "a round trip pricing unit is exactly two components", Details.of("count", count));
        }
        if (kind == JourneyType.ONE_WAY && count != 1) {
            throw new ValidationError("a one way pricing unit is exactly one component", Details.of("count", count));
        }
        if (kind == JourneyType.CIRCLE_TRIP && count < 3) {
            throw new ValidationError(
                    "a circle trip pricing unit is three components or more", Details.of("count", count));
        }
    }

    /** The airport the unit starts at. */
    public String origin() {
        return components.get(0).origin();
    }

    /** The airport the unit ends at. */
    public String destination() {
        return components.get(components.size() - 1).destination();
    }

    /**
     * Where the unit turns round: where its first component ends.
     *
     * @throws ConstructionError for a one way unit, which does not turn round
     */
    public String turnaround() {
        if (kind == JourneyType.ONE_WAY) {
            throw new ConstructionError("a one way pricing unit does not turn round");
        }
        return components.get(0).destination();
    }

    /**
     * Every component's total in neutral units, added together.
     *
     * @throws ConstructionError when a component has not been priced
     */
    public BigDecimal totalNuc() {
        return Numbers.sum(components.stream().map(FareComponent::totalNuc).toList());
    }

    /** Whether every component carries a fare. */
    public boolean isPriced() {
        return components.stream().allMatch(component -> component.nuc().isPresent());
    }

    /**
     * A copy with the component at one position swapped for another. This unit is left alone.
     *
     * @throws IndexOutOfBoundsException when the position is outside the unit
     */
    public PricingUnit replace(int index, FareComponent component) {
        List<FareComponent> parts = new ArrayList<>(components);
        parts.set(index, component);
        return new PricingUnit(parts, kind);
    }

    /** How many components the unit has. */
    public int size() {
        return components.size();
    }

    /** The kind and each component: {@code RT FRANYC KLWAP7RT NYCFRA KLWAP7RT}. */
    @Override
    public String toString() {
        return kind.value() + " "
                + components.stream().map(FareComponent::toString).collect(Collectors.joining(" "));
    }
}
