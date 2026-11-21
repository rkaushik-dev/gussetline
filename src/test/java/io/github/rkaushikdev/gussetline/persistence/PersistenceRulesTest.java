package io.github.rkaushikdev.gussetline.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.AdvancePurchase;
import io.github.rkaushikdev.gussetline.rules.Blackout;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.MaximumStay;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PersistenceRulesTest {

    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
    }

    private static RuleSet rule(String carrier, String number, String tariff, Provision... provisions) {
        return new RuleSet(carrier, number, List.of(provisions), tariff);
    }

    private static RuleSet rule() {
        return rule("LH", "0100", "1");
    }

    private static RuleSet full() {
        return full("LH", "0100");
    }

    private static RuleSet full(String carrier, String number) {
        return rule(
                carrier,
                number,
                "1",
                AdvancePurchase.builder()
                        .reservationDays(7)
                        .ticketingDaysBeforeDeparture(21)
                        .build(),
                new MinimumStay("SUN"),
                MaximumStay.builder().months(6).build(),
                new Penalty(Action.CHANGE, Money.of("50.00", "EUR")),
                new VoluntaryChange(RepricingBasis.CURRENT, Residual.REFUNDABLE));
    }

    /** Save one rule in a unit of its own, and commit. */
    private void saved(RuleSet rule) {
        try (UnitOfWork work = database.unit()) {
            work.rules().save(rule);
            work.commit();
        }
    }

    /** Save a whole file in a unit of its own, and commit. */
    private void filed(RuleSet... rules) {
        try (UnitOfWork work = database.unit()) {
            work.rules().saveAll(List.of(rules));
            work.commit();
        }
    }

    private Optional<RuleSet> read(String carrier, String number) {
        try (UnitOfWork work = database.unit()) {
            return work.rules().get(carrier, number);
        }
    }

    @Test
    void aRuleWithNothingInItStillSaves() {
        try (UnitOfWork work = database.unit()) {
            work.rules().save(rule());
            work.commit();
        }

        Optional<RuleSet> back = read("LH", "0100");
        assertThat(back).isPresent();
        assertThat(back.get().provisions()).isEmpty();
    }

    @Test
    void aRuleComesBackWithEveryProvisionItWentInWith() {
        saved(full());

        assertThat(read("LH", "0100")).contains(full());
    }

    @Test
    void theCategoriesComeBackInTheOrderTheyWereFiled() {
        saved(full());

        Optional<RuleSet> back = read("LH", "0100");
        assertThat(back).isPresent();
        assertThat(back.get().categories())
                .containsExactly(
                        Category.ADVANCE_RESERVATION,
                        Category.MINIMUM_STAY,
                        Category.MAXIMUM_STAY,
                        Category.PENALTIES,
                        Category.VOLUNTARY_CHANGES);
    }

    @Test
    void aRefiledRuleTakesTheOldProvisionsWithIt() {
        // The carrier sends the categories it now has. Merging would leave a minimum stay nobody
        // filed, which is the hardest wrong answer to find.
        saved(full());

        try (UnitOfWork work = database.unit()) {
            work.rules()
                    .save(rule(
                            "LH",
                            "0100",
                            "1",
                            AdvancePurchase.builder().reservationDays(14).build()));
            work.commit();
        }

        Optional<RuleSet> back;
        try (UnitOfWork work = database.unit()) {
            back = work.rules().get("LH", "0100");
            assertThat(work.rules().count()).isEqualTo(1);
        }
        assertThat(back).isPresent();
        assertThat(back.get().categories()).containsExactly(Category.ADVANCE_RESERVATION);
    }

    @Test
    void aRuleThatIsNotThereIsNothing() {
        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().get("LH", "9999")).isEmpty();
        }
    }

    @Test
    void twoTariffsAtOneNumberAreTwoRules() {
        try (UnitOfWork work = database.unit()) {
            work.rules().save(rule("LH", "0100", "1", new MinimumStay(3)));
            work.rules().save(rule("LH", "0100", "2", new MinimumStay(7)));
            work.commit();
        }

        Optional<RuleSet> one;
        Optional<RuleSet> two;
        try (UnitOfWork work = database.unit()) {
            one = work.rules().get("LH", "0100", "1");
            two = work.rules().get("LH", "0100", "2");
            assertThat(work.rules().count()).isEqualTo(2);
        }
        assertThat(one).isPresent();
        assertThat(two).isPresent();
        assertThat(one.get().provisions().get(0))
                .isNotEqualTo(two.get().provisions().get(0));
    }

    @Test
    void aProvisionWithNoCodecIsCountedRatherThanSwallowed() {
        // A rule stored without its category 11 permits travel on a day the carrier blacked out, so
        // the operator has to be told.
        RuleRepository.Saved written;
        try (UnitOfWork work = database.unit()) {
            written = work.rules().save(rule("LH", "0100", "1", new MinimumStay(3), new Blackout()));
            work.commit();
        }
        assertThat(written.dropped()).isEqualTo(1);

        Optional<RuleSet> back = read("LH", "0100");
        assertThat(back).isPresent();
        assertThat(back.get().size()).isEqualTo(1);
    }

    @Test
    void aWholeFileSavesAndSaysWhatItDropped() {
        RuleRepository.Filed filed;
        try (UnitOfWork work = database.unit()) {
            filed = work.rules().saveAll(List.of(full("LH", "0100"), rule("LH", "0200", "1", new Blackout())));
            work.commit();
        }
        assertThat(filed.rules()).isEqualTo(2);
        assertThat(filed.dropped()).isEqualTo(1);
    }

    @Test
    void aDeletedRuleLeavesTheDatabase() {
        saved(full());

        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().delete("LH", "0100")).isTrue();
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().count()).isZero();
            assertThat(work.rules().get("LH", "0100")).isEmpty();
        }
    }

    @Test
    void deletingARuleTakesItsProvisionsWithIt() {
        saved(full());

        try (UnitOfWork work = database.unit()) {
            work.rules().delete("LH", "0100");
            work.commit();
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().categoriesHeld()).isEmpty();
        }
    }

    @Test
    void deletingSomethingThatIsNotThereSaysSo() {
        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().delete("LH", "9999")).isFalse();
        }
    }

    @Test
    void theStoreForACarrierHoldsEveryRuleItFiled() {
        filed(full("LH", "0100"), full("LH", "0200"), full("UA", "0100"));

        RuleStore store;
        try (UnitOfWork work = database.unit()) {
            store = work.rules().storeFor(List.of("LH"));
        }
        assertThat(store.size()).isEqualTo(2);
        assertThat(store.numbers("LH")).containsExactly("0100", "0200");
        assertThat(store.find("UA", "0100")).isEmpty();
    }

    @Test
    void theStoreCarriesTheProvisionsAndNotOnlyTheRules() {
        saved(full());

        RuleStore store;
        try (UnitOfWork work = database.unit()) {
            store = work.rules().storeFor(List.of("LH"));
        }
        RuleSet found = store.get("LH", "0100");
        assertThat(found.size()).isEqualTo(5);
        assertThat(found.has(Category.PENALTIES)).isTrue();
    }

    @Test
    void theStoreForNoCarriersIsEmptyWithoutAQuery() {
        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().storeFor(List.of()).size()).isZero();
        }
    }

    @Test
    void theCategoriesHeldAreCountedForALoadReport() {
        filed(full("LH", "0100"), rule("LH", "0200", "1", new MinimumStay(3)));

        Map<Integer, Long> held;
        try (UnitOfWork work = database.unit()) {
            held = work.rules().categoriesHeld();
        }
        assertThat(held).containsEntry(Category.MINIMUM_STAY.value(), 2L);
        assertThat(held).containsEntry(Category.PENALTIES.value(), 1L);
    }

    @Test
    void aRuleThatIsNeverCommittedIsNotThere() {
        try (UnitOfWork work = database.unit()) {
            work.rules().save(full());
        }

        try (UnitOfWork work = database.unit()) {
            assertThat(work.rules().count()).isZero();
        }
    }
}
