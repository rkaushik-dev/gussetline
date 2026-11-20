package io.github.rkaushikdev.gussetline.reports;

import static io.github.rkaushikdev.gussetline.reports.Layout.GUTTER;
import static io.github.rkaushikdev.gussetline.reports.Layout.figures;
import static io.github.rkaushikdev.gussetline.reports.Layout.heading;
import static io.github.rkaushikdev.gussetline.reports.Layout.page;
import static io.github.rkaushikdev.gussetline.reports.Layout.section;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.reports.Layout.Figure;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReportsLayoutTest {

    @Test
    void namesArePaddedToTheWidestOneAndNotToATypedNumber() {
        List<String> lines = figures(new Figure("found", 1), new Figure("take rate", 2));
        assertThat(lines).containsExactly("found      1", "take rate  2");
    }

    @Test
    void theValuesAllStartInTheSameColumn() {
        List<String> lines = figures(new Figure("a", 1), new Figure("bb", 2), new Figure("ccc", 3));
        Set<Integer> columns = new HashSet<>();
        for (int index = 0; index < lines.size(); index++) {
            columns.add(lines.get(index).indexOf(String.valueOf(index + 1)));
        }
        assertThat(columns).hasSize(1);
    }

    @Test
    void aLongerNameMovesTheColumnForEverybody() {
        // The whole reason this is derived. Adding a figure used to leave the
        // block crooked until somebody counted the spaces again.
        List<String> narrow = figures(new Figure("found", 1));
        List<String> wide = figures(new Figure("found", 1), new Figure("other currency", 2));
        assertThat(wide.get(0).indexOf("1")).isGreaterThan(narrow.get(0).indexOf("1"));
    }

    @Test
    void theGutterIsTheSpaceBetweenTheWidestNameAndTheValues() {
        assertThat(figures(new Figure("found", 1)).get(0)).isEqualTo("found" + " ".repeat(GUTTER) + "1");
    }

    @Test
    void nothingToReportIsNoLinesAtAll() {
        assertThat(figures(List.of())).isEmpty();
    }

    @Test
    void aValueThatIsNotANumberIsPrintedAsItReads() {
        assertThat(figures(new Figure("claims", "1 worth 9.00 EUR"))).containsExactly("claims  1 worth 9.00 EUR");
    }

    @Test
    void aSectionWithNothingInItIsLeftOffThePage() {
        // An empty heading reads as a report that failed rather than as a desk
        // with no problems.
        assertThat(section("worth a letter", List.of())).isEmpty();
    }

    @Test
    void aSectionCarriesItsRowsIndentedUnderItsTitle() {
        assertThat(section("gaps", List.of("one", "two"))).containsExactly("", "gaps", "  one", "  two");
    }

    @Test
    void aSectionThatFitsSaysNothingAboutTheCount() {
        assertThat(section("gaps", List.of("one", "two"), 5).get(1)).isEqualTo("gaps");
    }

    @Test
    void aSectionThatIsCutSaysHowMuchOfItIsOnThePage() {
        // The count is usually the finding. Ten claims is a busy week and two
        // hundred is a supplier having an outage, and both print ten rows.
        List<String> lines = section("gaps", List.of("one", "two", "three"), 2);
        assertThat(lines.get(1)).isEqualTo("gaps, showing 2 of 3");
        assertThat(lines.subList(2, lines.size())).containsExactly("  one", "  two");
    }

    @Test
    void aSectionCutExactlyAtItsLimitDoesNotClaimToBeCut() {
        assertThat(section("gaps", List.of("one", "two"), 2).get(1)).isEqualTo("gaps");
    }

    @Test
    void aSectionPrintsEverythingWhenNobodySetsALimit() {
        List<String> rows = IntStream.range(0, 50).mapToObj(String::valueOf).toList();
        assertThat(section("gaps", rows)).hasSize(52);
    }

    @Test
    void aSectionTakesWhateverItIsGivenAndPrintsIt() {
        // A source that can be walked only once, read exactly once.
        Iterable<Integer> once = IntStream.range(0, 2).boxed()::iterator;
        assertThat(section("gaps", once)).containsExactly("", "gaps", "  0", "  1");
    }

    @Test
    void aHeadingLeavesABlankLineBeforeTheFigures() {
        assertThat(heading("savings in EUR")).containsExactly("savings in EUR", "");
    }

    @Test
    void aHeadingCarriesWhateverQualifiesIt() {
        assertThat(heading("reliability", "monday to friday")).containsExactly("reliability", "monday to friday", "");
    }

    @Test
    void aPageIsItsBlocksInOrder() {
        String out =
                page(heading("savings in EUR"), figures(new Figure("found", 1)), section("by month", List.of("july")));
        assertThat(out).isEqualTo("savings in EUR\n\nfound  1\n\nby month\n  july");
    }

    @Test
    void aPageOfNothingIsAnEmptyString() {
        assertThat(page()).isEmpty();
        assertThat(page(List.of(), List.of())).isEmpty();
    }
}
