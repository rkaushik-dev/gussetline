package io.github.rkaushikdev.gussetline.geography;

import static io.github.rkaushikdev.gussetline.geography.Geography.AIRPORTS;
import static io.github.rkaushikdev.gussetline.geography.Geography.COUNTRIES;
import static io.github.rkaushikdev.gussetline.geography.Geography.METROS;
import static io.github.rkaushikdev.gussetline.geography.Geography.airport;
import static io.github.rkaushikdev.gussetline.geography.Geography.airportOrCityCountry;
import static io.github.rkaushikdev.gussetline.geography.Geography.airportsIn;
import static io.github.rkaushikdev.gussetline.geography.Geography.areaOf;
import static io.github.rkaushikdev.gussetline.geography.Geography.cityOf;
import static io.github.rkaushikdev.gussetline.geography.Geography.country;
import static io.github.rkaushikdev.gussetline.geography.Geography.sameCountry;
import static io.github.rkaushikdev.gussetline.geography.Geography.subAreaOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GeographyTest {

    @Test
    void everyAirportNamesACountryWeHold() {
        List<String> missing = AIRPORTS.values().stream()
                .map(Airport::country)
                .filter(code -> !COUNTRIES.containsKey(code))
                .distinct()
                .sorted()
                .toList();
        assertThat(missing).isEmpty();
    }

    @Test
    void everyCountrySitsInExactlyOneArea() {
        for (Country row : COUNTRIES.values()) {
            assertThat(row.area()).isIn(Area.ONE, Area.TWO, Area.THREE);
        }
    }

    @Test
    void areasFollowTheSubAreaAndNotTheHemisphere() {
        assertThat(country("EG").area()).isSameAs(Area.TWO);
        assertThat(country("AE").area()).isSameAs(Area.TWO);
        assertThat(country("JP").area()).isSameAs(Area.THREE);
        assertThat(country("BR").area()).isSameAs(Area.ONE);
    }

    @Test
    void airportLookupIsCaseAndSpaceInsensitive() {
        assertThat(airport(" lhr ").code()).isEqualTo("LHR");
    }

    @Test
    void aMetroCodeIsNotAnAirport() {
        assertThatThrownBy(() -> airport("LON")).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> airport("NYC")).isInstanceOf(ValidationError.class);
    }

    @Test
    void cityOfFoldsTheAirportsOfAMetroTogether() {
        assertThat(cityOf("LHR")).isEqualTo("LON");
        assertThat(cityOf("LGW")).isEqualTo("LON");
        assertThat(cityOf("EWR")).isEqualTo("NYC");
        assertThat(cityOf("ORY")).isEqualTo("PAR");
    }

    @Test
    void cityOfAStandaloneAirportIsItsOwnCode() {
        assertThat(cityOf("ZRH")).isEqualTo("ZRH");
        assertThat(cityOf("DXB")).isEqualTo("DXB");
    }

    @Test
    void aMetroCodeAnswersForItself() {
        assertThat(cityOf("LON")).isEqualTo("LON");
    }

    @Test
    void airportsInListsTheMetroMembersSorted() {
        assertThat(airportsIn("LON")).containsExactly("LCY", "LGW", "LHR", "STN");
        assertThat(airportsIn("NYC")).containsExactly("EWR", "JFK", "LGA");
        assertThat(airportsIn("TYO")).containsExactly("HND", "NRT");
    }

    @Test
    void airportsInASingleAirportCityIsThatAirport() {
        assertThat(airportsIn("ZRH")).containsExactly("ZRH");
    }

    @Test
    void airportsInRefusesSomethingThatIsNeither() {
        assertThatThrownBy(() -> airportsIn("QQQ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void metrosAreDerivedAndNotTypedTwice() {
        for (Map.Entry<String, List<String>> metro : METROS.entrySet()) {
            for (String code : metro.getValue()) {
                assertThat(AIRPORTS.get(code).city()).isEqualTo(metro.getKey());
            }
        }
    }

    @Test
    void isMetroMarksTheAirportThatCarriesItsOwnCityCode() {
        assertThat(airport("ZRH").isMetro()).isTrue();
        assertThat(airport("LHR").isMetro()).isFalse();
    }

    @Test
    void subAreaAndAreaWorkFromAnAirportOrACity() {
        assertThat(subAreaOf("LHR")).isSameAs(SubArea.EUROPE);
        assertThat(subAreaOf("LON")).isSameAs(SubArea.EUROPE);
        assertThat(areaOf("JFK")).isSameAs(Area.ONE);
        assertThat(areaOf("NYC")).isSameAs(Area.ONE);
        assertThat(subAreaOf("NRT")).isSameAs(SubArea.JAPAN_KOREA);
        assertThat(subAreaOf("SIN")).isSameAs(SubArea.SOUTH_EAST_ASIA);
    }

    @Test
    void theMiddleEastIsAreaTwoAndSouthAsiaIsAreaThree() {
        assertThat(areaOf("DXB")).isSameAs(Area.TWO);
        assertThat(areaOf("DOH")).isSameAs(Area.TWO);
        assertThat(areaOf("DEL")).isSameAs(Area.THREE);
        assertThat(areaOf("BOM")).isSameAs(Area.THREE);
    }

    @Test
    void countryOfAPointReadsTheSameEitherWay() {
        assertThat(airportOrCityCountry("LHR")).isEqualTo("GB");
        assertThat(airportOrCityCountry("LON")).isEqualTo("GB");
    }

    @Test
    void sameCountryFoldsMetros() {
        assertThat(sameCountry("LHR", "MAN")).isTrue();
        assertThat(sameCountry("LON", "EDI")).isTrue();
        assertThat(sameCountry("LHR", "DUB")).isFalse();
    }

    @Test
    void utcOffsetsAreWholeMinutesAndIndiaIsTheReason() {
        assertThat(airport("DEL").utcOffsetMinutes()).isEqualTo(330);
        assertThat(airport("CMB").utcOffsetMinutes()).isEqualTo(330);
        assertThat(airport("AKL").utcOffsetMinutes()).isEqualTo(720);
    }

    @Test
    void coordinatesAreInsideTheSphere() {
        for (Airport a : AIRPORTS.values()) {
            assertThat(a.latitude()).isBetween(-90.0, 90.0);
            assertThat(a.longitude()).isBetween(-180.0, 180.0);
        }
    }

    @Test
    void unknownLookupsSayWhatWasAskedFor() {
        ValidationError caught = catchThrowableOfType(ValidationError.class, () -> country("QQ"));
        assertThat(caught.details()).containsEntry("code", "QQ");
    }
}
