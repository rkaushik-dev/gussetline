package io.github.rkaushikdev.gussetline.engine;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.components.Components;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.construction.Construction;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.fcl.Fcl;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.minimums.MinimumResult;
import io.github.rkaushikdev.gussetline.minimums.Minimums;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Outcome;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.Rules;
import io.github.rkaushikdev.gussetline.rules.Stopovers;
import io.github.rkaushikdev.gussetline.selection.Selection;
import io.github.rkaushikdev.gussetline.sidetrip.SideTrip;
import io.github.rkaushikdev.gussetline.surcharges.Surcharges;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * The pricing engine, which is the ladder run in the one order that works.
 *
 * <p>Split the journey into components, select a fare for each, add the surcharges, run the minimum
 * checks, group the components into pricing units, check the combinability, add the unit minimums,
 * convert once, and write the line. The order is not a preference and it took two rounds of arguing
 * with a supplier to get right.
 *
 * <p>The fare is selected at its filed amount with no band on it. The higher intermediate point and
 * backhaul checks run against that base and can replace it, because they are fare determination and
 * not surcharges. Only then does the mileage band apply, and it applies to whichever fare ended up
 * governing, so a component raised to the Chicago fare pays five percent of Chicago and not five
 * percent of Los Angeles. Q surcharges go on after that, then whatever the fare's rule charges for
 * the stopovers taken. Unit minimums come last before the single conversion into the selling
 * currency.
 *
 * <p>Put the band on at selection time instead and every component the minimum checks touch is
 * surcharged on the wrong base. It is a small number and it is wrong in the supplier's favour, which
 * is why nobody notices.
 *
 * <p>Side trips come out before any of that happens. An excursion off the middle of a journey is
 * priced as its own component and its points are not on the path of the component it hangs off, so
 * the journey has to be split at its edges before the shape is read at all. Reading the shape first
 * sees Frankfurt twice and calls a one way with an excursion a circle trip, which then breaks the
 * fare where nothing turns round.
 */
public final class Engine {

    private Engine() {}

    /**
     * {@link #price(Itinerary, Tariffs, Converter, LocalDate, String, Optional, Optional)} broken
     * where the journey turns round and at its excursions, on a trace named for the journey.
     */
    public static Priced price(
            Itinerary itinerary, Tariffs tariffs, Converter converter, LocalDate ticketing, String sellingCurrency) {
        return price(itinerary, tariffs, converter, ticketing, sellingCurrency, Optional.empty(), Optional.empty());
    }

    /**
     * {@link #price(Itinerary, Tariffs, Converter, LocalDate, String, Optional, Optional)} broken
     * where the caller says, on a trace named for the journey.
     */
    public static Priced price(
            Itinerary itinerary,
            Tariffs tariffs,
            Converter converter,
            LocalDate ticketing,
            String sellingCurrency,
            List<Integer> breaks) {
        return price(itinerary, tariffs, converter, ticketing, sellingCurrency, Optional.of(breaks), Optional.empty());
    }

    /**
     * {@link #price(Itinerary, Tariffs, Converter, LocalDate, String, Optional, Optional)} broken
     * where the journey turns round and at its excursions, recording on the trace given.
     */
    public static Priced price(
            Itinerary itinerary,
            Tariffs tariffs,
            Converter converter,
            LocalDate ticketing,
            String sellingCurrency,
            Trace trace) {
        return price(itinerary, tariffs, converter, ticketing, sellingCurrency, Optional.empty(), Optional.of(trace));
    }

