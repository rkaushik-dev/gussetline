package io.github.rkaushikdev.gussetline.api.routers;

import io.github.rkaushikdev.gussetline.api.Deps;
import io.github.rkaushikdev.gussetline.api.Requires;
import io.github.rkaushikdev.gussetline.api.Role;
import io.github.rkaushikdev.gussetline.api.Schemas.ChargeOut;
import io.github.rkaushikdev.gussetline.api.Schemas.ComponentOut;
import io.github.rkaushikdev.gussetline.api.Schemas.MoneyOut;
import io.github.rkaushikdev.gussetline.api.Schemas.PriceOut;
import io.github.rkaushikdev.gussetline.api.Schemas.PriceRequest;
import io.github.rkaushikdev.gussetline.api.Schemas.SectorIn;
import io.github.rkaushikdev.gussetline.api.Schemas.TrailStepOut;
import io.github.rkaushikdev.gussetline.components.Charge;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.engine.Engine;
import io.github.rkaushikdev.gussetline.engine.Priced;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.exchange.Exchange;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import io.github.rkaushikdev.gussetline.trace.Step;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Pricing a journey on demand, which is what the audit screen calls.
 *
 * <p>The interesting thing this returns is not the number, it is the trail. An agent looking at a
 * supplier quote that is forty euro high wants to know which step made the difference, and the trail
 * says so line by line. So the trail is part of the response and not a debug flag, and it is the part
 * clients actually render.
 */
@RestController
@RequestMapping("/pricing")
@Requires(Role.READER)
@Tag(name = "pricing")
public final class PricingRoutes {

    private final Deps deps;

    public PricingRoutes(Deps deps) {
        this.deps = deps;
    }

    /** Price the sectors given, from the tariff, and show the working. */
    @PostMapping
    @Operation(
            summary = "Price Journey",
            description = "Price the sectors given, from the tariff, and show the working.")
    public PriceOut priceJourney(@Valid @RequestBody PriceRequest request) {
        return deps.unit(work -> price(work, request));
    }

    private PriceOut price(UnitOfWork work, PriceRequest request) {
        List<Segment> segments =
                request.sectors().stream().map(PricingRoutes::segment).toList();
        Itinerary itinerary = new Itinerary(segments);

        TariffStore store = new TariffStore();
        for (Fare fare : work.tariff()
                .search(
                        segments.get(0).marketingCarrier(),
                        itinerary.origin(),
                        itinerary.destination(),
                        request.ticketing())) {
            store.add(fare);
        }

        Tariffs tariffs =
                Tariffs.builder(store).miles(Distance.defaultMileageTable()).build();
        Converter converter = new Converter(Exchange.defaultRoeTable());
        String currency =
                request.sellingCurrency().isEmpty() ? deps.settings().sellingCurrency() : request.sellingCurrency();

        Priced result = Engine.price(
                itinerary, tariffs, converter, request.ticketing(), currency, request.breaks(), Optional.empty());

        List<TrailStepOut> trail = new ArrayList<>();
        for (Step step : result.trace()) {
            trail.add(new TrailStepOut(step.stage().value(), step.verdict().value(), step.message()));
        }
        return new PriceOut(
                MoneyOut.of(result.baseFare()),
                result.totalNuc().toString(),
                result.line(),
                result.issuable(),
                result.refusals(),
                result.construction().components().stream()
                        .map(PricingRoutes::componentOut)
                        .toList(),
                trail);
    }

    /** One sector as the engine reads it: a cabin letter that is no cabin prices as economy. */
    private static Segment segment(SectorIn sector) {
        Cabin cabin = Arrays.stream(Cabin.values())
                .filter(known -> known.value().equals(sector.cabin()))
                .findFirst()
                .orElse(Cabin.ECONOMY);
        long days = ChronoUnit.DAYS.between(
                sector.departure().toLocalDate(), sector.arrival().toLocalDate());
        return new Segment(
                sector.carrier(),
                sector.flightNumber(),
                sector.origin(),
                sector.destination(),
                sector.departure(),
                sector.arrival(),
                sector.bookingClass(),
                cabin,
                sector.operatingCarrier(),
                Optional.empty(),
                (int) Math.max(0, days),
                0,
                Optional.empty(),
                false);
    }

    private static ComponentOut componentOut(FareComponent component) {
        return new ComponentOut(
                component.boardPoint(),
                component.offPoint(),
                component.fare().map(Fare::fareBasis).orElse(""),
                component.totalNuc().toString(),
                component.charges().stream().map(PricingRoutes::chargeOut).toList());
    }

    private static ChargeOut chargeOut(Charge charge) {
        return new ChargeOut(charge.reason(), charge.nuc().toString());
    }
}
