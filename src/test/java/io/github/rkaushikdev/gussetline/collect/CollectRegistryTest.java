package io.github.rkaushikdev.gussetline.collect;

import static io.github.rkaushikdev.gussetline.collect.Registry.classify;
import static io.github.rkaushikdev.gussetline.collect.Registry.defaultRegistry;
import static io.github.rkaushikdev.gussetline.collect.Registry.foldMarket;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.BudgetExhausted;
import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierThrottled;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CollectRegistryTest {

    @Test
    void nothingWrongIsOk() {
        assertThat(classify(null)).isSameAs(Outcome.OK);
    }

    @Test
    void eachFailureMapsToItsOwnOutcome() {
        assertThat(classify(new SupplierThrottled("429"))).isSameAs(Outcome.THROTTLED);
        assertThat(classify(new SupplierBlocked("403"))).isSameAs(Outcome.BLOCKED);
        assertThat(classify(new MalformedPayload("not json"))).isSameAs(Outcome.MALFORMED);
        assertThat(classify(new BudgetExhausted("spent"))).isSameAs(Outcome.BUDGET);
        assertThat(classify(new SupplierUnavailable("timeout"))).isSameAs(Outcome.TRANSIENT);
    }

    @Test
    void aMalformedPayloadIsNotTransient() {
        // Retrying gets the same bytes and the same parser.
        assertThat(classify(new MalformedPayload("not json")).retryLater()).isFalse();
    }

    @Test
    void ourOwnRefusalsAreNeitherTransientNorBlocking() {
        assertThat(classify(new NoFareFound("nothing published"))).isSameAs(Outcome.REFUSED);
        assertThat(classify(new ConstructionError("no through fare"))).isSameAs(Outcome.REFUSED);
        assertThat(classify(new ValidationError("bad market"))).isSameAs(Outcome.REFUSED);
    }

    @Test
    void somethingWeHaveNeverSeenIsTreatedAsTransient() {
        assertThat(classify(new IOException("connection reset"))).isSameAs(Outcome.TRANSIENT);
        assertThat(classify(new IllegalStateException("event loop closed"))).isSameAs(Outcome.TRANSIENT);
    }

    @Test
    void theTwoOutcomesWorthRetryingAreMarked() {
        assertThat(Outcome.TRANSIENT.retryLater()).isTrue();
        assertThat(Outcome.THROTTLED.retryLater()).isTrue();
        assertThat(Outcome.BLOCKED.retryLater()).isFalse();
        assertThat(Outcome.OK.retryLater()).isFalse();
    }

    @Test
    void theTwoOutcomesThatStopASupplierAreMarked() {
        assertThat(Outcome.BLOCKED.stopsTheSupplier()).isTrue();
        assertThat(Outcome.BUDGET.stopsTheSupplier()).isTrue();
        assertThat(Outcome.TRANSIENT.stopsTheSupplier()).isFalse();
    }

    @Test
    void aSupplierHasAName() {
        assertThatThrownBy(() -> new Supplier("  ", "x.example")).isInstanceOf(ValidationError.class);
        assertThat(new Supplier(" GDS ", "x.example").name()).isEqualTo("gds");
    }

    @Test
    void aCallDoesNotPayUs() {
        assertThatThrownBy(() -> Supplier.builder("gds", "x.example")
                        .costPerCall(new BigDecimal("-1"))
                        .build())
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aSupplierSaysWhatItCanDo() {
        Supplier supplier = Supplier.builder("gds", "x.example")
                .capabilities(Set.of(Capability.SHOP))
                .build();
        assertThat(supplier.can(Capability.SHOP)).isTrue();
        assertThat(supplier.can(Capability.QUOTE)).isFalse();
    }

    @Test
    void aSupplierWithNoMarketListCoversEverything() {
        Supplier supplier = new Supplier("gds", "x.example");
        assertThat(supplier.covers("FRA-JFK")).isTrue();
        assertThat(supplier.covers("anything")).isTrue();
    }

    @Test
    void aSupplierWithAMarketListCoversOnlyThose() {
        Supplier supplier =
                Supplier.builder("ndc", "x.example").markets(Set.of("FRA-JFK")).build();
        assertThat(supplier.covers("fra-jfk")).isTrue();
        assertThat(supplier.covers("FRA-BKK")).isFalse();
    }

    @Test
    void aFreeSupplierIsMarked() {
        assertThat(new Supplier("ndc", "x.example").isFree()).isTrue();
        assertThat(Supplier.builder("gds", "x.example")
                        .costPerCall(new BigDecimal("0.04"))
                        .build()
                        .isFree())
                .isFalse();
    }

    @Test
    void aSupplierReadsBackWithWhatItDoes() {
        Supplier supplier = Supplier.builder("gds", "gateway.example")
                .capabilities(Set.of(Capability.SHOP, Capability.QUOTE))
                .build();
        assertThat(supplier).hasToString("gds at gateway.example does quote/shop");
        assertThat(new Supplier("x", "h")).hasToString("x at h does nothing");
    }

    @Test
    void theRegistryFindsASupplierByName() {
        Registry registry = new Registry();
        registry.add(new Supplier("gds", "x.example"));
        assertThat(registry.get("GDS").name()).isEqualTo("gds");
        assertThat(registry.names()).containsExactly("gds");
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void aSupplierWeDoNotHoldIsRefused() {
        assertThatThrownBy(() -> new Registry().get("nobody")).isInstanceOf(ValidationError.class);
    }

    @Test
    void suppliersCanBeListedByCapability() {
        Registry registry = defaultRegistry();
        assertThat(registry.ableTo(Capability.SHOP).stream().map(Supplier::name).sorted())
                .containsExactly("gds", "ndc");
        assertThat(registry.ableTo(Capability.QUOTE).stream().map(Supplier::name))
                .containsExactly("calendar");
    }

    @Test
    void aMarketBothShopAsksTheFreeOneFirst() {
        Registry registry = defaultRegistry();
        assertThat(registry.forMarket("FRA-JFK", Capability.SHOP).stream().map(Supplier::name))
                .containsExactly("ndc", "gds");
        assertThat(registry.cheapestFor("FRA-JFK", Capability.SHOP))
                .hasValueSatisfying(cheapest -> assertThat(cheapest.name()).isEqualTo("ndc"));
    }

    @Test
    void aMarketOnlyOneCoversFallsToThatOne() {
        Registry registry = defaultRegistry();
        assertThat(registry.forMarket("FRA-BKK", Capability.SHOP).stream().map(Supplier::name))
                .containsExactly("gds");
    }

    @Test
    void aCapabilityNobodyHasAnswersEmpty() {
        Registry registry = defaultRegistry();
        assertThat(registry.forMarket("FRA-JFK", Capability.REPRICE).get(0).name())
                .isEqualTo("gds");
        assertThat(new Registry().cheapestFor("FRA-JFK", Capability.SHOP)).isEmpty();
    }

    @Test
    void theShippedRegistryHoldsTheThreeSuppliers() {
        Registry registry = defaultRegistry();
        assertThat(registry.names()).containsExactly("calendar", "gds", "ndc");
        assertThat(registry.get("gds").costPerCall()).isEqualByComparingTo("0.04");
        assertThat(registry.get("ndc").isFree()).isTrue();
        assertThat(registry.get("calendar").can(Capability.SHOP)).isFalse();
    }

    @Test
    void aSupplierMarketListIsFoldedToCitiesWhenItIsTakenIn() {
        // Suppliers publish coverage in airports and the schedule keys markets by
        // city, so an unfolded list matches nothing and every market falls through
        // to whichever supplier publishes no list at all.
        Supplier made = Supplier.builder("feed", "offers.example")
                .capabilities(Set.of(Capability.SHOP))
                .markets(Set.of("FRA-JFK", "MUC-ORD"))
                .build();
        assertThat(made.markets()).isEqualTo(Set.of("FRA-NYC", "MUC-CHI"));
    }

    @Test
    void aSupplierCoversAMarketAskedForInEitherForm() {
        Supplier made = Supplier.builder("feed", "offers.example")
                .capabilities(Set.of(Capability.SHOP))
                .markets(Set.of("FRA-JFK"))
                .build();
        assertThat(made.covers("FRA-NYC")).isTrue();
        assertThat(made.covers("FRA-EWR")).isTrue();
        assertThat(made.covers("fra-jfk")).isTrue();
        assertThat(made.covers("FRA-ORD")).isFalse();
    }

    @Test
    void aMarketStringThatIsNotAPairIsLeftAlone() {
        assertThat(foldMarket("nonsense")).isEqualTo("NONSENSE");
        assertThat(foldMarket("FRA-JFK")).isEqualTo("FRA-NYC");
    }

    @Test
    void theFreeFeedWinsAMarketBothSuppliersCover() {
        Registry registry = defaultRegistry();
        assertThat(registry.cheapestFor("FRA-NYC", Capability.SHOP))
                .hasValueSatisfying(cheapest -> assertThat(cheapest.name()).isEqualTo("ndc"));
    }

    @Test
    void theGatewayTakesAMarketTheFeedDoesNotPublish() {
        Registry registry = defaultRegistry();
        assertThat(registry.cheapestFor("LON-DXB", Capability.SHOP))
                .hasValueSatisfying(cheapest -> assertThat(cheapest.name()).isEqualTo("gds"));
    }
}
