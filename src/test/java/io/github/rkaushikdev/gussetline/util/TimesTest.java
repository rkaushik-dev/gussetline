package io.github.rkaushikdev.gussetline.util;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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

    @Test
    void aStoredTimeReadsBackAsTheTimeThatWasWritten() {
        LocalDateTime whole = LocalDateTime.of(2025, 3, 4, 10, 25);
        LocalDateTime fine = LocalDateTime.of(2025, 8, 4, 10, 25, 0, 12_345_000);
        assertThat(Times.parseIso(Times.iso(whole))).isEqualTo(whole);
        assertThat(Times.parseIso(Times.iso(fine))).isEqualTo(fine);
        assertThat(Times.parseIso(Text.plain(fine))).isEqualTo(fine);
    }

    @ParameterizedTest
    @CsvSource({
        "2025-03-04, 2025-03-04T00:00",
        "2025-03-04T10, 2025-03-04T10:00",
        "2025-03-04T10:25, 2025-03-04T10:25",
        "2025-03-04 10:25:07, 2025-03-04T10:25:07",
        "2025-03-04T10:25:07.5, 2025-03-04T10:25:07.500",
        "'2025-03-04T10:25:07,25', 2025-03-04T10:25:07.250",
        "2025-03-04T10:25:07.1234567, 2025-03-04T10:25:07.123456"
    })
    void aReadingIsTakenToWhateverPrecisionItWasWrittenTo(String text, LocalDateTime expected) {
        assertThat(Times.parseIso(text)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "yesterday",
                "2025-3-4",
                "2025-03-04T",
                "2025-03-04T1:00",
                "2025-03-04T10:25:00Z",
                "2025-03-04T10:25:00+01:00",
                "2025-02-30T10:25:00",
                "2025-03-04T24:00:00"
            })
    void anythingElseIsRefused(String text) {
        assertThatThrownBy(() -> Times.parseIso(text)).isInstanceOf(DateTimeParseException.class);
    }
}
