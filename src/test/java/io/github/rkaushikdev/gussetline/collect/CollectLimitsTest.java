package io.github.rkaushikdev.gussetline.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.assertj.core.api.Assertions.withinPercentage;

import io.github.rkaushikdev.gussetline.BudgetExhausted;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CollectLimitsTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 5, 6);
    private static final LocalDate TOMORROW = LocalDate.of(2025, 5, 7);

    @Test
    void aBucketNeedsAPositiveRateAndARealBurst() {
        ManualClock clock = new ManualClock();
        assertThatThrownBy(() -> new TokenBucket(0, 5, clock)).isInstanceOf(ValidationError.class);
        assertThatThrownBy(() -> new TokenBucket(1.0, 0, clock)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aBucketStartsFull() {
        assertThat(new TokenBucket(1.0, 5, new ManualClock()).tokens()).isEqualTo(5.0);
    }

    @Test
    void takingDrainsTheBucket() {
        TokenBucket bucket = new TokenBucket(1.0, 3, new ManualClock());
        assertThat(bucket.take()).isTrue();
        assertThat(bucket.take()).isTrue();
        assertThat(bucket.take()).isTrue();
        assertThat(bucket.take()).isFalse();
    }

    @Test
    void theBucketRefillsAtItsRate() {
        ManualClock clock = new ManualClock();
        TokenBucket bucket = new TokenBucket(2.0, 4, clock);
        for (int i = 0; i < 4; i++) {
            bucket.take();
        }
        assertThat(bucket.take()).isFalse();
        clock.advance(0.5);
        assertThat(bucket.take()).isTrue();
        assertThat(bucket.take()).isFalse();
    }

    @Test
    void theBucketDoesNotFillPastItsBurst() {
        ManualClock clock = new ManualClock();
        TokenBucket bucket = new TokenBucket(1.0, 3, clock);
        clock.advance(1000);
        assertThat(bucket.tokens()).isEqualTo(3.0);
    }

    @Test
    void takingNothingIsNotARequest() {
        TokenBucket bucket = new TokenBucket(1.0, 3, new ManualClock());
        assertThatThrownBy(() -> bucket.take(0)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aRequestBiggerThanTheBucketCanNeverBeServed() {
        TokenBucket bucket = new TokenBucket(1.0, 3, new ManualClock());
        assertThatThrownBy(() -> bucket.take(4)).isInstanceOf(ValidationError.class);
    }

    @Test
    void theWaitIsZeroWhenTheTokensAreThere() {
        assertThat(new TokenBucket(1.0, 3, new ManualClock()).waitFor()).isEqualTo(0.0);
    }

    @Test
    void theWaitIsTheTimeTheRefillNeeds() {
        ManualClock clock = new ManualClock();
        TokenBucket bucket = new TokenBucket(2.0, 2, clock);
        bucket.take(2);
        assertThat(bucket.waitFor()).isCloseTo(0.5, withinPercentage(1e-4));
    }

    @Test
    void aBucketReadsBackWithItsRate() {
        assertThat(new TokenBucket(1.0, 5, new ManualClock())).hasToString("5.0 of 5 tokens at 1.0/s");
    }

    @Test
    void aQuotaIsNotNegative() {
        assertThatThrownBy(() -> new DailyQuota(-1)).isInstanceOf(ValidationError.class);
    }

    @Test
    void aQuotaCountsDownAndThenRefuses() {
        DailyQuota quota = new DailyQuota(2);
        quota.spend(TODAY);
        quota.spend(TODAY);
        assertThat(quota.exhausted()).isTrue();
        BudgetExhausted caught = catchThrowableOfType(BudgetExhausted.class, () -> quota.spend(TODAY));
        assertThat(caught.details()).isEqualTo(Map.of("limit", 2, "used", 2, "wanted", 1));
    }

    @Test
    void aQuotaRollsOverAtMidnight() {
        DailyQuota quota = new DailyQuota(2);
        quota.spend(TODAY, 2);
        assertThat(quota.exhausted()).isTrue();
        quota.rollTo(TOMORROW);
        assertThat(quota.remaining()).isEqualTo(2);
    }

    @Test
    void rollingToTheSameDayTwiceDoesNotResetIt() {
        DailyQuota quota = new DailyQuota(5);
        quota.spend(TODAY, 3);
        quota.rollTo(TODAY);
        quota.rollTo(TODAY);
        assertThat(quota.used()).isEqualTo(3);
    }

    @Test
    void spendingMoreThanIsLeftIsRefusedAndNothingIsTaken() {
        DailyQuota quota = new DailyQuota(5);
        quota.spend(TODAY, 3);
        assertThatThrownBy(() -> quota.spend(TODAY, 3)).isInstanceOf(BudgetExhausted.class);
        assertThat(quota.used()).isEqualTo(3);
    }

    @Test
    void aQuotaReadsBackAsUsedOverLimit() {
        DailyQuota quota = new DailyQuota(5);
        quota.spend(TODAY, 2);
        assertThat(quota).hasToString("2 of 5 used");
    }

    private static HostBudget budget() {
        return budget(1.0, 2, 3, new ManualClock());
    }

    private static HostBudget budget(int burst, int quota) {
        return budget(1.0, burst, quota, new ManualClock());
    }

    private static HostBudget budget(double rate, int burst, int quota, ManualClock clock) {
        return new HostBudget("supplier.example", new TokenBucket(rate, burst, clock), new DailyQuota(quota));
    }

    @Test
    void aHostBudgetAllowsUntilTheRateRunsOut() {
        HostBudget b = budget(2, 10);
        assertThat(b.allow(TODAY)).isTrue();
        assertThat(b.allow(TODAY)).isTrue();
        assertThat(b.allow(TODAY)).isFalse();
        assertThat(b.lastReason()).isEqualTo("rate");
        assertThat(b.refusals()).isEqualTo(1);
    }

    @Test
    void aHostBudgetAllowsUntilTheQuotaRunsOut() {
        HostBudget b = budget(10, 2);
        assertThat(b.allow(TODAY)).isTrue();
        assertThat(b.allow(TODAY)).isTrue();
        assertThat(b.allow(TODAY)).isFalse();
        assertThat(b.lastReason()).isEqualTo("quota");
    }

    @Test
    void theQuotaIsCheckedBeforeTheRateSoATokenIsNotWasted() {
        ManualClock clock = new ManualClock();
        HostBudget b = budget(1.0, 5, 0, clock);
        assertThat(b.allow(TODAY)).isFalse();
        assertThat(b.lastReason()).isEqualTo("quota");
        assertThat(b.bucket().tokens()).isEqualTo(5.0);
    }

    @Test
    void waitingHelpsARateRefusalAndNotAQuotaOne() {
        ManualClock clock = new ManualClock();
        HostBudget rated = budget(1.0, 1, 10, clock);
        rated.allow(TODAY);
        assertThat(rated.waitFor()).isGreaterThan(0);

        HostBudget spent = budget(10, 1);
        spent.allow(TODAY);
        assertThat(spent.waitFor()).isEqualTo(0.0);
    }

    @Test
    void aHostBudgetReadsBackWithBothBudgets() {
        HostBudget b = budget();
        assertThat(b.toString()).contains(b.host()).contains("tokens").contains("used");
    }

    @Test
    void aLimiterMakesADefaultBudgetForAHostItHasNotSeen() {
        Limiter limiter = new Limiter(new ManualClock(), Limiter.RATE, 1, 5);
        assertThat(limiter.allow("one.example", TODAY)).isTrue();
        assertThat(limiter.allow("one.example", TODAY)).isFalse();
        assertThat(limiter.knownHosts()).containsExactly("one.example");
    }

    @Test
    void hostsDoNotShareABudget() {
        Limiter limiter = new Limiter(new ManualClock(), Limiter.RATE, 1, Limiter.QUOTA);
        assertThat(limiter.allow("one.example", TODAY)).isTrue();
        assertThat(limiter.allow("two.example", TODAY)).isTrue();
        assertThat(limiter.allow("one.example", TODAY)).isFalse();
    }

    @Test
    void aHostCanBeConfiguredMoreTightlyThanTheDefault() {
        Limiter limiter = new Limiter(new ManualClock(), Limiter.RATE, 10, Limiter.QUOTA);
        limiter.configure("slow.example", limiter.defaultRate(), 1, 100);
        assertThat(limiter.allow("slow.example", TODAY)).isTrue();
        assertThat(limiter.allow("slow.example", TODAY)).isFalse();
    }

    @Test
    void refusalsAreCountedPerHostAndOnlyWhenThereAreAny() {
        Limiter limiter = new Limiter(new ManualClock(), Limiter.RATE, 1, Limiter.QUOTA);
        limiter.allow("one.example", TODAY);
        limiter.allow("one.example", TODAY);
        limiter.allow("two.example", TODAY);
        assertThat(limiter.refused()).isEqualTo(Map.of("one.example", 1));
    }
}
