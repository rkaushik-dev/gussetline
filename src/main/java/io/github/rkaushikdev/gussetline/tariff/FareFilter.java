package io.github.rkaushikdev.gussetline.tariff;

import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.indicators.Indicators;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * What a tariff search narrows on, beyond the carrier and the market. Each part left absent lets
 * every fare through.
 *
 * <p>Start from {@link #any()} or {@link #dated(LocalDate, LocalDate)} and add the rest with the
 * {@code with} methods: {@code FareFilter.any().withCabin(Cabin.BUSINESS)}.
 *
 * @param ticketing keeps the fares whose sale window holds this date
 * @param departure keeps the fares whose travel window holds this date
 * @param indicator keeps the fares whose filed indicator may price a journey under this one, so an
 *     AT filing still answers an SA journey
 * @param cabin keeps the fares in this cabin
 * @param bookingClass keeps the fares that price in this booking class, held upper case without
 *     surrounding white space
 */
public record FareFilter(
        Optional<LocalDate> ticketing,
        Optional<LocalDate> departure,
        Optional<GlobalIndicator> indicator,
        Optional<Cabin> cabin,
        Optional<String> bookingClass) {

    private static final FareFilter ANY =
            new FareFilter(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());

    public FareFilter {
        Objects.requireNonNull(ticketing, "ticketing");
        Objects.requireNonNull(departure, "departure");
        Objects.requireNonNull(indicator, "indicator");
        Objects.requireNonNull(cabin, "cabin");
        bookingClass = Objects.requireNonNull(bookingClass, "bookingClass").map(Text::code);
    }

    /** No narrowing at all: every fare in the market. */
    public static FareFilter any() {
        return ANY;
    }

    /** The fares that may be sold on the ticketing date and flown on the departure date. */
    public static FareFilter dated(LocalDate ticketing, LocalDate departure) {
        return any().withTicketing(ticketing).withDeparture(departure);
    }

    public FareFilter withTicketing(LocalDate date) {
        return new FareFilter(Optional.of(date), departure, indicator, cabin, bookingClass);
    }

    public FareFilter withDeparture(LocalDate date) {
        return new FareFilter(ticketing, Optional.of(date), indicator, cabin, bookingClass);
    }

    public FareFilter withIndicator(GlobalIndicator journey) {
        return new FareFilter(ticketing, departure, Optional.of(journey), cabin, bookingClass);
    }

    public FareFilter withCabin(Cabin wanted) {
        return new FareFilter(ticketing, departure, indicator, Optional.of(wanted), bookingClass);
    }

    /** A copy keeping one booking class, given in any case and with surrounding white space. */
    public FareFilter withBookingClass(String letter) {
        return new FareFilter(ticketing, departure, indicator, cabin, Optional.of(letter));
    }

    /** Whether a fare gets through every part that is set, read in the order the parts are listed. */
    boolean admits(Fare fare) {
        return ticketing.map(fare::sellableOn).orElse(true)
                && departure.map(fare::flyableOn).orElse(true)
                && indicator
                        .map(journey -> Indicators.indicatorMatches(fare.globalIndicator(), journey))
                        .orElse(true)
                && cabin.map(wanted -> fare.cabin() == wanted).orElse(true)
                && bookingClass
                        .map(wanted -> fare.bookingClass().equals(wanted))
                        .orElse(true);
    }
}
