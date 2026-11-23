package io.github.rkaushikdev.gussetline.discounts;

import static io.github.rkaushikdev.gussetline.discounts.Discounts.defaultDiscountStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class PartyTest {

    private static final LocalDate OUT = LocalDate.of(2025, 7, 1);
    private static final LocalDate HOME = LocalDate.of(2025, 7, 20);

    private static final Passenger ADULT = new Passenger(PassengerType.ADULT);
    private static final Passenger BABY = new Passenger(PassengerType.INFANT, LocalDate.of(2024, 9, 1));

    private static List<PassengerType> kinds(List<Passenger> ticketed) {
        return ticketed.stream().map(Passenger::kind).toList();
    }

    @Test
    void anAdultHoldsOneInfantOnTheLap() {
        Party family = new Party(List.of(ADULT, BABY));
        assertThat(kinds(family.ticketedOn(OUT))).containsExactly(PassengerType.ADULT, PassengerType.INFANT);
        assertThat(family.seatsOn(OUT)).isEqualTo(1);
    }

    @Test
    void aSecondLapInfantOnOneAdultTakesASeat() {
        Passenger twin = new Passenger(PassengerType.INFANT, LocalDate.of(2024, 9, 1));
        Party twins = new Party(List.of(ADULT, BABY, twin));
        assertThat(kinds(twins.ticketedOn(OUT)))
                .containsExactly(PassengerType.ADULT, PassengerType.INFANT, PassengerType.INFANT_WITH_SEAT);
        assertThat(twins.seatsOn(OUT)).isEqualTo(2);
        Party twoAdults = new Party(List.of(ADULT, BABY, twin, new Passenger(PassengerType.SENIOR)));
        assertThat(twoAdults.seatsOn(OUT)).isEqualTo(2);
    }

    @Test
    void aChildIsNotALapEvenWhenItNeedsAnAdult() {
        Passenger child = new Passenger(PassengerType.CHILD, LocalDate.of(2018, 1, 1));
        Passenger twin = new Passenger(PassengerType.INFANT, LocalDate.of(2024, 9, 1));
        Party party = new Party(List.of(ADULT, child, BABY, twin));
        assertThat(kinds(party.ticketedOn(OUT)))
                .containsExactly(
                        PassengerType.ADULT, PassengerType.CHILD, PassengerType.INFANT, PassengerType.INFANT_WITH_SEAT);
    }

    @Test
    void anInfantWhoTurnsTwoBeforeTheWayHomeFliesHomeInASeat() {
        Passenger turning = new Passenger(PassengerType.INFANT, LocalDate.of(2023, 7, 10));
        Party family = new Party(List.of(ADULT, turning));
        assertThat(kinds(family.ticketedOn(OUT))).containsExactly(PassengerType.ADULT, PassengerType.INFANT);
        assertThat(kinds(family.ticketedOn(HOME))).containsExactly(PassengerType.ADULT, PassengerType.CHILD);
        assertThat(family.ticketedOn(HOME).get(1).born()).isEqualTo(turning.born());
        assertThat(family.seatsOn(HOME)).isEqualTo(2);
    }

    @Test
    void anInfantWithNoBirthDateStaysOnTheLap() {
        Party family = new Party(List.of(ADULT, new Passenger(PassengerType.INFANT)));
        assertThat(kinds(family.ticketedOn(HOME))).containsExactly(PassengerType.ADULT, PassengerType.INFANT);
    }

    @Test
    void aBookingOfChildrenAloneIsRefused() {
        assertThatThrownBy(() -> new Party(List.of(new Passenger(PassengerType.CHILD), BABY)))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("somebody in charge");
        assertThatThrownBy(() -> new Party(List.of())).isInstanceOf(ValidationError.class);
    }

    @Test
    void aWholeBookingIsPricedAsItIsTicketed() {
        // One adult at 400, a lap infant at ten percent, and the twin in a seat at seventy five.
        Passenger twin = new Passenger(PassengerType.INFANT, LocalDate.of(2024, 9, 1));
        Party twins = new Party(List.of(ADULT, BABY, twin));
        assertThat(Discounts.partyNuc(new BigDecimal("400.00"), "LH", twins, OUT, defaultDiscountStore()))
                .isEqualByComparingTo("740.00");
    }

    @Test
    void theShippedTableKeepsItsAgeBands() {
        List<Discount> filed = defaultDiscountStore().forCarrier("BA");
        assertThat(filed).extracting(Discount::name).containsExactly("IN90", "IN25", "CH25", "YT10");
        assertThat(filed.get(0).minAge()).isEmpty();
        assertThat(filed.get(0).maxAge()).contains(1);
        assertThat(filed.get(2).minAge()).contains(2);
        assertThat(filed.get(3).maxAge()).contains(24);
    }
}
