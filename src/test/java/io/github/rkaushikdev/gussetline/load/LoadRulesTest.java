package io.github.rkaushikdev.gussetline.load;

import static io.github.rkaushikdev.gussetline.load.RuleFeed.apply;
import static io.github.rkaushikdev.gussetline.load.RuleFeed.categoriesOf;
import static io.github.rkaushikdev.gussetline.load.RuleFeed.loadInto;
import static io.github.rkaushikdev.gussetline.load.RuleFeed.missingCategories;
import static io.github.rkaushikdev.gussetline.load.RuleFeed.read;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.RecordSpec;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.AdvancePurchase;
import io.github.rkaushikdev.gussetline.rules.Applies;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.FormOfPayment;
import io.github.rkaushikdev.gussetline.rules.MaximumStay;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.rules.SalesRestriction;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class LoadRulesTest {

    /**
     * One record of a rule file, at the values most tests want. Each {@link #with} sets one field by
     * its name in the record spec before the record is written; a null leaves the field blank.
     */
    private static final class Line {

        private final RecordSpec spec;
        private final Map<String, Object> values = new HashMap<>();

        private Line(RecordSpec spec) {
            this.spec = spec;
        }

        Line with(String field, Object value) {
            values.put(field, value);
            return this;
        }

        String write() {
            return spec.write(values);
        }
    }

    /** A header filing rule 0100 of LH in tariff 001. */
    private static Line head() {
        return new Line(RuleFeed.RULE_HEADER)
                .with("carrier", "LH")
                .with("number", "0100")
                .with("tariff", "001")
                .with("action", "A");
    }

    /** Seven days of advance reservation, and no ticketing periods. */
    private static Line advance() {
        return new Line(RuleFeed.ADVANCE).with("reservation_days", 7);
    }

    /** A minimum stay with nothing in it yet. */
    private static Line minimum() {
        return new Line(RuleFeed.MINIMUM);
    }

    /** A maximum stay of six months from origin. */
    private static Line maximum() {
        return new Line(RuleFeed.MAXIMUM).with("months", 6);
    }

    /** A sales restriction with nothing in it yet. */
    private static Line sales() {
        return new Line(RuleFeed.SALES);
    }

    /** A permitted change at fifty euro before departure and a hundred after. */
    private static Line penalty() {
        return new Line(RuleFeed.PENALTY)
                .with("action", "C")
                .with("permitted", "Y")
                .with("currency", "EUR")
                .with("before", new BigDecimal("50.00"))
                .with("after", new BigDecimal("100.00"));
    }

    /** Changes permitted, priced from the current tariff, the residual refundable. */
    private static Line changes() {
        return new Line(RuleFeed.CHANGES)
                .with("permitted", "Y")
                .with("basis", "C")
                .with("residual", "R");
    }

    private static String fileOf(Line... lines) {
        return Arrays.stream(lines).map(Line::write).collect(Collectors.joining("\n"));
    }

    /** The first provision of the first rule, which has to be of the type asked for. */
    private static <P extends Provision> P firstProvision(RuleLoad load, Class<P> type) {
        Provision provision = load.rules().get(0).provisions().get(0);
        assertThat(provision).isInstanceOf(type);
        return type.cast(provision);
    }

    @Test
    void anEmptyFileReadsAsNoRules() {
        RuleLoad load = read("");
        assertThat(load.size()).isZero();
        assertThat(load.clean()).isTrue();
    }

    @Test
    void aHeaderOnItsOwnIsARuleWithNoConditions() {
        // Not a mistake. An unrestricted fare points at exactly this, and skipping
        // it leaves the previous version of the rule in place with its conditions.
        RuleLoad load = read(fileOf(head()));
        assertThat(load.size()).isEqualTo(1);
        assertThat(load.rules().get(0).provisions()).isEmpty();
        assertThat(load.rules().get(0).carrier()).isEqualTo("LH");
        assertThat(load.rules().get(0).number()).isEqualTo("0100");
    }

    @Test
    void theRecordsAfterAHeaderBelongToThatHeader() {
        RuleLoad load = read(fileOf(head(), advance(), minimum().with("days", 3)));
        RuleSet rule = load.rules().get(0);
        assertThat(rule.size()).isEqualTo(2);
        assertThat(rule.has(Category.ADVANCE_RESERVATION)).isTrue();
        assertThat(rule.has(Category.MINIMUM_STAY)).isTrue();
    }

    @Test
    void aMinimumStayCanBeFiledInMonths() {
        RuleLoad load =
                read(fileOf(head(), minimum().with("months", 1), minimum().with("days", 3)));
        List<Provision> filed = load.rules().get(0).forCategory(Category.MINIMUM_STAY);
        assertThat(filed).containsExactly(MinimumStay.ofMonths(1), new MinimumStay(3));
        assertThat(load.problems()).isEmpty();
    }

    @Test
    void aPenaltyCanBeFiledAsAPercentageOfTheFare() {
        RuleLoad load = read(fileOf(head(), penalty().with("percent", new BigDecimal("10.00"))));
        Penalty filed =
                (Penalty) load.rules().get(0).forCategory(Category.PENALTIES).get(0);
        assertThat(filed.percent()).contains(new BigDecimal("10.00"));
        assertThat(filed.beforeDeparture()).contains(Money.of("50.00", "EUR"));
        assertThat(load.problems()).isEmpty();
    }

    @Test
    void aSecondHeaderClosesTheFirstRule() {
        RuleLoad load = read(fileOf(
                head().with("number", "0100"),
                advance(),
                head().with("number", "0200"),
                minimum().with("days", 3)));
        assertThat(load.size()).isEqualTo(2);
        assertThat(load.get("LH", "0100")).isPresent();
        assertThat(load.get("LH", "0200")).isPresent();
        RuleSet first = load.get("LH", "0100").orElseThrow();
        RuleSet second = load.get("LH", "0200").orElseThrow();
        assertThat(first.categories()).containsExactly(Category.ADVANCE_RESERVATION);
        assertThat(second.categories()).containsExactly(Category.MINIMUM_STAY);
    }

    @Test
    void aCategoryRecordBeforeAnyHeaderIsAnOrphan() {
        RuleLoad load = read(fileOf(advance(), head(), minimum().with("days", 3)));
        assertThat(load.orphans()).isEqualTo(1);
        assertThat(load.problems()).hasSize(1);
        assertThat(load.problems().get(0).reason()).contains("no rule above it");
        assertThat(load.rules()).hasSize(1);
        assertThat(load.rules().get(0).categories()).containsExactly(Category.MINIMUM_STAY);
    }

    @Test
    void aTruncatedFileShowsUpAsARunOfOrphans() {
        RuleLoad load = read(fileOf(advance(), minimum().with("days", 3), maximum()));
        assertThat(load.orphans()).isEqualTo(3);
        assertThat(load.rules()).isEmpty();
    }

    @Test
    void advancePurchaseReadsItsThreeNumbers() {
        RuleLoad load = read(fileOf(
                head(),
                advance().with("reservation_days", 7).with("ticketing_after", 3).with("ticketing_before", 21)));
        AdvancePurchase provision = firstProvision(load, AdvancePurchase.class);
        assertThat(provision.reservationDays()).contains(7);
        assertThat(provision.ticketingDaysAfterReservation()).contains(3);
        assertThat(provision.ticketingDaysBeforeDeparture()).contains(21);
    }

    @Test
    void aBlankNumberIsUnrestrictedAndNeverAZero() {
        // A zero would say the fare has to be bought on the day of departure.
        RuleLoad load = read(fileOf(head(), advance().with("reservation_days", null)));
        AdvancePurchase provision = firstProvision(load, AdvancePurchase.class);
        assertThat(provision.reservationDays()).isEmpty();
    }

    @Test
    void aMinimumStayCanBeDaysOrAWeekday() {
        RuleLoad load = read(fileOf(head(), minimum().with("days", 3), minimum().with("weekday", "SUN")));
        List<Provision> provisions = load.rules().get(0).provisions();
        assertThat(provisions).hasSize(2);
        assertThat(provisions.get(0)).isInstanceOf(MinimumStay.class);
        assertThat(((MinimumStay) provisions.get(0)).days()).contains(3);
        assertThat(provisions.get(1)).isInstanceOf(MinimumStay.class);
        assertThat(((MinimumStay) provisions.get(1)).weekday()).contains("SUN");
    }

    @Test
    void aMaximumStayReadsItsMonthsAndItsFlag() {
        RuleLoad load = read(fileOf(head(), maximum().with("months", 6).with("from_turnaround", "Y")));
        MaximumStay provision = firstProvision(load, MaximumStay.class);
        assertThat(provision.months()).contains(6);
        assertThat(provision.fromTurnaround()).isTrue();
    }

    @Test
    void aFlagThatIsNotAYesIsANo() {
        RuleLoad load = read(fileOf(head(), maximum().with("from_turnaround", null)));
        MaximumStay provision = firstProvision(load, MaximumStay.class);
        assertThat(provision.fromTurnaround()).isFalse();
    }

    @Test
    void aSalesRestrictionSplitsItsCountryList() {
        RuleLoad load = read(fileOf(head(), sales().with("countries", "DE/AT/CH")));
        SalesRestriction provision = firstProvision(load, SalesRestriction.class);
        assertThat(provision.countries()).containsExactly("DE", "AT", "CH");
    }

    @Test
    void aSalesRestrictionReadsItsWindowAndItsPayment() {
        RuleLoad load = read(fileOf(
                head(),
                sales().with("payment", "C")
                        .with("first_sale", LocalDate.of(2025, 1, 1))
                        .with("last_sale", LocalDate.of(2025, 6, 30))));
        SalesRestriction provision = firstProvision(load, SalesRestriction.class);
        assertThat(provision.payment()).isSameAs(FormOfPayment.CARD);
        assertThat(provision.firstSale()).contains(LocalDate.of(2025, 1, 1));
        assertThat(provision.lastSale()).contains(LocalDate.of(2025, 6, 30));
    }

    @Test
    void aBlankPaymentCodeMeansTheTariffDefault() {
        RuleLoad load = read(fileOf(head(), sales().with("countries", "DE")));
        SalesRestriction provision = firstProvision(load, SalesRestriction.class);
        assertThat(provision.payment()).isSameAs(FormOfPayment.ANY);
    }

    @Test
    void aPenaltyReadsBothAmountsInTheFiledCurrency() {
        RuleLoad load = read(fileOf(
                head(),
                penalty()
                        .with("before", new BigDecimal("50.00"))
                        .with("after", new BigDecimal("100.00"))
                        .with("currency", "EUR")));
        Penalty provision = firstProvision(load, Penalty.class);
        assertThat(provision.action()).isSameAs(Action.CHANGE);
        assertThat(provision.beforeDeparture()).contains(Money.of("50.00", "EUR"));
        assertThat(provision.afterDeparture()).contains(Money.of("100.00", "EUR"));
        assertThat(provision.permitted()).isTrue();
    }

    @Test
    void aPenaltyThatForbidsTheActionSaysSo() {
        RuleLoad load = read(fileOf(head(), penalty().with("permitted", "N")));
        Penalty provision = firstProvision(load, Penalty.class);
        assertThat(provision.permitted()).isFalse();
    }

    @Test
    void aPenaltyScopeCodeReadsIntoTheScope() {
        RuleLoad load = read(fileOf(head(), penalty().with("applies", "H")));
        Penalty provision = firstProvision(load, Penalty.class);
        assertThat(provision.applies()).isSameAs(Applies.HIGHEST_OF);
    }

    @Test
    void aCodeNobodyFilesIsOneBadRecord() {
        RuleLoad load =
                read(fileOf(head(), penalty().with("applies", "Z"), minimum().with("days", 3)));
        assertThat(load.problems()).hasSize(1);
        assertThat(load.problems().get(0).reason()).contains("no such code");
        assertThat(load.rules().get(0).categories()).containsExactly(Category.MINIMUM_STAY);
    }

    @Test
    void voluntaryChangesReadAllSixOfTheirFields() {
        RuleLoad load = read(fileOf(
                head(),
                changes()
                        .with("basis", "H")
                        .with("residual", "C")
                        .with("keep_breaks", "Y")
                        .with("same_carrier", "Y")
                        .with("once_only", "Y")));
        VoluntaryChange provision = firstProvision(load, VoluntaryChange.class);
        assertThat(provision.permitted()).isTrue();
        assertThat(provision.basis()).isSameAs(RepricingBasis.HISTORICAL);
        assertThat(provision.residual()).isSameAs(Residual.RETAINED);
        assertThat(provision.keepFareBreaks()).isTrue();
        assertThat(provision.sameCarrierOnly()).isTrue();
        assertThat(provision.onceOnly()).isTrue();
    }

    @Test
    void changesThatAreNotPermittedReadAsNotPermitted() {
        RuleLoad load = read(fileOf(head(), changes().with("permitted", "N")));
        VoluntaryChange provision = firstProvision(load, VoluntaryChange.class);
        assertThat(provision.permitted()).isFalse();
    }

    @Test
    void aRuleLandsInTheStoreUnderItsOwnKey() {
        RuleStore store = new RuleStore();
        loadInto(store, fileOf(head(), advance()));
        RuleSet rule = store.get("LH", "0100", "001");
        assertThat(rule.has(Category.ADVANCE_RESERVATION)).isTrue();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void aRefiledRuleReplacesTheWholeOfTheOldOne() {
        // There is no record that removes a category, so the second filing has to
        // take the first one's conditions with it.
        RuleStore store = new RuleStore();
        loadInto(store, fileOf(head(), advance(), minimum().with("days", 3)));
        RulesApplied applied = loadInto(store, fileOf(head(), advance())).applied();
        assertThat(applied.replaced()).isEqualTo(1);
        RuleSet rule = store.get("LH", "0100", "001");
        assertThat(rule.categories()).containsExactly(Category.ADVANCE_RESERVATION);
    }

    @Test
    void aRuleRefiledWithNothingInItClearsItsConditions() {
        RuleStore store = new RuleStore();
        loadInto(store, fileOf(head(), advance(), minimum().with("days", 3)));
        RulesApplied applied = loadInto(store, fileOf(head())).applied();
        assertThat(applied.emptyRules()).isEqualTo(1);
        assertThat(store.get("LH", "0100", "001").size()).isZero();
    }

    @Test
    void aDeletedRuleLeavesTheStore() {
        RuleStore store = new RuleStore();
        loadInto(store, fileOf(head(), advance()));
        RulesApplied applied =
                loadInto(store, fileOf(head().with("action", "D"))).applied();
        assertThat(applied.deleted()).isEqualTo(1);
        assertThat(store.find("LH", "0100", "001")).isEmpty();
    }

    @Test
    void aDeleteCarriesNoConditionsWithIt() {
        RuleLoad load = read(fileOf(head().with("action", "D"), advance()));
        assertThat(load.rules()).isEmpty();
        assertThat(load.deleted()).containsExactly(new RuleLoad.Withdrawn("LH", "0100", "001"));
    }

    @Test
    void aDeleteForARuleWeNeverHeldIsCountedAndIgnored() {
        RuleStore store = new RuleStore();
        RulesApplied applied =
                loadInto(store, fileOf(head().with("action", "D"))).applied();
        assertThat(applied.unknownDeletes()).isEqualTo(1);
        assertThat(applied.deleted()).isZero();
    }

    @Test
    void twoCarriersAreTwoRulesEvenAtTheSameNumber() {
        RuleStore store = new RuleStore();
        loadInto(
                store,
                fileOf(
                        head().with("carrier", "LH"),
                        advance(),
                        head().with("carrier", "UA"),
                        minimum().with("days", 3)));
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.numbers("LH")).containsExactly("0100");
    }

    @Test
    void readingNeverTouchesTheStore() {
        RuleStore store = new RuleStore();
        RuleLoad load = read(fileOf(head(), advance()));
        assertThat(store.size()).isZero();
        assertThat(apply(store, load).loaded()).isEqualTo(1);
    }

    @Test
    void theCategoriesInAFileAreCountedForTheLoadReport() {
        RuleLoad load =
                read(fileOf(head(), advance(), minimum().with("days", 3), head().with("number", "0200"), advance()));
        assertThat(categoriesOf(load)).isEqualTo(Map.of(5, 2, 6, 1));
    }

    @Test
    void theCategoriesARuleDoesNotFileCanBeListed() {
        RuleLoad load = read(fileOf(head(), advance()));
        List<Category> wanted = List.of(Category.ADVANCE_RESERVATION, Category.MINIMUM_STAY, Category.PENALTIES);
        assertThat(missingCategories(load.rules().get(0), wanted))
                .containsExactly(Category.MINIMUM_STAY, Category.PENALTIES);
    }

    @Test
    void aLoadReadsBackInOneLine() {
        RuleLoad load =
                read(fileOf(head(), advance(), head().with("number", "0200").with("action", "D")));
        assertThat(load).hasToString("1 rules, 1 deleted, 0 problems");
    }

    @Test
    void whatWasAppliedReadsBackInOneLine() {
        RuleStore store = new RuleStore();
        RulesApplied applied = loadInto(store, fileOf(head(), advance())).applied();
        assertThat(applied).hasToString("1 rules loaded, 0 of them replacing one, 0 deleted, 0 with no conditions");
    }
}
