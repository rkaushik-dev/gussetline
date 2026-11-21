package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.discounts.Passenger;
import io.github.rkaushikdev.gussetline.discounts.PassengerType;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RulesSalesTest {

    private static final LocalDate TICKETING = LocalDate.of(2025, 2, 14);

    private static FareComponent out() {
        return new FareComponent(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Context context() {
        return new Context(
                new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"))),
                out(),
                TICKETING);
    }

    private static Context context(Passenger passenger) {
        return context().withPassenger(passenger);
    }

    private static Eligibility.Builder eligibility() {
        return Eligibility.builder();
    }

    private static SalesRestriction.Builder restriction() {
        return SalesRestriction.builder();
    }

    @Test
    void aCountryOfSaleHasToBeOneWeKnow() {
        assertThatThrownBy(() -> new Sale("QQ")).isInstanceOf(ValidationError.class);
        assertThat(new Sale(" de ").country()).isEqualTo("DE");
    }

    @Test
    void anEligibilityFilingWithNothingInItDoesNotApply() {
        assertThat(new Eligibility().evaluate(context()).applied()).isFalse();
    }

    @Test
    void anUnknownResidencyCountryIsRefused() {
        assertThatThrownBy(() -> eligibility().residency("QQ").build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPassengerOfTheNamedTypeQualifies() {
        Eligibility rule =
                eligibility().passengerTypes(List.of(PassengerType.STUDENT)).build();
        Context student = context(new Passenger(PassengerType.STUDENT));
        assertThat(rule.evaluate(student).failed()).isFalse();
    }

    @Test
    void aPassengerOfAnotherTypeDoesNot() {
        Eligibility rule =
                eligibility().passengerTypes(List.of(PassengerType.STUDENT)).build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("wanted", List.of("STU"), "had", "ADT"));
    }

    @Test
    void anAgeConditionWithNoDateOfBirthFailsRatherThanAssuming() {
        Eligibility rule = eligibility().minAge(60).build();
        Outcome outcome = rule.evaluate(context(new Passenger(PassengerType.SENIOR)));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.message()).contains("no date of birth");
    }

    @Test
    void aPassengerInsideTheAgeRangeQualifies() {
        Eligibility rule = eligibility().minAge(60).build();
        Context senior = context(new Passenger(PassengerType.SENIOR, LocalDate.of(1955, 1, 1)));
        assertThat(rule.evaluate(senior).failed()).isFalse();
    }

    @Test
    void aPassengerUnderTheMinimumAgeDoesNot() {
        Eligibility rule = eligibility().minAge(60).build();
        Outcome outcome = rule.evaluate(context(new Passenger(PassengerType.SENIOR, LocalDate.of(1985, 1, 1))));
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("age", 40);
    }

    @Test
    void aPassengerOverTheMaximumAgeDoesNot() {
        Eligibility rule = eligibility().maxAge(24).build();
        Outcome outcome = rule.evaluate(context(new Passenger(PassengerType.YOUTH, LocalDate.of(1985, 1, 1))));
        assertThat(outcome.failed()).isTrue();
    }

    @Test
    void aResidencyConditionNeedsTheResidencyStated() {
        Eligibility rule = eligibility().residency("DE").build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("wanted", "DE", "had", "unstated"));
    }

    @Test
    void aResidentOfTheRightCountryQualifies() {
        Eligibility rule = eligibility().residency("DE").residentOf("DE").build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aResidentOfAnotherCountryDoesNot() {
        Eligibility rule = eligibility().residency("DE").residentOf("GB").build();
        assertThat(rule.evaluate(context()).failed()).isTrue();
    }

    @Test
    void aMembershipConditionFailsUntilItIsHeld() {
        Eligibility rule = eligibility().membership("senator").build();
        assertThat(rule.evaluate(context()).failed()).isTrue();
        Eligibility held =
                eligibility().membership("senator").holdsMembership(true).build();
        assertThat(held.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aSalesFilingWithNothingInItDoesNotApply() {
        assertThat(new SalesRestriction().evaluate(context()).applied()).isFalse();
    }

    @Test
    void aFilingNamesPermittedCountriesOrExcludedOnesAndNotBoth() {
        assertThatThrownBy(() -> restriction()
                        .countries(List.of("DE"))
                        .excludedCountries(List.of("GB"))
                        .build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void anUnknownCountryInAFilingIsRefused() {
        assertThatThrownBy(() -> restriction().countries(List.of("QQ")).build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRestrictedFareWithNoSaleDetailsFailsRatherThanPassing() {
        SalesRestriction rule = restriction().countries(List.of("DE")).build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.message()).contains("no sale details");
    }

    @Test
    void aSaleInAPermittedCountryPasses() {
        SalesRestriction rule =
                restriction().countries(List.of("DE")).sale(new Sale("DE")).build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aSaleInAnotherCountryFailsAndNamesBoth() {
        SalesRestriction rule =
                restriction().countries(List.of("DE")).sale(new Sale("GB")).build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("sold_in", "GB", "permitted", List.of("DE")));
    }

    @Test
    void anExcludedCountryFailsAndEverythingElsePasses() {
        SalesRestriction rule = restriction()
                .excludedCountries(List.of("US"))
                .sale(new Sale("US"))
                .build();
        assertThat(rule.evaluate(context()).failed()).isTrue();
        SalesRestriction elsewhere = restriction()
                .excludedCountries(List.of("US"))
                .sale(new Sale("DE"))
                .build();
        assertThat(elsewhere.evaluate(context()).failed()).isFalse();
    }

    @Test
    void anAgencyOffTheListFails() {
        SalesRestriction rule = restriction()
                .agencies(List.of("ABC123"))
                .sale(Sale.builder("DE").agency("XYZ999").build())
                .build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("agency", "XYZ999");
    }

    @Test
    void anAgencyOnTheListPassesWhateverCaseItWasGivenIn() {
        SalesRestriction rule = restriction()
                .agencies(List.of("abc123"))
                .sale(Sale.builder("DE").agency("AbC123").build())
                .build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aFormOfPaymentConditionIsChecked() {
        SalesRestriction rule = restriction()
                .payment(FormOfPayment.CARD)
                .sale(Sale.builder("DE").payment(FormOfPayment.CASH).build())
                .build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).isEqualTo(Map.of("wanted", "card", "had", "cash"));
    }

    @Test
    void aMatchingFormOfPaymentPasses() {
        SalesRestriction rule = restriction()
                .payment(FormOfPayment.CARD)
                .sale(Sale.builder("DE").payment(FormOfPayment.CARD).build())
                .build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }

    @Test
    void aSaleBeforeTheFareGoesOnSaleFails() {
        SalesRestriction rule = restriction()
                .firstSale(LocalDate.of(2025, 3, 1))
                .sale(new Sale("DE"))
                .build();
        Outcome outcome = rule.evaluate(context());
        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.details()).containsEntry("first_sale", "2025-03-01");
    }

    @Test
    void aSaleAfterTheFareComesOffFails() {
        SalesRestriction rule = restriction()
                .lastSale(LocalDate.of(2025, 1, 31))
                .sale(new Sale("DE"))
                .build();
        assertThat(rule.evaluate(context()).failed()).isTrue();
    }

    @Test
    void theSaleDateFallsBackToTheTicketingDate() {
        SalesRestriction rule = restriction()
                .lastSale(LocalDate.of(2025, 1, 31))
                .sale(Sale.builder("DE").on(LocalDate.of(2025, 1, 5)).build())
                .build();
        assertThat(rule.evaluate(context()).failed()).isFalse();
    }
}
