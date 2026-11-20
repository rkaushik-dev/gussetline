package io.github.rkaushikdev.gussetline.distance;

import static io.github.rkaushikdev.gussetline.distance.Distance.defaultMileageTable;
import static io.github.rkaushikdev.gussetline.distance.Distance.greatCircle;
import static io.github.rkaushikdev.gussetline.distance.Distance.pointOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.MileageUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import org.junit.jupiter.api.Test;

class DistanceTest {

    @Test
    void greatCircleIsSymmetric() {
        assertThat(greatCircle("FRA", "JFK")).isEqualTo(greatCircle("JFK", "FRA"));
    }

    @Test
    void greatCircleOfAPointWithItselfIsZero() {
        assertThat(greatCircle("FRA", "FRA")).isZero();
    }

    @Test
    void greatCircleLandsNearThePublishedFigures() {
        // Not equal to the published ticketed point mileage, and that is the whole reason the table
        // exists, but a great circle that is out by hundreds means a bad row.
        assertThat(Math.abs(greatCircle("FRA", "JFK") - 3851)).isLessThan(120);
        assertThat(Math.abs(greatCircle("LHR", "JFK") - 3451)).isLessThan(120);
        assertThat(Math.abs(greatCircle("SIN", "SYD") - 3915)).isLessThan(150);
    }

    @Test
    void greatCircleCrossesTheMeridianAndTheDateLineSanely() {
        assertThat(greatCircle("LAX", "NRT")).isStrictlyBetween(5000, 6000);
        assertThat(greatCircle("LHR", "AKL")).isStrictlyBetween(9000, 12000);
    }

    private static MileageTable table() {
        MileageTable t = new MileageTable();
        t.add("FRA", "JFK", 3851);
        t.add("LON", "JFK", 3451);
        return t;
    }

    @Test
    void aLookupFoldsAirportsUpToTheMetro() {
        MileageTable table = table();
        assertThat(table.published("LHR", "JFK")).isEqualTo(3451);
        assertThat(table.published("LGW", "EWR")).isEqualTo(3451);
        assertThat(table.published("STN", "LGA")).isEqualTo(3451);
    }

    @Test
    void aPairIsUnordered() {
        assertThat(table().published("JFK", "FRA")).isEqualTo(3851);
    }

    @Test
    void aPairWeDoNotHoldRaisesAndNamesTheCities() {
        MileageTable table = table();
        MileageUnavailable caught = catchThrowableOfType(MileageUnavailable.class, () -> table.published("FRA", "BKK"));
        assertThat(caught.details().get("origin")).isIn("BKK", "FRA");
    }

    @Test
    void hasAnswersWithoutRaising() {
        MileageTable table = table();
        assertThat(table.has("FRA", "JFK")).isTrue();
        assertThat(table.has("FRA", "BKK")).isFalse();
    }

    @Test
    void aMileageHasToBePositive() {
        MileageTable table = table();
        assertThatThrownBy(() -> table.add("FRA", "BKK", 0)).isInstanceOf(MileageUnavailable.class);
        assertThatThrownBy(() -> table.add("FRA", "BKK", -100)).isInstanceOf(MileageUnavailable.class);
    }

    @Test
    void tpmFallsBackToGreatCircleAndSaysSo() {
        MileageTable table = table();
        Mileage published = table.tpm("FRA", "JFK");
        assertThat(published.miles()).isEqualTo(3851);
        assertThat(published.published()).isTrue();
        assertThat(published.isFallback()).isFalse();

        Mileage fallback = table.tpm("FRA", "BKK");
        assertThat(fallback.miles()).isEqualTo(greatCircle("FRA", "BKK"));
        assertThat(fallback.isFallback()).isTrue();
    }

    @Test
    void aPairFiledWithoutAnIndicatorAnswersForAnyOfThem() {
        MileageTable table = table();
        assertThat(table.published("FRA", "JFK", GlobalIndicator.AT)).isEqualTo(3851);
        assertThat(table.published("FRA", "JFK", GlobalIndicator.PA)).isEqualTo(3851);
    }

    @Test
    void aPairFiledPerIndicatorAnswersOnlyForThatOne() {
        MileageTable t = new MileageTable();
        t.add("FRA", "TYO", 5928, GlobalIndicator.TS);
        t.add("FRA", "TYO", 7169, GlobalIndicator.PA);
        assertThat(t.published("FRA", "TYO", GlobalIndicator.TS)).isEqualTo(5928);
        assertThat(t.published("FRA", "TYO", GlobalIndicator.PA)).isEqualTo(7169);
        assertThatThrownBy(() -> t.published("FRA", "TYO", GlobalIndicator.AT)).isInstanceOf(MileageUnavailable.class);
        assertThatThrownBy(() -> t.published("FRA", "TYO")).isInstanceOf(MileageUnavailable.class);
    }

    @Test
    void theSpecificFilingWinsOverTheGeneralOne() {
        MileageTable t = new MileageTable();
        t.add("FRA", "TYO", 6000);
        t.add("FRA", "TYO", 5928, GlobalIndicator.TS);
        assertThat(t.published("FRA", "TYO", GlobalIndicator.TS)).isEqualTo(5928);
        assertThat(t.published("FRA", "TYO", GlobalIndicator.PA)).isEqualTo(6000);
        assertThat(t.published("FRA", "TYO")).isEqualTo(6000);
    }

    @Test
    void theShippedTableHoldsTheTokyoPairBothWaysRound() {
        MileageTable table = defaultMileageTable();
        Mileage east = table.tpm("FRA", "TYO", GlobalIndicator.TS);
        Mileage west = table.tpm("FRA", "TYO", GlobalIndicator.PA);
        assertThat(east.published()).isTrue();
        assertThat(west.published()).isTrue();
        assertThat(east.miles()).isLessThan(west.miles());
    }

    @Test
    void theShippedTableReachesTheMetroMembers() {
        MileageTable table = defaultMileageTable();
        assertThat(table.tpm("LHR", "JFK").published()).isTrue();
        assertThat(table.tpm("HND", "LAX").published()).isTrue();
        assertThat(table.size()).isGreaterThan(40);
    }

    @Test
    void anUnlistedPairStillPricesOffTheShippedTable() {
        Mileage reading = defaultMileageTable().tpm("BGI", "NBO");
        assertThat(reading.isFallback()).isTrue();
        assertThat(reading.miles()).isPositive();
    }

    @Test
    void aCityCodeMeasuresFromOneOfItsAirports() {
        // A metro code has no coordinates. Measuring from it raises an unknown airport, which the
        // mileage check then reports as a bad itinerary instead of as a mileage nobody published.
        assertThat(pointOf("NYC")).isIn(Geography.airportsIn("NYC"));
        assertThat(pointOf("JFK")).isEqualTo("JFK");
    }

    @Test
    void aCodeThatIsNeitherAnAirportNorACityIsRefused() {
        assertThatThrownBy(() -> pointOf("ZZZ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void anUnpublishedCityPairFallsBackInsteadOfRaising() {
        Mileage reading = new MileageTable().tpm("MUC", "NYC");
        assertThat(reading.isFallback()).isTrue();
        assertThat(reading.miles()).isGreaterThan(3000);
    }

    @Test
    void aPublishedPairIsNotAFallback() {
        MileageTable table = new MileageTable();
        table.add("MUC", "NYC", 4100);
        Mileage reading = table.tpm("MUC", "NYC");
        assertThat(reading.published()).isTrue();
        assertThat(reading.miles()).isEqualTo(4100);
    }
}
