package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.carriers.Alliance;
import io.github.rkaushikdev.gussetline.carriers.Carrier;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Airport;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.models.AirportRow;
import io.github.rkaushikdev.gussetline.persistence.models.CarrierRow;
import io.github.rkaushikdev.gussetline.persistence.models.CombinabilityRow;
import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RunResultRow;
import io.github.rkaushikdev.gussetline.persistence.models.RunRow;
import io.github.rkaushikdev.gussetline.persistence.models.SectorRow;
import io.github.rkaushikdev.gussetline.persistence.models.TaxRow;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Turning domain objects into rows and back, in one place.
 *
 * <p>The domain does not know the database exists. That is worth the cost of this class, because it
 * is the reason the fare construction can be tested with three lines of setup instead of a schema,
 * and it is the reason a fare read out of a fixed width payload and one read out of the fares table
 * are the same object by the time anything prices them.
 *
 * <p>Both directions are here together on purpose. A mapper split across two files drifts:
 * somebody adds a column, fills it on the way in, and forgets the way out, and the field comes back
 * empty for a year without anybody noticing, because nothing reads it until the day something does.
 *
 * <p>A row made here is not in any session. A child row made on its own (a sector, a tax, a result)
 * is attached by adding it to its parent; the rows for a whole offer or a whole run come with their
 * children already attached.
 */
public final class Mappers {

    /** How much of a reason a result row keeps. */
    private static final int REASON_LENGTH = 128;

    private Mappers() {}

    public static AirportRow airportToRow(Airport airport) {
        AirportRow row = new AirportRow();
        row.setCode(airport.code());
        row.setName(airport.name());
        row.setCity(airport.city());
        row.setCountry(airport.country());
        row.setLatitude(airport.latitude());
        row.setLongitude(airport.longitude());
        row.setUtcOffsetMinutes(airport.utcOffsetMinutes());
        return row;
    }

    public static Airport rowToAirport(AirportRow row) {
        return new Airport(
                row.getCode(),
                row.getName(),
                row.getCity(),
                row.getCountry(),
                row.getLatitude(),
                row.getLongitude(),
                row.getUtcOffsetMinutes());
    }

    public static CarrierRow carrierToRow(Carrier carrier) {
        CarrierRow row = new CarrierRow();
        row.setCode(carrier.code());
        row.setName(carrier.name());
        row.setCountry(carrier.country());
        row.setAlliance(carrier.alliance().value());
        row.setLowCost(carrier.lowCost());
        return row;
    }

    public static Carrier rowToCarrier(CarrierRow row) {
        return new Carrier(
                row.getCode(), row.getName(), row.getCountry(), Alliance.of(row.getAlliance()), row.isLowCost());
    }

    /**
     * A fare as the row that files it under a load. Filing the row through {@link
     * io.github.rkaushikdev.gussetline.persistence.models.LoadRow#addFare} sets the load from the
     * load itself, which is how a load not yet written gets its fares.
     */
    public static FareRow fareToRow(Fare fare, int loadId) {
        FareRow row = new FareRow();
        row.setLoadId(loadId);
        row.setCarrier(fare.carrier());
        row.setOrigin(fare.origin());
        row.setDestination(fare.destination());
        row.setFareBasis(fare.fareBasis());
        row.setAmount(fare.amount().amount());
        row.setCurrency(fare.amount().currency());
        row.setOwrt(fare.owrt().value());
        row.setGlobalIndicator(fare.globalIndicator().value());
        row.setRuleNumber(fare.ruleNumber());
        row.setRoutingNumber(fare.routingNumber());
        row.setTariffNumber(fare.tariffNumber());
        row.setFootnote(fare.footnote());
        row.setCabin(fare.cabin().value());
        row.setFareType(fare.fareType().value());
        row.setEffective(fare.effective());
        row.setDiscontinue(fare.discontinue());
        row.setFirstTravel(fare.firstTravel());
        row.setLastTravel(fare.lastTravel());
        return row;
    }

    public static Fare rowToFare(FareRow row) {
        return new Fare(
                row.getCarrier(),
                row.getOrigin(),
                row.getDestination(),
                row.getFareBasis(),
                new Money(row.getAmount(), row.getCurrency()),
                OwRt.of(row.getOwrt()),
                GlobalIndicator.of(row.getGlobalIndicator()),
                row.getRuleNumber(),
                row.getRoutingNumber(),
                row.getTariffNumber(),
                row.getFootnote(),
                Cabin.of(row.getCabin()),
                FareType.of(row.getFareType()),
                row.getEffective(),
                row.getDiscontinue(),
                row.getFirstTravel(),
                row.getLastTravel());
    }

