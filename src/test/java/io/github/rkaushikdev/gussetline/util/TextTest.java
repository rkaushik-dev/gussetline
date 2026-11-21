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
    void codePointOrderPutsACharacterAboveTheBasicPlaneAfterEveryOneInIt() {
        String fullWidth = "\uFF21";
        String grinning = "\uD83D\uDE00";
        assertThat(fullWidth.compareTo(grinning)).isPositive();
        assertThat(Text.CODE_POINT_ORDER.compare(fullWidth, grinning)).isNegative();
        assertThat(Text.CODE_POINT_ORDER.compare(grinning, fullWidth)).isPositive();
        assertThat(Text.CODE_POINT_ORDER.compare("ann", "anna")).isNegative();
        assertThat(Text.CODE_POINT_ORDER.compare("anna", "bruno")).isNegative();
        assertThat(Text.CODE_POINT_ORDER.compare("anna", "anna")).isZero();
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
    void linesSplitOnEveryBreakAndKeepEmptyLinesInside() {
        assertThat(Text.lines("H1\nF1\r\nF2\rF3")).containsExactly("H1", "F1", "F2", "F3");
        assertThat(Text.lines("a\n\nb")).containsExactly("a", "", "b");
        assertThat(Text.lines("a\u000Bb\u000Cc\u001Cd\u001De\u001Ef\u0085g h i"))
                .containsExactly("a", "b", "c", "d", "e", "f", "g", "h", "i");
        assertThat(Text.lines("a\tb c")).containsExactly("a\tb c");
    }

    @Test
    void linesDropOnlyTheEmptyLineAfterAFinalBreak() {
        assertThat(Text.lines("a\n")).containsExactly("a");
        assertThat(Text.lines("a\r\n")).containsExactly("a");
        assertThat(Text.lines("\n")).containsExactly("");
        assertThat(Text.lines("a\n\n")).containsExactly("a", "");
        assertThat(Text.lines("")).isEmpty();
    }

    @Test
    void aSliceCountsCodePointsAndStopsAtTheEnd() {
        assertThat(Text.slice("F1KLWAP7RT  EUR", 2, 12)).isEqualTo("KLWAP7RT  ");
        assertThat(Text.slice("F1KL", 2, 10)).isEqualTo("KL");
        assertThat(Text.slice("F1", 5, 9)).isEmpty();
        assertThat(Text.slice("T1😀AB", 2, 4)).isEqualTo("😀A");
        assertThat(Text.length("T1😀AB")).isEqualTo(5);
    }

    @Test
    void paddingCountsCodePointsAndNeverCutsALongerText() {
        assertThat(Text.padEnd("gds", 6)).isEqualTo("gds   ");
        assertThat(Text.padEnd("x😀y", 5)).isEqualTo("x😀y  ");
        assertThat(Text.padEnd("calendar", 4)).isEqualTo("calendar");
        assertThat(Text.padEnd("", 2)).isEqualTo("  ");
    }

    @Test
    void aSliceCountsANegativePositionBackFromTheEnd() {
        assertThat(Text.slice("F1KL", -2, 4)).isEqualTo("KL");
        assertThat(Text.slice("F1KL", 0, -1)).isEqualTo("F1K");
        assertThat(Text.slice("F1KL", -9, 2)).isEqualTo("F1");
        assertThat(Text.slice("F1KL", 3, 1)).isEmpty();
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
    void upperCaseNeedsACasedCharacterAndNoLowerCaseOne() {
        assertThat(Text.isUpper("NYC")).isTrue();
        assertThat(Text.isUpper("A1B")).isTrue();
        assertThat(Text.isUpper("\u00c9TE")).isTrue();
        assertThat(Text.isUpper("123")).isFalse();
        assertThat(Text.isUpper("")).isFalse();
        assertThat(Text.isUpper("Nyc")).isFalse();
        assertThat(Text.isUpper("N\u01c5C")).isFalse();
        assertThat(Text.isUpper("\u00df")).isFalse();
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
