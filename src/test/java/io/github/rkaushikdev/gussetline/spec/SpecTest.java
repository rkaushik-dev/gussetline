package io.github.rkaushikdev.gussetline.spec;

import static io.github.rkaushikdev.gussetline.spec.Spec.monthOf;
import static io.github.rkaushikdev.gussetline.spec.Spec.nominalBlock;
import static io.github.rkaushikdev.gussetline.spec.Spec.parse;
import static io.github.rkaushikdev.gussetline.spec.Spec.render;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.flights.YearRoll;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.itinerary.SurfaceSector;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpecTest {

    private static final LocalDate JULY = LocalDate.of(2025, 7, 22);

    @Test
    void aSingleSectorReads() {
        Itinerary made = parse("FRA LH400 K 04AUG JFK", JULY);
        assertThat(made.segments()).hasSize(1);
        Segment leg = made.segments().get(0);
        assertThat(leg.marketingCarrier()).isEqualTo("LH");
        assertThat(leg.flightNumber()).isEqualTo("400");
        assertThat(leg.bookingClass()).isEqualTo("K");
        assertThat(leg.origin()).isEqualTo("FRA");
        assertThat(leg.destination()).isEqualTo("JFK");
        assertThat(leg.departure().toLocalDate()).isEqualTo(LocalDate.of(2025, 8, 4));
    }

    @Test
    void aRoundTripReadsBothWays() {
        Itinerary made = parse("FRA LH400 K 04AUG JFK LH401 K 18AUG FRA", JULY);
        assertThat(made.segments()).extracting(Segment::destination).containsExactly("JFK", "FRA");
        assertThat(made.origin()).isEqualTo("FRA");
        assertThat(made.destination()).isEqualTo("FRA");
    }

    @Test
    void theEntryIsCaseInsensitive() {
        assertThat(parse("fra lh400 k 04aug jfk", JULY).segments()).hasSize(1);
    }

    @Test
    void commasAreAllowedBetweenSectors() {
        Itinerary made = parse("FRA LH400 K 04AUG JFK, JFK LH401 K 18AUG FRA", JULY);
        assertThat(made.segments()).hasSize(2);
    }

    @Test
    void anEntryThatDoesNotJoinUpIsRefused() {
        assertThatThrownBy(() -> parse("FRA LH400 K 04AUG JFK LH401 K 18AUG FRA LH900 K 20AUG JFK MUC", JULY))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theYearComesFromTheDayBeingPriced() {
        Itinerary made = parse("FRA LH400 K 04AUG JFK", LocalDate.of(2025, 7, 22));
        assertThat(made.segments().get(0).departure().getYear()).isEqualTo(2025);
    }

    @Test
    void aMonthAlreadyPastIsNextYear() {
        // Priced in December for a January departure. Reading that as this January puts the outbound
        // eleven months in the past.
        Itinerary made = parse("FRA LH400 K 04JAN JFK", LocalDate.of(2025, 12, 20));
        assertThat(made.segments().get(0).departure().toLocalDate()).isEqualTo(LocalDate.of(2026, 1, 4));
    }

    @Test
    void theYearRollsForwardAcrossANewYearInsideOneEntry() {
        Itinerary made = parse("FRA LH400 K 28DEC JFK LH401 K 04JAN FRA", LocalDate.of(2025, 12, 1));
        List<Segment> flown = made.segments();
        assertThat(flown).hasSize(2);
        LocalDate out = flown.get(0).departure().toLocalDate();
        LocalDate back = flown.get(1).departure().toLocalDate();
        assertThat(out).isEqualTo(LocalDate.of(2025, 12, 28));
        assertThat(back).isEqualTo(LocalDate.of(2026, 1, 4));
        assertThat(ChronoUnit.DAYS.between(out, back)).isEqualTo(7);
    }

    @Test
    void theYearNeverRollsBackwards() {
        Itinerary made = parse("FRA LH400 K 28DEC JFK LH401 K 04JAN FRA LH900 K 20JAN JFK", LocalDate.of(2025, 12, 1));
        assertThat(made.segments())
                .extracting(segment -> segment.departure().getYear())
                .containsExactly(2025, 2026, 2026);
    }

    @Test
    void twoSectorsOnOneDayDoNotRollAnything() {
        Itinerary made = parse("FRA LH100 K 04AUG MUC LH400 K 04AUG JFK", JULY);
        assertThat(made.segments())
                .extracting(segment -> segment.departure().toLocalDate())
                .isEqualTo(Collections.nCopies(2, LocalDate.of(2025, 8, 4)));
    }

    @Test
    void aSurfaceSectorIsTwoSlashes() {
        Itinerary made = parse("FRA LH100 K 04AUG MUC // VIE LH500 K 18AUG FRA", JULY);
        assertThat(made.elements()).hasSize(3);
        assertThat(made.elements().get(1)).isInstanceOf(SurfaceSector.class);
        assertThat(made.elements().get(1).origin()).isEqualTo("MUC");
        assertThat(made.elements().get(1).destination()).isEqualTo("VIE");
    }

    @Test
    void aSurfaceSectorWithNowhereToLandIsRefused() {
        assertThatThrownBy(() -> parse("FRA LH100 K 04AUG MUC //", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("somewhere to arrive");
    }

    @Test
    void anEntryThatOpensWithASurfaceSectorIsRefused() {
        assertThatThrownBy(() -> parse("FRA // MUC LH400 K 04AUG JFK", JULY)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTimeBelongsToTheDateAndNotTheFlight() {
        Itinerary made = parse("FRA LH400 K 04AUG/1025 JFK", JULY);
        Segment leg = made.segments().get(0);
        assertThat(leg.departure().getHour()).isEqualTo(10);
        assertThat(leg.departure().getMinute()).isEqualTo(25);
        assertThat(leg.estimated()).isFalse();
    }

    @Test
    void aSectorWithNoTimeGetsAMadeUpOneAndSaysSo() {
        Segment leg = parse("FRA LH400 K 04AUG JFK", JULY).segments().get(0);
        assertThat(leg.departure().getHour()).isEqualTo(12);
        assertThat(leg.estimated()).isTrue();
        assertThat(leg.elapsedMinutes()).contains(nominalBlock("FRA", "JFK"));
    }

    @Test
    void aMadeUpBlockIsNeverUnderAnHour() {
        assertThat(nominalBlock("FRA", "MUC")).isEqualTo(60);
    }

    @Test
    void aMadeUpBlockGrowsWithTheDistance() {
        assertThat(nominalBlock("FRA", "JFK")).isGreaterThan(nominalBlock("FRA", "MUC"));
    }

    @Test
    void aWestboundCrossingLandsOnTheDayItLeft() {
        Segment leg = parse("FRA LH400 K 04AUG JFK", JULY).segments().get(0);
        assertThat(leg.dayChange()).isZero();
    }

    @Test
    void anEastboundCrossingLandsTheNextDay() {
        Segment leg = parse("JFK LH401 K 04AUG FRA", JULY).segments().get(0);
        assertThat(leg.dayChange()).isEqualTo(1);
    }

    @Test
    void aPointNobodyHasHeardOfIsRefusedByName() {
        assertThatThrownBy(() -> parse("FRA LH400 K 04AUG ZZZ", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("airport");
    }

    @Test
    void somethingThatIsNotAPointIsRefused() {
        assertThatThrownBy(() -> parse("FRANKFURT LH400 K 04AUG JFK", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a point");
    }

    @Test
    void somethingThatIsNotAFlightIsRefused() {
        assertThatThrownBy(() -> parse("FRA LUFTHANSA K 04AUG JFK", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a flight");
    }

    @Test
    void aBookingClassOfMoreThanOneLetterIsRefused() {
        assertThatThrownBy(() -> parse("FRA LH400 KK 04AUG JFK", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("one letter");
    }

    @Test
    void aMonthNobodyFilesIsRefused() {
        assertThatThrownBy(() -> parse("FRA LH400 K 04ZZZ JFK", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a month");
    }

    @Test
    void aDayThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> parse("FRA LH400 K 31FEB JFK", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("no such day");
    }

    @Test
    void aTimeThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> parse("FRA LH400 K 04AUG/2599 JFK", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a time");
    }

    @Test
    void aSectorMissingItsLastTokenIsRefusedWithWhatItSaw() {
        assertThatThrownBy(() -> parse("FRA LH400 K 04AUG", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("a flight, a class, a date");
    }

    @Test
    void anEmptyEntryIsRefused() {
        assertThatThrownBy(() -> parse("   ", JULY))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("nothing to price");
    }

    @Test
    void aPointOnItsOwnIsNotAnItinerary() {
        assertThatThrownBy(() -> parse("FRA", JULY)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFlightNumberKeepsItsSignificantDigits() {
        assertThat(parse("FRA LH0400 K 04AUG JFK", JULY).segments().get(0).flightNumber())
                .isEqualTo("400");
    }

    @Test
    void anEntryWritesBackOutTheWayItWasTyped() {
        String entry = "FRA LH400 K 04AUG JFK LH401 K 18AUG FRA";
        assertThat(render(parse(entry, JULY))).isEqualTo(entry);
    }

    @Test
    void aSurfaceSectorSurvivesTheRoundTrip() {
        String entry = "FRA LH100 K 04AUG MUC // VIE LH500 K 18AUG FRA";
        assertThat(render(parse(entry, JULY))).isEqualTo(entry);
    }

    @Test
    void aMonthReadsByItsThreeLetters() {
        assertThat(monthOf("aug")).isEqualTo(8);
        assertThatThrownBy(() -> monthOf("august")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aYearRollFromAYearStartsWhereItIsTold() {
        YearRoll roll = new YearRoll(2025);
        assertThat(roll.resolve(8, 4)).isEqualTo(LocalDate.of(2025, 8, 4));
        assertThat(roll.resolve(1, 4)).isEqualTo(LocalDate.of(2026, 1, 4));
    }

    @Test
    void aYearRollFromADayAlreadyKnowsTheMonth() {
        YearRoll roll = YearRoll.fromDate(LocalDate.of(2025, 12, 20));
        assertThat(roll.resolve(1, 4)).isEqualTo(LocalDate.of(2026, 1, 4));
    }

    @Test
    void aYearRollFromADayDoesNotRollALaterMonth() {
        YearRoll roll = YearRoll.fromDate(LocalDate.of(2025, 7, 22));
        assertThat(roll.resolve(8, 4)).isEqualTo(LocalDate.of(2025, 8, 4));
    }
}
