package io.github.rkaushikdev.gussetline.minimums;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareFilter;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Minimum checks, which exist so a longer journey cannot cost less.
 *
 * <p>The tariff publishes fares between cities, and city pairs are not consistent with each other.
 * Frankfurt to Chicago can be dearer than Frankfurt to Los Angeles even though Los Angeles is
 * further, because the two markets have different competition. Left alone that means a passenger
 * books Frankfurt to Los Angeles, gets off in Chicago, and pays less than the Chicago fare.
 *
 * <p>The higher intermediate point check ({@link #hipCheck}) closes that. Every intermediate point on
 * a component is priced against both ends, and if any of those fares beats the one selected, the
 * component is raised to it. The check reads the same tariff the selection did, which is why it can
 * disagree with it.
 *
 * <p>Only stopovers count. A passenger who connects through Chicago never has the option of stopping
 * there, so the Chicago fare is not something they could have bought instead, and charging them for
 * it would be charging for a journey they did not take.
 *
 * <p>Every check that takes a {@link Trace} records what it decided under {@link Stage#MINIMUM}: a
 * note when there is nothing to compare, a passed step otherwise, naming the {@code pair} and the
 * amount {@code added} when it raised the fare.
 */
public final class Minimums {

    /** The finding of a check that had nothing to raise. */
    public static final MinimumResult NOTHING = new MinimumResult(BigDecimal.ZERO, "nothing to raise");

    private Minimums() {}

    /** {@link #hipCheck(FareComponent, TariffStore, Converter, LocalDate, List, Trace)} with nothing recorded. */
    public static MinimumResult hipCheck(
            FareComponent component,
            TariffStore store,
            Converter converter,
            LocalDate ticketing,
            List<String> stopovers) {
        return hipCheck(component, store, converter, ticketing, stopovers, new Trace());
    }

    /**
     * Raise a component to its highest intermediate fare, if there is one.
     *
     * <p>The stopovers name the points a passenger could have broken at, as the component names them.
     * An empty list is not a shortcut for all of them, it means the component has no stopovers and the
     * check has nothing to compare, which is the correct answer for a straight through component.
     * Each stopover, folded to its city, is priced from the board point and to the off point, and every
     * two stopovers are priced against each other in journey order, all on the priced fare's carrier.
     *
     * @throws NoFareFound when the component has not been priced
     */
    public static MinimumResult hipCheck(
            FareComponent component,
            TariffStore store,
            Converter converter,
            LocalDate ticketing,
            List<String> stopovers,
            Trace trace) {
        Fare fare = pricedFare(component);
        BigDecimal priced = component.nuc().orElseThrow();

        // Folded to cities, because the tariff publishes cities and a finding
        // that names an airport cannot be looked up in it.
        List<String> interior = component.viaPoints().stream()
                .filter(stopovers::contains)
                .map(Geography::cityOf)
                .toList();
        if (interior.isEmpty()) {
            trace.note(Stage.MINIMUM, "no stopover to compare, HIP does not apply");
            return NOTHING;
        }

        String board = component.boardPoint();
        String off = component.offPoint();
        List<CityPair> candidates = new ArrayList<>();
        for (String point : interior) {
            candidates.add(new CityPair(board, point));
            candidates.add(new CityPair(point, off));
        }
        for (int left = 0; left < interior.size(); left++) {
            for (int right = left + 1; right < interior.size(); right++) {
                candidates.add(new CityPair(interior.get(left), interior.get(right)));
            }
        }

        Dearest dearest = new Dearest(priced);
        for (CityPair pair : candidates) {
            if (!pair.origin().equals(pair.destination())) {
                dearest.consider(
                        pair, fareBetween(store, fare.carrier(), pair, converter, ticketing, departure(component)));
            }
        }

        if (dearest.winner.isEmpty()) {
            trace.passed(Stage.MINIMUM, "no intermediate fare beats the component");
            return new MinimumResult(BigDecimal.ZERO, "no higher intermediate point");
        }
        return found(dearest, priced, "higher intermediate point", trace);
    }

    /**
     * Put a higher intermediate point finding onto the component as a surcharge noted {@code HIP}.
     *
     * <p>It rides in the surcharge field rather than replacing the fare, because the fare basis on the
     * ticket is still the one that was selected and the fare calculation line has to show both. A
     * finding that did not apply hands the component back unchanged.
     */
    public static FareComponent applyHip(FareComponent component, MinimumResult result) {
        return applyMinimum(component, result, "HIP");
    }

    /** {@link #backhaulCheck(FareComponent, TariffStore, Converter, LocalDate, MileageTable, Trace)} with nothing recorded. */
    public static MinimumResult backhaulCheck(
            FareComponent component, TariffStore store, Converter converter, LocalDate ticketing, MileageTable table) {
        return backhaulCheck(component, store, converter, ticketing, table, new Trace());
    }

    /**
     * Charge for a journey that goes past its destination and comes back.
     *
     * <p>A component from Frankfurt to Chicago routed through Los Angeles is not a Chicago journey with
     * a detour, it is a Los Angeles journey that ends short. The passenger has had the use of the Los
     * Angeles sector and the Chicago fare does not pay for it.
     *
     * <p>Two things separate this from the higher intermediate point check and both matter. It looks
     * at every ticketed point and not only the stopovers, because a passenger who backtracked was
     * carried past the point whether they got off or not. And it only fires when the geometry says
     * there was a backtrack (a point further from the board point than the off point is), which is a
     * reading of the mileage table rather than of the fares, so the two checks can disagree and often
     * do.
     *
     * @throws NoFareFound when the component has not been priced
     */
    public static MinimumResult backhaulCheck(
            FareComponent component,
            TariffStore store,
            Converter converter,
            LocalDate ticketing,
            MileageTable table,
            Trace trace) {
        Fare fare = pricedFare(component);
        BigDecimal priced = component.nuc().orElseThrow();

        String board = component.boardPoint();
        int toDestination = table.tpm(board, component.offPoint()).miles();
        List<String> beyond = component.viaPoints().stream()
                .map(Geography::cityOf)
                .filter(point -> table.tpm(board, point).miles() > toDestination)
                .toList();
        if (beyond.isEmpty()) {
            trace.note(Stage.MINIMUM, "nothing is beyond the destination, no backhaul");
            return new MinimumResult(BigDecimal.ZERO, "no backhaul");
        }

        Dearest dearest = new Dearest(priced);
        for (String point : beyond) {
            CityPair pair = new CityPair(board, point);
            dearest.consider(
                    pair, fareBetween(store, fare.carrier(), pair, converter, ticketing, departure(component)));
        }

        if (dearest.winner.isEmpty()) {
            trace.passed(Stage.MINIMUM, "backtrack found but no dearer fare beyond");
            return new MinimumResult(BigDecimal.ZERO, "no backhaul");
        }
        return found(dearest, priced, "backhaul", trace);
    }

    /**
     * Put any minimum finding onto a component as a surcharge under its own label. A finding that did
     * not apply hands the component back unchanged.
     */
    public static FareComponent applyMinimum(FareComponent component, MinimumResult result, String label) {
        if (!result.applied()) {
            return component;
        }
        return component.withSurcharge(result.added(), label);
    }

    /**
     * The dearest of several checks, because they do not stack; on a tie the first one given.
     *
     * <p>A component that fails both the higher intermediate point check and the backhaul check is
     * raised once, to whichever found more. Adding both would charge twice for the same sector, which
     * is the mistake this method exists to make impossible at the call site. No checks at all is
     * {@link #NOTHING}.
     */
    public static MinimumResult highestOf(MinimumResult... results) {
        MinimumResult highest = NOTHING;
        for (int i = 0; i < results.length; i++) {
            if (i == 0 || results[i].added().compareTo(highest.added()) > 0) {
                highest = results[i];
            }
        }
        return highest;
    }

    /** {@link #circleTripMinimum(PricingUnit, TariffStore, Converter, LocalDate, Trace)} with nothing recorded. */
    public static MinimumResult circleTripMinimum(
            PricingUnit unit, TariffStore store, Converter converter, LocalDate ticketing) {
        return circleTripMinimum(unit, store, converter, ticketing, new Trace());
    }

    /**
     * A circle trip may not undercut a round trip to any point on it.
     *
     * <p>Frankfurt to New York to Los Angeles and home is three components and can add up to less than
     * the Frankfurt to Los Angeles round trip, because the three markets are priced independently. The
     * passenger has been to Los Angeles and back either way, so the round trip fare is the floor. A
     * filed round trip half is doubled for the comparison and a one way is taken as it stands. A unit
     * that is not a circle trip is left alone.
     *
     * @throws NoFareFound when the unit is a circle trip that has not been priced
     */
    public static MinimumResult circleTripMinimum(
            PricingUnit unit, TariffStore store, Converter converter, LocalDate ticketing, Trace trace) {
        if (unit.kind() != JourneyType.CIRCLE_TRIP) {
            return new MinimumResult(BigDecimal.ZERO, "not a circle trip");
        }
        if (!unit.isPriced()) {
            throw new NoFareFound("a minimum check runs after the unit is priced");
        }

        FareComponent first = unit.components().get(0);
        String carrier = first.fare().orElseThrow().carrier();
        String board = first.boardPoint();

        Dearest dearest = new Dearest(unit.totalNuc());
        for (FareComponent component : unit.components()) {
            String point = component.offPoint();
            if (!point.equals(board)) {
                CityPair pair = new CityPair(board, point);
                dearest.consider(pair, roundTripNuc(store, carrier, pair, converter, ticketing, departure(first)));
            }
        }

        if (dearest.winner.isEmpty()) {
            trace.passed(Stage.MINIMUM, "circle trip clears every round trip on it");
            return new MinimumResult(BigDecimal.ZERO, "no circle trip minimum");
        }
        return found(dearest, unit.totalNuc(), "circle trip minimum", trace);
    }

    /** {@link #openJawMinimum(PricingUnit, TariffStore, Converter, LocalDate, Trace)} with nothing recorded. */
    public static MinimumResult openJawMinimum(
            PricingUnit unit, TariffStore store, Converter converter, LocalDate ticketing) {
        return openJawMinimum(unit, store, converter, ticketing, new Trace());
    }

    /**
     * An open jaw may not undercut the round trip it is a variation of.
     *
     * <p>Out to New York and home to Munich instead of Frankfurt is a Frankfurt to New York round trip
     * with the last sector changed. It may cost more and it frequently does, but it may not cost less,
     * because the passenger would then buy the open jaw and throw the ticket away at Munich. The round
     * trip is the first component's board point to its off point, priced as {@link
     * #circleTripMinimum} prices one. A unit that is not an open jaw is left alone.
     *
     * @throws NoFareFound when the unit is an open jaw that has not been priced
     */
    public static MinimumResult openJawMinimum(
            PricingUnit unit, TariffStore store, Converter converter, LocalDate ticketing, Trace trace) {
        if (unit.kind() != JourneyType.OPEN_JAW) {
            return new MinimumResult(BigDecimal.ZERO, "not an open jaw");
        }
        if (!unit.isPriced()) {
            throw new NoFareFound("a minimum check runs after the unit is priced");
        }

        FareComponent first = unit.components().get(0);
        String carrier = first.fare().orElseThrow().carrier();
        CityPair pair = new CityPair(first.boardPoint(), first.offPoint());

        Optional<BigDecimal> floor = roundTripNuc(store, carrier, pair, converter, ticketing, departure(first));
        if (floor.isEmpty() || floor.get().compareTo(unit.totalNuc()) <= 0) {
            trace.passed(Stage.MINIMUM, "open jaw clears the round trip it varies");
            return new MinimumResult(BigDecimal.ZERO, "no open jaw minimum");
        }

        Dearest dearest = new Dearest(unit.totalNuc());
        dearest.consider(pair, floor);
        return found(dearest, unit.totalNuc(), "open jaw minimum", trace);
    }

    private static Fare pricedFare(FareComponent component) {
        if (component.nuc().isEmpty() || component.fare().isEmpty()) {
            throw new NoFareFound("a minimum check runs after the component is priced");
        }
        return component.fare().get();
    }

    private static LocalDate departure(FareComponent component) {
        return component.segments().get(0).departure().toLocalDate();
    }

    /** The finding for the dearest market, recorded as a passed step. */
    private static MinimumResult found(Dearest dearest, BigDecimal base, String reason, Trace trace) {
        CityPair winner = dearest.winner.orElseThrow();
        BigDecimal added = dearest.highest.subtract(base, Numbers.CONTEXT);
        MinimumResult result = new MinimumResult(added, reason, winner, dearest.highest);
        trace.passed(
                Stage.MINIMUM, result.toString(), Details.of("pair", winner.toString(), "added", added.toString()));
        return result;
    }

    /**
     * The cheapest published fare between two points, in neutral units, on sale on the ticketing date
     * and flyable on the departure.
     *
     * <p>Empty rather than a refusal when nothing is published, because an intermediate market with no
     * fare on this carrier simply does not constrain the component and is not an error.
     */
    private static Optional<BigDecimal> fareBetween(
            TariffStore store,
            String carrier,
            CityPair pair,
            Converter converter,
            LocalDate ticketing,
            LocalDate departure) {
        return cheapest(store, carrier, pair, ticketing, departure)
                .map(found -> converter.toNuc(found.amount(), ticketing));
    }

    /**
     * What a plain round trip between two points would have cost, in neutral units.
     *
     * <p>A filed round trip half is doubled and a one way is taken as it stands, because the comparison
     * is against what the passenger could have bought instead, and what they could have bought is a
     * whole round trip.
     */
    private static Optional<BigDecimal> roundTripNuc(
            TariffStore store,
            String carrier,
            CityPair pair,
            Converter converter,
            LocalDate ticketing,
            LocalDate departure) {
        return cheapest(store, carrier, pair, ticketing, departure).map(found -> {
            BigDecimal amount = converter.toNuc(found.amount(), ticketing);
            if (found.owrt() == OwRt.ROUND_TRIP_HALF) {
                return amount.multiply(BigDecimal.TWO, Numbers.CONTEXT);
            }
            return amount;
        });
    }

    private static Optional<Fare> cheapest(
            TariffStore store, String carrier, CityPair pair, LocalDate ticketing, LocalDate departure) {
        try {
            return Optional.of(
                    store.cheapest(carrier, pair.origin(), pair.destination(), FareFilter.dated(ticketing, departure)));
        } catch (NoFareFound nothing) {
            return Optional.empty();
        }
    }

    /** The dearest market seen so far, starting from the amount it has to beat. */
    private static final class Dearest {

        private BigDecimal highest;
        private Optional<CityPair> winner = Optional.empty();

        Dearest(BigDecimal start) {
            highest = start;
        }

        /** Take the market when its fare beats every one before it, never on a tie. */
        void consider(CityPair pair, Optional<BigDecimal> fare) {
            if (fare.isPresent() && fare.get().compareTo(highest) > 0) {
                highest = fare.get();
                winner = Optional.of(pair);
            }
        }
    }
}
