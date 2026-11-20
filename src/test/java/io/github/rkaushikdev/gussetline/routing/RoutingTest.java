package io.github.rkaushikdev.gussetline.routing;

import static io.github.rkaushikdev.gussetline.routing.RoutingMap.MILEAGE_ROUTING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RoutingTest {

    @Test
    void aHopFoldsToCities() {
        Hop hop = new Hop("LHR", "EWR");
        assertThat(hop.origin()).isEqualTo("LON");
        assertThat(hop.destination()).isEqualTo("NYC");
    }

    @Test
    void aHopGoesSomewhere() {
        assertThatThrownBy(() -> new Hop("LHR", "LGW")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aHopWithNoCarriersPermitsAnybody() {
        Hop hop = new Hop("FRA", "JFK");
        assertThat(hop.anyCarrier()).isTrue();
        assertThat(hop.permits("LH")).isTrue();
        assertThat(hop.permits(Optional.empty())).isTrue();
    }

    @Test
    void aHopWithCarriersPermitsOnlyThose() {
        Hop hop = new Hop("FRA", "JFK", Set.of("LH", "UA"));
        assertThat(hop.permits("LH")).isTrue();
        assertThat(hop.permits("ua")).isTrue();
        assertThat(hop.permits("BA")).isFalse();
        assertThat(hop.permits(Optional.empty())).isFalse();
    }

    @Test
    void aHopReversesAndKeepsItsCarriers() {
        Hop hop = new Hop("FRA", "JFK", Set.of("LH")).reversed();
        assertThat(List.of(hop.origin(), hop.destination())).containsExactly("NYC", "FRA");
        assertThat(hop.permits("LH")).isTrue();
    }

    @Test
    void aHopReadsBackWithItsCarriers() {
        assertThat(new Hop("FRA", "JFK", Set.of("UA", "LH"))).hasToString("FRA-NYC[LH/UA]");
        assertThat(new Hop("FRA", "JFK")).hasToString("FRA-NYC[any]");
    }

    @Test
    void aMapNeedsANumber() {
        assertThatThrownBy(() -> new RoutingMap("  ")).isInstanceOf(ValidationError.class);
    }

    @Test
    void aMapIsBidirectionalByDefault() {
        RoutingMap m = new RoutingMap("0101");
        m.add("FRA", "JFK", List.of("LH"));
        assertThat(m.permits(List.of("FRA", "JFK"), List.of("LH"))).isTrue();
        assertThat(m.permits(List.of("JFK", "FRA"), List.of("LH"))).isTrue();
        assertThat(m.size()).isEqualTo(2);
    }

    @Test
    void aOneWayMapOnlyReadsForwards() {
        RoutingMap m = new RoutingMap("0102", false);
        m.add("FRA", "JFK", List.of("LH"));
        assertThat(m.permits(List.of("FRA", "JFK"), List.of("LH"))).isTrue();
        assertThat(m.permits(List.of("JFK", "FRA"), List.of("LH"))).isFalse();
    }

    @Test
    void aStringAddsEveryHopAlongIt() {
        RoutingMap m = new RoutingMap("0103");
        m.addString(List.of("FRA", "LHR", "JFK"), List.of("LH", "BA"));
        assertThat(m.permits(List.of("FRA", "LON", "NYC"), List.of("LH", "BA"))).isTrue();
        assertThat(m.points()).containsExactly("FRA", "LON", "NYC");
    }

    @Test
    void aStringOfOnePointIsRefused() {
        assertThatThrownBy(() -> new RoutingMap("0104").addString(List.of("FRA")))
                .isInstanceOf(ValidationError.class);
    }

    @Test
    void aPathTheMapDoesNotHoldIsRefused() {
        RoutingMap m = new RoutingMap("0105");
        m.addString(List.of("FRA", "LHR", "JFK"), List.of("LH"));
        assertThat(m.permits(List.of("FRA", "AMS", "NYC"), List.of("LH", "LH"))).isFalse();
    }

    @Test
    void theRightPathOnTheWrongCarrierIsRefused() {
        RoutingMap m = new RoutingMap("0106");
        m.add("FRA", "JFK", List.of("LH"));
        assertThat(m.permits(List.of("FRA", "NYC"), List.of("BA"))).isFalse();
    }

    @Test
    void aMissingCarrierNeedsAnAnyCarrierHop() {
        RoutingMap strict = new RoutingMap("0107");
        strict.add("FRA", "JFK", List.of("LH"));
        assertThat(strict.permits(List.of("FRA", "NYC"))).isFalse();

        RoutingMap open = new RoutingMap("0108");
        open.add("FRA", "JFK");
        assertThat(open.permits(List.of("FRA", "NYC"))).isTrue();
    }

    @Test
    void carriersShorterThanTheHopsOnlyChecksWhatItWasGiven() {
        RoutingMap m = new RoutingMap("0109");
        m.add("FRA", "LHR", List.of("LH"));
        m.add("LHR", "JFK");
        assertThat(m.permits(List.of("FRA", "LON", "NYC"), List.of("LH"))).isTrue();
    }

    @Test
    void theMileageRoutingPermitsEverything() {
        RoutingMap m = new RoutingMap(MILEAGE_ROUTING);
        assertThat(m.isMileage()).isTrue();
        assertThat(m.permits(List.of("FRA", "SIN", "SYD", "NYC"))).isTrue();
        assertThat(m.refusal(List.of("FRA", "SIN"))).isEmpty();
    }

    @Test
    void aCheckNeedsTwoPoints() {
        assertThatThrownBy(() -> new RoutingMap("0110").permits(List.of("FRA"))).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRefusalNamesTheHopThatIsNotFiled() {
        RoutingMap m = new RoutingMap("0111");
        m.addString(List.of("FRA", "LHR", "JFK"), List.of("LH"));
        Optional<String> said = m.refusal(List.of("FRA", "AMS", "NYC"), List.of("LH", "LH"));
        assertThat(said).hasValue("routing 0111 does not permit FRA-AMS at all");
    }

    @Test
    void aRefusalSeparatesABadCarrierFromABadHop() {
        RoutingMap m = new RoutingMap("0112");
        m.add("FRA", "JFK", List.of("LH"));
        Optional<String> said = m.refusal(List.of("FRA", "NYC"), List.of("BA"));
        assertThat(said).hasValue("routing 0112 does not permit BA on FRA-NYC");
    }

    @Test
    void aPathThatPassesHasNoRefusal() {
        RoutingMap m = new RoutingMap("0113");
        m.add("FRA", "JFK", List.of("LH"));
        assertThat(m.refusal(List.of("FRA", "NYC"), List.of("LH"))).isEmpty();
    }

    @Test
    void aMapReadsBackWithItsSize() {
        RoutingMap m = new RoutingMap("0114");
        m.addString(List.of("FRA", "LHR", "JFK"));
        assertThat(m).hasToString("routing 0114 over 3 points");
    }

    @Test
    void theStoreFindsAMapByCarrierAndNumber() {
        RoutingStore store = new RoutingStore();
        RoutingMap m = new RoutingMap("0201");
        m.add("FRA", "JFK", List.of("LH"));
        store.add("LH", m);
        assertThat(store.get("lh", "0201")).containsSame(m);
        assertThat(store.get("BA", "0201")).isEmpty();
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void theStoreAppliesTheMapWhenItHasOne() {
        RoutingStore store = new RoutingStore();
        RoutingMap m = new RoutingMap("0202");
        m.add("FRA", "JFK", List.of("LH"));
        store.add("LH", m);
        assertThat(store.permits("LH", "0202", List.of("FRA", "NYC"), List.of("LH")))
                .isTrue();
        assertThat(store.permits("LH", "0202", List.of("FRA", "AMS"), List.of("LH")))
                .isFalse();
    }

    @Test
    void theStoreLetsAMileageRoutingThroughWithoutAMap() {
        assertThat(new RoutingStore().permits("LH", MILEAGE_ROUTING, List.of("FRA", "SIN")))
                .isTrue();
    }

    @Test
    void aMapWeDoNotHoldFallsThroughToMileage() {
        // Conservative on purpose. Refusing here would drop every fare on a carrier whose maps have
        // not loaded, which is worse than checking the mileage instead.
        assertThat(new RoutingStore().permits("LH", "0999", List.of("FRA", "AMS", "NYC"), List.of("LH", "LH")))
                .isTrue();
    }
}
