package io.github.rkaushikdev.gussetline.selection;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.distance.MileageTable;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.indicators.Indicators;
import io.github.rkaushikdev.gussetline.mileage.ExtraMileageAllowance;
import io.github.rkaushikdev.gussetline.mileage.MileagePrinciple;
import io.github.rkaushikdev.gussetline.mileage.MileageResult;
import io.github.rkaushikdev.gussetline.routing.RoutingStore;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareFilter;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Which published fare prices a component: the winner, the runners up and everything that was
 * refused.
 *
 * <p>The tariff answers with everything filed on the market and most of it does not apply. A fare is
 * a candidate only once its dates, its booking class, its global indicator, its routing and its
 * mileage have all been checked ({@link #select}), and the cheapest survivor wins, measured on the
 * total after the mileage surcharge.
 *
 * <p>The surcharge is a percentage of the fare, so it cannot reorder the survivors on one component,
 * and carrying it here is still worth doing: the number that leaves the selection is compared
 * against other components and against what the supplier quoted, and a base fare that has not had
 * its band applied is not comparable to either.
 *
 * <p>Every fare that was thrown out keeps its reason. Nine times in ten a desk question is not what
 * did we price but why did we not price the fare the supplier used, and the only cheap answer to
 * that is a list.
 *
 * @param chosen the cheapest survivor
 * @param considered every survivor, the chosen one included, in the order the tariff returned them
 * @param rejected every fare refused, with its reason, in the same order
 */
public record Selection(Candidate chosen, List<Candidate> considered, List<Rejection> rejected) {

    private static final Comparator<Candidate> CHEAPEST = Comparator.comparing(Candidate::totalNuc)
            .thenComparing(candidate -> candidate.fare().fareBasis());

    public Selection {
        Objects.requireNonNull(chosen, "chosen");
        considered = List.copyOf(considered);
        rejected = List.copyOf(rejected);
    }

    /** A selection that considered nothing else and refused nothing. */
    public Selection(Candidate chosen) {
        this(chosen, List.of(), List.of());
    }

    /** {@link #select(FareComponent, TariffStore, RoutingStore, MileageTable, Converter, LocalDate,
     * Optional, boolean, Trace)} with no allowances, half fares considered, and nothing recorded. */
    public static Selection select(
            FareComponent component,
            TariffStore tariff,
            RoutingStore routings,
            MileageTable miles,
            Converter converter,
            LocalDate ticketing) {
        return select(component, tariff, routings, miles, converter, ticketing, Optional.empty(), false, new Trace());
    }

    /** {@link #select(FareComponent, TariffStore, RoutingStore, MileageTable, Converter, LocalDate,
     * Optional, boolean, Trace)} with no allowances and half fares considered. */
    public static Selection select(
            FareComponent component,
            TariffStore tariff,
            RoutingStore routings,
            MileageTable miles,
            Converter converter,
            LocalDate ticketing,
            Trace trace) {
        return select(component, tariff, routings, miles, converter, ticketing, Optional.empty(), false, trace);
    }

    /** {@link #select(FareComponent, TariffStore, RoutingStore, MileageTable, Converter, LocalDate,
     * Optional, boolean, Trace)} with no allowances and nothing recorded. */
    public static Selection select(
            FareComponent component,
            TariffStore tariff,
            RoutingStore routings,
            MileageTable miles,
            Converter converter,
            LocalDate ticketing,
            boolean oneWay) {
        return select(component, tariff, routings, miles, converter, ticketing, Optional.empty(), oneWay, new Trace());
    }

    /**
     * Pick the cheapest fare that survives every check, or say why none did.
     *
     * <p>The fares searched are the governing carrier's, between the component's board and off points,
     * on sale on the ticketing date and flyable on the first flown sector's departure. A fare is
     * refused when it is a round trip half and the component stands alone, when its global indicator
     * does not cover the component's, when no sector is booked in its class, when its routing refuses
     * the path, or when the component is over the mileage ladder. The cheapest total wins, ties going
     * to the fare basis that sorts first. The choice is recorded as a passed {@link Stage#SELECTION}
     * step with how many were {@code considered} and {@code refused}; when nothing survives, every
     * refusal is recorded as a failed step instead.
     *
     * @param oneWay whether this component will stand on its own, which decides whether a round trip
     *     half may be considered at all. It is passed in and not worked out here: a component does not
     *     know what it will be assembled into, and the journey above it does.
     * @throws NoFareFound when the component has no flown sector, when nothing is published for it
     *     (naming the {@code carrier}, {@code origin} and {@code destination}), or when every
     *     published fare was refused (naming the same and how many were {@code refused})
     */
    public static Selection select(
            FareComponent component,
            TariffStore tariff,
            RoutingStore routings,
            MileageTable miles,
            Converter converter,
            LocalDate ticketing,
            Optional<ExtraMileageAllowance> allowances,
            boolean oneWay,
            Trace trace) {
        if (component.segments().isEmpty()) {
            throw new NoFareFound("a component with no flown sector cannot be priced");
        }

        String carrier = component.governingCarrier();
        LocalDate departure = component.segments().get(0).departure().toLocalDate();
        GlobalIndicator indicator = component.indicator();
        List<String> cities = new ArrayList<>();
        cities.add(component.boardPoint());
        cities.addAll(component.viaPoints());
        cities.add(component.offPoint());
        List<String> carriers =
                component.segments().stream().map(Segment::marketingCarrier).toList();

        List<Fare> filed = tariff.search(
                carrier, component.boardPoint(), component.offPoint(), FareFilter.dated(ticketing, departure));
        if (filed.isEmpty()) {
            throw new NoFareFound(
                    "nothing published for this component",
                    Details.of(
                            "carrier", carrier,
                            "origin", component.boardPoint(),
                            "destination", component.offPoint()));
        }

        List<Candidate> survivors = new ArrayList<>();
        List<Rejection> rejected = new ArrayList<>();

        for (Fare fare : filed) {
            if (oneWay && fare.owrt() == OwRt.ROUND_TRIP_HALF) {
                // A half is not a cheap fare, it is half of one. Considering it
                // here lets it win on price and fail at assembly, which reads as
                // a broken engine rather than as a market with no one way filed.
                rejected.add(new Rejection(fare.fareBasis(), "a round trip half cannot stand on its own"));
                continue;
            }
            if (!Indicators.indicatorMatches(fare.globalIndicator(), indicator)) {
                rejected.add(new Rejection(
                        fare.fareBasis(),
                        "filed " + fare.globalIndicator().value() + ", journey is " + indicator.value()));
                continue;
            }
            if (!classAvailable(component, fare)) {
                rejected.add(new Rejection(fare.fareBasis(), "no sector booked in " + fare.bookingClass()));
                continue;
            }
            if (!routings.permits(fare.carrier(), fare.routingNumber(), cities, carriers)) {
                rejected.add(new Rejection(fare.fareBasis(), "routing " + fare.routingNumber() + " refuses the path"));
                continue;
            }

            MileageResult result = MileagePrinciple.check(
                    component.boardPoint(),
                    component.offPoint(),
                    component.viaPoints(),
                    miles,
                    Optional.of(indicator),
                    allowances,
                    Optional.empty());
            if (!result.permitted()) {
                rejected.add(new Rejection(fare.fareBasis(), "over the mileage ladder"));
                continue;
            }

            survivors.add(new Candidate(fare, converter.toNuc(fare.amount(), ticketing), result));
        }

        if (survivors.isEmpty()) {
            for (Rejection refusal : rejected) {
                trace.failedStep(Stage.SELECTION, refusal.fareBasis() + " refused, " + refusal.reason());
            }
            throw new NoFareFound(
                    "every published fare was refused",
                    Details.of(
                            "carrier", carrier,
                            "origin", component.boardPoint(),
                            "destination", component.offPoint(),
                            "refused", rejected.size()));
        }

        Candidate chosen = survivors.stream().min(CHEAPEST).orElseThrow();
        trace.passed(
                Stage.SELECTION,
                "took " + chosen.fare().fareBasis() + " at " + chosen.totalNuc() + " NUC",
                Details.of("considered", survivors.size(), "refused", rejected.size()));
        return new Selection(chosen, survivors, rejected);
    }

    /**
     * Attach the winning fare at its filed amount, with no band on it yet.
     *
     * <p>The band is left off because the minimum checks run against the base and can raise it, and
     * the mileage percentage applies to whatever fare ends up governing rather than to the one that
     * was selected.
     */
    public static FareComponent applyFare(FareComponent component, Selection selection) {
        return component.pricedWith(
                selection.chosen().fare(), selection.chosen().baseNuc());
    }

    /**
     * Put the mileage surcharge on, measured against the fare as it now is, under the band as its
     * note. Nothing is added when nothing is owed.
     */
    public static FareComponent applyBand(FareComponent component, Selection selection) {
        MileageResult result = selection.chosen().mileage();
        BigDecimal amount = result.surchargeOn(component.totalNuc());
        if (amount.signum() <= 0) {
            return component;
        }
        return component.withSurcharge(amount, result.band());
    }

    /** Fare and band in one step, for a component nothing else will touch. */
    public static FareComponent applySelection(FareComponent component, Selection selection) {
        return applyBand(applyFare(component, selection), selection);
    }

    /** Why one named fare was not used, given in any case, or empty when it was not refused. */
    public Optional<String> reasonFor(String fareBasis) {
        String wanted = Text.code(fareBasis);
        return rejected.stream()
                .filter(refusal -> refusal.fareBasis().equals(wanted))
                .map(Rejection::reason)
                .findFirst();
    }

    /** The cheapest survivor other than the one chosen, or empty when nothing else survived. */
    public Optional<Candidate> runnerUp() {
        return considered.stream()
                .filter(candidate -> candidate != chosen)
                .min(Comparator.comparing(Candidate::totalNuc));
    }

    /**
     * Whether some sector on the component is booked in the fare's class.
     *
     * <p>Checking every sector would be stricter and would be wrong, because a component priced on a
     * through fare frequently has a feeder in a different class and the tariff does not care.
     */
    private static boolean classAvailable(FareComponent component, Fare fare) {
        return component.segments().stream()
                .anyMatch(segment -> segment.bookingClass().equals(fare.bookingClass()));
    }
}
