package io.github.rkaushikdev.gussetline.flights;

import static io.github.rkaushikdev.gussetline.flights.Flights.inferDayChange;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlightsTest {

    private static final LocalDateTime DEPARTS = LocalDateTime.of(2025, 3, 4, 10, 25);
    private static final LocalDateTime ARRIVES = LocalDateTime.of(2025, 3, 4, 13, 5);

    /** Frankfurt to New York in K, the sector every test here starts from. */
    private static Segment seg() {
        return new Segment("LH", "400", "FRA", "JFK", DEPARTS, ARRIVES, "K", Cabin.ECONOMY);
    }

    private static Segment seg(String origin, String destination, LocalDateTime departure, LocalDateTime arrival) {
        return new Segment("LH", "400", origin, destination, departure, arrival, "K", Cabin.ECONOMY);
    }

    @Test
    void cabinsRankAndOutrank() {
        assertThat(Cabin.FIRST.outranks(Cabin.BUSINESS)).isTrue();
        assertThat(Cabin.BUSINESS.outranks(Cabin.PREMIUM)).isTrue();
        assertThat(Cabin.PREMIUM.outranks(Cabin.ECONOMY)).isTrue();
        assertThat(Cabin.ECONOMY.outranks(Cabin.ECONOMY)).isFalse();
    }

    @Test
    void aSegmentNormalisesItsCodes() {
        Segment s = new Segment("lh", "400", " fra ", "jfk", DEPARTS, ARRIVES, "k", Cabin.ECONOMY);
        assertThat(List.of(s.marketingCarrier(), s.origin(), s.destination(), s.bookingClass()))
                .containsExactly("LH", "FRA", "JFK", "K");
    }

    @Test
    void aSectorCannotEndWhereItStarted() {
        assertThatThrownBy(() -> seg("FRA", "FRA", DEPARTS, ARRIVES)).isInstanceOf(ValidationError.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "KK", "1"})
    void aBookingClassIsOneLetter(String value) {
        assertThatThrownBy(() -> new Segment("LH", "400", "FRA", "JFK", DEPARTS, ARRIVES, value, Cabin.ECONOMY))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aSegmentNeedsAFlightNumber() {
        assertThatThrownBy(() -> new Segment("LH", "   ", "FRA", "JFK", DEPARTS, ARRIVES, "K", Cabin.ECONOMY))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void negativeDayChangeAndStopsAreRefused() {
        assertThatThrownBy(() -> seg().withDayChange(-1)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> seg().withStops(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void designatorJoinsTheCarrierAndTheNumber() {
        Segment s = new Segment("LH", " 400 ", "FRA", "JFK", DEPARTS, ARRIVES, "K", Cabin.ECONOMY);
        assertThat(s.designator()).isEqualTo("LH400");
    }

    @Test
    void operatingCarrierDefaultsToTheMarketingOne() {
        assertThat(seg().operatedBy()).isEqualTo("LH");
        assertThat(seg().isCodeshare()).isFalse();
        assertThat(seg().withOperatingCarrier("LX").operatedBy()).isEqualTo("LX");
        assertThat(seg().withOperatingCarrier("LX").isCodeshare()).isTrue();
    }

    @Test
    void aCodeshareOnItselfIsNotACodeshare() {
        assertThat(seg().withOperatingCarrier("lh").isCodeshare()).isFalse();
    }

    @Test
    void utcConversionUsesEachEndOfTheSector() {
        Segment s = seg();
        // Frankfurt is UTC+1, New York is UTC-5.
        assertThat(s.departureUtc()).isEqualTo(LocalDateTime.of(2025, 3, 4, 9, 25));
        assertThat(s.arrivalUtc()).isEqualTo(LocalDateTime.of(2025, 3, 4, 18, 5));
        assertThat(s.elapsed()).isEqualTo(Duration.ofHours(8).plusMinutes(40));
    }

    @Test
    void aDayChangeMovesTheArrivalDateAndTheBlockTime() {
        Segment s = seg("FRA", "NRT", LocalDateTime.of(2025, 3, 4, 17, 20), LocalDateTime.of(2025, 3, 5, 11, 55))
                .withDayChange(1);
        assertThat(s.arrivalDate()).isEqualTo(s.departureDate().plusDays(1));
        // Frankfurt is UTC+1 and Tokyo is UTC+9, so the wall clock difference of eighteen and a
        // half hours is ten hours thirty five of block.
        assertThat(s.elapsed()).isEqualTo(Duration.ofHours(10).plusMinutes(35));
    }

    @Test
    void theArrivalTimeOfDayIsReadFromTheArrivalNotTheDate() {
        Segment s =
                seg("FRA", "JFK", DEPARTS, LocalDateTime.of(1999, 1, 1, 13, 5)).withDayChange(0);
        assertThat(s.arrivalUtc()).isEqualTo(LocalDateTime.of(2025, 3, 4, 18, 5));
    }

    @Test
    void nonstopIsTheDefaultAndAStopShows() {
        assertThat(seg().isNonstop()).isTrue();
        assertThat(seg().withStops(1).isNonstop()).isFalse();
    }

    @Test
    void theKeyIsStableAndCarriesTheDeparture() {
        assertThat(seg().key()).isEqualTo(new Segment.Key("LH400", "FRA", "JFK", "2025-03-04T10:25:00"));
    }

    @Test
    void stringFormReadsLikeATimetableLine() {
        assertThat(seg()).hasToString("LH400 FRAJFK 04Mar25 1025");
    }

    @Test
    void inferDayChangeOnASameDaySector() {
        assertThat(inferDayChange(
                        "FRA", "JFK", LocalDateTime.of(2025, 3, 4, 10, 25), LocalDateTime.of(2025, 3, 4, 13, 5)))
                .isZero();
    }

    @Test
    void inferDayChangeWhenTheClockGoesBackwards() {
        assertThat(inferDayChange(
                        "FRA", "NRT", LocalDateTime.of(2025, 3, 4, 17, 20), LocalDateTime.of(2025, 3, 5, 11, 55)))
                .isEqualTo(1);
    }

    @Test
    void theBlockTimeAnswersWhereTheWallClockOnlyGuesses() {
        // Frankfurt to Auckland the long way, twenty four hours twenty five of block, landing on
        // the day after next in Auckland's own calendar.
        assertThat(inferDayChange(
                        "FRA", "AKL", LocalDateTime.of(2025, 3, 4, 21, 40), LocalDateTime.of(2025, 3, 6, 9, 5), 1465))
                .isEqualTo(2);
    }

    @Test
    void theWallClockAloneWouldHaveSaidOneDayThere() {
        assertThat(inferDayChange(
                        "FRA", "AKL", LocalDateTime.of(2025, 3, 4, 21, 40), LocalDateTime.of(2025, 3, 6, 9, 5)))
                .isEqualTo(1);
    }

    @Test
    void aWestboundSectorCanLandBeforeItLeftInWallClockTerms() {
        assertThat(inferDayChange(
                        "NRT", "LAX", LocalDateTime.of(2025, 3, 4, 17, 0), LocalDateTime.of(2025, 3, 4, 10, 30)))
                .isZero();
    }

    @Test
    void inferDayChangeRefusesANegativeBlock() {
        assertThatThrownBy(() -> inferDayChange(
                        "FRA", "JFK", LocalDateTime.of(2025, 3, 4, 10, 0), LocalDateTime.of(2025, 3, 4, 12, 0), -5))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theBuilderNamesEveryFieldItIsStillMissing() {
        SegmentBuilder b = new SegmentBuilder().marketingCarrier("LH").flightNumber("400");
        assertThat(b.missing())
                .containsExactly("origin", "destination", "departure", "arrival", "booking_class", "cabin");
        ValidationError caught = catchThrowableOfType(ValidationError.class, b::build);
        assertThat(caught.details().get("missing"))
                .asInstanceOf(InstanceOfAssertFactories.LIST)
                .contains("origin");
    }

    private static SegmentBuilder fullBuilder() {
        return new SegmentBuilder()
                .marketingCarrier("LH")
                .flightNumber("400")
                .origin("FRA")
                .destination("JFK")
                .departure(LocalDateTime.of(2025, 3, 4, 10, 25))
                .arrival(LocalDateTime.of(2025, 3, 4, 13, 5))
                .bookingClass("K")
                .cabin(Cabin.ECONOMY);
    }

    @Test
    void theBuilderProducesASegmentWhenItIsComplete() {
        assertThat(fullBuilder().build().designator()).isEqualTo("LH400");
    }

    @Test
    void theBuilderFallsBackToTheClockWhenNothingElseIsSent() {
        SegmentBuilder b = fullBuilder().destination("NRT").arrival(LocalDateTime.of(2025, 3, 5, 9, 5));
        assertThat(b.build().dayChange()).isEqualTo(1);
    }

    @Test
    void theBuilderPrefersTheBlockTimeOverTheClock() {
        SegmentBuilder b = fullBuilder()
                .destination("AKL")
                .departure(LocalDateTime.of(2025, 3, 4, 21, 40))
                .arrival(LocalDateTime.of(2025, 3, 6, 9, 5))
                .elapsedMinutes(1465);
        assertThat(b.build().dayChange()).isEqualTo(2);
    }

    @Test
    void anExplicitDayChangeWinsOverBoth() {
        SegmentBuilder b = fullBuilder()
                .destination("AKL")
                .departure(LocalDateTime.of(2025, 3, 4, 21, 40))
                .arrival(LocalDateTime.of(2025, 3, 6, 9, 5))
                .elapsedMinutes(1465)
                .dayChange(1);
        assertThat(b.build().dayChange()).isEqualTo(1);
    }
}
