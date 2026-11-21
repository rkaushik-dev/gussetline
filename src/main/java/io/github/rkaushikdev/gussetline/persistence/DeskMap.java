package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.desk.Coupon;
import io.github.rkaushikdev.gussetline.desk.CouponStatus;
import io.github.rkaushikdev.gussetline.desk.Observation;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.Retired;
import io.github.rkaushikdev.gussetline.desk.State;
import io.github.rkaushikdev.gussetline.desk.Ticket;
import io.github.rkaushikdev.gussetline.desk.Watch;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Mapping the desk's own objects, which are the ones people have edited.
 *
 * <p>Apart from the tariff mappers because the direction of trust is different. A fare row is written
 * once by a loader and read a thousand times; a ticket, a watch and a recommendation are written by
 * a loader, edited by an agent through the API, edited again by the night run, and read by a report
 * that has to give the same answer next year. Everything here has to survive a round trip through
 * somebody else's hands.
 *
 * <p>Three things about the shapes that are not obvious from the columns.
 *
 * <p>The ticket row carries the market and the departure even though the coupons already say it.
 * That is denormalised for one query, the one that finds the tickets in a market, and it is derived
 * on the way in rather than taken from a caller, because a row where the market disagrees with its
 * own coupons is unfindable afterwards.
 *
 * <p>The watch row has no history on it. Observations live in their own table and a watch reads them
 * back by series key, so a watch built from a row starts with an empty history and the caller fills
 * it. That is deliberate: the night run wants the last few readings and the coverage report wants all
 * of them, and a mapper that guessed which would be wrong for one of them.
 *
 * <p>And the recommendation row carries no link to the observation it came from, only its
 * fingerprint. The observation is a reading of a market at a moment and it rolls off; the
 * recommendation is what the desk told a customer and it does not. A foreign key would take the
 * second down with the first.
 */
public final class DeskMap {

    /** What separates the fare bases stored on a recommendation. */
    public static final String BASES = "/";

    /**
     * What marks the allowance in a recommendation's note. The allowance rides in the note column
     * rather than in one of its own: it is a short string the desk reads and never groups by, and a
     * column would be a migration for something that has never been queried.
     */
    public static final String BAGGAGE_MARK = "baggage=";

    private static final int FARE_CALCULATION_LENGTH = 512;
    private static final int FARE_BASES_LENGTH = 128;
    private static final int DECIDED_BY_LENGTH = 32;
    private static final int NOTE_LENGTH = 256;

    private DeskMap() {}

    /** One coupon as its row, marked flown when it has been used. */
    public static CouponRow couponToRow(Coupon coupon) {
        CouponRow row = new CouponRow();
        row.setPosition(coupon.position());
        row.setCarrier(coupon.carrier());
        row.setFlightNumber(coupon.flightNumber());
        row.setOrigin(coupon.origin());
        row.setDestination(coupon.destination());
        row.setDeparture(coupon.departure());
        row.setBookingClass(coupon.bookingClass());
        row.setFareBasis(coupon.fareBasis());
        row.setStatus(coupon.status().value());
        row.setFlown(coupon.status() == CouponStatus.USED);
        return row;
    }

    /** The coupon back out, in economy. */
    public static Coupon rowToCoupon(CouponRow row) {
        return new Coupon(
                row.getPosition(),
                row.getCarrier(),
                row.getFlightNumber(),
                row.getOrigin(),
                row.getDestination(),
                row.getDeparture(),
                row.getBookingClass(),
                row.getFareBasis(),
                CouponStatus.of(row.getStatus()));
    }

    /**
     * A ticket and its coupons as one row and its children, the fare calculation cut to what the
     * column holds rather than refused.
     *
     * <p>The market is taken off the coupons rather than from anywhere else. A row whose origin
     * disagrees with its own first coupon cannot be found by either of the two queries that look for
     * it.
     */
    public static TicketRow ticketToRow(Ticket ticket) {
        TicketRow row = new TicketRow();
        row.setNumber(ticket.number());
        row.setRecordLocator(ticket.recordLocator());
        row.setPassengerName(ticket.passengerName());
        row.setOrigin(ticket.origin());
        row.setDestination(ticket.destination());
        row.setDeparture(ticket.departure());
        row.setIssuedOn(ticket.issuedOn());
        row.setValidatingCarrier(ticket.validatingCarrier());
        row.setFareCalculation(Text.slice(ticket.fareCalculation(), 0, FARE_CALCULATION_LENGTH));
        row.setBaseAmount(ticket.baseFare().amount());
        row.setBaseCurrency(ticket.baseFare().currency());
        row.setTotalAmount(ticket.totalPaid().amount());
        row.setCountryOfSale(ticket.countryOfSale());
        row.setReissuedFrom(ticket.reissuedFrom());
        row.setCoupons(ticket.coupons().stream().map(DeskMap::couponToRow).toList());
        return row;
    }

