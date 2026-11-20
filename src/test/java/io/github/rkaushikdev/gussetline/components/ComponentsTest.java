package io.github.rkaushikdev.gussetline.components;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.JourneyType;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentsTest {

    private static Segment outSector() {
        return segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05");
    }

    private static Segment homeSector() {
        return segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05");
    }

    private static Segment feeder() {
        return segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40");
    }

    private static Fare aFare() {
        return Fare.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .fareBasis("KLWAP7RT")
                .amount(Money.of("420.00", "EUR"))
                .owrt(OwRt.ROUND_TRIP_HALF)
                .globalIndicator(GlobalIndicator.AT)
                .ruleNumber("1100")
                .routingNumber("0001")
                .build();
    }

    @Test
    void aComponentNeedsASector() {
        assertThatThrownBy(() -> new FareComponent(List.of())).isInstanceOf(ValidationError.class);
    }

    @Test
    void aComponentHasToJoinUp() {
        assertThatThrownBy(() -> new FareComponent(List.of(outSector(), outSector())))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void boardAndOffPointsAreCities() {
        FareComponent c = new FareComponent(List.of(outSector()));
        assertThat(c.boardPoint()).isEqualTo("FRA");
        assertThat(c.offPoint()).isEqualTo("NYC");
    }

    @Test
    void viaPointsAreTheInteriorOnly() {
        FareComponent c = new FareComponent(
                List.of(feeder(), segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00")));
        assertThat(c.viaPoints()).containsExactly("LHR");
        assertThat(c.origin()).isEqualTo("FRA");
        assertThat(c.destination()).isEqualTo("JFK");
    }

    @Test
    void theIndicatorComesOffTheComponentAndNotTheJourney() {
        FareComponent c = new FareComponent(List.of(outSector()));
        assertThat(c.indicator()).isSameAs(GlobalIndicator.AT);
    }

    @Test
    void theGoverningCarrierIsTheFirstInternationalSector() {
        FareComponent c = new FareComponent(List.of(
                segment("LH", "10", "FRA", "MUC", "2025-03-04 06:00", "2025-03-04 07:00"),
                segment("LX", "400", "MUC", "JFK", "2025-03-04 09:00", "2025-03-04 12:30")));
        assertThat(c.carriers()).containsExactly("LH", "LX");
        assertThat(c.governingCarrier()).isEqualTo("LX");
    }

    @Test
    void aWhollyDomesticComponentTakesItsFirstCarrier() {
        FareComponent c = new FareComponent(List.of(
                segment("UA", "100", "JFK", "ORD", "2025-03-04 08:00", "2025-03-04 09:45"),
                segment("AA", "200", "ORD", "LAX", "2025-03-04 11:00", "2025-03-04 13:30")));
        assertThat(c.governingCarrier()).isEqualTo("UA");
    }

    @Test
    void aComponentOfOnlySurfaceHasNoGoverningCarrier() {
        FareComponent c = new FareComponent(List.of(surface("JFK", "BOS")));
        assertThatThrownBy(c::governingCarrier).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aComponentKnowsWhenItCarriesSurface() {
        assertThat(new FareComponent(List.of(outSector())).hasSurface()).isFalse();
        assertThat(new FareComponent(List.of(outSector(), surface("JFK", "BOS"))).hasSurface())
                .isTrue();
    }

    @Test
    void anUnpricedComponentRefusesToTotal() {
        assertThatThrownBy(() -> new FareComponent(List.of(outSector())).totalNuc())
                .isInstanceOf(ConstructionError.class);
    }

    @Test
    void pricingAComponentReturnsANewOne() {
        FareComponent plain = new FareComponent(List.of(outSector()));
        FareComponent priced = plain.pricedWith(aFare(), new BigDecimal("441.64"));
        assertThat(plain.nuc()).isEmpty();
        assertThat(priced.nuc().orElseThrow()).isEqualByComparingTo("441.64");
        assertThat(priced.totalNuc()).isEqualByComparingTo("441.64");
        assertThat(priced.fare()).isPresent();
    }

    @Test
    void aSurchargeAddsToTheTotalAndLeavesANote() {
        FareComponent priced = new FareComponent(List.of(outSector())).pricedWith(aFare(), new BigDecimal("441.64"));
        FareComponent surcharged = priced.withSurcharge(new BigDecimal("22.08"), "5M");
        assertThat(surcharged.totalNuc()).isEqualByComparingTo("463.72");
        assertThat(surcharged.notes()).containsExactly("5M");
        assertThat(priced.totalNuc()).isEqualByComparingTo("441.64");
    }

    @Test
    void surchargesAccumulateAndKeepBothNotes() {
        FareComponent c = new FareComponent(List.of(outSector()))
                .pricedWith(aFare(), new BigDecimal("400.00"))
                .withSurcharge(new BigDecimal("20.00"), "5M")
                .withSurcharge(new BigDecimal("10.00"), "Q");
        assertThat(c.totalNuc()).isEqualByComparingTo("430.00");
        assertThat(c.notes()).containsExactly("5M", "Q");
    }

    @Test
    void stringFormSaysUnpricedUntilItIsNot() {
        assertThat(new FareComponent(List.of(outSector()))).hasToString("FRANYC unpriced");
        FareComponent priced = new FareComponent(List.of(outSector())).pricedWith(aFare(), new BigDecimal("441.64"));
        assertThat(priced).hasToString("FRANYC KLWAP7RT");
    }

    private static FareComponent priced(List<? extends Element> elements) {
        return priced(elements, "441.64");
    }

    private static FareComponent priced(List<? extends Element> elements, String amount) {
        return new FareComponent(elements).pricedWith(aFare(), new BigDecimal(amount));
    }

    @Test
    void aRoundTripUnitIsExactlyTwoComponents() {
        PricingUnit unit = new PricingUnit(
                List.of(priced(List.of(outSector())), priced(List.of(homeSector()))), JourneyType.ROUND_TRIP);
        assertThat(unit.size()).isEqualTo(2);
        assertThat(unit.turnaround()).isEqualTo("JFK");
        assertThat(unit.totalNuc()).isEqualByComparingTo("883.28");
    }

    @Test
    void aRoundTripUnitRefusesAThirdComponent() {
        assertThatThrownBy(() -> new PricingUnit(
                        List.of(
                                priced(List.of(outSector())),
                                priced(List.of(homeSector())),
                                priced(List.of(outSector()))),
                        JourneyType.ROUND_TRIP))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aOneWayUnitIsExactlyOneComponentAndDoesNotTurnRound() {
        PricingUnit unit = new PricingUnit(List.of(priced(List.of(outSector()))), JourneyType.ONE_WAY);
        assertThat(unit.origin()).isEqualTo("FRA");
        assertThat(unit.destination()).isEqualTo("JFK");
        assertThatThrownBy(unit::turnaround).isInstanceOf(ConstructionError.class);
    }

    @Test
    void aOneWayUnitRefusesASecondComponent() {
        assertThatThrownBy(() -> new PricingUnit(
                        List.of(priced(List.of(outSector())), priced(List.of(homeSector()))), JourneyType.ONE_WAY))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aCircleTripUnitNeedsThree() {
        assertThatThrownBy(() -> new PricingUnit(
                        List.of(priced(List.of(outSector())), priced(List.of(homeSector()))), JourneyType.CIRCLE_TRIP))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void anEmptyUnitIsRefused() {
        assertThatThrownBy(() -> new PricingUnit(List.of(), JourneyType.ONE_WAY))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void replacingAComponentLeavesTheOriginalAlone() {
        PricingUnit unit = new PricingUnit(
                List.of(priced(List.of(outSector())), priced(List.of(homeSector()))), JourneyType.ROUND_TRIP);
        FareComponent dearer = unit.components().get(0).withSurcharge(new BigDecimal("50.00"), "HIP");
        PricingUnit updated = unit.replace(0, dearer);
        assertThat(updated.totalNuc()).isEqualByComparingTo("933.28");
        assertThat(unit.totalNuc()).isEqualByComparingTo("883.28");
    }

    @Test
    void isPricedReadsEveryComponent() {
        PricingUnit half = new PricingUnit(
                List.of(priced(List.of(outSector())), new FareComponent(List.of(homeSector()))),
                JourneyType.ROUND_TRIP);
        assertThat(half.isPriced()).isFalse();
    }

    @Test
    void splitCutsAtTheBreakPoints() {
        List<Segment> elements = List.of(
                feeder(),
                segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
                segment("LH", "401", "JFK", "LHR", "2025-03-18 18:30", "2025-03-19 06:30"),
                segment("LH", "993", "LHR", "FRA", "2025-03-19 09:00", "2025-03-19 11:35"));
        List<FareComponent> parts = Components.splitAt(elements, List.of(1));
        assertThat(parts).hasSize(2);
        assertThat(List.of(parts.get(0).origin(), parts.get(0).destination())).containsExactly("FRA", "JFK");
        assertThat(List.of(parts.get(1).origin(), parts.get(1).destination())).containsExactly("JFK", "FRA");
    }

    @Test
    void splitWithNoBreakGivesOneComponent() {
        List<FareComponent> parts = Components.splitAt(List.of(outSector()), List.of());
        assertThat(parts).hasSize(1);
    }

    @Test
    void breakPointsHaveToClimb() {
        List<Segment> elements =
                List.of(feeder(), segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"));
        assertThatThrownBy(() -> Components.splitAt(elements, List.of(1, 0))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBreakPastTheEndIsRefused() {
        assertThatThrownBy(() -> Components.splitAt(List.of(outSector(), homeSector()), List.of(1)))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void splittingNothingIsRefused() {
        assertThatThrownBy(() -> Components.splitAt(List.of(), List.of())).isInstanceOf(ValidationError.class);
    }

    @Test
    void aChargeReadsBackAsItsReasonAndAmount() {
        assertThat(new Charge("5M", new BigDecimal("22.08"))).hasToString("5M 22.08");
    }

    @Test
    void chargesAreKeptApartAndTheTotalStillAddsUp() {
        FareComponent c = new FareComponent(List.of(outSector()))
                .pricedWith(aFare(), new BigDecimal("400.00"))
                .withSurcharge(new BigDecimal("20.00"), "5M")
                .withSurcharge(new BigDecimal("30.00"), "Q");
        assertThat(c.charges())
                .containsExactly(new Charge("5M", new BigDecimal("20.00")), new Charge("Q", new BigDecimal("30.00")));
        assertThat(c.surcharges()).isEqualByComparingTo("50.00");
        assertThat(c.totalNuc()).isEqualByComparingTo("450.00");
    }

    @Test
    void oneReasonCanBeReadBackOnItsOwn() {
        FareComponent c = new FareComponent(List.of(outSector()))
                .pricedWith(aFare(), new BigDecimal("400.00"))
                .withSurcharge(new BigDecimal("20.00"), "5M")
                .withSurcharge(new BigDecimal("30.00"), "Q");
        assertThat(c.chargeFor("Q")).isEqualByComparingTo("30.00");
        assertThat(c.chargeFor("5M")).isEqualByComparingTo("20.00");
        assertThat(c.chargeFor("HIP")).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void theSameReasonTwiceAddsTogether() {
        FareComponent c = new FareComponent(List.of(outSector()))
                .pricedWith(aFare(), new BigDecimal("400.00"))
                .withSurcharge(new BigDecimal("20.00"), "Q")
                .withSurcharge(new BigDecimal("12.00"), "Q");
        assertThat(c.chargeFor("Q")).isEqualByComparingTo("32.00");
        assertThat(c.notes()).containsExactly("Q", "Q");
    }

    @Test
    void pricingAComponentKeepsChargesAlreadyOnIt() {
        FareComponent plain = new FareComponent(List.of(outSector())).withSurcharge(new BigDecimal("20.00"), "5M");
        FareComponent priced = plain.pricedWith(aFare(), new BigDecimal("400.00"));
        assertThat(priced.chargeFor("5M")).isEqualByComparingTo("20.00");
        assertThat(priced.totalNuc()).isEqualByComparingTo("420.00");
    }

    @Test
    void aComponentWithNoChargesHasNoneOfEither() {
        FareComponent plain = new FareComponent(List.of(outSector()));
        assertThat(plain.charges()).isEmpty();
        assertThat(plain.notes()).isEmpty();
        assertThat(plain.surcharges()).isEqualByComparingTo(BigDecimal.ZERO);
    }
}
