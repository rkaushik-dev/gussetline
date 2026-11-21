package io.github.rkaushikdev.gussetline.construction;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * A price built out of published fares, in neutral units, with the trail that built it.
 *
 * <p>The ladder is always the same. Each component takes a published fare, the amount is converted
 * into neutral units and truncated ({@link #priceComponent}), surcharges are added in neutral units,
 * the components are grouped into pricing units ({@link #assembleOneWay}, {@link
 * #assembleRoundTrip}, {@link #assembleCircleTrip}) and added together ({@link #baseNuc()}), the
 * minimum checks raise the total if they have to ({@link #withMinimum}), and the result is converted
 * once into the selling currency and rounded up to the fare unit ({@link #baseFare}).
 *
 * <p>Converting each component into the selling currency and adding those is the classic wrong
 * answer. It rounds up several times instead of once and comes out a few units high, which looks
 * exactly like a supplier being greedy and is actually us.
 *
 * <p>Two constructions are equal when they hold the same units for the same currency and ticketing
 * date, share one trace, and have had the same amount added by the minimum checks, whatever its
 * scale.
 *
 * @param units the pricing units, in journey order, every component priced
 * @param sellingCurrency the currency the price is sold in
 * @param ticketing the date of ticketing, which picks the rate of exchange
 * @param trace the trail the price leaves; the minimum checks add to it
 * @param minimumAdded what the minimum checks have added on top of the components, in neutral units
 */
public record Construction(
        List<PricingUnit> units, String sellingCurrency, LocalDate ticketing, Trace trace, BigDecimal minimumAdded) {

    /**
     * Keeps a copy of the units.
     *
     * @throws ConstructionError when there is no unit, or a component in one has not been priced
     */
    public Construction {
        units = List.copyOf(units);
        Objects.requireNonNull(sellingCurrency, "sellingCurrency");
        Objects.requireNonNull(ticketing, "ticketing");
        Objects.requireNonNull(trace, "trace");
        Objects.requireNonNull(minimumAdded, "minimumAdded");
        if (units.isEmpty()) {
            throw new ConstructionError("a construction needs a pricing unit");
        }
        for (PricingUnit unit : units) {
            if (!unit.isPriced()) {
                throw new ConstructionError("every component has to carry a fare first");
            }
        }
    }

    /** A construction nothing has been added to yet, recording on the trace given. */
    public Construction(List<PricingUnit> units, String sellingCurrency, LocalDate ticketing, Trace trace) {
        this(units, sellingCurrency, ticketing, trace, BigDecimal.ZERO);
    }

    /** A construction nothing has been added to yet, on a fresh trace. */
    public Construction(List<PricingUnit> units, String sellingCurrency, LocalDate ticketing) {
        this(units, sellingCurrency, ticketing, new Trace());
    }

    /**
     * One published fare in neutral units, truncated to two decimals.
     *
     * <p>A round trip half converts as filed and stays a half. Doubling happens when the pricing unit
     * is assembled, not here, because a half that has already been doubled cannot be told from a one
     * way and the minimum checks need to know which it is.
     *
     * @throws ValidationError when the converter holds no rate for the fare's currency on the date
     */
    public static BigDecimal fareToNuc(Fare fare, Converter converter, LocalDate ticketing) {
        return converter.toNuc(fare.amount(), ticketing);
    }

    /** {@link #priceComponent(FareComponent, Fare, Converter, LocalDate, Trace)} with nothing recorded. */
    public static FareComponent priceComponent(
            FareComponent component, Fare fare, Converter converter, LocalDate ticketing) {
        return priceComponent(component, fare, converter, ticketing, new Trace());
    }

    /**
     * A copy of the component carrying the fare at its amount in neutral units, with the conversion
     * recorded as a passed {@link Stage#CONVERSION} step naming the amount {@code filed} and the
     * {@code nuc} it became.
     *
     * @throws ValidationError when the converter holds no rate for the fare's currency on the date
     */
    public static FareComponent priceComponent(
            FareComponent component, Fare fare, Converter converter, LocalDate ticketing, Trace trace) {
        BigDecimal nuc = fareToNuc(fare, converter, ticketing);
        trace.passed(
                Stage.CONVERSION,
                fare.fareBasis() + " converts to " + nuc + " NUC",
                Details.of("filed", fare.amount().toString(), "nuc", nuc.toString()));
        return component.pricedWith(fare, nuc);
    }

    /** {@link #assembleRoundTrip(FareComponent, FareComponent, Trace)} with nothing recorded. */
    public static PricingUnit assembleRoundTrip(FareComponent outbound, FareComponent inbound) {
        return assembleRoundTrip(outbound, inbound, new Trace());
    }

    /**
     * Group two components as a round trip, checking they are halves.
     *
     * <p>A round trip made of two one way only fares is not a round trip, it is two one ways that
     * happen to touch, and pricing it as a unit applies a minimum check the tariff never asked for.
     * The assembly is recorded as a passed {@link Stage#SELECTION} step naming both fare bases.
     *
     * @throws ConstructionError when either component carries no fare, or either fare is one way only
     *     (naming the {@code outbound} and {@code inbound} fare bases)
     */
    public static PricingUnit assembleRoundTrip(FareComponent outbound, FareComponent inbound, Trace trace) {
        String unpriced = "a round trip unit needs both components priced";
        Fare going = fareOf(outbound, unpriced);
        Fare coming = fareOf(inbound, unpriced);
        if (going.owrt() == OwRt.ONE_WAY_ONLY || coming.owrt() == OwRt.ONE_WAY_ONLY) {
            throw new ConstructionError(
                    "a one way only fare cannot be half of a round trip",
                    Details.of("outbound", going.fareBasis(), "inbound", coming.fareBasis()));
        }
        trace.passed(
                Stage.SELECTION,
                "round trip unit assembled",
                Details.of("outbound", going.fareBasis(), "inbound", coming.fareBasis()));
        return new PricingUnit(List.of(outbound, inbound), JourneyType.ROUND_TRIP);
    }

    /** {@link #assembleOneWay(FareComponent, Trace)} with nothing recorded. */
    public static PricingUnit assembleOneWay(FareComponent component) {
        return assembleOneWay(component, new Trace());
    }

    /**
     * Group one component as a one way, checking the fare may stand alone. The assembly is recorded as
     * a passed {@link Stage#SELECTION} step naming the {@code fare} basis.
     *
     * @throws ConstructionError when the component carries no fare, or the fare is a round trip half
     *     (naming the {@code fare_basis})
     */
    public static PricingUnit assembleOneWay(FareComponent component, Trace trace) {
        Fare fare = fareOf(component, "a one way unit needs its component priced");
        if (fare.owrt() == OwRt.ROUND_TRIP_HALF) {
            throw new ConstructionError(
                    "a round trip half cannot stand as a one way", Details.of("fare_basis", fare.fareBasis()));
        }
        trace.passed(Stage.SELECTION, "one way unit assembled", Details.of("fare", fare.fareBasis()));
        return new PricingUnit(List.of(component), JourneyType.ONE_WAY);
    }

    /** {@link #assembleCircleTrip(List, Trace)} with nothing recorded. */
    public static PricingUnit assembleCircleTrip(List<FareComponent> components) {
        return assembleCircleTrip(components, new Trace());
    }

    /**
     * Group three or more components as a circle trip. The assembly is recorded as a passed {@link
     * Stage#SELECTION} step with the {@code count}.
     *
     * @throws ConstructionError when there are fewer than three components (naming the {@code
     *     count}), or one of them carries no fare
     */
    public static PricingUnit assembleCircleTrip(List<FareComponent> components, Trace trace) {
        if (components.size() < 3) {
            throw new ConstructionError("a circle trip needs three components", Details.of("count", components.size()));
        }
        for (FareComponent component : components) {
            fareOf(component, "a circle trip unit needs every component priced");
        }
        trace.passed(Stage.SELECTION, "circle trip unit assembled", Details.of("count", components.size()));
        return new PricingUnit(components, JourneyType.CIRCLE_TRIP);
    }

    /** Every component, unit by unit, in journey order. */
    public List<FareComponent> components() {
        return units.stream().flatMap(unit -> unit.components().stream()).toList();
    }

    /** The components added up, before any minimum check, truncated to two decimals. */
    public BigDecimal baseNuc() {
        return Money.truncateNuc(
                Numbers.sum(units.stream().map(PricingUnit::totalNuc).toList()));
    }

    /** The base and everything the minimum checks added, truncated to two decimals. */
    public BigDecimal totalNuc() {
        return Money.truncateNuc(baseNuc().add(minimumAdded, Numbers.CONTEXT));
    }

    /**
     * The one conversion into the selling currency, rounded up to its fare unit once.
     *
     * @throws ValidationError when the converter holds no rate for the selling currency on the date
     */
    public Money baseFare(Converter converter) {
        return converter.fromNuc(totalNuc(), sellingCurrency, ticketing);
    }

    /**
     * A copy raised by what one minimum check found, recorded on the shared trace as a passed {@link
     * Stage#MINIMUM} step under the reason, with the amount {@code added}. This construction is left
     * alone; the copy shares its trace.
     *
     * @throws ConstructionError naming the amount {@code added} when it is negative
     */
    public Construction withMinimum(BigDecimal added, String reason) {
        if (added.signum() < 0) {
            throw new ConstructionError(
                    "a minimum check does not reduce a fare", Details.of("added", added.toString()));
        }
        trace.passed(Stage.MINIMUM, reason, Details.of("added", added.toString()));
        return new Construction(units, sellingCurrency, ticketing, trace, minimumAdded.add(added, Numbers.CONTEXT));
    }

    /** Equal when everything matches, the trace being the same object and the minimum the same number. */
    @Override
    public boolean equals(Object other) {
        return other instanceof Construction that
                && units.equals(that.units)
                && sellingCurrency.equals(that.sellingCurrency)
                && ticketing.equals(that.ticketing)
                && trace.equals(that.trace)
                && minimumAdded.compareTo(that.minimumAdded) == 0;
    }

    @Override
    public int hashCode() {
        return Objects.hash(units, sellingCurrency, ticketing, trace, minimumAdded.stripTrailingZeros());
    }

    /** The total and where it is going: {@code 883.28 NUC into EUR}. */
    @Override
    public String toString() {
        return totalNuc() + " NUC into " + sellingCurrency;
    }

    private static Fare fareOf(FareComponent component, String refusal) {
        return component.fare().orElseThrow(() -> new ConstructionError(refusal));
    }
}
