package io.github.rkaushikdev.gussetline.components;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.indicators.Indicators;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.SurfaceSector;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One published fare and the sectors it covers, from one fare break point to the next.
 *
 * <p>A component starts unpriced. {@link #pricedWith} attaches the fare and its amount in neutral
 * units, and {@link #withSurcharge} adds a {@link Charge} on top; each gives a new component and
 * leaves this one alone.
 *
 * <p>Two components are equal when they cover the same sectors with the same fare, the same amount
 * whatever its scale, and the same charges in the same order.
 *
 * @param elements the sectors covered, flown or surface, in order, joining up
 * @param fare the fare that prices it, once priced
 * @param nuc the fare's amount in neutral units, once priced
 * @param charges what has been added on top of the fare, in the order it was added
 */
public record FareComponent(
        List<Element> elements, Optional<Fare> fare, Optional<BigDecimal> nuc, List<Charge> charges) {

    /**
     * Keeps copies of the sectors and the charges.
     *
     * @throws ValidationError when there is no sector, or one sector does not end where the next
     *     starts (naming the index {@code at})
     */
    public FareComponent {
        elements = List.copyOf(elements);
        Objects.requireNonNull(fare, "fare");
        Objects.requireNonNull(nuc, "nuc");
        charges = List.copyOf(charges);
        if (elements.isEmpty()) {
            throw new ValidationError("a fare component covers at least one sector");
        }
        for (int index = 0; index < elements.size() - 1; index++) {
            Element left = elements.get(index);
            Element right = elements.get(index + 1);
            if (!left.destination().equals(right.origin())) {
                throw new ValidationError("a fare component has to join up", Details.of("at", index));
            }
        }
    }

    /** An unpriced component over the sectors, with nothing charged. */
    public FareComponent(List<? extends Element> elements) {
        this(List.<Element>copyOf(elements), Optional.empty(), Optional.empty(), List.of());
    }

    /** The airport the first sector leaves from. */
    public String origin() {
        return elements.get(0).origin();
    }

    /** The airport the last sector arrives at. */
    public String destination() {
        return elements.get(elements.size() - 1).destination();
    }

    /** The city the fare is published from. */
    public String boardPoint() {
        return Geography.cityOf(origin());
    }

    /** The city the fare is published to. */
    public String offPoint() {
        return Geography.cityOf(destination());
    }

    /** The airports between the two ends, in order: where each sector but the last arrives. */
    public List<String> viaPoints() {
        return elements.subList(0, elements.size() - 1).stream()
                .map(Element::destination)
                .toList();
    }

    /** The flown sectors alone, in order. */
    public List<Segment> segments() {
        return elements.stream()
                .filter(Segment.class::isInstance)
                .map(Segment.class::cast)
                .toList();
    }

    /** The marketing carriers, each once, in the order they first fly. */
    public List<String> carriers() {
        Set<String> seen = new LinkedHashSet<>();
        segments().forEach(segment -> seen.add(segment.marketingCarrier()));
        return List.copyOf(seen);
    }

    /**
     * Whose fare prices the component.
     *
     * <p>The carrier of the first international sector governs. On a component with no international
     * sector the first carrier governs, which is the same answer arrived at by a shorter road.
     *
     * @throws ConstructionError when no sector is flown
     */
    public String governingCarrier() {
        List<Segment> flown = segments();
        if (flown.isEmpty()) {
            throw new ConstructionError("a component with no flown sector has no carrier");
        }
        for (Segment segment : flown) {
            if (!Geography.sameCountry(segment.origin(), segment.destination())) {
                return segment.marketingCarrier();
            }
        }
        return flown.get(0).marketingCarrier();
    }

    /** The global indicator of the component itself, through its via points, not of the journey. */
    public GlobalIndicator indicator() {
        return Indicators.globalIndicator(origin(), destination(), viaPoints());
    }

    /** Whether any sector is covered on the ground. */
    public boolean hasSurface() {
        return elements.stream().anyMatch(SurfaceSector.class::isInstance);
    }

    /** Everything charged on top of the fare, in neutral units. Zero when nothing is. */
    public BigDecimal surcharges() {
        return Numbers.sum(charges.stream().map(Charge::nuc).toList());
    }

    /** The reason of each charge, in the order charged, repeats included. */
    public List<String> notes() {
        return charges.stream().map(Charge::reason).toList();
    }

    /**
     * The fare in neutral units and everything charged on top of it.
     *
     * @throws ConstructionError when the component has not been priced
     */
    public BigDecimal totalNuc() {
        BigDecimal priced = nuc.orElseThrow(() -> new ConstructionError("this component has not been priced yet"));
        return priced.add(surcharges(), Numbers.CONTEXT);
    }

    /** What one named reason added, every charge under it together. Zero when it did not fire. */
    public BigDecimal chargeFor(String reason) {
        return Numbers.sum(charges.stream()
                .filter(charge -> charge.reason().equals(reason))
                .map(Charge::nuc)
                .toList());
    }

    /** A copy priced with a fare at an amount in neutral units, keeping the charges already on it. */
    public FareComponent pricedWith(Fare priced, BigDecimal amount) {
        return new FareComponent(elements, Optional.of(priced), Optional.of(amount), charges);
    }

    /** A copy with one more charge after the others. */
    public FareComponent withSurcharge(BigDecimal amount, String reason) {
        List<Charge> more = new ArrayList<>(charges);
        more.add(new Charge(reason, amount));
        return new FareComponent(elements, fare, nuc, more);
    }

    /** Equal when the sectors, the fare and the charges match and the amounts are the same number. */
    @Override
    public boolean equals(Object other) {
        return other instanceof FareComponent that
                && elements.equals(that.elements)
                && fare.equals(that.fare)
                && sameAmount(nuc, that.nuc)
                && charges.equals(that.charges);
    }

    @Override
    public int hashCode() {
        return Objects.hash(elements, fare, nuc.map(BigDecimal::stripTrailingZeros), charges);
    }

    /** The two cities and the fare basis, or {@code unpriced}: {@code FRANYC KLWAP7RT}. */
    @Override
    public String toString() {
        return boardPoint() + offPoint() + " " + fare.map(Fare::fareBasis).orElse("unpriced");
    }

    private static boolean sameAmount(Optional<BigDecimal> left, Optional<BigDecimal> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return left.isEmpty() && right.isEmpty();
        }
        return left.get().compareTo(right.get()) == 0;
    }
}
