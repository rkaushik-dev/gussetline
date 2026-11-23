package io.github.rkaushikdev.gussetline.taxes;

import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.Fixtures.surface;
import static io.github.rkaushikdev.gussetline.taxes.Taxes.assess;
import static io.github.rkaushikdev.gussetline.taxes.Taxes.carrierTotal;
import static io.github.rkaushikdev.gussetline.taxes.Taxes.defaultTaxTable;
import static io.github.rkaushikdev.gussetline.taxes.Taxes.governmentTotal;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TaxesTest {

    /** The German transport tax, with whatever a test changes about it. */
    private static RuleBuilder rule() {
        return new RuleBuilder();
    }

    @ParameterizedTest
    @ValueSource(strings = {"D", "DEUT", "d-", ""})
    void aCodeThatIsNotTwoOrThreeCharactersIsRefused(String code) {
        assertThatThrownBy(() -> rule().code(code).build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aTaxIsNotNegative() {
        assertThatThrownBy(() -> rule().amount("-1").build()).isInstanceOf(ValidationError.class);
    }

    @Test
    void aPercentageOverAHundredIsRefused() {
        assertThatThrownBy(
                        () -> rule().basis(Basis.PERCENT_OF_BASE).amount("101").build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aNegativeTransitBreakIsRefused() {
        assertThatThrownBy(() -> rule().exemptTransit(true).transitHours(-1).build())
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("transit break");
    }

    @Test
    void twoRulesAreEqualWhenTheirAmountsAreTheSameNumber() {
        TaxRule written = rule().amount("10.0").build();
        assertThat(written).isEqualTo(rule().amount("10.00").build());
        assertThat(written).hasSameHashCodeAs(rule().amount("10.00").build());
        assertThat(written).isNotEqualTo(rule().amount("10.01").build());
        assertThat(written)
                .isNotEqualTo(rule().amount("10.0").exemptTransit(true).build());
    }

    @Test
    void yqAndYrAreMarkedAsCarrierMoney() {
        assertThat(rule().code("YQ").country("ZZ").build().isCarrierImposed()).isTrue();
        assertThat(rule().code("YR").country("ZZ").build().isCarrierImposed()).isTrue();
        assertThat(rule().build().isCarrierImposed()).isFalse();
    }

    @Test
    void theTableKeepsCarrierRulesOutOfTheCountryIndex() {
        TaxTable table = new TaxTable();
        table.add(rule().build());
        table.add(rule().code("YQ").country("ZZ").build());
        assertThat(table.forCountry("DE")).hasSize(1);
        assertThat(table.carrierRules()).hasSize(1);
        assertThat(table.size()).isEqualTo(2);
    }

    @Test
    void aCountryWithNothingFiledAnswersEmpty() {
        assertThat(new TaxTable().forCountry("DE")).isEmpty();
    }

    private static Itinerary oneWay() {
        return new Itinerary(List.of(segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary connecting() {
        return new Itinerary(List.of(
                segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
    }

    private static Itinerary stoppingOver() {
        return new Itinerary(List.of(
                segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
                segment("LH", "400", "FRA", "JFK", "2025-03-06 10:25", "2025-03-06 13:05")));
    }

    @Test
    void aFlatDepartureTaxIsChargedOnce() {
        TaxTable table = new TaxTable();
        table.add(rule().build());
        List<AppliedTax> owed = assess(oneWay(), Money.of("500", "EUR"), table);
        assertThat(owed).hasSize(1);
        assertThat(owed.get(0).code()).isEqualTo("DE");
        assertThat(owed.get(0).amount()).isEqualTo(Money.of("70.83", "EUR"));
        assertThat(owed.get(0).point()).isEqualTo("FRA");
    }

    @Test
    void aDomesticSectorDoesNotOweTheInternationalDepartureTax() {
        TaxTable table = new TaxTable();
        table.add(rule().build());
        Itinerary domestic =
                new Itinerary(List.of(segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00")));
        assertThat(assess(domestic, Money.of("500", "EUR"), table)).isEmpty();
    }

    @Test
    void aConnectingPassengerIsExemptAndAStoppingOneIsNot() {
        TaxTable table = new TaxTable();
        table.add(rule().exemptTransit(true).build());
        assertThat(assess(connecting(), Money.of("500", "EUR"), table)).isEmpty();
        List<AppliedTax> charged = assess(stoppingOver(), Money.of("500", "EUR"), table);
        assertThat(charged).extracting(AppliedTax::code).containsExactly("DE");
    }

    @Test
    void theExemptionNeverAppliesAtThePointTheJourneyStarts() {
        TaxTable table = new TaxTable();
        table.add(rule().exemptTransit(true).build());
        List<AppliedTax> owed = assess(oneWay(), Money.of("500", "EUR"), table);
        assertThat(owed).hasSize(1);
    }

    @Test
    void withoutTheExemptionTheConnectionOwesItAnyway() {
        TaxTable table = new TaxTable();
        table.add(rule().exemptTransit(false).build());
        assertThat(assess(connecting(), Money.of("500", "EUR"), table)).hasSize(1);
    }

    @Test
    void aTaxReadsItsOwnTransitBreakRatherThanTheStopoverLine() {
        // Fourteen hours in Frankfurt is a connection for the fare, which draws its line at a day,
        // and a departure for a tax that only spares twelve.
        Itinerary overnight = new Itinerary(List.of(
                segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
                segment("LH", "400", "FRA", "JFK", "2025-03-04 21:00", "2025-03-04 23:40")));
        assertThat(overnight.stopovers()).isEmpty();
        TaxTable twelve = new TaxTable();
        twelve.add(rule().exemptTransit(true).transitHours(12).build());
        assertThat(assess(overnight, Money.of("500", "EUR"), twelve))
                .extracting(AppliedTax::point)
                .containsExactly("FRA");
        TaxTable day = new TaxTable();
        day.add(rule().exemptTransit(true).transitHours(24).build());
        assertThat(assess(overnight, Money.of("500", "EUR"), day)).isEmpty();
    }

    @Test
    void aDomesticStopoverForTheFareCanStillBeATransitForTheTax() {
        Itinerary domestic = new Itinerary(List.of(
                segment("UA", "100", "ORD", "DEN", "2025-03-04 06:00", "2025-03-04 08:00"),
                segment("UA", "200", "DEN", "LAX", "2025-03-04 15:00", "2025-03-04 16:30")));
        assertThat(domestic.stopovers()).containsExactly("DEN");
        TaxTable table = new TaxTable();
        table.add(rule().code("XF")
                .country("US")
                .applies(Applies.PER_DEPARTURE)
                .amount("4.50")
                .exemptTransit(true)
                .transitHours(12)
                .build());
        assertThat(assess(domestic, Money.of("500", "EUR"), table))
                .extracting(AppliedTax::point)
                .containsExactly("ORD");
    }

    @Test
    void aPointPassedTwiceIsReadAtEachVisit() {
        // Frankfurt is a connection on the way out and a two night stop on the way home. The security
        // charge is spared the first time and owed the second.
        Itinerary twice = new Itinerary(List.of(
                segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("LH", "401", "JFK", "FRA", "2025-03-18 18:30", "2025-03-19 08:05"),
                segment("LH", "11", "FRA", "MUC", "2025-03-21 09:00", "2025-03-21 10:00")));
        TaxTable table = new TaxTable();
        table.add(rule().code("RA")
                .applies(Applies.PER_DEPARTURE)
                .amount("10.00")
                .exemptTransit(true)
                .build());
        assertThat(assess(twice, Money.of("500", "EUR"), table))
                .extracting(AppliedTax::point)
                .containsExactly("MUC", "FRA");
    }

    @Test
    void anArrivalTaxChargesAConnectionLongerThanItsTransitBreak() {
        Itinerary through = new Itinerary(List.of(
                segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),
                segment("UA", "300", "JFK", "LAX", "2025-03-04 19:30", "2025-03-04 22:45")));
        TaxTable table = new TaxTable();
        table.add(rule().code("XA")
                .country("US")
                .applies(Applies.PER_ARRIVAL)
                .amount("7.00")
                .exemptTransit(true)
                .transitHours(4)
                .build());
        assertThat(assess(through, Money.of("500", "EUR"), table))
                .extracting(AppliedTax::point)
                .containsExactly("JFK", "LAX");
        TaxTable longer = new TaxTable();
        longer.add(rule().code("XA")
                .country("US")
                .applies(Applies.PER_ARRIVAL)
                .amount("7.00")
                .exemptTransit(true)
                .transitHours(8)
                .build());
        assertThat(assess(through, Money.of("500", "EUR"), longer))
                .extracting(AppliedTax::point)
                .containsExactly("LAX");
    }

    @Test
    void aSurfaceSectorIsNeitherBoardedNorATransit() {
        Itinerary overland = new Itinerary(List.of(
                segment("LH", "10", "MUC", "FRA", "2025-03-04 06:00", "2025-03-04 07:00"),
                surface("FRA", "DUS"),
                segment("LH", "400", "DUS", "JFK", "2025-03-04 10:25", "2025-03-04 13:05")));
        TaxTable table = new TaxTable();
        table.add(rule().code("RA")
                .applies(Applies.PER_DEPARTURE)
                .amount("10.00")
                .exemptTransit(true)
                .build());
        assertThat(assess(overland, Money.of("500", "EUR"), table))
                .extracting(AppliedTax::point)
                .containsExactly("MUC", "DUS");
    }

    @Test
    void theShippedFacilityChargeSparesOnlyAShortConnection() {
        TaxRule pfc = defaultTaxTable().forCountry("US").stream()
                .filter(rule -> rule.code().equals("XF"))
                .findFirst()
                .orElseThrow();
        assertThat(pfc.transitHours()).isEqualTo(Taxes.PFC_TRANSIT_HOURS);
    }

    @Test
    void aPercentageTaxReadsTheBaseFare() {
        TaxTable table = new TaxTable();
        table.add(rule().code("US")
                .country("DE")
                .basis(Basis.PERCENT_OF_BASE)
                .amount("7.5")
                .applies(Applies.PER_TICKET)
                .build());
        List<AppliedTax> owed = assess(oneWay(), Money.of("500", "EUR"), table);
        assertThat(owed.get(0).amount()).isEqualTo(Money.of("37.50", "EUR"));
    }

    @Test
    void aPerCouponTaxIsChargedOnEveryElement() {
        TaxTable table = new TaxTable();
        table.add(rule().code("ZP").applies(Applies.PER_COUPON).amount("5.00").build());
        List<AppliedTax> owed = assess(connecting(), Money.of("500", "EUR"), table);
        assertThat(owed).extracting(AppliedTax::point).containsExactly("MUC", "FRA");
    }

    @Test
    void aPerCouponTaxKeysOnTheCountryTheJourneyStartsIn() {
        // Filed by the United States, and this journey never departs from there, so no coupon on it
        // owes the charge however many sectors it has.
        TaxTable table = new TaxTable();
        table.add(rule().code("ZP")
                .country("US")
                .applies(Applies.PER_COUPON)
                .amount("5.00")
                .build());
        assertThat(assess(connecting(), Money.of("500", "EUR"), table)).isEmpty();
    }

    @Test
    void anArrivalTaxChargesAtTheFarEnd() {
        TaxTable table = new TaxTable();
        table.add(rule().code("XA")
                .country("US")
                .applies(Applies.PER_ARRIVAL)
                .amount("7.00")
                .build());
        List<AppliedTax> owed = assess(oneWay(), Money.of("500", "EUR"), table);
        assertThat(owed).extracting(AppliedTax::point).containsExactly("JFK");
    }

    @Test
    void theCarrierSurchargeRidesAlongAndStaysSeparate() {
        TaxTable table = new TaxTable();
        table.add(rule().build());
        table.add(rule().code("YQ")
                .country("ZZ")
                .amount("180.00")
                .applies(Applies.PER_TICKET)
                .build());
        List<AppliedTax> owed = assess(oneWay(), Money.of("500", "EUR"), table);
        assertThat(governmentTotal(owed, "EUR")).isEqualTo(Money.of("70.83", "EUR"));
        assertThat(carrierTotal(owed, "EUR")).isEqualTo(Money.of("180.00", "EUR"));
    }

    @Test
    void theTotalsAreZeroWhenNothingIsOwed() {
        assertThat(governmentTotal(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
        assertThat(carrierTotal(List.of(), "EUR")).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void anAppliedTaxReadsBackAsCodeAndAmount() {
        AppliedTax applied = new AppliedTax("DE", Money.of("70.83", "EUR"), "FRA");
        assertThat(applied).hasToString("DE 70.83 EUR");
    }

    @Test
    void theShippedTableCoversTheMarketsTheDeskSells() {
        TaxTable table = defaultTaxTable();
        assertThat(table.size()).isGreaterThan(8);
        assertThat(table.forCountry("US")).extracting(TaxRule::code).containsOnly("US", "ZP", "XF");
        assertThat(table.carrierRules()).extracting(TaxRule::code).containsExactly("YQ");
    }

    @Test
    void theShippedTablePricesAFrankfurtDeparture() {
        List<AppliedTax> owed = assess(oneWay(), Money.of("500", "EUR"), defaultTaxTable());
        List<String> codes = owed.stream().map(AppliedTax::code).sorted().toList();
        assertThat(codes).containsExactly("DE", "RA", "YQ");
    }

    /** A tax rule built from the German transport tax, one field changed at a time. */
    private static final class RuleBuilder {

        private String code = "DE";
        private String country = "DE";
        private Basis basis = Basis.FLAT;
        private BigDecimal amount = new BigDecimal("70.83");
        private Applies applies = Applies.PER_INTERNATIONAL_DEPARTURE;
        private boolean exemptTransit;
        private int transitHours = TaxRule.DEFAULT_TRANSIT_HOURS;

        RuleBuilder code(String value) {
            code = value;
            return this;
        }

        RuleBuilder country(String value) {
            country = value;
            return this;
        }

        RuleBuilder basis(Basis value) {
            basis = value;
            return this;
        }

        RuleBuilder amount(String value) {
            amount = new BigDecimal(value);
            return this;
        }

        RuleBuilder applies(Applies value) {
            applies = value;
            return this;
        }

        RuleBuilder exemptTransit(boolean value) {
            exemptTransit = value;
            return this;
        }

        RuleBuilder transitHours(int value) {
            transitHours = value;
            return this;
        }

        TaxRule build() {
            return new TaxRule(
                    code,
                    country,
                    "German air transport tax",
                    basis,
                    amount,
                    "EUR",
                    applies,
                    exemptTransit,
                    transitHours);
        }
    }
}