    /**
     * Run the whole ladder over one itinerary.
     *
     * <p>{@code breaks} names where the fare components split, as element indices: the last element
     * of each component but the final one. Without it the journey breaks where it turns round, unless
     * it is a one way, and at the edges and the middle of every excursion. It is not searched for on
     * purpose. Finding the cheapest split is a search over every combination and belongs above this
     * method, which prices the split it is given so that the search can compare two of them.
     *
     * <p>A price that a rule or the combinability refuses still comes back, with the refusals on it
     * ({@link Priced#issuable()}). Every decision lands on the trace; without one, a trace named for
     * the journey's two ends is started, such as {@code FRAFRA}.
     *
     * @param breaks where the components split, when the caller knows
     * @param trace the trace to record on, when the caller keeps one
     * @throws io.github.rkaushikdev.gussetline.NoFareFound when a component has nothing published
     *     for it, or every fare published for it is refused
     * @throws ConstructionError when the components cannot be grouped into pricing units, such as a
     *     round trip half left standing alone
     * @throws io.github.rkaushikdev.gussetline.ValidationError when the breaks do not climb or run
     *     past the end, or the converter holds no rate needed
     */
    public static Priced price(
            Itinerary itinerary,
            Tariffs tariffs,
            Converter converter,
            LocalDate ticketing,
            String sellingCurrency,
            Optional<List<Integer>> breaks,
            Optional<Trace> trace) {
        Trace trail = trace.orElseGet(() -> new Trace(itinerary.origin() + itinerary.destination()));
        List<SideTrip> excursions = SideTrip.find(itinerary);
        JourneyType kind = shapeOf(itinerary, excursions, trail);
        List<Integer> points = breaks.orElseGet(() -> defaultBreaks(itinerary, kind, excursions));
        List<FareComponent> components = Components.splitAt(itinerary.elements(), points);
        List<String> stopovers = itinerary.stopovers();
        List<String> connections = itinerary.connections();

        List<FareComponent> priced = new ArrayList<>();
        List<String> refused = new ArrayList<>();
        boolean oneWay = kind == JourneyType.ONE_WAY && components.size() == 1;
        for (FareComponent component : components) {
            Charged built =
                    priceComponent(component, itinerary, tariffs, converter, ticketing, stopovers, trail, oneWay);
            priced.add(built.component());
            refused.addAll(built.refusals());
        }

        List<PricingUnit> units = assemble(priced, kind, trail);
        for (PricingUnit unit : units) {
            refused.addAll(Combinability.checkUnit(unit, tariffs.combinability(), trail));
        }
        refused.addAll(Combinability.checkEndOnEnd(units, tariffs.combinability(), trail));

        Construction construction = new Construction(units, sellingCurrency, ticketing, trail);
        for (PricingUnit unit : units) {
            construction = applyUnitMinimums(construction, unit, tariffs, converter, ticketing, trail);
        }

        Money base = construction.baseFare(converter);
        trail.passed(Stage.CONVERSION, construction.totalNuc() + " NUC becomes " + base);
        BigDecimal roe = converter.roe().rateFor(sellingCurrency, ticketing);
        return new Priced(construction, base, Fcl.render(construction, roe, connections), refused);
    }

    /** A component taken through the ladder, and what its rule refused. */
    private record Charged(FareComponent component, List<String> refusals) {}

    /**
     * Take one component from unpriced to fully charged.
     *
     * <p>Pulled out of {@link #price} because the ladder for a single component is eight steps in a
     * fixed order, and having it inline inside a loop inside the journey level method made the order
     * look like an accident of where the lines happened to sit.
     */
    private static Charged priceComponent(
            FareComponent component,
            Itinerary itinerary,
            Tariffs tariffs,
            Converter converter,
            LocalDate ticketing,
            List<String> stopovers,
            Trace trail,
            boolean oneWay) {
        Selection chosen = Selection.select(
                component,
                tariffs.fares(),
                tariffs.routings(),
                tariffs.miles(),
                converter,
                ticketing,
                Optional.of(tariffs.allowances()),
                oneWay,
                trail);
        FareComponent withFare = Selection.applyFare(component, chosen);

        List<String> said = checkRules(withFare, itinerary, tariffs, ticketing, trail);

        MinimumResult hip = Minimums.hipCheck(withFare, tariffs.fares(), converter, ticketing, stopovers, trail);
        MinimumResult backhaul =
                Minimums.backhaulCheck(withFare, tariffs.fares(), converter, ticketing, tariffs.miles(), trail);
        FareComponent governing = Minimums.applyMinimum(withFare, Minimums.highestOf(hip, backhaul), "HIP");
        FareComponent banded = Selection.applyBand(governing, chosen);
        FareComponent charged = Surcharges.applyAll(
                banded, Surcharges.assessComponent(banded, tariffs.surcharges(), converter, ticketing, trail));
        return new Charged(chargeStopovers(charged, itinerary, tariffs, ticketing, trail), said);
    }

