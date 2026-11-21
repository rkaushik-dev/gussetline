package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Market;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One issued ticket and everything a reprice needs to know about it.
 *
 * <p>The ticketed itinerary is not the same thing as an itinerary. An itinerary is a plan. A ticket
 * is a plan that has been paid for, and the difference is the coupons: each one has a status, and a
 * coupon that has been flown cannot be changed, cannot be refunded, and takes the whole ticket out of
 * the before departure penalty band.
 *
 * <p>So the question every reprice asks first is which coupons are still open, and the answer is a
 * reading of the coupon statuses against today rather than a property of the ticket. A ticket with an
 * open outbound is worth watching. A ticket with a flown outbound and an open return is worth watching
 * for less, because only half of it can be repriced. A ticket with everything flown is worth nothing
 * and its watch should be closed.
 *
 * @param number the ticket number
 * @param recordLocator the booking the ticket was issued against
 * @param passengerName the passenger, as the ticket names them
 * @param coupons the flight coupons, at least one, in position order
 * @param issuedOn the day the ticket was issued
 * @param validatingCarrier the carrier the ticket is issued on
 * @param baseFare the fare paid, before taxes
 * @param totalPaid everything paid, taxes included
 * @param fareCalculation the fare calculation line; empty when not kept
 * @param countryOfSale where the ticket was sold; empty when not known
 * @param reissuedFrom the ticket this one was reissued from; empty for a first issue
 * @param notes remarks made about the ticket
 */
public record Ticket(
        String number,
        String recordLocator,
        String passengerName,
        List<Coupon> coupons,
        LocalDate issuedOn,
        String validatingCarrier,
        Money baseFare,
        Money totalPaid,
        String fareCalculation,
        String countryOfSale,
        String reissuedFrom,
        List<String> notes) {

    /**
     * Checks the coupons.
     *
     * @throws ValidationError naming the {@code number} when there is no coupon, the coupons are not in
     *     position order, or two share a position
     */
    public Ticket {
        Objects.requireNonNull(number, "number");
        Objects.requireNonNull(recordLocator, "recordLocator");
        Objects.requireNonNull(passengerName, "passengerName");
        Objects.requireNonNull(issuedOn, "issuedOn");
        Objects.requireNonNull(validatingCarrier, "validatingCarrier");
        Objects.requireNonNull(baseFare, "baseFare");
        Objects.requireNonNull(totalPaid, "totalPaid");
        Objects.requireNonNull(fareCalculation, "fareCalculation");
        Objects.requireNonNull(countryOfSale, "countryOfSale");
        Objects.requireNonNull(reissuedFrom, "reissuedFrom");
        coupons = List.copyOf(coupons);
        notes = List.copyOf(notes);
        if (coupons.isEmpty()) {
            throw new ValidationError("a ticket has at least one coupon", Details.of("number", number));
        }
        for (int index = 1; index < coupons.size(); index++) {
            if (coupons.get(index).position() < coupons.get(index - 1).position()) {
                throw new ValidationError("coupons are in order", Details.of("number", number));
            }
        }
        Set<Integer> positions = new HashSet<>();
        for (Coupon coupon : coupons) {
            if (!positions.add(coupon.position())) {
                throw new ValidationError("two coupons at the same position", Details.of("number", number));
            }
        }
    }

    /** A first issue with no fare calculation, country of sale or notes kept. */
    public Ticket(
            String number,
            String recordLocator,
            String passengerName,
            List<Coupon> coupons,
            LocalDate issuedOn,
            String validatingCarrier,
            Money baseFare,
            Money totalPaid) {
        this(
                number,
                recordLocator,
                passengerName,
                coupons,
                issuedOn,
                validatingCarrier,
                baseFare,
                totalPaid,
                "",
                "",
                "",
                List.of());
    }

    /** A copy reissued from another ticket. */
    public Ticket withReissuedFrom(String previous) {
        return new Ticket(
                number,
                recordLocator,
                passengerName,
                coupons,
                issuedOn,
                validatingCarrier,
                baseFare,
                totalPaid,
                fareCalculation,
                countryOfSale,
                previous,
                notes);
    }

    /** Where the first coupon leaves from. */
    public String origin() {
        return coupons.get(0).origin();
    }

    /** Where the last coupon goes to. */
    public String destination() {
        return coupons.get(coupons.size() - 1).destination();
    }

    /**
     * The cities the two ends price as.
     *
     * @throws ValidationError when an end is neither a city nor an airport
     */
    public Market market() {
        return new Market(Geography.cityOf(origin()), Geography.cityOf(destination()));
    }

    /** The day the first coupon leaves. */
    public LocalDate departure() {
        return coupons.get(0).departure().toLocalDate();
    }

    /** The cabin of the first coupon. */
    public Cabin cabin() {
        return coupons.get(0).cabin();
    }

    /** The coupons not yet flown by a day, in order. */
    public List<Coupon> openCoupons(LocalDate today) {
        return coupons.stream().filter(coupon -> !coupon.flownBy(today)).toList();
    }

    /** The coupons flown by a day, in order. */
    public List<Coupon> flownCoupons(LocalDate today) {
        return coupons.stream().filter(coupon -> coupon.flownBy(today)).toList();
    }

    /** Nothing flown yet, so the whole ticket can still be repriced. */
    public boolean isUntouched(LocalDate today) {
        return openCoupons(today).size() == coupons.size();
    }

    /** Some coupons flown and some not. */
    public boolean isPartiallyFlown(LocalDate today) {
        int flown = flownCoupons(today).size();
        return 0 < flown && flown < coupons.size();
    }

    /** Every coupon flown, so nothing is left to reprice. */
    public boolean isFinished(LocalDate today) {
        return openCoupons(today).isEmpty();
    }

    /**
     * How much of what was paid is still on the table: the base fare's share of open coupons, to the
     * cent, half to even. Nothing once the ticket is finished.
     *
     * <p>Apportioned by open coupons, which is rough and is what the carriers do. A ticket half flown is
     * worth half its fare on a reissue, and arguing for a better apportionment costs more than it
     * recovers.
     */
    public Money repriceableValue(LocalDate today) {
        if (isFinished(today)) {
            return Money.zero(baseFare.currency());
        }
        BigDecimal share =
                Numbers.divide(BigDecimal.valueOf(openCoupons(today).size()), BigDecimal.valueOf(coupons.size()));
        return new Money(
                baseFare.amount().multiply(share, Numbers.CONTEXT).setScale(2, RoundingMode.HALF_EVEN),
                baseFare.currency());
    }

    /** When the passenger next flies, which is what a penalty band reads. Empty once nothing is open. */
    public Optional<LocalDate> nextDeparture(LocalDate today) {
        List<Coupon> remaining = openCoupons(today);
        if (remaining.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(remaining.get(0).departure().toLocalDate());
    }

    /** The fare bases on the coupons, each once, in the order first met. */
    public List<String> fareBases() {
        return coupons.stream().map(Coupon::fareBasis).distinct().toList();
    }

    /** {@code 220-1234567890 FRAFRA 1090.83 EUR}. */
    @Override
    public String toString() {
        return number + " " + origin() + destination() + " " + totalPaid;
    }
}