    /**
     * The ticket back out, with no notes.
     *
     * <p>Both money fields read the base currency, because the row keeps only one: a ticket priced in
     * one currency and paid in another is a thing, and it is not a thing this desk sells.
     */
    public static Ticket rowToTicket(TicketRow row) {
        return new Ticket(
                row.getNumber(),
                row.getRecordLocator(),
                row.getPassengerName(),
                row.getCoupons().stream().map(DeskMap::rowToCoupon).toList(),
                row.getIssuedOn(),
                row.getValidatingCarrier(),
                new Money(row.getBaseAmount(), row.getBaseCurrency()),
                new Money(row.getTotalAmount(), row.getBaseCurrency()),
                row.getFareCalculation(),
                row.getCountryOfSale(),
                row.getReissuedFrom(),
                List.of());
    }

    /** A watch as its row, not yet on any ticket; adding it to a ticket row sets that. */
    public static WatchRow watchToRow(Watch watch) {
        return watchToRow(watch, Optional.empty());
    }

    /** A watch as its row, on the ticket with this id. */
    public static WatchRow watchToRow(Watch watch, int ticketId) {
        return watchToRow(watch, Optional.of(ticketId));
    }

    /** A watch as its row, on the ticket with this id when one is given. */
    public static WatchRow watchToRow(Watch watch, Optional<Integer> ticketId) {
        WatchRow row = new WatchRow();
        row.setSeriesKey(watch.seriesKey());
        row.setOrigin(watch.origin());
        row.setDestination(watch.destination());
        row.setDeparture(watch.departure());
        row.setCabin(watch.cabin().value());
        row.setThresholdAmount(watch.threshold().amount());
        row.setThresholdCurrency(watch.threshold().currency());
        row.setActive(watch.isActive());
        row.setRetiredReason(watch.retiredReason().map(Retired::value).orElse(""));
        row.setLastChecked(watch.lastChecked().orElse(null));
        row.setChecks(watch.checks());
        ticketId.ifPresent(row::setTicketId);
        return row;
    }

    /** {@link #rowToWatch(WatchRow, List)} with no history. */
    public static Watch rowToWatch(WatchRow row) {
        return rowToWatch(row, List.of());
    }

    /**
     * A watch, with whatever history the caller decided it wanted, read off a row whose ticket is
     * loaded.
     *
     * <p>The history is a parameter and not a lookup. The night run wants the last few readings and
     * the coverage report wants all of them, and a mapper that picked one would be wrong for the other
     * every time.
     *
     * <p>A retirement reason nobody can read, written by an older version or typed at a console, reads
     * as {@link Retired#BY_HAND}: the watch stays retired, because a reason that cannot be read is not
     * a reason to start spending shopping requests on it again.
     */
    public static Watch rowToWatch(WatchRow row, List<Observation> history) {
        return Watch.builder()
                .ticket(rowToTicket(row.getTicket()))
                .origin(row.getOrigin())
                .destination(row.getDestination())
                .departure(row.getDeparture())
                .threshold(new Money(row.getThresholdAmount(), row.getThresholdCurrency()))
                .cabin(Cabin.of(row.getCabin()))
                .active(row.isActive())
                .retiredReason(retired(row.getRetiredReason()))
                .checks(row.getChecks())
                .lastChecked(row.getLastChecked())
                .history(history)
                .build();
    }

    /**
     * One stored offer as the reading a watch keeps, on the day it was last seen.
     *
     * <p>The total and not the base fare, because what a watch compares is what the customer would
     * pay, and a drop in the fare that a rise in tax swallows is not a drop the desk can quote.
     */
    public static Observation observationRowToReading(ObservationRow row) {
        return new Observation(
                row.getLastSeen().toLocalDate(),
                new Money(row.getTotalAmount(), row.getBaseCurrency()),
                row.getFingerprint(),
                row.getSource(),
                new Baggage(row.getBagPieces(), Optional.empty()));
    }

    /** A recommendation as its row, not yet under any watch; adding it to a watch row sets that. */
    public static RecommendationRow recommendationToRow(Recommendation made) {
        return recommendationToRow(made, Optional.empty());
    }

    /** A recommendation as its row, under the watch with this id. */
    public static RecommendationRow recommendationToRow(Recommendation made, int watchId) {
        return recommendationToRow(made, Optional.of(watchId));
    }

