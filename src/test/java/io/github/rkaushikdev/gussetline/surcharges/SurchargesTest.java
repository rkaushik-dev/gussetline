package io.github.rkaushikdev.gussetline.surcharges;

import static io.github.rkaushikdev.gussetline.Fixtures.fare;
import static io.github.rkaushikdev.gussetline.Fixtures.flatConverter;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static io.github.rkaushikdev.gussetline.construction.Construction.priceComponent;
import static io.github.rkaushikdev.gussetline.surcharges.Surcharges.applyAll;
import static io.github.rkaushikdev.gussetline.surcharges.Surcharges.assessComponent;
import static io.github.rkaushikdev.gussetline.surcharges.Surcharges.totalNuc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class SurchargesTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private final Converter flatConverter = flatConverter();

    /** LH's 30.00 EUR filing on Frankfurt to New York, for a test to change what it cares about. */
    private static QSurcharge.Builder charge() {
        return QSurcharge.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .amount(Money.of("30.00", "EUR"));
    }

    private static FareComponent direct() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static FareComponent connecting() {
        return new FareComponent(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
    }

    private FareComponent priced(FareComponent component) {
        return priceComponent(component, fare(), flatConverter, TICKETING);
    }

    @Test
    void aSurchargeFoldsItsMarketToCities() {
        assertThat(charge().origin("fra").destination("ewr").build().destination())
                .isEqualTo("NYC");
    }

    @Test
    void aSurchargeCoversAMarketAndNotAPoint() {
        assertThatThrownBy(() -> charge().destination("FRA").build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aSurchargeIsPositive() {
        assertThatThrownBy(() -> charge().amount(Money.of("0", "EUR")).build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTravelWindowThatClosesBeforeItOpensIsRefused() {
        assertThatThrownBy(() -> charge().firstTravel(LocalDate.of(2025, 6, 1))
                        .lastTravel(LocalDate.of(2025, 5, 1))
                        .build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aSurchargeReadsEitherWayRoundUnlessItIsDirectional() {
        QSurcharge both = charge().build();
        assertThat(both.covers("FRA", "JFK")).isTrue();
        assertThat(both.covers("JFK", "FRA")).isTrue();

        QSurcharge oneWay = charge().directional(true).build();
        assertThat(oneWay.covers("FRA", "JFK")).isTrue();
        assertThat(oneWay.covers("JFK", "FRA")).isFalse();
    }

    @Test
    void aSurchargeReadsBackAsItsMarketAndAmount() {
        assertThat(charge().build()).hasToString("Q FRANYC 30.00 EUR");
    }

    @Test
    void theTableIndexesByCarrier() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().build());
        table.add(charge().carrier("BA").build());
        assertThat(table.forCarrier("LH")).hasSize(1);
        assertThat(table.forCarrier("ba")).hasSize(1);
        assertThat(table.forCarrier("AF")).isEmpty();
        assertThat(table.size()).isEqualTo(2);
    }

    @Test
    void aComponentSurchargeIsChargedOnceHoweverManySectors() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().build());
        List<AppliedSurcharge> applied = assessComponent(priced(connecting()), table, flatConverter, TICKETING);
        assertThat(applied).hasSize(1);
        assertThat(applied.get(0).times()).isEqualTo(1);
        assertThat(applied.get(0).nuc()).isEqualByComparingTo("30.00");
    }

    @Test
    void aSectorSurchargeIsChargedForEachMatchingSector() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().origin("FRA").destination("LHR").scope(Scope.SECTOR).build());
        List<AppliedSurcharge> applied = assessComponent(priced(connecting()), table, flatConverter, TICKETING);
        assertThat(applied.get(0).times()).isEqualTo(1);
        assertThat(applied.get(0).nuc()).isEqualByComparingTo("30.00");
    }

    @Test
    void aSectorSurchargeDoublesWhenTwoSectorsMatch() {
        FareComponent there = new FareComponent(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "993", "LHR", "FRA", "2025-03-06 09:00", "2025-03-06 11:35")));
        SurchargeTable table = new SurchargeTable();
        table.add(charge().origin("FRA").destination("LHR").scope(Scope.SECTOR).build());
        FareComponent priced = priceComponent(there, fare("FRA", "LHR"), flatConverter, TICKETING);
        List<AppliedSurcharge> applied = assessComponent(priced, table, flatConverter, TICKETING);
        assertThat(applied.get(0).times()).isEqualTo(2);
        assertThat(applied.get(0).nuc()).isEqualByComparingTo("60.00");
    }

    @Test
    void aSectorSurchargeThatMatchesNothingIsNotCharged() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().origin("AMS").destination("JFK").scope(Scope.SECTOR).build());
        assertThat(assessComponent(priced(connecting()), table, flatConverter, TICKETING))
                .isEmpty();
    }

    @Test
    void aComponentSurchargeReadsTheEndsAndNotTheSectors() {
        // The component is Frankfurt to New York through London. A surcharge filed on Frankfurt to
        // London does not attach, because the component is not a Frankfurt to London component.
        SurchargeTable table = new SurchargeTable();
        table.add(charge().origin("FRA").destination("LHR").build());
        assertThat(assessComponent(priced(connecting()), table, flatConverter, TICKETING))
                .isEmpty();
    }

    @Test
    void aSurchargeOutsideItsTravelWindowIsNotCharged() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().firstTravel(LocalDate.of(2025, 6, 1))
                .lastTravel(LocalDate.of(2025, 9, 30))
                .build());
        assertThat(assessComponent(priced(direct()), table, flatConverter, TICKETING))
                .isEmpty();
    }

    @Test
    void aSurchargeInsideItsTravelWindowIsCharged() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().firstTravel(LocalDate.of(2025, 3, 1))
                .lastTravel(LocalDate.of(2025, 3, 31))
                .build());
        assertThat(assessComponent(priced(direct()), table, flatConverter, TICKETING))
                .hasSize(1);
    }

    @Test
    void anotherCarriersFilingDoesNotApply() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().carrier("BA").build());
        assertThat(assessComponent(priced(direct()), table, flatConverter, TICKETING))
                .isEmpty();
    }

    @Test
    void aSurchargeCheckRunsAfterTheComponentIsPriced() {
        assertThatThrownBy(() -> assessComponent(direct(), new SurchargeTable(), flatConverter, TICKETING))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void everyChargeIsRecorded() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().build());
        table.add(charge().amount(Money.of("12.00", "EUR")).reason("QS").build());
        Trace trace = new Trace();
        assessComponent(priced(direct()), table, flatConverter, TICKETING, trace);
        assertThat(trace.forStage(Stage.SURCHARGE)).hasSize(2);
        assertThat(trace.failed()).isFalse();
    }

    @Test
    void theTotalAddsTheChargesUp() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().build());
        table.add(charge().amount(Money.of("12.00", "EUR")).reason("QS").build());
        List<AppliedSurcharge> applied = assessComponent(priced(direct()), table, flatConverter, TICKETING);
        assertThat(totalNuc(applied)).isEqualByComparingTo("42.00");
        assertThat(totalNuc(List.of())).isEqualByComparingTo("0");
    }

    @Test
    void applyingTheChargesPutsEachUnderItsOwnNote() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().build());
        table.add(charge().amount(Money.of("12.00", "EUR")).reason("QS").build());
        FareComponent priced = priced(direct());
        List<AppliedSurcharge> applied = assessComponent(priced, table, flatConverter, TICKETING);
        FareComponent charged = applyAll(priced, applied);
        assertThat(charged.notes()).containsExactly("Q", "QS");
        assertThat(charged.totalNuc()).isEqualByComparingTo("462.00");
    }

    @Test
    void anAppliedChargeReadsBackWithItsCount() {
        SurchargeTable table = new SurchargeTable();
        table.add(charge().origin("FRA").destination("LHR").scope(Scope.SECTOR).build());
        List<AppliedSurcharge> applied = assessComponent(priced(connecting()), table, flatConverter, TICKETING);
        assertThat(applied.get(0)).hasToString("Q 30.00 NUC");
    }

    /** Frankfurt to London on Tuesday the fourth and back on Thursday the sixth, both booked in K. */
    private FareComponent thereAndBack() {
        FareComponent there = new FareComponent(List.of(
                segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                segment("LH", "993", "LHR", "FRA", "2025-03-06 09:00", "2025-03-06 11:35")));
        return priceComponent(there, fare("FRA", "LHR"), flatConverter, TICKETING);
    }

    private List<AppliedSurcharge> owed(FareComponent component, QSurcharge filing) {
        SurchargeTable table = new SurchargeTable();
        table.add(filing);
        return assessComponent(component, table, flatConverter, TICKETING);
    }

    @Test
    void aSectorSurchargeForSomeWeekdaysReadsOnlyTheSectorsLeavingThen() {
        QSurcharge thursdays = charge().origin("FRA")
                .destination("LHR")
                .scope(Scope.SECTOR)
                .days(DayOfWeek.THURSDAY)
                .build();
        assertThat(owed(thereAndBack(), thursdays).get(0).times()).isEqualTo(1);
        QSurcharge weekends = charge().origin("FRA")
                .destination("LHR")
                .scope(Scope.SECTOR)
                .days(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
                .build();
        assertThat(owed(thereAndBack(), weekends)).isEmpty();
    }

    @Test
    void aSurchargeForSomeBookingClassesReadsTheClassInAnyCase() {
        QSurcharge inK = charge().origin("FRA")
                .destination("LHR")
                .scope(Scope.SECTOR)
                .bookingClasses("k")
                .build();
        assertThat(inK.bookingClasses()).containsExactly("K");
        assertThat(owed(thereAndBack(), inK).get(0).times()).isEqualTo(2);
        QSurcharge inQ = charge().origin("FRA")
                .destination("LHR")
                .scope(Scope.SECTOR)
                .bookingClasses("Q")
                .build();
        assertThat(owed(thereAndBack(), inQ)).isEmpty();
    }

    @Test
    void aComponentSurchargeForSomeWeekdaysReadsTheFirstFlownSector() {
        FareComponent tuesday = priced(direct());
        assertThat(owed(tuesday, charge().days(DayOfWeek.FRIDAY).build())).isEmpty();
        assertThat(owed(tuesday, charge().days(DayOfWeek.TUESDAY).build())).hasSize(1);
        assertThat(owed(tuesday, charge().bookingClasses("K").build())).hasSize(1);
    }

    @Test
    void aSurfaceSectorIsReadOnlyByAFilingThatNamesNoDayOrClass() {
        FareComponent overland = priceComponent(
                new FareComponent(List.of(
                        segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
                        surface("LHR", "MAN"),
                        segment("EI", "203", "MAN", "DUB", "2025-03-06 09:00", "2025-03-06 10:00"))),
                fare("FRA", "DUB"),
                flatConverter,
                TICKETING);
        QSurcharge open =
                charge().origin("LON").destination("MAN").scope(Scope.SECTOR).build();
        assertThat(owed(overland, open).get(0).times()).isEqualTo(1);
        QSurcharge tuesdays = charge().origin("LON")
                .destination("MAN")
                .scope(Scope.SECTOR)
                .days(DayOfWeek.TUESDAY)
                .build();
        assertThat(owed(overland, tuesdays)).isEmpty();
    }

    @Test
    void aFilingSaysWhetherItIsHeldToDaysOrClasses() {
        assertThat(charge().build().isRestricted()).isFalse();
        assertThat(charge().days(DayOfWeek.MONDAY).build().isRestricted()).isTrue();
        assertThat(charge().bookingClasses("Y").build().isRestricted()).isTrue();
        assertThat(charge().build().readsSector(direct().segments().get(0))).isTrue();
    }
}
