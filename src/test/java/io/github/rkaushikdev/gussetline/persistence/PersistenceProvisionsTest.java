package io.github.rkaushikdev.gussetline.persistence;

import static io.github.rkaushikdev.gussetline.persistence.Provisions.CODECS;
import static io.github.rkaushikdev.gussetline.persistence.Provisions.kindOf;
import static io.github.rkaushikdev.gussetline.persistence.Provisions.pack;
import static io.github.rkaushikdev.gussetline.persistence.Provisions.provisionToRow;
import static io.github.rkaushikdev.gussetline.persistence.Provisions.rowToProvision;
import static io.github.rkaushikdev.gussetline.persistence.Provisions.storable;
import static io.github.rkaushikdev.gussetline.persistence.Provisions.unpack;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.ProvisionRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.AdvancePurchase;
import io.github.rkaushikdev.gussetline.rules.Applies;
import io.github.rkaushikdev.gussetline.rules.Blackout;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.FormOfPayment;
import io.github.rkaushikdev.gussetline.rules.InstantPurchase;
import io.github.rkaushikdev.gussetline.rules.MaximumStay;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.SalesRestriction;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class PersistenceProvisionsTest {

    /**
     * Every provision the codecs claim to handle, with the awkward corners filled in rather than left
     * at their defaults. The round trip test walks this, so a field added to a provision and forgotten
     * in one direction fails here.
     */
    private static final List<Provision> EVERY_KIND = List.of(
            AdvancePurchase.builder()
                    .reservationDays(7)
                    .ticketingDaysAfterReservation(3)
                    .ticketingDaysBeforeDeparture(21)
                    .build(),
            new AdvancePurchase(),
            AdvancePurchase.builder().reservationDays(0).build(),
            new InstantPurchase(24),
            new InstantPurchase(),
            new MinimumStay(3),
            new MinimumStay("SUN"),
            MinimumStay.ofMonths(1),
            new MinimumStay(Optional.of(3), Optional.of("SUN"), Optional.of(2)),
            new MinimumStay(),
            MaximumStay.builder().days(30).build(),
            MaximumStay.builder().months(6).fromTurnaround(true).build(),
            new MaximumStay(),
            SalesRestriction.builder().countries(List.of("DE", "AT")).build(),
            SalesRestriction.builder()
                    .excludedCountries(List.of("US"))
                    .payment(FormOfPayment.CARD)
                    .build(),
            SalesRestriction.builder()
                    .firstSale(LocalDate.of(2025, 1, 1))
                    .lastSale(LocalDate.of(2025, 6, 30))
                    .build(),
            SalesRestriction.builder()
                    .agencies(List.of("IATA12345678", "IATA87654321"))
                    .build(),
            new SalesRestriction(),
            new Penalty(Action.CHANGE, Money.of("50.00", "EUR")),
            Penalty.builder()
                    .action(Action.CANCEL)
                    .beforeDeparture(Money.of("50.00", "EUR"))
                    .afterDeparture(Money.of("100.00", "EUR"))
                    .applies(Applies.PER_COMPONENT)
                    .noShowForfeits(true)
                    .build(),
            Penalty.builder().action(Action.NO_SHOW).permitted(false).build(),
            new Penalty(),
            new VoluntaryChange(),
            VoluntaryChange.builder()
                    .permitted(false)
                    .basis(RepricingBasis.HISTORICAL)
                    .residual(Residual.RETAINED)
                    .keepFareBreaks(true)
                    .sameCarrierOnly(true)
                    .onceOnly(true)
                    .build(),
            VoluntaryChange.builder().sameCarrierOnly(false).onceOnly(false).build());

    static Stream<Named<Provision>> everyKind() {
        return EVERY_KIND.stream()
                .map(provision -> Named.of(provision.getClass().getSimpleName(), provision));
    }

    @ParameterizedTest
    @MethodSource("everyKind")
    void everyProvisionSurvivesTheRoundTrip(Provision provision) {
        // The one test that catches a field filled on the way in and forgotten on the way out,
        // which otherwise reads empty for a year.
        assertThat(rowToProvision(provisionToRow(provision))).isEqualTo(provision);
    }

    @ParameterizedTest
    @MethodSource("everyKind")
    void everyProvisionKeepsItsCategoryOnTheRow(Provision provision) {
        ProvisionRow row = provisionToRow(provision);
        assertThat(row.getCategory()).isEqualTo(provision.category().value());
        assertThat(CODECS).containsKey(row.getKind());
        assertThat(row.getDetail()).isNotNull();
    }

    @Test
    void everyCodecNamesAProvisionClassThatExists() {
        CODECS.forEach((name, codec) -> {
            assertThat(Provision.class.isAssignableFrom(codec.type())).isTrue();
            assertThat(name).isEqualTo(name.toLowerCase(Locale.ROOT)).containsPattern("[a-z]");
        });
    }

    @Test
    void aProvisionNamesTheKindItStoresAs() {
        assertThat(kindOf(new MinimumStay(3))).isEqualTo("minimum_stay");
        assertThat(kindOf(new VoluntaryChange())).isEqualTo("voluntary_change");
    }

    @Test
    void aProvisionWithNoCodecIsRefusedByClassName() {
        assertThatThrownBy(() -> kindOf(new Blackout()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("Blackout");
    }

    @Test
    void whetherAProvisionCanBeStoredCanBeAskedWithoutRaising() {
        assertThat(storable(new MinimumStay(3))).isTrue();
        assertThat(storable(new Blackout())).isFalse();
    }

    @Test
    void aRowWithAKindWeDoNotKnowIsRefused() {
        ProvisionRow row = provisionToRow(new MinimumStay(3));
        row.setKind("nonsense");
        assertThatThrownBy(() -> rowToProvision(row))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("no codec");
    }

    @Test
    void theRuleIdIsSetWhenItIsGiven() {
        assertThat(provisionToRow(new MinimumStay(3), 7).getRuleId()).isEqualTo(7);
    }

    @Test
    void aMinimumStayPutsItsDaysInAColumnAReportCanGroupBy() {
        ProvisionRow row = provisionToRow(new MinimumStay(3));
        assertThat(row.getNumberOne()).contains(3);
        assertThat(row.getDetail()).isEmpty();
    }

    @Test
    void aSalesRestrictionPutsItsCountriesInColumnsAndTheRestInTheSpill() {
        // Somebody does want to ask which fares may be sold in Austria, so that is a column.
        // Nobody groups by the form of payment.
        ProvisionRow row = provisionToRow(SalesRestriction.builder()
                .countries(List.of("DE", "AT"))
                .payment(FormOfPayment.CARD)
                .build());
        assertThat(row.getTextOne()).isEqualTo("DE/AT");
        assertThat(row.getDetail()).contains("payment=card");
    }

    @Test
    void aPenaltyPutsTheFeeBeforeDepartureInTheAmountColumn() {
        ProvisionRow row = provisionToRow(new Penalty(Action.CHANGE, Money.of("50.00", "EUR")));
        assertThat(row.getAmount()).contains(Money.of("50.00", "EUR").amount());
        assertThat(row.getCurrency()).isEqualTo("EUR");
    }

    @Test
    void aPenaltyWithOnlyAnAfterAmountStillKeepsItsCurrency() {
        ProvisionRow row = provisionToRow(
                Penalty.builder().afterDeparture(Money.of("100.00", "GBP")).build());
        assertThat(row.getCurrency()).isEqualTo("GBP");
        Provision back = rowToProvision(row);
        assertThat(back).isInstanceOf(Penalty.class);
        assertThat(((Penalty) back).afterDeparture()).contains(Money.of("100.00", "GBP"));
    }

    @Test
    void theSpillWritesThePairsInASettledOrder() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("b", 2);
        values.put("a", 1);
        assertThat(pack(values)).isEqualTo("a=1;b=2");
    }

    @Test
    void theSpillLeavesOutWhatIsNotThere() {
        Map<String, Object> values = new HashMap<>();
        values.put("a", null);
        values.put("b", "");
        values.put("c", List.of());
        values.put("d", 1);
        assertThat(pack(values)).isEqualTo("d=1");
    }

    @Test
    void theSpillWritesADateTheWayItReadsOne() {
        assertThat(pack(Map.of("first", LocalDate.of(2025, 1, 1)))).isEqualTo("first=2025-01-01");
    }

    @Test
    void theSpillRefusesAValueCarryingItsOwnSeparator() {
        assertThatThrownBy(() -> pack(Map.of("a", "one;two")))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("separator");
        assertThatThrownBy(() -> pack(Map.of("a", "one=two")))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("separator");
    }

    @Test
    void theSpillReadsBackWhatItWrote() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("a", 1);
        values.put("b", "two");
        assertThat(unpack(pack(values))).isEqualTo(Map.of("a", "1", "b", "two"));
    }

    @Test
    void anEmptySpillReadsAsNothing() {
        assertThat(unpack("")).isEmpty();
    }

    @Test
    void aSpillWithRubbishInItYieldsWhatItCan() {
        assertThat(unpack("a=1;nonsense;b=2")).isEqualTo(Map.of("a", "1", "b", "2"));
    }

    @Test
    void aValueMayCarryAnEqualsWhenItComesBack() {
        // Writing one is refused, but a row written by an older version might.
        assertThat(unpack("a=one=two")).isEqualTo(Map.of("a", "one=two"));
    }

    @Test
    void aZeroIsKeptAndNotMistakenForNothing() {
        // A category 5 filed at zero days says the ticket is bought on the day of departure.
        // Dropping it would say there is no advance purchase at all.
        ProvisionRow row = provisionToRow(
                AdvancePurchase.builder().ticketingDaysBeforeDeparture(0).build());
        Provision back = rowToProvision(row);
        assertThat(back).isInstanceOf(AdvancePurchase.class);
        assertThat(((AdvancePurchase) back).ticketingDaysBeforeDeparture()).contains(0);
    }

    @Test
    void theCategoriesStoredCoverTheOnesTheRepriceNeeds() {
        Set<Integer> stored = EVERY_KIND.stream()
                .map(provision -> provision.category().value())
                .collect(Collectors.toSet());
        assertThat(stored)
                .contains(
                        Category.ADVANCE_RESERVATION.value(),
                        Category.MINIMUM_STAY.value(),
                        Category.MAXIMUM_STAY.value(),
                        Category.PENALTIES.value(),
                        Category.VOLUNTARY_CHANGES.value());
    }
}
