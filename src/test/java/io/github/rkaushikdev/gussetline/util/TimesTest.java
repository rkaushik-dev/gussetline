package io.github.rkaushikdev.gussetline.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;

class TimesTest {

    @Test
    void isoAlwaysWritesTheSeconds() {
        assertThat(Times.iso(LocalDateTime.of(2025, 3, 4, 10, 25))).isEqualTo("2025-03-04T10:25:00");
    }

    @Test
    void isoWritesMicrosecondsOnlyWhenThereAreSome() {
        assertThat(Times.iso(LocalDateTime.of(2025, 8, 4, 10, 25, 0, 12_345_000)))
                .isEqualTo("2025-08-04T10:25:00.012345");
    }

    @Test
    void isoToTheSecondDropsTheFraction() {
        assertThat(Times.isoSeconds(LocalDateTime.of(2025, 8, 4, 10, 25, 0, 12_345_000)))
                .isEqualTo("2025-08-04T10:25:00");
    }

    @Test
    void nowIsKeptToTheMicrosecond() {
        LocalDateTime now = Times.utcNow();
        assertThat(now.truncatedTo(ChronoUnit.MICROS)).isEqualTo(now);
    }
}
