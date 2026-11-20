package io.github.rkaushikdev.gussetline.retention;

import static io.github.rkaushikdev.gussetline.retention.Retention.ARCHIVE_DAYS;
import static io.github.rkaushikdev.gussetline.retention.Retention.OBSERVATION_DAYS;
import static io.github.rkaushikdev.gussetline.retention.Retention.RUN_DAYS;
import static io.github.rkaushikdev.gussetline.retention.Retention.defaultPolicy;
import static io.github.rkaushikdev.gussetline.retention.Retention.keepable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.ValidationError;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RetentionTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 1, 15);

    @Test
    void theShippedPolicyKeepsEachKindForADifferentWhile() {
        Policy policy = defaultPolicy();
        assertThat(policy.observationDays()).isGreaterThan(policy.runDays());
        assertThat(policy.runDays()).isGreaterThan(policy.archiveDays());
    }

    @Test
    void aRetentionOfNothingIsRefused() {
        assertThatThrownBy(() -> new Policy(0, RUN_DAYS, ARCHIVE_DAYS))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("at least a day");
    }

    @Test
    void keepingPayloadsLongerThanTheRunsThatExplainThemIsRefused() {
        // Disk with nothing to say about it.
        assertThatThrownBy(() -> new Policy(OBSERVATION_DAYS, 30, 90))
                .isInstanceOf(ValidationError.class)
                .hasMessageContaining("at least as long");
    }

    @Test
    void aPolicyReadsBackInOneLine() {
        assertThat(new Policy(400, 120, 90)).hasToString("observations 400d, runs 120d, payloads 90d");
    }

    @Test
    void theCutoffsAreWorkedOutFromToday() {
        Policy policy = new Policy(400, 120, 90);
        assertThat(policy.observationsBefore(TODAY)).isEqualTo(LocalDate.of(2024, 12, 11));
        assertThat(policy.runsBefore(TODAY)).isEqualTo(LocalDateTime.of(2025, 9, 17, 0, 0));
        assertThat(policy.archiveBefore(TODAY)).isEqualTo(LocalDateTime.of(2025, 10, 17, 0, 0));
    }

    @Test
    void aManifestRowOlderThanTheCutoffDoesNotSurvive() {
        List<Map<String, String>> rows = List.of(
                Map.of("digest", "a".repeat(32), "seen_at", "2025-01-01T06:00:00"),
                Map.of("digest", "b".repeat(32), "seen_at", "2026-01-01T06:00:00"));
        Kept<Map<String, String>> kept = keepable(rows, LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(kept.rows()).hasSize(1);
        assertThat(kept.named()).isEqualTo(Set.of("b".repeat(32)));
    }

    @Test
    void aPayloadASurvivingRowStillNamesIsKept() {
        // The same payload seen on thirty mornings is one blob and thirty rows.
        List<Map<String, String>> rows = List.of(
                Map.of("digest", "a".repeat(32), "seen_at", "2025-01-01T06:00:00"),
                Map.of("digest", "a".repeat(32), "seen_at", "2026-01-01T06:00:00"));
        Kept<Map<String, String>> kept = keepable(rows, LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(kept.rows()).hasSize(1);
        assertThat(kept.named()).isEqualTo(Set.of("a".repeat(32)));
    }

    @Test
    void aRowWithNoStampOnItIsKeptRatherThanGuessedAt() {
        Kept<Map<String, String>> kept =
                keepable(List.of(Map.of("digest", "a".repeat(32), "seen_at", "")), LocalDateTime.of(2025, 6, 1, 0, 0));
        assertThat(kept.rows()).hasSize(1);
    }
}
