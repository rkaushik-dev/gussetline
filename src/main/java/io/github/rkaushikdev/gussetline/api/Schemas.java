package io.github.rkaushikdev.gussetline.api;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The shapes that cross the wire, which are not the domain objects.
 *
 * <p>Two reasons for the copy, and both have been paid for.
 *
 * <p>An amount is a string on the wire and a decimal in the domain. JSON has one number type and it is
 * a binary float, so a fare sent as a number arrives as a float somewhere, and the somewhere is always
 * a client the desk does not control.
 *
 * <p>And the wire shape is a promise. The domain gets refactored, fields move between objects, and a
 * schema that was the domain object would break every client each time. This layer is the thing that
 * does not change, and when it has to, it changes on purpose.
 *
 * <p>Every shape is written with snake case field names ({@link Wire}). The shapes a route reads,
 * {@link PriceRequest}, {@link SectorIn} and {@link DecisionIn}, fill in their defaults when a field is
 * left out; a required field left out stays empty until the route's validation refuses the request,
 * so a route never sees one.
 */
public final class Schemas {

    private Schemas() {}

    /**
     * An amount and a currency. The amount is a string, always.
     *
     * @param amount the decimal amount as written, such as {@code 840.00}
     * @param currency the ISO code
     */
    public record MoneyOut(String amount, String currency) {

        public MoneyOut {
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(currency, "currency");
        }

        /** An amount as it crosses the wire. */
        public static MoneyOut of(Money money) {
            return new MoneyOut(money.amount().toString(), money.currency());
        }

        /** The amount back as money. */
        public Money toMoney() {
            return new Money(Numbers.parseDecimal(amount), currency);
        }
    }

    /** One flown sector of an offer. */
    public record SectorOut(
            String carrier,
            String flightNumber,
            String origin,
            String destination,
            LocalDateTime departure,
            LocalDateTime arrival,
            String bookingClass,
            String cabin) {}

    /** One tax on an offer, and whether the carrier imposed it. */
    public record TaxOut(String code, MoneyOut amount, boolean carrierImposed) {

        /** A government tax. */
        public TaxOut(String code, MoneyOut amount) {
            this(code, amount, false);
        }
    }

    /**
     * One observed offer, as a screen wants to draw it.
     *
     * @param quotedTotal the total the supplier stated, when it stated one
     * @param seats the seats the supplier said were left, when it said
     * @param sightings how many times the offer was seen
     */
    public record OfferOut(
            String fingerprint,
            String source,
            String origin,
            String destination,
            LocalDate departure,
            String cabin,
            List<String> fareBases,
            MoneyOut base,
            List<TaxOut> taxes,
            MoneyOut surcharges,
            MoneyOut total,
            Optional<MoneyOut> quotedTotal,
            boolean addsUp,
            Optional<Integer> seats,
            List<SectorOut> sectors,
            LocalDateTime firstSeen,
            LocalDateTime lastSeen,
            int sightings) {

        public OfferOut {
            fareBases = List.copyOf(fareBases);
            taxes = List.copyOf(taxes);
            sectors = List.copyOf(sectors);
        }
    }

    /**
     * A price series, cheapest first, with the cheapest beside it.
     *
     * @param cheapest the cheapest total in the series, when it holds an offer
     */
    public record SeriesOut(
            String seriesKey,
            String origin,
            String destination,
            LocalDate departure,
            String cabin,
            List<OfferOut> offers,
            Optional<MoneyOut> cheapest) {

        public SeriesOut {
            offers = List.copyOf(offers);
        }
    }

    /**
     * What to price. Sectors in, a construction out.
     *
     * @param ticketing the day the fare is priced on
     * @param sellingCurrency the currency to sell in, {@code EUR} unless said; empty means the desk's own
     * @param breaks where the fare components split, as sector indices, when the caller knows
     * @param sectors the sectors, in order
     */
    public record PriceRequest(
            @NotNull(message = Problems.FIELD_REQUIRED) LocalDate ticketing,
            String sellingCurrency,
            Optional<List<Integer>> breaks,
            @NotNull(message = Problems.FIELD_REQUIRED) List<@Valid @NotNull SectorIn> sectors) {

        public PriceRequest {
            sellingCurrency = Objects.requireNonNullElse(sellingCurrency, "EUR");
            breaks = Objects.requireNonNullElse(breaks, Optional.<List<Integer>>empty())
                    .map(List::copyOf);
            // Kept as sent, empty places and all, so that validation can say which place was empty.
            sectors = sectors == null ? null : Collections.unmodifiableList(new ArrayList<>(sectors));
        }
    }