    /** A flown sector as the row at its position in the journey, nought first. */
    public static SectorRow segmentToRow(Segment segment, int position) {
        SectorRow row = new SectorRow();
        row.setPosition(position);
        row.setMarketingCarrier(segment.marketingCarrier());
        row.setOperatingCarrier(segment.operatingCarrier().orElse(""));
        row.setFlightNumber(segment.flightNumber());
        row.setOrigin(segment.origin());
        row.setDestination(segment.destination());
        row.setDeparture(segment.departure());
        row.setArrival(segment.arrival());
        row.setDayChange(segment.dayChange());
        row.setBookingClass(segment.bookingClass());
        row.setCabin(segment.cabin().value());
        return row;
    }

    /** The sector back, flown by its marketing carrier when the row names nobody else. */
    public static Segment rowToSegment(SectorRow row) {
        String operating = row.getOperatingCarrier();
        return new Segment(
                row.getMarketingCarrier(),
                row.getFlightNumber(),
                row.getOrigin(),
                row.getDestination(),
                row.getDeparture(),
                row.getArrival(),
                row.getBookingClass(),
                Cabin.of(row.getCabin()),
                operating.isEmpty() ? Optional.empty() : Optional.of(operating),
                Optional.empty(),
                row.getDayChange(),
                0,
                Optional.empty(),
                false);
    }

    public static TaxRow taxToRow(AppliedTax tax) {
        TaxRow row = new TaxRow();
        row.setCode(tax.code());
        row.setAmount(tax.amount().amount());
        row.setCurrency(tax.amount().currency());
        row.setPoint(tax.point());
        row.setCarrierImposed(tax.carrierImposed());
        return row;
    }

    public static AppliedTax rowToTax(TaxRow row) {
        return new AppliedTax(
                row.getCode(), new Money(row.getAmount(), row.getCurrency()), row.getPoint(), row.isCarrierImposed());
    }

    /** One offer as the row that will be written, sectors and taxes included. */
    public static ObservationRow offerToRow(Offer offer) {
        PriceBreakdown breakdown = offer.breakdown();
        ObservationRow row = new ObservationRow();
        row.setSeriesKey(offer.seriesKey().joined());
        row.setFingerprint(offer.fingerprint());
        row.setSource(offer.source());
        row.setOrigin(offer.origin());
        row.setDestination(offer.destination());
        row.setDeparture(offer.itinerary().departure().toLocalDate());
        row.setCabin(offer.cabin().value());
        row.setFareBases(String.join("/", offer.fareBases()));
        row.setBaseAmount(breakdown.base().amount());
        row.setBaseCurrency(breakdown.base().currency());
        row.setTaxAmount(breakdown.taxTotal().amount());
        row.setSurchargeAmount(breakdown.surchargeTotal().amount());
        row.setTotalAmount(offer.total().amount());
        row.setQuotedTotal(breakdown.quotedTotal().map(Money::amount).orElse(null));
        row.setSeats(offer.seats().orElse(null));
        row.setBagPieces(offer.baggage().pieces().orElse(null));
        row.setRefundable(offer.refundable().orElse(null));
        row.setValidatingCarrier(offer.validatingCarrier().orElse(""));
        row.setRawDigest(offer.rawDigest().orElse(""));
        row.setFirstSeen(offer.observedAt());
        row.setLastSeen(offer.observedAt());
        List<Segment> segments = offer.itinerary().segments();
        for (int position = 0; position < segments.size(); position++) {
            row.addSector(segmentToRow(segments.get(position), position));
        }
        for (AppliedTax tax : breakdown.taxes()) {
            row.addTax(taxToRow(tax));
        }
        return row;
    }

    /**
     * The offer back out, priced the way it was written, as last seen.
     *
     * <p>The quoted total is restored even when it disagrees with the parts, because the
     * disagreement is the finding, and dropping it on the way through storage would quietly close
     * every open discrepancy.
     */
    public static Offer rowToOffer(ObservationRow row) {
        Itinerary itinerary = new Itinerary(
                row.getSectors().stream().map(Mappers::rowToSegment).toList());
        PriceBreakdown breakdown = new PriceBreakdown(
                new Money(row.getBaseAmount(), row.getBaseCurrency()),
                row.getTaxes().stream().map(Mappers::rowToTax).toList(),
                row.getQuotedTotal().map(amount -> new Money(amount, row.getBaseCurrency())));
        return Offer.builder()
                .source(row.getSource())
                .itinerary(itinerary)
                .breakdown(breakdown)
                .fareBases(Arrays.stream(row.getFareBases().split("/", -1))
                        .filter(basis -> !basis.isEmpty())
                        .toList())
                .observedAt(row.getLastSeen())
                .cabin(Cabin.of(row.getCabin()))
                .seats(row.getSeats())
                .baggage(new Baggage(row.getBagPieces(), Optional.empty()))
                .refundable(row.getRefundable())
                .validatingCarrier(present(row.getValidatingCarrier()))
                .rawDigest(present(row.getRawDigest()))
                .build();
    }

