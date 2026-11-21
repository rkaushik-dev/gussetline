package io.github.rkaushikdev.gussetline.negotiated;

import static io.github.rkaushikdev.gussetline.negotiated.Negotiated.bestFor;
import static io.github.rkaushikdev.gussetline.negotiated.Negotiated.savingAgainst;
import static io.github.rkaushikdev.gussetline.negotiated.Negotiated.visibleTo;
import static io.github.rkaushikdev.gussetline.negotiated.Negotiated.withInheritedRule;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NegotiatedTest {

    private static final LocalDate ON = LocalDate.of(2025, 10, 20);

    /** LH Frankfurt to New York, 420.00 EUR filed as half a round trip, on sale through 2025. */
    private static Fare.Builder filing() {
        return Fare.builder()
                .carrier("LH")
                .origin("FRA")
                .destination("JFK")
                .fareBasis("KLWAP7RT")
                .amount(Money.of("420.00", "EUR"))
                .owrt(OwRt.ROUND_TRIP_HALF)
                .globalIndicator(GlobalIndicator.AT)
                .ruleNumber("0100")
                .routingNumber("0011")
                .tariffNumber("1")
                .footnote("12")
                .fareType(FareType.PUBLISHED)
                .effective(LocalDate.of(2025, 1, 1))
                .discontinue(LocalDate.of(2025, 12, 31));
    }

    private static Fare fare() {
        return filing().build();
    }

    private static Fare fare(String amount) {
        return filing().amount(Money.of(amount, "EUR")).build();
    }

    private static Fare fare(String amount, String basis) {
        return filing().amount(Money.of(amount, "EUR")).fareBasis(basis).build();
    }

    private static Negotiated filed() {
        return filed("320.00", "KCORP");
    }

    private static Negotiated filed(String amount, String basis) {
        return filed(amount, basis, "ACME", "", "");
    }

    private static Negotiated filedFor(String account) {
        return filed("320.00", "KCORP", account, "", "");
    }

    private static Negotiated inheriting(String amount, String inherits) {
        return filed(amount, "KCORP", "ACME", inherits, "");
    }

    private static Negotiated filed(String amount, String basis, String account, String inherits, String rule) {
        return new Negotiated(
                new Account(account, "Acme Industries"),
                filing().amount(Money.of(amount, "EUR"))
                        .fareBasis(basis)
                        .fareType(FareType.NEGOTIATED)
                        .ruleNumber(rule)
                        .routingNumber("")
                        .footnote("")
                        .build(),
                inherits);
    }

    @Test
    void anAccountIsACodeAndAName() {
        Account made = new Account("acme", "Acme Industries");
        assertThat(made.code()).isEqualTo("ACME");
        assertThat(made).hasToString("ACME Acme Industries");
    }

    @Test
    void anAccountWithNoCodeIsRefused() {
        assertThatThrownBy(() -> new Account("  "))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("has a code");
    }

    @Test
    void anAccountCodeNobodyCouldTypeIsRefused() {
        assertThatThrownBy(() -> new Account("A".repeat(40)))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not an account code");
    }

    @Test
    void aPublicFareCannotBeFiledAsANegotiatedOne() {
        assertThatThrownBy(() -> new Negotiated(new Account("ACME"), fare()))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("filed as one");
    }

    @Test
    void aPrivateFareReadsBackWithTheAccountItBelongsTo() {
        assertThat(filed().toString()).contains("for ACME");
    }

    @Test
    void aCallerWithTheCodeSeesTheFare() {
        NegotiatedStore store = new NegotiatedStore();
        store.add(filed());
        assertThat(store.forAccount(List.of("ACME"), "LH", "FRA", "JFK")).isNotEmpty();
    }

    @Test
    void aCallerWithNoCodeSeesNothingAtAll() {
        // Not the cheapest published one and not a fallback. A caller without a
        // code has no business seeing another customer's terms.
        NegotiatedStore store = new NegotiatedStore();
        store.add(filed());
        assertThat(store.forAccount(List.of(), "LH", "FRA", "JFK")).isEmpty();
        assertThat(store.forAccount(List.of(""), "LH", "FRA", "JFK")).isEmpty();
    }

    @Test
    void aCallerWithTheWrongCodeSeesNothingEither() {
        NegotiatedStore store = new NegotiatedStore();
        store.add(filedFor("ACME"));
        assertThat(store.forAccount(List.of("OTHERCO"), "LH", "FRA", "JFK")).isEmpty();
    }

    @Test
    void aCallerMayPresentMoreThanOneCode() {
        NegotiatedStore store = new NegotiatedStore();
        store.addMany(List.of(filedFor("ACME"), filed("320.00", "KBETA", "BETA", "", "")));
        assertThat(store.forAccount(List.of("ACME", "BETA"), "LH", "FRA", "JFK"))
                .hasSize(2);
    }

    @Test
    void theSameCodeTwiceIsNotTwoFares() {
        NegotiatedStore store = new NegotiatedStore();
        store.add(filed());
        assertThat(store.forAccount(List.of("ACME", "acme"), "LH", "FRA", "JFK"))
                .hasSize(1);
    }

    @Test
    void theFaresComeBackCheapestFirst() {
        NegotiatedStore store = new NegotiatedStore();
        store.addMany(List.of(filed("360.00", "KB"), filed("320.00", "KA")));
        List<Negotiated> found = store.forAccount(List.of("ACME"), "LH", "FRA", "JFK");
        assertThat(found).extracting(f -> f.fare().fareBasis()).containsExactly("KA", "KB");
    }

    @Test
    void theMarketIsFoldedTheWayTheTariffFilesIt() {
        NegotiatedStore store = new NegotiatedStore();
        store.add(filed());
        assertThat(store.forAccount(List.of("ACME"), "LH", "FRA", "EWR")).isNotEmpty();
    }

    @Test
    void aFareOutsideItsSaleWindowIsNotShown() {
        NegotiatedStore store = new NegotiatedStore();
        store.add(filed());
        assertThat(store.forAccount(List.of("ACME"), "LH", "FRA", "JFK", LocalDate.of(2026, 6, 1)))
                .isEmpty();
        assertThat(store.forAccount(List.of("ACME"), "LH", "FRA", "JFK", ON)).isNotEmpty();
    }

    @Test
    void theStoreListsTheAccountsItHoldsFaresFor() {
        NegotiatedStore store = new NegotiatedStore();
        store.addMany(List.of(filedFor("ACME"), filed("320.00", "KBETA", "BETA", "", "")));
        assertThat(store.accounts()).containsExactly("ACME", "BETA");
        assertThat(store.size()).isEqualTo(2);
    }

    @Test
    void whetherAFareIsVisibleCanBeAskedDirectly() {
        Negotiated filed = filedFor("ACME");
        assertThat(visibleTo(filed, List.of("acme"))).isTrue();
        assertThat(visibleTo(filed, List.of("beta"))).isFalse();
        assertThat(visibleTo(filed, List.of())).isFalse();
    }

    @Test
    void aPrivateFareTakesTheRuleOfThePublicFareItNames() {
        // The carrier does not refile twenty categories to change a number.
        Negotiated filed = inheriting("320.00", "KLWAP7RT");
        Fare built = withInheritedRule(filed, List.of(fare()));
        assertThat(built.ruleNumber()).isEqualTo("0100");
        assertThat(built.tariffNumber()).isEqualTo("1");
        assertThat(built.footnote()).isEqualTo("12");
    }

    @Test
    void theInheritedFareKeepsItsOwnAmountAndBasis() {
        Fare built = withInheritedRule(inheriting("320.00", "KLWAP7RT"), List.of(fare()));
        assertThat(built.amount()).isEqualTo(Money.of("320.00", "EUR"));
        assertThat(built.fareBasis()).isEqualTo("KCORP");
        assertThat(built.fareType()).isSameAs(FareType.NEGOTIATED);
    }

    @Test
    void theInheritedRoutingIsUsedWhenThePrivateFareFilesNone() {
        Fare built = withInheritedRule(
                inheriting("320.00", "KLWAP7RT"),
                List.of(filing().routingNumber("0011").build()));
        assertThat(built.routingNumber()).isEqualTo("0011");
    }

    @Test
    void aPrivateFareWithARuleOfItsOwnKeepsIt() {
        Negotiated filed = filed("320.00", "KCORP", "ACME", "", "0900");
        assertThat(withInheritedRule(filed, List.of(fare())).ruleNumber()).isEqualTo("0900");
    }

    @Test
    void aPrivateFareWithNoRuleAndNothingToInheritIsRefused() {
        // A fully flexible ticket at a discount fare price is the most expensive
        // way to be wrong here.
        assertThatThrownBy(() -> withInheritedRule(filed(), List.of(fare())))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("rule of its own");
    }

    @Test
    void inheritingFromAFareNobodyLoadedIsRefusedByName() {
        assertThatThrownBy(() -> withInheritedRule(inheriting("320.00", "KGONE"), List.of(fare())))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("not loaded");
    }

    @Test
    void aNegotiatedFareBeatsAPublishedOneAtTheSamePrice() {
        // The opposite of the add-on rule, for the opposite reason.
        Optional<Fare> chosen = bestFor(List.of(fare("320.00")), List.of(filed("320.00", "KCORP")));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().fareBasis()).isEqualTo("KCORP");
    }

    @Test
    void aCheaperPublishedFareStillWins() {
        Optional<Fare> chosen = bestFor(List.of(fare("300.00", "KPUB")), List.of(filed("320.00", "KCORP")));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().fareBasis()).isEqualTo("KPUB");
    }

    @Test
    void withNoPrivateFareThePublishedOneIsWhatThereIs() {
        Optional<Fare> chosen = bestFor(List.of(fare()), List.of());
        assertThat(chosen).isPresent();
        assertThat(chosen.get().fareBasis()).isEqualTo("KLWAP7RT");
    }

    @Test
    void withNoPublishedFareThePrivateOneIsWhatThereIs() {
        Optional<Fare> chosen = bestFor(List.of(), List.of(filed()));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().fareBasis()).isEqualTo("KCORP");
    }

    @Test
    void withNothingAtAllThereIsNothing() {
        assertThat(bestFor(List.of(), List.of())).isEmpty();
    }

    @Test
    void theCheapestOfSeveralPrivateFaresIsTheOneChosen() {
        Optional<Fare> chosen = bestFor(List.of(), List.of(filed("360.00", "KB"), filed("320.00", "KCORP")));
        assertThat(chosen).isPresent();
        assertThat(chosen.get().amount()).isEqualTo(Money.of("320.00", "EUR"));
    }

    @Test
    void theSavingIsWhatThePrivateFareIsWorthOverThePublicOne() {
        assertThat(savingAgainst(List.of(fare("420.00")), filed("320.00", "KCORP")))
                .isEqualTo(Money.of("100.00", "EUR"));
    }

    @Test
    void aPrivateFareDearerThanThePublicOneSavesNothing() {
        assertThat(savingAgainst(List.of(fare("300.00")), filed("320.00", "KCORP")))
                .isEqualTo(Money.zero("EUR"));
    }

    @Test
    void withNothingPublishedThereIsNothingToSaveAgainst() {
        assertThat(savingAgainst(List.of(), filed())).isEqualTo(Money.zero("EUR"));
    }
}
