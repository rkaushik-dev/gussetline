package io.github.rkaushikdev.gussetline.discounts;

import static io.github.rkaushikdev.gussetline.discounts.Discounts.defaultDiscountStore;
import static io.github.rkaushikdev.gussetline.discounts.Discounts.discountedNuc;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class DiscountsTest {

    private static final LocalDate DEPARTURE = LocalDate.of(2025, 3, 4);

    private static BigDecimal percent(int value) {
        return BigDecimal.valueOf(value);
    }

    @Test
    void anInfantDoesNotOccupyASeatAndEverybodyElseDoes() {
        assertThat(PassengerType.INFANT.occupiesSeat()).isFalse();
        assertThat(PassengerType.INFANT_WITH_SEAT.occupiesSeat()).isTrue();
        assertThat(PassengerType.CHILD.occupiesSeat()).isTrue();
    }

    @Test
    void childrenAndInfantsNeedAnAdultAndYouthsDoNot() {
        assertThat(PassengerType.CHILD.needsAdult()).isTrue();
        assertThat(PassengerType.INFANT.needsAdult()).isTrue();
        assertThat(PassengerType.YOUTH.needsAdult()).isFalse();
        assertThat(PassengerType.ADULT.needsAdult()).isFalse();
    }

    @Test
    void ageIsWholeYearsOnTheDateAskedFor() {
        Passenger born = new Passenger(PassengerType.CHILD, LocalDate.of(2013, 6, 15));
        assertThat(born.ageOn(LocalDate.of(2025, 6, 14))).contains(11);
        assertThat(born.ageOn(LocalDate.of(2025, 6, 15))).contains(12);
        assertThat(born.ageOn(LocalDate.of(2025, 6, 16))).contains(12);
    }

    @Test
    void aPassengerWithNoBirthDateHasNoAge() {
        assertThat(new Passenger(PassengerType.CHILD).ageOn(DEPARTURE)).isEmpty();
    }

    @Test
    void aPassengerReadsBackAsItsCode() {
        assertThat(new Passenger(PassengerType.CHILD)).hasToString("CHD");
    }

    @Test
    void aDiscountIsBetweenNothingAndEverything() {
        assertThatThrownBy(() -> new Discount("LH", PassengerType.CHILD, percent(-1)))
                .isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new Discount("LH", PassengerType.CHILD, percent(101)))
                .isInstanceOf(ValidationError.class);
        assertThat(new Discount("LH", PassengerType.CHILD, percent(0)).multiplier())
                .isEqualByComparingTo("1");
        assertThat(new Discount("LH", PassengerType.CHILD, percent(100)).multiplier())
                .isEqualByComparingTo("0");
    }

    @Test
    void anAgeRangeThatClosesBeforeItOpensIsRefused() {
        assertThatThrownBy(() -> new Discount("LH", PassengerType.CHILD, percent(25))
                        .withMinAge(11)
                        .withMaxAge(2))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void theMultiplierAndTheAmountAgree() {
        Discount child = new Discount("LH", PassengerType.CHILD, percent(25));
        assertThat(child.multiplier()).isEqualByComparingTo("0.75");
        assertThat(child.applyTo(new BigDecimal("441.64"))).isEqualByComparingTo("331.23");
    }

    @Test
    void aNinetyPercentDiscountLeavesATenth() {
        Discount infant = new Discount("LH", PassengerType.INFANT, percent(90));
        assertThat(infant.applyTo(new BigDecimal("441.64"))).isEqualByComparingTo("44.16");
    }

    @Test
    void aFilingCoversOnlyItsOwnPassengerType() {
        Discount child = new Discount("LH", PassengerType.CHILD, percent(25));
        assertThat(child.covers(new Passenger(PassengerType.CHILD), DEPARTURE)).isTrue();
        assertThat(child.covers(new Passenger(PassengerType.INFANT), DEPARTURE)).isFalse();
    }

    @Test
    void aFilingWithNoAgeRangeCoversAnybodyOfThatType() {
        Discount senior = new Discount("LH", PassengerType.SENIOR, percent(10));
        assertThat(senior.covers(new Passenger(PassengerType.SENIOR), DEPARTURE))
                .isTrue();
    }

    @Test
    void anAgeRangeIsReadOnTheDepartureDate() {
        Discount child = new Discount("LH", PassengerType.CHILD, percent(25))
                .withMinAge(2)
                .withMaxAge(11);
        Passenger turningTwelve = new Passenger(PassengerType.CHILD, LocalDate.of(2013, 6, 15));
        assertThat(child.covers(turningTwelve, LocalDate.of(2025, 6, 14))).isTrue();
        assertThat(child.covers(turningTwelve, LocalDate.of(2025, 6, 15))).isFalse();
    }

    @Test
    void anAgeRangeAndNoBirthDateDoNotMatch() {
        Discount child = new Discount("LH", PassengerType.CHILD, percent(25))
                .withMinAge(2)
                .withMaxAge(11);
        assertThat(child.covers(new Passenger(PassengerType.CHILD), DEPARTURE)).isFalse();
    }

    @Test
    void aPassengerUnderTheRangeIsNotCovered() {
        Discount child = new Discount("LH", PassengerType.CHILD, percent(25))
                .withMinAge(2)
                .withMaxAge(11);
        assertThat(child.covers(new Passenger(PassengerType.CHILD, LocalDate.of(2024, 6, 15)), DEPARTURE))
                .isFalse();
    }

    @Test
    void aDiscountReadsBackWithItsDesignator() {
        assertThat(new Discount("LH", PassengerType.CHILD, percent(25), "CH25")).hasToString("LH CH25 25 percent");
        assertThat(new Discount("LH", PassengerType.CHILD, percent(25))).hasToString("LH CHD 25 percent");
    }

    @Test
    void theStoreKeepsTheOrderItWasLoadedIn() {
        DiscountStore store = new DiscountStore();
        Discount narrow = store.add(new Discount("LH", PassengerType.CHILD, percent(50), "CH50").withMaxAge(4));
        store.add(new Discount("LH", PassengerType.CHILD, percent(25), "CH25").withMaxAge(11));
        assertThat(store.find("LH", new Passenger(PassengerType.CHILD, LocalDate.of(2022, 1, 1)), DEPARTURE))
                .containsSame(narrow);
    }

    @Test
    void aPassengerOutsideTheFirstFilingFallsToTheSecond() {
        DiscountStore store = new DiscountStore();
        store.add(new Discount("LH", PassengerType.CHILD, percent(50), "CH50").withMaxAge(4));
        Discount wide = store.add(new Discount("LH", PassengerType.CHILD, percent(25), "CH25").withMaxAge(11));
        assertThat(store.find("LH", new Passenger(PassengerType.CHILD, LocalDate.of(2016, 1, 1)), DEPARTURE))
                .containsSame(wide);
    }

    @Test
    void anotherCarriersFilingDoesNotApply() {
        DiscountStore store = new DiscountStore();
        store.add(new Discount("LH", PassengerType.CHILD, percent(25)));
        assertThat(store.find("BA", new Passenger(PassengerType.CHILD), DEPARTURE))
                .isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void anAdultIsNeverDiscounted() {
        DiscountStore store = new DiscountStore();
        store.add(new Discount("LH", PassengerType.CHILD, percent(25)));
        BigDecimal nuc =
                discountedNuc(new BigDecimal("441.64"), "LH", new Passenger(PassengerType.ADULT), DEPARTURE, store);
        assertThat(nuc).isEqualByComparingTo("441.64");
    }

    @Test
    void aChildWithAFilingPaysTheDiscountedFare() {
        DiscountStore store = new DiscountStore();
        store.add(new Discount("LH", PassengerType.CHILD, percent(25), "CH25"));
        Trace trace = new Trace();
        BigDecimal nuc = discountedNuc(
                new BigDecimal("441.64"), "LH", new Passenger(PassengerType.CHILD), DEPARTURE, store, trace);
        assertThat(nuc).isEqualByComparingTo("331.23");
        assertThat(trace.forStage(Stage.SELECTION).get(0).details().get("designator"))
                .isEqualTo("CH25");
    }

    @Test
    void aChildWithNoFilingPaysTheAdultFareAndItIsNoted() {
        Trace trace = new Trace();
        BigDecimal nuc = discountedNuc(
                new BigDecimal("441.64"),
                "LH",
                new Passenger(PassengerType.CHILD),
                DEPARTURE,
                new DiscountStore(),
                trace);
        assertThat(nuc).isEqualByComparingTo("441.64");
        assertThat(trace.forStage(Stage.SELECTION).get(0).message()).contains("no CHD discount filed");
        assertThat(trace.failed()).isFalse();
    }

    @Test
    void theShippedStorePricesTheUsualFourTypes() {
        DiscountStore store = defaultDiscountStore();
        Passenger infant = new Passenger(PassengerType.INFANT, LocalDate.of(2024, 6, 1));
        Passenger child = new Passenger(PassengerType.CHILD, LocalDate.of(2017, 6, 1));
        Passenger youth = new Passenger(PassengerType.YOUTH, LocalDate.of(2007, 6, 1));
        assertThat(discountedNuc(new BigDecimal("400.00"), "LH", infant, DEPARTURE, store))
                .isEqualByComparingTo("40.00");
        assertThat(discountedNuc(new BigDecimal("400.00"), "LH", child, DEPARTURE, store))
                .isEqualByComparingTo("300.00");
        assertThat(discountedNuc(new BigDecimal("400.00"), "LH", youth, DEPARTURE, store))
                .isEqualByComparingTo("360.00");
    }

    @Test
    void theShippedStoreCoversTheCarriersTheDeskSells() {
        DiscountStore store = defaultDiscountStore();
        assertThat(store.forCarrier("LH")).hasSize(4);
        assertThat(store.forCarrier("EK")).isEmpty();
    }

    @Test
    void anInfantWithASeatIsADifferentDiscountFromOneWithout() {
        DiscountStore store = defaultDiscountStore();
        Passenger lap = new Passenger(PassengerType.INFANT, LocalDate.of(2024, 6, 1));
        Passenger seated = new Passenger(PassengerType.INFANT_WITH_SEAT, LocalDate.of(2024, 6, 1));
        assertThat(discountedNuc(new BigDecimal("400.00"), "LH", lap, DEPARTURE, store))
                .isEqualByComparingTo("40.00");
        assertThat(discountedNuc(new BigDecimal("400.00"), "LH", seated, DEPARTURE, store))
                .isEqualByComparingTo("300.00");
    }
}