    /** The rule the component's fare names, if it is filed. */
    private static Optional<RuleSet> ruleOf(Fare fare, Tariffs tariffs) {
        return tariffs.rules().find(fare.carrier(), fare.ruleNumber(), fare.tariffNumber());
    }

    /** Run the fare's own rule and return everything that refused. A rule nobody filed is noted. */
    private static List<String> checkRules(
            FareComponent component, Itinerary itinerary, Tariffs tariffs, LocalDate ticketing, Trace trail) {
        if (component.fare().isEmpty()) {
            return List.of();
        }
        Fare fare = component.fare().get();
        Optional<RuleSet> rule = ruleOf(fare, tariffs);
        if (rule.isEmpty()) {
            trail.note(Stage.RULE, "no rule " + fare.ruleNumber() + " filed for " + fare.carrier());
            return List.of();
        }
        List<Outcome> outcomes =
                Rules.evaluate(rule.get().provisions(), new Context(itinerary, component, ticketing), trail);
        return Rules.refusals(outcomes);
    }

    /**
     * Add whatever the fare charges for the stopovers actually taken, under the note {@code SO}.
     *
     * <p>This is a rule producing money rather than a verdict, which is why it happens here and not
     * inside the rule check. It lands after the surcharges and before the unit minimums, in the same
     * place a Q surcharge does.
     */
    private static FareComponent chargeStopovers(
            FareComponent component, Itinerary itinerary, Tariffs tariffs, LocalDate ticketing, Trace trail) {
        if (component.fare().isEmpty()) {
            return component;
        }
        Optional<RuleSet> rule = ruleOf(component.fare().get(), tariffs);
        if (rule.isEmpty()) {
            return component;
        }
        Context context = new Context(itinerary, component, ticketing);
        FareComponent charged = component;
        for (Provision provision : rule.get().provisions()) {
            if (!(provision instanceof Stopovers stopovers)) {
                continue;
            }
            BigDecimal amount = stopovers.chargeFor(context);
            if (amount.signum() > 0) {
                trail.passed(
                        Stage.SURCHARGE, "stopover charge " + amount + " NUC", Details.of("nuc", amount.toString()));
                charged = charged.withSurcharge(amount, "SO");
            }
        }
        return charged;
    }

    /**
     * What kind of journey this is, once the excursions are set aside.
     *
     * <p>Frankfurt, Vienna, Frankfurt, New York touches Frankfurt twice and reads as a circle trip
     * while the excursion is still in it. Take the excursion out and it is the one way it always was.
     * Setting excursions aside is noted under {@link Stage#SHAPE}.
     */
    private static JourneyType shapeOf(Itinerary itinerary, List<SideTrip> excursions, Trace trail) {
        if (excursions.isEmpty()) {
            return itinerary.journeyType();
        }
        JourneyType kind = SideTrip.without(itinerary, excursions).journeyType();
        trail.note(
                Stage.SHAPE,
                excursions.size() + " side trips set aside, leaving a " + kind.value(),
                Details.of(
                        "excursions",
                        excursions.stream().map(SideTrip::toString).collect(Collectors.joining(", "))));
        return kind;
    }

