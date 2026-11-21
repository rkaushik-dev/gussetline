package io.github.rkaushikdev.gussetline.reports;

import static io.github.rkaushikdev.gussetline.Fixtures.at;
import static io.github.rkaushikdev.gussetline.Fixtures.segment;
import static io.github.rkaushikdev.gussetline.reports.Discrepancy.CASE_THRESHOLD;
import static io.github.rkaushikdev.gussetline.reports.Discrepancy.build;
import static io.github.rkaushikdev.gussetline.reports.Discrepancy.keyFor;
import static io.github.rkaushikdev.gussetline.reports.Discrepancy.render;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.audit.Audit;
import io.github.rkaushikdev.gussetline.audit.Finding;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.reports.Discrepancy.Case;
import io.github.rkaushikdev.gussetline.reports.Discrepancy.Key;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ReportsDiscrepancyTest {

    private static final LocalDateTime SEEN = at("2025-07-16 07:00");

    /**
     * One audit as a test describes it: a supplier high by three euros on a gds offer for LH400 from
     * Frankfurt to New York on KLWAP7RT, unless told otherwise. Each {@link #audit()} is a fresh one.
     */
    private static final class Seen {

        private Finding kind = Finding.SUPPLIER_HIGH;
        private String gap = "3.00";
        private String currency = "EUR";
        private String source = "gds";
        private String destination = "JFK";
        private String carrier = "LH";
        private String basis = "KLWAP7RT";

        Seen kind(Finding value) {
            kind = value;
            return this;
        }

        Seen gap(String value) {
            gap = value;
            return this;
        }

        Seen currency(String value) {
            currency = value;
            return this;
        }

        Seen source(String value) {
            source = value;
            return this;
        }

        Seen destination(String value) {
            destination = value;
            return this;
        }

        Seen carrier(String value) {
            carrier = value;
            return this;
        }

        Seen basis(String value) {
            basis = value;
            return this;
        }

        Audit audit() {
            return Audit.builder(offer(source, destination, carrier, basis, currency), kind)
                    .ours(Money.of("897.00", currency))
                    .theirs(Money.of("900.00", currency))
                    .difference(Money.of(gap, currency))
                    .build();
        }
    }

    private static Offer offer() {
        return offer("gds", "JFK", "LH", "KLWAP7RT", "EUR");
    }

    private static Offer offer(String source, String destination, String carrier, String basis, String currency) {
        return Offer.builder()
                .source(source)
                .itinerary(new Itinerary(
                        List.of(segment(carrier, "400", "FRA", destination, "2025-08-04 10:00", "2025-08-04 12:40"))))
                .breakdown(new PriceBreakdown(Money.of("900.00", currency)))
                .fareBases(List.of(basis))
                .observedAt(SEEN)
                .validatingCarrier(carrier)
                .build();
    }

    private static Seen finding() {
        return new Seen();
    }

    private static Audit agreed() {
        Offer made = offer();
        return Audit.builder(made, Finding.AGREES)
                .difference(Money.zero(made.currency()))
                .build();
    }

    /** {@code count} fresh audits of one description. */
    private static List<Audit> times(int count, Seen seen) {
        return IntStream.range(0, count).mapToObj(index -> seen.audit()).toList();
    }

    private static List<Audit> joined(List<Audit> first, List<Audit> second) {
        List<Audit> rows = new ArrayList<>(first);
        rows.addAll(second);
        return rows;
    }

    /** How many times a part occurs in a text, without overlaps. */
    private static int occurrences(String text, String part) {
        int count = 0;
        for (int from = text.indexOf(part); from >= 0; from = text.indexOf(part, from + part.length())) {
            count += 1;
        }
        return count;
    }

    @Test
    void nothingAuditedReadsAsNothing() {
        Discrepancy report = build(List.of(), "EUR");
        assertThat(report.audited()).isZero();
        assertThat(report.claims()).isEmpty();
        assertThat(report.claimValue()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void theOnesThatAgreeAreCountedAndGoNoFurther() {
        Discrepancy report = build(List.of(agreed(), agreed(), finding().audit()), "EUR");
        assertThat(report.audited()).isEqualTo(3);
        assertThat(report.agreed()).isEqualTo(2);
        assertThat(report.noise()).isEqualTo(1);
    }

    @Test
    void oneOfferOnItsOwnIsNoiseAndNotALetter() {
        Discrepancy report = build(List.of(finding().audit()), "EUR");
        assertThat(report.claims()).isEmpty();
        assertThat(report.noise()).isEqualTo(1);
        assertThat(report.singles()).hasSize(1);
    }

    @Test
    void enoughOffersAgreeingMakesItACase() {
        Discrepancy report = build(times(CASE_THRESHOLD, finding()), "EUR");
        assertThat(report.claims()).hasSize(1);
        Case found = report.claims().get(0);
        assertThat(found.offers()).isEqualTo(CASE_THRESHOLD);
        assertThat(found.worth()).isEqualTo(Money.of("9.00", "EUR"));
        assertThat(report.noise()).isZero();
    }

    @Test
    void offersThatDisagreeAboutTheSizeDoNotGroup() {
        // Same carrier, same market, same fare, but one is three euros out and the
        // other is ninety. Those are two problems and grouping them hides both.
        Discrepancy report = build(
                List.of(
                        finding().gap("3.00").audit(),
                        finding().gap("3.00").audit(),
                        finding().gap("90.00").audit()),
                "EUR");
        assertThat(report.claims()).isEmpty();
        assertThat(report.singles()).hasSize(2);
    }

    @Test
    void aGapIsRoundedBeforeItIsCompared() {
        List<Audit> rows = List.of(
                finding().gap("3.10").audit(),
                finding().gap("3.40").audit(),
                finding().gap("2.90").audit());
        Discrepancy report = build(rows, "EUR");
        assertThat(report.claims()).hasSize(1);
        assertThat(report.claims().get(0).key().gap()).isEqualByComparingTo("3");
    }

    @Test
    void theWorthIsTheRealAmountsAndNotTheRoundedOnes() {
        List<Audit> rows = List.of(
                finding().gap("3.10").audit(),
                finding().gap("3.40").audit(),
                finding().gap("2.90").audit());
        assertThat(build(rows, "EUR").claims().get(0).worth()).isEqualTo(Money.of("9.40", "EUR"));
    }

    @Test
    void twoCarriersAreTwoGroupsHoweverAlikeTheGapIs() {
        List<Audit> rows =
                joined(times(3, finding().carrier("LH")), times(3, finding().carrier("UA")));
        Discrepancy report = build(rows, "EUR");
        assertThat(report.claims()).hasSize(2);
        assertThat(report.claims().stream().map(found -> found.key().carrier())).containsExactlyInAnyOrder("LH", "UA");
    }

    @Test
    void twoMarketsAreTwoGroups() {
        List<Audit> rows = joined(
                times(3, finding().destination("JFK")), times(3, finding().destination("ORD")));
        assertThat(build(rows, "EUR").claims()).hasSize(2);
    }

    @Test
    void twoFareBasesAreTwoGroups() {
        List<Audit> rows =
                joined(times(3, finding().basis("KLWAP7RT")), times(3, finding().basis("MLWAP7RT")));
        assertThat(build(rows, "EUR").claims()).hasSize(2);
    }

    @Test
    void twoSuppliersAreTwoGroupsBecauseTheLettersGoToTwoPlaces() {
        List<Audit> rows =
                joined(times(3, finding().source("gds")), times(3, finding().source("ndc")));
        assertThat(build(rows, "EUR").claims().stream().map(found -> found.key().source()))
                .containsExactlyInAnyOrder("gds", "ndc");
    }

    @Test
    void aSupplierComingInLowIsOurGapAndNeverAClaim() {
        Discrepancy report = build(times(4, finding().kind(Finding.SUPPLIER_LOW)), "EUR");
        assertThat(report.claims()).isEmpty();
        assertThat(report.ourGaps()).hasSize(1);
        assertThat(report.ourGaps().get(0).offers()).isEqualTo(4);
    }

    @Test
    void aTaxDifferenceGroupsButIsNotAClaimEither() {
        Discrepancy report = build(times(3, finding().kind(Finding.TAX_DIFFERENCE)), "EUR");
        assertThat(report.claims()).isEmpty();
        assertThat(report.cases()).hasSize(1);
    }

    @Test
    void somethingThatWouldNotPriceIsCountedAndSetAside() {
        Discrepancy report = build(List.of(new Audit(offer(), Finding.NOT_PRICEABLE)), "EUR");
        assertThat(report.notPriceable()).isEqualTo(1);
        assertThat(report.cases()).isEmpty();
    }

    @Test
    void aGapTooSmallToChaseNeverReachesAGroup() {
        Discrepancy report = build(times(5, finding().gap("0.40")), "EUR");
        assertThat(report.cases()).isEmpty();
        assertThat(report.noise()).isZero();
    }

    @Test
    void anotherCurrencyIsSetAsideRatherThanConverted() {
        // Converting here would use today's rate on a fare quoted in March, and
        // the resulting total reconciles against nothing.
        Discrepancy report = build(times(4, finding().currency("GBP")), "EUR");
        assertThat(report.otherCurrency()).isEqualTo(4);
        assertThat(report.cases()).isEmpty();
        assertThat(report.claimValue()).isEqualTo(Money.zero("EUR"));
    }

    @Test
    void theClaimValueOnlyCountsTheGroupsBigEnoughToSend() {
        List<Audit> rows =
                joined(times(3, finding()), List.of(finding().gap("50.00").audit()));
        Discrepancy report = build(rows, "EUR");
        assertThat(report.claimValue()).isEqualTo(Money.of("9.00", "EUR"));
    }

    @Test
    void theBiggestGroupsComeFirst() {
        List<Audit> rows =
                joined(times(5, finding().carrier("LH")), times(3, finding().carrier("UA")));
        assertThat(build(rows, "EUR").cases().stream().map(found -> found.key().carrier()))
                .containsExactly("LH", "UA");
    }

    @Test
    void aKeySaysWhatWouldHaveToMatch() {
        Key key = keyFor(finding().audit());
        assertThat(key.source()).isEqualTo("gds");
        assertThat(key.carrier()).isEqualTo("LH");
        assertThat(key.market()).isEqualTo("FRA-JFK");
        assertThat(key.fareBasis()).isEqualTo("KLWAP7RT");
        assertThat(key).hasToString("gds LH FRA-JFK KLWAP7RT off by 3");
    }

    @Test
    void aCaseReadsBackWithItsSizeAndItsWorth() {
        Case found = build(times(3, finding()), "EUR").claims().get(0);
        assertThat(found).hasToString("gds LH FRA-JFK KLWAP7RT off by 3 on 3 offers worth 9.00 EUR");
    }

    @Test
    void theReportReadsBackWithTheThreeNumbersThatMatter() {
        Discrepancy report =
                build(joined(times(3, finding()), List.of(finding().gap("70.00").audit())), "EUR");
        assertThat(report).hasToString("4 audited, 1 claims worth 9.00 EUR, 1 left as noise");
    }

    @Test
    void theRenderedReportSeparatesTheLettersFromOurOwnGaps() {
        List<Audit> rows = joined(
                times(3, finding()),
                times(3, finding().kind(Finding.SUPPLIER_LOW).carrier("UA")));
        String out = render(build(rows, "EUR"));
        assertThat(out).contains("worth a letter");
        assertThat(out).contains("fares we do not hold");
        assertThat(out).contains("claims          1 worth 9.00 EUR");
    }

    @Test
    void theRenderedReportLeavesOutTheSectionsItHasNothingFor() {
        String out = render(build(List.of(agreed()), "EUR"));
        assertThat(out).doesNotContain("worth a letter");
        assertThat(out).doesNotContain("fares we do not hold");
        assertThat(out).contains("audited         1");
    }

    @Test
    void theRenderedReportHonoursTheLimit() {
        List<Audit> rows = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            rows.addAll(times(3, finding().basis("KLWAP7R" + index)));
        }
        String out = render(build(rows, "EUR"), 2);
        assertThat(occurrences(out, "worth 9.00 EUR")).isEqualTo(2);
    }

    @Test
    void aCutListOfClaimsSaysHowManyThereWere() {
        // It used to stop at the limit and say nothing, so a report with eleven
        // claims printed ten and looked complete. The person who writes the
        // letters had no way to know about the eleventh.
        List<Audit> rows = new ArrayList<>();
        for (int index = 0; index < 6; index++) {
            rows.addAll(times(3, finding().basis("KLWAP7R" + index)));
        }
        String out = render(build(rows, "EUR"), 2);
        assertThat(out).contains("worth a letter, showing 2 of 6");
    }

    @Test
    void aListOfClaimsThatFitsSaysNothingAboutTheCount() {
        String out = render(build(times(3, finding()), "EUR"), 10);
        assertThat(out).contains("worth a letter");
        assertThat(out).doesNotContain("showing");
    }
}
