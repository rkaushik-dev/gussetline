package io.github.rkaushikdev.gussetline.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Breaker.State;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectBreakerTest {

    private static Breaker breaker() {
        return breaker(3);
    }

    private static Breaker breaker(int threshold) {
        return breaker(threshold, 60.0, new ManualClock());
    }

    private static Breaker breaker(int threshold, double cooldown, ManualClock clock) {
        return new Breaker("gds", threshold, cooldown, clock);
    }

    @Test
    void aBreakerNeedsARealThresholdAndCooldown() {
        assertThatThrownBy(() -> new Breaker("x", 0, 60.0)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new Breaker("x", 5, -1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aFreshBreakerIsClosedAndLetsEverythingThrough() {
        Breaker b = breaker();
        assertThat(b.state()).isSameAs(State.CLOSED);
        assertThat(b.allow()).isTrue();
        assertThat(b.allow()).isTrue();
    }

    @Test
    void failuresUnderTheThresholdDoNotTripIt() {
        Breaker b = breaker(3);
        b.failed();
        b.failed();
        assertThat(b.state()).isSameAs(State.CLOSED);
        assertThat(b.allow()).isTrue();
    }

    @Test
    void aRunOfFailuresTripsIt() {
        Breaker b = breaker(3);
        for (int i = 0; i < 3; i++) {
            b.failed();
        }
        assertThat(b.isOpen()).isTrue();
        assertThat(b.allow()).isFalse();
        assertThat(b.trips()).isEqualTo(1);
    }

    @Test
    void aSuccessClearsTheRun() {
        Breaker b = breaker(3);
        b.failed();
        b.failed();
        b.succeeded();
        b.failed();
        assertThat(b.state()).isSameAs(State.CLOSED);
    }

    @Test
    void anOpenBreakerStaysShutUntilTheCooldownPasses() {
        ManualClock clock = new ManualClock();
        Breaker b = breaker(1, 60.0, clock);
        b.failed();
        assertThat(b.allow()).isFalse();
        clock.advance(59);
        assertThat(b.allow()).isFalse();
        clock.advance(1);
        assertThat(b.allow()).isTrue();
    }

    @Test
    void theCooldownLetsExactlyOneProbeThrough() {
        ManualClock clock = new ManualClock();
        Breaker b = breaker(1, 10.0, clock);
        b.failed();
        clock.advance(10);
        assertThat(b.allow()).isTrue();
        assertThat(b.state()).isSameAs(State.HALF_OPEN);
        assertThat(b.allow()).isFalse();
        assertThat(b.probes()).isEqualTo(1);
    }

    @Test
    void aProbeThatWorksClosesTheBreaker() {
        ManualClock clock = new ManualClock();
        Breaker b = breaker(1, 10.0, clock);
        b.failed();
        clock.advance(10);
        b.allow();
        b.succeeded();
        assertThat(b.state()).isSameAs(State.CLOSED);
        assertThat(b.allow()).isTrue();
        assertThat(b.allow()).isTrue();
    }

    @Test
    void aProbeThatFailsReopensForAnotherCooldown() {
        ManualClock clock = new ManualClock();
        Breaker b = breaker(1, 10.0, clock);
        b.failed();
        clock.advance(10);
        b.allow();
        b.failed();
        assertThat(b.isOpen()).isTrue();
        assertThat(b.trips()).isEqualTo(2);
        assertThat(b.allow()).isFalse();
        clock.advance(10);
        assertThat(b.allow()).isTrue();
    }

    @Test
    void aFailingProbeDoesNotNeedTheThresholdAgain() {
        ManualClock clock = new ManualClock();
        Breaker b = breaker(5, 10.0, clock);
        for (int i = 0; i < 5; i++) {
            b.failed();
        }
        clock.advance(10);
        b.allow();
        b.failed();
        assertThat(b.isOpen()).isTrue();
    }

    @Test
    void aZeroCooldownReopensImmediatelyForAProbe() {
        Breaker b = breaker(1, 0.0, new ManualClock());
        b.failed();
        assertThat(b.allow()).isTrue();
    }

    @Test
    void theGuardRaisesRatherThanAnswering() {
        Breaker b = breaker(1);
        b.failed();
        SupplierUnavailable caught = catchThrowableOfType(SupplierUnavailable.class, b::guard);
        assertThat(caught.details()).containsEntry("supplier", "gds");
    }

    @Test
    void theGuardIsQuietWhileTheBreakerIsClosed() {
        Breaker b = breaker();
        b.guard();
        assertThat(b.state()).isSameAs(State.CLOSED);
    }

    @Test
    void aBreakerReadsBackWithItsState() {
        Breaker b = breaker(3);
        b.failed();
        assertThat(b).hasToString("gds closed after 1 failures");
    }

    @Test
    void aBoardMakesABreakerPerSupplier() {
        BreakerBoard board = new BreakerBoard(new ManualClock(), 1, 60.0);
        assertThat(board.allow("gds")).isTrue();
        board.failed("gds");
        assertThat(board.allow("gds")).isFalse();
        assertThat(board.allow("ndc")).isTrue();
        assertThat(board.size()).isEqualTo(2);
    }

    @Test
    void aBoardListsTheSuppliersThatAreOut() {
        BreakerBoard board = new BreakerBoard(new ManualClock(), 1, 60.0);
        board.failed("gds");
        board.failed("calendar");
        board.succeeded("ndc");
        assertThat(board.openSuppliers()).containsExactly("calendar", "gds");
    }

    @Test
    void aBoardSummarisesEveryBreakerItHolds() {
        BreakerBoard board = new BreakerBoard(new ManualClock(), 1, 60.0);
        board.failed("gds");
        board.succeeded("ndc");
        assertThat(board.summary()).isEqualTo(Map.of("gds", "open", "ndc", "closed"));
    }
}