    /** The key an observation is grouped under. Kept under the name callers use. */
    public static String seriesKeyOf(String origin, String destination, LocalDate departure, String cabin) {
        return Series.key(origin, destination, departure, cabin);
    }

    /** One task's outcome, flattened onto the row that keeps it, its reason cut to what the row holds. */
    public static RunResultRow resultToRow(Result result) {
        RunResultRow row = new RunResultRow();
        row.setSupplier(result.task().supplier());
        row.setMarket(result.task().market().key());
        row.setDeparture(result.task().departure());
        row.setOutcome(result.outcome().value());
        row.setOffers(result.offers());
        row.setAttempts(result.attempts());
        row.setDigest(result.digest());
        row.setReason(Text.slice(result.reason(), 0, REASON_LENGTH));
        row.setFresh(result.fresh());
        row.setProblems(result.problems());
        return row;
    }

    /**
     * Rebuild a result, working out again what was never stored.
     *
     * <p>How far out the departure was is derived from the run's own start rather than kept, because
     * a stored copy can disagree with the departure sitting next to it, and there is no way
     * afterwards to tell which of the two is wrong.
     */
    public static Result rowToResult(RunResultRow row, LocalDate started) {
        String[] ends = row.getMarket().split("-", 2);
        Task task = new Task(
                row.getSupplier(),
                new Market(ends[0], ends[1]),
                row.getDeparture(),
                Math.toIntExact(ChronoUnit.DAYS.between(started, row.getDeparture())));
        return new Result(
                task,
                Outcome.of(row.getOutcome()),
                row.getOffers(),
                row.getDigest(),
                row.isFresh(),
                row.getAttempts(),
                row.getProblems(),
                row.getReason());
    }

    /** A whole run that has not finished, with its results hanging off it. */
    public static RunRow runToRow(RunReport report) {
        return runToRow(report, Optional.empty());
    }

    /** A whole run, finished at a time, with its results hanging off it. */
    public static RunRow runToRow(RunReport report, LocalDateTime finished) {
        return runToRow(report, Optional.of(finished));
    }

    /** A whole run, finished if it says when, with its results hanging off it. */
    public static RunRow runToRow(RunReport report, Optional<LocalDateTime> finished) {
        RunRow row = new RunRow();
        row.setStartedAt(report.started());
        row.setFinishedAt(finished.orElse(null));
        row.setTasks(report.results().size());
        row.setCalls(report.calls());
        row.setOffers(report.offers());
        row.setSkippedBreaker(report.skippedBreaker());
        row.setSkippedBudget(report.skippedBudget());
        row.setResults(report.results().stream().map(Mappers::resultToRow).toList());
        return row;
    }

    /** Rebuild a run report out of the database, results and all. */
    public static RunReport rowToRun(RunRow row) {
        LocalDate started = row.getStartedAt().toLocalDate();
        return new RunReport(
                row.getStartedAt(),
                row.getResults().stream()
                        .map(result -> rowToResult(result, started))
                        .toList(),
                row.getSkippedBreaker(),
                row.getSkippedBudget());
    }

    /** A combinability record not filed under any load. */
    public static CombinabilityRow combinabilityToRow(Combinability record) {
        return combinabilityToRow(record, Optional.empty());
    }

    /** A combinability record filed under a load. */
    public static CombinabilityRow combinabilityToRow(Combinability record, int loadId) {
        return combinabilityToRow(record, Optional.of(loadId));
    }

    /** A combinability record, filed under a load when one is given. The eight flags are named alike. */
    public static CombinabilityRow combinabilityToRow(Combinability record, Optional<Integer> loadId) {
        CombinabilityRow row = new CombinabilityRow();
        row.setLoadId(loadId.orElse(null));
        row.setCarrier(record.carrier());
        row.setFareBasis(record.fareBasis());
        row.setRoundTrip(record.roundTrip());
        row.setCircleTrip(record.circleTrip());
        row.setOpenJaw(record.openJaw());
        row.setDoubleOpenJaw(record.doubleOpenJaw());
        row.setEndOnEnd(record.endOnEnd());
        row.setSameCarrierOnly(record.sameCarrierOnly());
        row.setSameRuleOnly(record.sameRuleOnly());
        row.setSideTrip(record.sideTrip());
        return row;
    }

    public static Combinability rowToCombinability(CombinabilityRow row) {
        return new Combinability(
                row.getCarrier(),
                row.getFareBasis(),
                row.isRoundTrip(),
                row.isCircleTrip(),
                row.isOpenJaw(),
                row.isDoubleOpenJaw(),
                row.isEndOnEnd(),
                row.isSameCarrierOnly(),
                row.isSameRuleOnly(),
                row.isSideTrip());
    }

    /** Text the row keeps empty for nothing, as nothing. */
    private static Optional<String> present(String text) {
        return text.isEmpty() ? Optional.empty() : Optional.of(text);
    }
}