    /**
     * Break the fare where the journey turns round, and at every excursion.
     *
     * <p>An excursion is its own component whatever else the journey does, so its edges are breaks
     * even on a one way. It also breaks in the middle: an excursion leaves a point and comes back to
     * it, which is a round trip in miniature, and pricing it whole asks the tariff for a fare from
     * Frankfurt to Frankfurt that nobody has ever filed.
     *
     * <p>The turnaround break is worked out on the stripped journey and put back in the full
     * journey's own numbering, because a break index means nothing once the elements have moved.
     */
    private static List<Integer> defaultBreaks(Itinerary itinerary, JourneyType kind, List<SideTrip> excursions) {
        Set<Integer> points = new TreeSet<>();
        for (SideTrip trip : excursions) {
            if (trip.first() > 0) {
                points.add(trip.first() - 1);
            }
            if (trip.last() < itinerary.elements().size() - 1) {
                points.add(trip.last());
            }
            excursionBreak(trip).ifPresent(points::add);
        }

        if (kind != JourneyType.ONE_WAY) {
            turnaroundBreak(itinerary, excursions).ifPresent(points::add);
        }
        return List.copyOf(points);
    }

    /** Where an excursion turns round, in the full journey's numbering. */
    private static Optional<Integer> excursionBreak(SideTrip trip) {
        if (trip.elements().size() < 2) {
            return Optional.empty();
        }
        int turn = new Itinerary(trip.elements()).turnaroundIndex();
        if (turn <= 0 || turn >= trip.elements().size()) {
            return Optional.empty();
        }
        return Optional.of(trip.first() + turn - 1);
    }

    /** Where the journey turns round, counted in the full element list. */
    private static Optional<Integer> turnaroundBreak(Itinerary itinerary, List<SideTrip> excursions) {
        Itinerary stripped = SideTrip.without(itinerary, excursions);
        int turn = stripped.turnaroundIndex();
        if (turn <= 0 || turn >= stripped.elements().size()) {
            return Optional.empty();
        }

        List<Integer> kept = new ArrayList<>();
        for (int index = 0; index < itinerary.elements().size(); index++) {
            int at = index;
            if (excursions.stream().noneMatch(trip -> trip.first() <= at && at <= trip.last())) {
                kept.add(index);
            }
        }
        return Optional.of(kept.get(turn - 1));
    }

    /**
     * Group the components into pricing units.
     *
     * <p>The shape of the journey decides the grouping only when the split fits it. A round trip cut
     * into three components is not a round trip pricing unit, it is three one way units joined end on
     * end, and it has to be priced as such rather than quietly losing the third component.
     */
    private static List<PricingUnit> assemble(List<FareComponent> components, JourneyType kind, Trace trace) {
        if (components.isEmpty()) {
            throw new ConstructionError("nothing to assemble");
        }
        if (components.size() == 1) {
            return List.of(Construction.assembleOneWay(components.get(0), trace));
        }
        if (kind == JourneyType.CIRCLE_TRIP && components.size() >= 3) {
            return List.of(Construction.assembleCircleTrip(components, trace));
        }
        if (components.size() == 2 && kind == JourneyType.OPEN_JAW) {
            return List.of(new PricingUnit(components, JourneyType.OPEN_JAW));
        }
        if (components.size() == 2 && kind == JourneyType.ROUND_TRIP) {
            return List.of(Construction.assembleRoundTrip(components.get(0), components.get(1), trace));
        }
        trace.note(
                Stage.SELECTION,
                components.size() + " components do not fit one " + kind.value() + " unit, pricing end on end");
        return components.stream()
                .map(component -> Construction.assembleOneWay(component, trace))
                .toList();
    }

    private static Construction applyUnitMinimums(
            Construction construction,
            PricingUnit unit,
            Tariffs tariffs,
            Converter converter,
            LocalDate ticketing,
            Trace trace) {
        MinimumResult circle = Minimums.circleTripMinimum(unit, tariffs.fares(), converter, ticketing, trace);
        MinimumResult jaw = Minimums.openJawMinimum(unit, tariffs.fares(), converter, ticketing, trace);
        MinimumResult raised = Minimums.highestOf(circle, jaw);
        if (raised.applied()) {
            return construction.withMinimum(raised.added(), raised.reason());
        }
        return construction;
    }
}
