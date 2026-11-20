package io.github.rkaushikdev.gussetline.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TextTest {

    @Test
    void stripRemovesNoBreakSpacesAndNextLineAsWellAsAsciiSpace() {
        assertThat(Text.strip("  \t LH   \u0085\n")).isEqualTo("LH");
        assertThat(Text.stripLeading("  LH ")).isEqualTo("LH ");
        assertThat(Text.stripTrailing(" LH  ")).isEqualTo(" LH");
    }

    @Test
    void stripLeavesInnerSpaceAndOrdinaryTextAlone() {
        assertThat(Text.strip("FRA LH NYC")).isEqualTo("FRA LH NYC");
        assertThat(Text.strip("")).isEmpty();
        assertThat(Text.strip("   ")).isEmpty();
    }

    @Test
    void aCodeIsStrippedAndUpperCase() {
        assertThat(Text.code(" lh ")).isEqualTo("LH");
    }

    @Test
    void blankIsEmptyOrSpaceOnly() {
        assertThat(Text.isBlank("  ")).isTrue();
        assertThat(Text.isBlank(" x ")).isFalse();
    }

    @Test
    void wordsSplitOnRunsOfSpaceAndDropTheEnds() {
        assertThat(Text.words("  FRA  LH400\tK  JFK ")).containsExactly("FRA", "LH400", "K", "JFK");
        assertThat(Text.words("   ")).isEmpty();
    }

    @Test
    void characterClassesNeedAtLeastOneCharacter() {
        assertThat(Text.isDigits("0400")).isTrue();
        assertThat(Text.isDigits("")).isFalse();
        assertThat(Text.isDigits("04a")).isFalse();
        assertThat(Text.isLetters("K")).isTrue();
        assertThat(Text.isLetters("")).isFalse();
        assertThat(Text.isLetters("K1")).isFalse();
        assertThat(Text.isLettersOrDigits("LH400")).isTrue();
        assertThat(Text.isLettersOrDigits("LH-400")).isFalse();
    }

    @Test
    void reprQuotesTextInSingleQuotesUnlessItHoldsOne() {
        assertThat(Text.repr("LH")).isEqualTo("'LH'");
        assertThat(Text.repr("it's")).isEqualTo("\"it's\"");
        assertThat(Text.repr("both ' and \"")).isEqualTo("'both \\' and \"'");
        assertThat(Text.repr("a\\b\nc\td\u0001")).isEqualTo("'a\\\\b\\nc\\td\\x01'");
    }

    @Test
    void reprWritesTheOtherShapesPlainly() {
        assertThat(Text.repr(null)).isEqualTo("None");
        assertThat(Text.repr(true)).isEqualTo("True");
        assertThat(Text.repr(false)).isEqualTo("False");
        assertThat(Text.repr(12)).isEqualTo("12");
        assertThat(Text.repr(1.5)).isEqualTo("1.5");
        assertThat(Text.repr(new BigDecimal("90.00"))).isEqualTo("90.00");
        assertThat(Text.repr(List.of("origin", "destination"))).isEqualTo("['origin', 'destination']");
        assertThat(Text.repr(Arrays.asList(1, null))).isEqualTo("[1, None]");
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("a", 1);
        map.put("b", "x");
        assertThat(Text.repr(map)).isEqualTo("{'a': 1, 'b': 'x'}");
        assertThat(Text.repr(LocalDate.of(2025, 3, 4))).isEqualTo("2025-03-04");
        assertThat(Text.repr(LocalDateTime.of(2025, 3, 4, 10, 25))).isEqualTo("2025-03-04T10:25:00");
    }

    @Test
    void plainLeavesTextUnquotedAndPutsASpaceInATime() {
        assertThat(Text.plain("no fare found")).isEqualTo("no fare found");
        assertThat(Text.plain(List.of("DE"))).isEqualTo("['DE']");
        assertThat(Text.plain(LocalDateTime.of(2025, 3, 4, 10, 25))).isEqualTo("2025-03-04 10:25:00");
        assertThat(Text.plain(8120)).isEqualTo("8120");
    }
}