    /**
     * A recommendation as its row, under the watch with this id when one is given. The fare bases, who
     * decided and the note are cut to what their columns hold, and the allowance rides at the end of
     * the note.
     */
    public static RecommendationRow recommendationToRow(Recommendation made, Optional<Integer> watchId) {
        RecommendationRow row = new RecommendationRow();
        row.setRaisedOn(made.raisedOn());
        row.setState(made.state().value());
        row.setPaidAmount(made.paid().amount());
        row.setNewFareAmount(made.newFare().amount());
        row.setPenaltyAmount(made.penalty().amount());
        row.setBenefitAmount(made.benefit().amount());
        row.setCurrency(made.currency());
        row.setResidual(made.residual().value());
        row.setFareBases(Text.slice(String.join(BASES, made.fareBases()), 0, FARE_BASES_LENGTH));
        row.setObservationFingerprint(made.fingerprint());
        row.setDecidedOn(made.decidedOn().orElse(null));
        row.setDecidedBy(Text.slice(made.decidedBy(), 0, DECIDED_BY_LENGTH));
        row.setNote(Text.slice(withBaggage(made.note(), made.baggage()), 0, NOTE_LENGTH));
        watchId.ifPresent(row::setWatchId);
        return row;
    }

    /**
     * The recommendation back out, the allowance taken back off the note.
     *
     * <p>The ticket number and the series key come from the caller because the row reaches them only
     * through its watch, and loading that relationship for every row turns a savings report over a
     * year into forty thousand queries.
     */
    public static Recommendation rowToRecommendation(RecommendationRow row, String ticketNumber, String seriesKey) {
        String currency = row.getCurrency();
        return Recommendation.builder()
                .ticketNumber(ticketNumber)
                .seriesKey(seriesKey)
                .raisedOn(row.getRaisedOn())
                .paid(new Money(row.getPaidAmount(), currency))
                .newFare(new Money(row.getNewFareAmount(), currency))
                .penalty(new Money(row.getPenaltyAmount(), currency))
                .benefit(new Money(row.getBenefitAmount(), currency))
                .residual(Residual.of(row.getResidual()))
                .fareBases(Arrays.stream(row.getFareBases().split(BASES, -1))
                        .filter(part -> !part.isEmpty())
                        .toList())
                .fingerprint(row.getObservationFingerprint())
                .state(State.of(row.getState()))
                .decidedOn(row.getDecidedOn())
                .decidedBy(row.getDecidedBy())
                .note(withoutBaggage(row.getNote()))
                .baggage(baggageIn(row.getNote()))
                .build();
    }

    /**
     * Move a stored recommendation to the state the domain object is in: its state, when and by whom
     * it was decided, and the note, cut to what their columns hold.
     *
     * <p>Only the fields that can move. Everything else on a recommendation is a copy of what the desk
     * said and must read the same in a year, so writing it again here would be the one way it could
     * change.
     */
    public static RecommendationRow applyRecommendation(RecommendationRow row, Recommendation made) {
        row.setState(made.state().value());
        row.setDecidedOn(made.decidedOn().orElse(null));
        row.setDecidedBy(Text.slice(made.decidedBy(), 0, DECIDED_BY_LENGTH));
        row.setNote(Text.slice(made.note(), 0, NOTE_LENGTH));
        return row;
    }

    /** What a watch row's series key should be, worked out from its own fields. */
    public static String seriesKeyForRow(WatchRow row) {
        return Series.key(row.getOrigin(), row.getDestination(), row.getDeparture(), row.getCabin());
    }

    /**
     * Refuse a row whose key disagrees with the fields it was built from.
     *
     * <p>Nothing edits the key on purpose, but the API takes one from a caller and a key that does not
     * match its own market makes the watch invisible to every observation the collectors store for it.
     *
     * @throws ValidationError naming the {@code stored} key and the {@code wanted} one when they differ
     */
    public static void checkSeriesKey(WatchRow row) {
        String wanted = seriesKeyForRow(row);
        if (!row.getSeriesKey().equals(wanted)) {
            throw new ValidationError(
                    "the series key does not match the watch",
                    Details.of("stored", row.getSeriesKey(), "wanted", wanted));
        }
    }

    /** The day a watch was last checked, or nothing when it never has been. */
    public static Optional<LocalDate> latestCheck(WatchRow row) {
        return row.getLastChecked().map(checked -> checked.toLocalDate());
    }

    private static Optional<Retired> retired(String raw) {
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Retired.of(raw));
        } catch (IllegalArgumentException unreadable) {
            return Optional.of(Retired.BY_HAND);
        }
    }

    private static String withBaggage(String note, String baggage) {
        if (baggage.isEmpty()) {
            return note;
        }
        return Text.strip(note + " " + BAGGAGE_MARK + baggage);
    }

    private static String withoutBaggage(String note) {
        int mark = note.indexOf(BAGGAGE_MARK);
        return Text.strip(mark < 0 ? note : note.substring(0, mark));
    }

    /** The first word after the mark, or nothing when there is no mark or no word after it. */
    private static String baggageIn(String note) {
        int mark = note.indexOf(BAGGAGE_MARK);
        if (mark < 0) {
            return "";
        }
        List<String> words = Text.words(note.substring(mark + BAGGAGE_MARK.length()));
        return words.isEmpty() ? "" : words.get(0);
    }
}