    /**
     * One sector to price.
     *
     * @param bookingClass the booking class, {@code Y} unless said
     * @param cabin the cabin letter, {@code Y} unless said; a letter that is no cabin prices as economy
     * @param operatingCarrier who flies it, when it is not the carrier selling it
     */
    public record SectorIn(
            @NotNull(message = Problems.FIELD_REQUIRED) String carrier,
            @NotNull(message = Problems.FIELD_REQUIRED) String flightNumber,
            @NotNull(message = Problems.FIELD_REQUIRED) String origin,
            @NotNull(message = Problems.FIELD_REQUIRED) String destination,
            @NotNull(message = Problems.FIELD_REQUIRED) LocalDateTime departure,
            @NotNull(message = Problems.FIELD_REQUIRED) LocalDateTime arrival,
            String bookingClass,
            String cabin,
            @JsonDeserialize(contentUsing = Wire.NullableText.class) Optional<String> operatingCarrier) {

        public SectorIn {
            bookingClass = Objects.requireNonNullElse(bookingClass, "Y");
            cabin = Objects.requireNonNullElse(cabin, "Y");
            operatingCarrier = Objects.requireNonNullElse(operatingCarrier, Optional.empty());
        }
    }

    /** One fare component of a price, with what was added to it. */
    public record ComponentOut(
            String boardPoint, String offPoint, String fareBasis, String nuc, List<ChargeOut> surcharges) {

        public ComponentOut {
            surcharges = List.copyOf(surcharges);
        }
    }

    /** One amount added to a component, and why. */
    public record ChargeOut(String reason, String nuc) {}

    /** A finished price and the trail that built it. */
    public record PriceOut(
            MoneyOut baseFare,
            String totalNuc,
            String fareCalculation,
            boolean issuable,
            List<String> refusals,
            List<ComponentOut> components,
            List<TrailStepOut> trail) {

        public PriceOut {
            refusals = List.copyOf(refusals);
            components = List.copyOf(components);
            trail = List.copyOf(trail);
        }
    }

    /** One step of the trail: which stage, what it decided, and why. */
    public record TrailStepOut(String stage, String verdict, String message) {}

    /**
     * One watch on a ticket's market.
     *
     * @param lastChecked when the market was last looked at, if it has been
     */
    public record WatchOut(
            String seriesKey,
            String ticketNumber,
            String origin,
            String destination,
            LocalDate departure,
            String cabin,
            MoneyOut threshold,
            boolean active,
            String retiredReason,
            int checks,
            Optional<LocalDateTime> lastChecked) {}

    /**
     * One recommendation, with every number behind it.
     *
     * @param decidedOn the day it was settled, if it has been
     */
    public record RecommendationOut(
            String ticketNumber,
            String seriesKey,
            LocalDate raisedOn,
            String state,
            MoneyOut paid,
            MoneyOut newFare,
            MoneyOut penalty,
            MoneyOut benefit,
            String residual,
            Optional<LocalDate> decidedOn,
            String decidedBy) {}

    /**
     * Taking or killing a recommendation. Both need a name against them.
     *
     * @param by who decided, one to thirty-two characters
     * @param note what they said, up to two hundred and fifty-six characters, empty unless given
     */
    public record DecisionIn(
            @NotNull(message = Problems.FIELD_REQUIRED) @Length(min = 1, max = 32) String by,
            @Length(max = 256) String note) {

        public DecisionIn {
            note = Objects.requireNonNullElse(note, "");
        }

        /** A decision with nothing said. */
        public DecisionIn(String by) {
            this(by, "");
        }
    }

    /**
     * One supplier on the supplier screen.
     *
     * @param breaker the state of the supplier's circuit breaker in this process
     * @param quotaUsed the calls spent today in this process
     * @param quotaLimit the calls the desk allows itself a day
     */
    public record SupplierOut(
            String name,
            String host,
            List<String> capabilities,
            String costPerCall,
            String breaker,
            int quotaUsed,
            int quotaLimit) {

        public SupplierOut {
            capabilities = List.copyOf(capabilities);
        }

        /** A supplier whose breaker is closed and that has spent nothing yet. */
        public SupplierOut(String name, String host, List<String> capabilities, String costPerCall, int quotaLimit) {
            this(name, host, capabilities, costPerCall, "closed", 0, quotaLimit);
        }
    }

    /**
     * A page of anything, with the cursor to ask for the next one.
     *
     * @param nextCursor where the next page starts, when there is one
     * @param total how many there are in all, when that is known
     */
    public record PageOut(List<Map<String, Object>> items, Optional<String> nextCursor, Optional<Integer> total) {

        public PageOut {
            items = List.copyOf(items);
        }
    }

    /**
     * One recommendation as the worklist screen shows it.
     *
     * @param score what it is worth chasing right now. A string, like every other amount here: the
     *     score is a decimal and a float would round it differently in every client that read it
     */
    public record PlaceOut(
            String ticketNumber,
            String seriesKey,
            LocalDate raisedOn,
            LocalDate expiresOn,
            MoneyOut benefit,
            String score,
            int daysLeft,
            int daysOut,
            boolean urgent,
            boolean fareMoved,
            String why) {}

    /**
     * The whole worklist, with the three numbers the screen shows at the top.
     *
     * @param asOf the day the queue was read as of
     * @param open how many recommendations are open
     * @param urgent how many of them go today
     * @param worth what the whole queue is worth if every one is taken
     * @param places the places, in the order they should be worked
     */
    public record QueueOut(LocalDate asOf, int open, int urgent, MoneyOut worth, List<PlaceOut> places) {

        public QueueOut {
            places = List.copyOf(places);
        }
    }
}
