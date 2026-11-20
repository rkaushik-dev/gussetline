package io.github.rkaushikdev.gussetline.series;

import static io.github.rkaushikdev.gussetline.series.Series.FIELDS;
import static io.github.rkaushikdev.gussetline.series.Series.key;
import static io.github.rkaushikdev.gussetline.series.Series.parts;
import static io.github.rkaushikdev.gussetline.series.Series.read;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import java.time.LocalDate;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class SeriesTest {

    private static final LocalDate DEPARTURE = LocalDate.of(2025, 8, 4);

    @Test
    void aKeyReadsAsTheFourThingsItIsAbout() {
        assertThat(key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY)).isEqualTo("FRA-NYC-2025-08-04-Y");
    }

    @Test
    void anAirportIsFoldedToTheCityTheTariffPrices() {
        // The failure this class exists for. A watch on Newark and an observation on New York are the
        // same series, and two spellings of it is a night run that finds no history and raises
        // nothing, quietly, every night.
        assertThat(key("FRA", "EWR", DEPARTURE, Cabin.ECONOMY)).isEqualTo(key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY));
    }

    @Test
    void aCityGivenAsACityIsLeftAlone() {
        assertThat(key("FRA", "NYC", DEPARTURE, Cabin.ECONOMY)).isEqualTo("FRA-NYC-2025-08-04-Y");
    }

    @Test
    void bothEndsAreFoldedAndNotOnlyTheDestination() {
        assertThat(key("EWR", "FRA", DEPARTURE, Cabin.ECONOMY)).startsWith("NYC-");
    }

    @Test
    void aCabinMayArriveAsTheEnumOrAsWhatTheDatabaseHolds() {
        assertThat(key("FRA", "JFK", DEPARTURE, Cabin.BUSINESS)).isEqualTo(key("FRA", "JFK", DEPARTURE, "C"));
    }

    @Test
    void aLowercaseCabinIsTheSameCabin() {
        assertThat(key("FRA", "JFK", DEPARTURE, "y")).isEqualTo(key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY));
    }

    @Test
    void aDepartureMayArriveAsADayOrAsAMoment() {
        LocalDateTime when = LocalDateTime.of(2025, 8, 4, 10, 25);
        assertThat(key("FRA", "JFK", when, Cabin.ECONOMY)).isEqualTo(key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY));
    }

    @Test
    void theTimeOfDayIsNotPartOfTheSeries() {
        // Two departures on one day are one series. A watch is on a day and a market, because that
        // is what the passenger can be moved between.
        LocalDateTime morning = LocalDateTime.of(2025, 8, 4, 7, 5);
        LocalDateTime evening = LocalDateTime.of(2025, 8, 4, 21, 40);
        assertThat(key("FRA", "JFK", morning, "Y")).isEqualTo(key("FRA", "JFK", evening, "Y"));
    }

    @Test
    void thePartsAreTheKeyBeforeItIsJoined() {
        assertThat(parts("FRA", "EWR", DEPARTURE, Cabin.ECONOMY))
                .isEqualTo(new Series.Parts("FRA", "NYC", "2025-08-04", "Y"));
    }

    @Test
    void thePartsFoldTheSameWayTheKeyDoes() {
        Series.Parts made = parts("FRA", "EWR", DEPARTURE, "Y");
        assertThat(String.join("-", made.origin(), made.destination(), made.departure(), made.cabin()))
                .isEqualTo(key("FRA", "EWR", DEPARTURE, "Y"));
    }

    @Test
    void aKeySurvivesBeingReadBack() {
        assertThat(read(key("FRA", "JFK", DEPARTURE, Cabin.ECONOMY)))
                .isEqualTo(new Series.Reading("FRA", "NYC", DEPARTURE, "Y"));
    }

    @Test
    void readingCountsFromBothEndsBecauseTheDateHasDashesOfItsOwn() {
        assertThat(read("FRA-NYC-2025-08-04-Y").departure()).isEqualTo(DEPARTURE);
    }

    @Test
    void aKeyHasExactlyThisManyFields() {
        assertThat(key("FRA", "JFK", DEPARTURE, "Y").split("-", -1)).hasSize(FIELDS);
    }

    @Test
    void somethingThatIsNotAKeyIsRefusedAndNotGuessedAt() {
        assertThatThrownBy(() -> read("FRA-NYC"))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a series key");
    }

    @Test
    void aKeyWithTooMuchInItIsRefused() {
        assertThatThrownBy(() -> read("FRA-NYC-2025-08-04-Y-Y"))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a series key");
    }

    @Test
    void aKeyWithADayThatDoesNotExistIsRefused() {
        assertThatThrownBy(() -> read("FRA-NYC-2025-02-30-Y"))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a series key");
    }

    @Test
    void aKeyWithWordsWhereTheDateGoesIsRefused() {
        assertThatThrownBy(() -> read("FRA-NYC-next-august-ish-Y"))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not a series key");
    }

    @Test
    void nothingAtAllIsRefused() {
        assertThatThrownBy(() -> read("")).isInstanceOf(ValidationError.class).hasMessageContaining("not a series key");
    }
}
