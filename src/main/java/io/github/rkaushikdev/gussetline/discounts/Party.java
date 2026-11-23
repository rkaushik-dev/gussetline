package io.github.rkaushikdev.gussetline.discounts;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * The people on one booking, who are priced one by one and seated together.
 *
 * <p>Three rules decide what each of them is actually ticketed as, and the order matters because each
 * reads what the one before it settled.
 *
 * <p>Somebody has to be in charge. A child or an infant travels with a passenger who needs nobody,
 * and a booking of two children is not a booking the desk can reprice.
 *
 * <p>An infant on a lap is an infant under two on the day the sector leaves. One who has had a
 * second birthday by then needs a seat of their own and is ticketed as a child, which on a round
 * trip over the birthday means a lap on the way out and a seat on the way home.
 *
 * <p>And a lap holds one infant. A second lap infant on one adult travels in a seat at the seated
 * infant fare, which is how a family of one adult and twins arrives with a ticket the airport will
 * not accept.
 *
 * @param passengers everybody on the booking, in the order booked
 */
public record Party(List<Passenger> passengers) {

    /** The age at which an infant has to have a seat of their own. */
    public static final int LAP_AGE_LIMIT = 2;

    /**
     * Keeps a copy of the passengers.
     *
     * @throws ValidationError when there is nobody on the booking, or every passenger needs an adult,
     *     naming how many {@code passengers} there are
     */
    public Party {
        passengers = List.copyOf(passengers);
        if (passengers.isEmpty()) {
            throw new ValidationError("a booking has somebody on it");
        }
        if (passengers.stream().allMatch(passenger -> passenger.kind().needsAdult())) {
            throw new ValidationError(
                    "a child or an infant travels with somebody in charge",
                    Details.of("passengers", passengers.size()));
        }
    }

    /**
     * What each passenger is ticketed as on a sector leaving on a day, in the order booked.
     *
     * <p>A lap infant who is two by then becomes a child; after that, lap infants beyond one for each
     * passenger in charge move into a seat, the later bookings first. A lap infant with no birth
     * date given stays on the lap, because nothing says otherwise.
     */
    public List<Passenger> ticketedOn(LocalDate departure) {
        long laps = passengers.stream()
                .filter(passenger -> !passenger.kind().needsAdult())
                .count();
        List<Passenger> ticketed = new ArrayList<>();
        int held = 0;
        for (Passenger passenger : passengers) {
            if (passenger.kind() != PassengerType.INFANT) {
                ticketed.add(passenger);
            } else if (passenger
                    .ageOn(departure)
                    .filter(age -> age >= LAP_AGE_LIMIT)
                    .isPresent()) {
                ticketed.add(new Passenger(PassengerType.CHILD, passenger.born()));
            } else if (held < laps) {
                held++;
                ticketed.add(passenger);
            } else {
                ticketed.add(new Passenger(PassengerType.INFANT_WITH_SEAT, passenger.born()));
            }
        }
        return List.copyOf(ticketed);
    }

    /** How many seats the booking takes on a sector leaving on a day: everybody but a lap infant. */
    public int seatsOn(LocalDate departure) {
        return (int) ticketedOn(departure).stream()
                .filter(passenger -> passenger.kind().occupiesSeat())
                .count();
    }
}
