package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * One flight coupon, which is the unit a reissue actually works on.
 *
 * @param position where the coupon sits on the ticket, from nought
 * @param carrier the marketing carrier
 * @param flightNumber the flight number, without the carrier
 * @param origin the airport the coupon leaves from
 * @param destination the airport the coupon goes to
 * @param departure the local departure time
 * @param bookingClass the booking class sold
 * @param fareBasis the fare basis the coupon was priced on
 * @param status what has happened to the coupon
 * @param cabin the cabin booked
 */
public record Coupon(
        int position,
        String carrier,
        String flightNumber,
        String origin,
        String destination,
        LocalDateTime departure,
        String bookingClass,
        String fareBasis,
        CouponStatus status,
        Cabin cabin) {

    /**
     * Checks the position and the two ends.
     *
     * @throws ValidationError when the position is negative, naming the {@code value}, or the coupon
     *     leaves from where it goes to, naming the {@code point}
     */
    public Coupon {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(flightNumber, "flightNumber");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(bookingClass, "bookingClass");
        Objects.requireNonNull(fareBasis, "fareBasis");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(cabin, "cabin");
        if (position < 0) {
            throw new ValidationError("a coupon position is not negative", Details.of("value", position));
        }
        if (origin.equals(destination)) {
            throw new ValidationError("a coupon goes somewhere", Details.of("point", origin));
        }
    }

    /** An open coupon in economy. */
    public Coupon(
            int position,
            String carrier,
            String flightNumber,
            String origin,
            String destination,
            LocalDateTime departure,
            String bookingClass,
            String fareBasis) {
        this(
                position,
                carrier,
                flightNumber,
                origin,
                destination,
                departure,
                bookingClass,
                fareBasis,
                CouponStatus.OPEN,
                Cabin.ECONOMY);
    }

    /** A coupon in economy, in any status. */
    public Coupon(
            int position,
            String carrier,
            String flightNumber,
            String origin,
            String destination,
            LocalDateTime departure,
            String bookingClass,
            String fareBasis,
            CouponStatus status) {
        this(
                position,
                carrier,
                flightNumber,
                origin,
                destination,
                departure,
                bookingClass,
                fareBasis,
                status,
                Cabin.ECONOMY);
    }

    /** The carrier and the flight number together: {@code LH400}. */
    public String designator() {
        return carrier + flightNumber;
    }

    /**
     * Whether this coupon is behind us, by status or by the calendar: used, or open and departed
     * before today. Any other status has not been flown, however old the coupon is.
     *
     * <p>The status is authoritative when it says used, and the calendar is the fallback, because a
     * carrier that has not sent the used message yet has still flown the passenger and the penalty
     * band has moved.
     */
    public boolean flownBy(LocalDate today) {
        if (status == CouponStatus.USED) {
            return true;
        }
        if (!status.isOpen()) {
            return false;
        }
        return departure.toLocalDate().isBefore(today);
    }

    /** A copy on another flight number. */
    public Coupon withFlightNumber(String number) {
        return new Coupon(
                position, carrier, number, origin, destination, departure, bookingClass, fareBasis, status, cabin);
    }

    /** A copy priced on another fare basis. */
    public Coupon withFareBasis(String basis) {
        return new Coupon(
                position, carrier, flightNumber, origin, destination, departure, bookingClass, basis, status, cabin);
    }

    /** A copy in another status. */
    public Coupon withStatus(CouponStatus changed) {
        return new Coupon(
                position,
                carrier,
                flightNumber,
                origin,
                destination,
                departure,
                bookingClass,
                fareBasis,
                changed,
                cabin);
    }

    /** A copy in another cabin. */
    public Coupon withCabin(Cabin booked) {
        return new Coupon(
                position,
                carrier,
                flightNumber,
                origin,
                destination,
                departure,
                bookingClass,
                fareBasis,
                status,
                booked);
    }

    /** {@code 0 LH400 FRAJFK OK}. */
    @Override
    public String toString() {
        return position + " " + designator() + " " + origin + destination + " " + status.value();
    }
}
