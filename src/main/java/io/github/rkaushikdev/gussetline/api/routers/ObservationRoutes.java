package io.github.rkaushikdev.gussetline.api.routers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.api.Deps;
import io.github.rkaushikdev.gussetline.api.Length;
import io.github.rkaushikdev.gussetline.api.Requires;
import io.github.rkaushikdev.gussetline.api.Role;
import io.github.rkaushikdev.gussetline.api.Schemas.MoneyOut;
import io.github.rkaushikdev.gussetline.api.Schemas.OfferOut;
import io.github.rkaushikdev.gussetline.api.Schemas.SectorOut;
import io.github.rkaushikdev.gussetline.api.Schemas.SeriesOut;
import io.github.rkaushikdev.gussetline.api.Schemas.TaxOut;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.persistence.Mappers;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reading what the collectors saw.
 *
 * <p>Read only, on purpose. Nothing writes an observation through here, because an observation is a
 * reading of a market and the only thing entitled to make one is a collector that actually asked a
 * supplier. A route that let a person post one would put a number into the savings report that nobody
 * ever saw.
 */
@RestController
@RequestMapping("/observations")
@Requires(Role.READER)
@Tag(name = "observations")
public final class ObservationRoutes {

    private final Deps deps;

    public ObservationRoutes(Deps deps) {
        this.deps = deps;
    }

    /** {@link #offerOut(Offer, int)} for an offer seen once. */
    public static OfferOut offerOut(Offer offer) {
        return offerOut(offer, 1);
    }

    /** One observed offer as it crosses the wire. */
    public static OfferOut offerOut(Offer offer, int sightings) {
        return new OfferOut(
                offer.fingerprint(),
                offer.source(),
                offer.origin(),
                offer.destination(),
                offer.itinerary().departure().toLocalDate(),
                offer.cabin().value(),
                offer.fareBases(),
                MoneyOut.of(offer.breakdown().base()),
                offer.breakdown().taxes().stream()
                        .map(ObservationRoutes::taxOut)
                        .toList(),
                MoneyOut.of(offer.breakdown().surchargeTotal()),
                MoneyOut.of(offer.total()),
                offer.breakdown().quotedTotal().map(MoneyOut::of),
                offer.breakdown().addsUp(),
                offer.seats(),
                offer.itinerary().segments().stream()
                        .map(ObservationRoutes::sectorOut)
                        .toList(),
                offer.observedAt(),
                offer.observedAt(),
                sightings);
    }

    /**
     * Every offer in one series, cheapest first.
     *
     * <p>A key that is not a key is refused rather than looked up. Both answers are empty, but a 404
     * tells a caller who fat fingered the departure that the desk has nothing for that series, which
     * is not what happened.
     */
    @GetMapping("/series/{series_key}")
    @Operation(summary = "Read Series", description = "Every offer in one series, cheapest first.")
    public SeriesOut readSeries(@PathVariable("series_key") String seriesKey) {
        return deps.unit(work -> series(work, seriesKey));
    }

    /** The same thing, addressed the way a person would say it. */
    @GetMapping("/market")
    @Operation(summary = "Read Market", description = "The same thing, addressed the way a person would say it.")
    public SeriesOut readMarket(
            @RequestParam @Length(min = 3, max = 3) String origin,
            @RequestParam @Length(min = 3, max = 3) String destination,
            @RequestParam LocalDate departure,
            @RequestParam(defaultValue = "Y") @Length(min = 1, max = 1) String cabin) {
        String key = Mappers.seriesKeyOf(upper(origin), upper(destination), departure, upper(cabin));
        return deps.unit(work -> series(work, key));
    }

    private static SeriesOut series(UnitOfWork work, String seriesKey) {
        Series.read(seriesKey);
        List<Offer> offers = work.observations().series(seriesKey);
        if (offers.isEmpty()) {
            throw new NotFoundError("no observations in that series", Details.of("series_key", seriesKey));
        }
        Offer first = offers.get(0);
        return new SeriesOut(
                seriesKey,
                first.origin(),
                first.destination(),
                first.itinerary().departure().toLocalDate(),
                first.cabin().value(),
                offers.stream().map(ObservationRoutes::offerOut).toList(),
                Optional.of(MoneyOut.of(first.total())));
    }

    private static TaxOut taxOut(AppliedTax tax) {
        return new TaxOut(tax.code(), MoneyOut.of(tax.amount()), tax.carrierImposed());
    }

    private static SectorOut sectorOut(Segment sector) {
        return new SectorOut(
                sector.marketingCarrier(),
                sector.flightNumber(),
                sector.origin(),
                sector.destination(),
                sector.departure(),
                sector.arrival(),
                sector.bookingClass(),
                sector.cabin().value());
    }

    private static String upper(String text) {
        return text.toUpperCase(Locale.ROOT);
    }
}
