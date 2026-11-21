package io.github.rkaushikdev.gussetline.reports;

import io.github.rkaushikdev.gussetline.audit.Audit;
import io.github.rkaushikdev.gussetline.audit.Finding;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.reports.Layout.Figure;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turning a pile of audits into the few letters that are worth writing.
 *
 * <p>An audit run over a day produces thousands of findings and most of them are one offer apiece.
 * Sending those out one at a time is how the desk got a reputation for noise, because a single offer
 * that is three euros high is rounding, a bad rate of exchange on their side, or a fare filed the hour
 * after we loaded the tariff. Nobody can act on it and nobody should be asked to.
 *
 * <p>Forty offers on the same carrier, in the same market, on the same fare basis, all three euros
 * high, is not rounding. It is a filing error, and it is one letter about one fare rather than forty
 * letters about forty tickets. So the report groups first and counts second, and a group only becomes
 * a case when enough of its members agree about the size of the gap as well as its direction.
 *
 * <p>The other thing the report insists on is not adding up money it cannot add up. The audits arrive
 * in whatever currency the supplier quoted, and a total that silently sums euros and pounds is worse
 * than no total. Anything outside the report's own currency is counted and set aside rather than
 * converted here, because converting at report time uses today's rate for a fare that was quoted in
 * March and the resulting number cannot be reconciled against anything.
 *
 * <p>Findings where the supplier came in low are kept, but they are not claims. A supplier lower than
 * our reprice nearly always means we are missing a fare, so those go in their own section under our
 * own name, and the daily habit of reading that section is what keeps the tariff loaded.
 *
 * @param currency the report's own currency
 * @param audited how many audits were read
 * @param agreed how many agreed
 * @param notPriceable how many could not be priced from the tariff
 * @param otherCurrency how many were in another currency and set aside
 * @param cases the groups big enough to be a case, the biggest first
 * @param singles the groups too small to send, the biggest first
 */
public record Discrepancy(
        String currency,
        int audited,
        int agreed,
        int notPriceable,
        int otherCurrency,
        List<Case> cases,
        List<Case> singles) {

    /** How many offers have to agree before a group is worth writing about. */
    public static final int CASE_THRESHOLD = 3;

    /** Gaps are grouped to the nearest whole unit, half to even, before they are counted as equal. */
    public static final BigDecimal GAP_STEP = new BigDecimal("1");

    /** How many rows of each list a rendered report shows unless told otherwise. */
    private static final int SHOWN = 10;

    /** Biggest group first, then by supplier, carrier and market. */
    private static final Comparator<Case> BIGGEST_FIRST = Comparator.comparingInt(Case::offers)
            .reversed()
            .thenComparing(found -> found.key().source(), Text.CODE_POINT_ORDER)
            .thenComparing(found -> found.key().carrier(), Text.CODE_POINT_ORDER)
            .thenComparing(found -> found.key().market(), Text.CODE_POINT_ORDER);

    public Discrepancy {
        Objects.requireNonNull(currency, "currency");
        cases = List.copyOf(cases);
        singles = List.copyOf(singles);
    }

    /** The groups the desk will write to a supplier about, in order. */
    public List<Case> claims() {
        return cases.stream().filter(Case::claimable).toList();
    }

    /** Groups where the supplier beat our tariff, which is our problem, in order. */
    public List<Case> ourGaps() {
        return cases.stream()
                .filter(found -> found.key().finding() == Finding.SUPPLIER_LOW)
                .toList();
    }

    /** What the claims are worth together, zero when there are none. */
    public Money claimValue() {
        Money running = Money.zero(currency);
        for (Case found : claims()) {
            running = running.plus(found.worth());
        }
        return running;
    }

    /** Findings that never made it into a group big enough to send. */
    public int noise() {
        return singles.stream().mapToInt(Case::offers).sum();
    }

    /** {@code 4 audited, 1 claims worth 9.00 EUR, 1 left as noise}. */
    @Override
    public String toString() {
        return audited + " audited, " + claims().size() + " claims worth " + claimValue() + ", " + noise()
                + " left as noise";
    }

    /**
     * What makes two findings the same problem rather than two problems.
     *
     * <p>Two keys are equal when every part is, the gap compared as a number whatever its scale.
     *
     * @param source the supplier, which is who the letter goes to
     * @param carrier the validating carrier, or the first marketing carrier when none was named
     * @param market {@code FRA-JFK}
     * @param fareBasis the first fare basis
     * @param finding what kind of disagreement it is
     * @param gap the size of the disagreement to the nearest {@link #GAP_STEP}
     */
    public record Key(String source, String carrier, String market, String fareBasis, Finding finding, BigDecimal gap) {

        public Key {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(carrier, "carrier");
            Objects.requireNonNull(market, "market");
            Objects.requireNonNull(fareBasis, "fareBasis");
            Objects.requireNonNull(finding, "finding");
            Objects.requireNonNull(gap, "gap");
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Key that
                    && source.equals(that.source)
                    && carrier.equals(that.carrier)
                    && market.equals(that.market)
                    && fareBasis.equals(that.fareBasis)
                    && finding == that.finding
                    && gap.compareTo(that.gap) == 0;
        }

        @Override
        public int hashCode() {
            return Objects.hash(source, carrier, market, fareBasis, finding, gap.stripTrailingZeros());
        }

        /** {@code gds LH FRA-JFK KLWAP7RT off by 3}. */
        @Override
        public String toString() {
            return source + " " + carrier + " " + market + " " + fareBasis + " off by " + gap;
        }
    }

    /**
     * One group of findings that agree about the direction and the size.
     *
     * @param key what the findings share
     * @param audits the findings, in the order read
     * @param currency the currency the group's worth is added up in
     */
    public record Case(Key key, List<Audit> audits, String currency) {

        public Case {
            Objects.requireNonNull(key, "key");
            audits = List.copyOf(audits);
            Objects.requireNonNull(currency, "currency");
        }

        /** How many offers are in the group. */
        public int offers() {
            return audits.size();
        }

        /** What the whole group is worth if the supplier pays it: every real gap, not the rounded one. */
        public Money worth() {
            Money running = Money.zero(currency);
            for (Audit item : audits) {
                if (item.difference().isPresent()) {
                    running = running.plus(item.difference().get().abs());
                }
            }
            return running;
        }

        /** Whether enough offers agree to make this worth a letter: at least {@link #CASE_THRESHOLD}. */
        public boolean isACase() {
            return offers() >= CASE_THRESHOLD;
        }

        /** Whether it is a case and the supplier came in high, which is what a claim is. */
        public boolean claimable() {
            return isACase() && key.finding() == Finding.SUPPLIER_HIGH;
        }

        /** {@code gds LH FRA-JFK KLWAP7RT off by 3 on 3 offers worth 9.00 EUR}. */
        @Override
        public String toString() {
            return key + " on " + offers() + " offers worth " + worth();
        }
    }

    /** What this finding would have to share with another to be the same one. */
    public static Key keyFor(Audit item) {
        Offer offer = item.offer();
        return new Key(
                offer.source(),
                carrier(offer),
                offer.origin() + "-" + offer.destination(),
                fareBasis(offer),
                item.finding(),
                gap(item));
    }

    /**
     * Group a run of audits and split the letters from the noise.
     *
     * <p>Every audit is counted. One that agrees, one that could not be priced, and one in another
     * currency than the report's are each counted and go no further, and a gap under {@link
     * Audit#MATERIAL} is dropped. The rest are grouped by their {@linkplain #keyFor key}; a group of at
     * least {@link #CASE_THRESHOLD} is a case and anything smaller is a single. Both lists hold the
     * biggest group first, then go by supplier, carrier and market, and then the order first met.
     */
    public static Discrepancy build(Iterable<Audit> audits, String currency) {
        int audited = 0;
        int agreed = 0;
        int notPriceable = 0;
        int otherCurrency = 0;
        Map<Key, List<Audit>> groups = new LinkedHashMap<>();

        for (Audit item : audits) {
            audited += 1;
            if (item.finding() == Finding.AGREES) {
                agreed += 1;
                continue;
            }
            if (item.finding() == Finding.NOT_PRICEABLE) {
                notPriceable += 1;
                continue;
            }
            if (!item.offer().currency().equals(currency)) {
                otherCurrency += 1;
                continue;
            }
            if (!item.material()) {
                continue;
            }
            groups.computeIfAbsent(keyFor(item), key -> new ArrayList<>()).add(item);
        }

        List<Case> ordered = new ArrayList<>();
        groups.forEach((key, members) -> ordered.add(new Case(key, members, currency)));
        ordered.sort(BIGGEST_FIRST);
        return new Discrepancy(
                currency,
                audited,
                agreed,
                notPriceable,
                otherCurrency,
                ordered.stream().filter(Case::isACase).toList(),
                ordered.stream().filter(found -> !found.isACase()).toList());
    }

    /** {@link #render(Discrepancy, int)} showing ten of each list. */
    public static String render(Discrepancy report) {
        return render(report, SHOWN);
    }

    /**
     * The report as it goes to the person who writes the letters: the heading, the figures, then the
     * claims and our own gaps, each cut at {@code limit} rows and left out when it has nothing in it.
     */
    public static String render(Discrepancy report, int limit) {
        return Layout.page(
                Layout.heading("discrepancies in " + report.currency()),
                Layout.figures(
                        new Figure("audited", report.audited()),
                        new Figure("agreed", report.agreed()),
                        new Figure("not priceable", report.notPriceable()),
                        new Figure("other currency", report.otherCurrency()),
                        new Figure("claims", report.claims().size() + " worth " + report.claimValue()),
                        new Figure("noise", report.noise() + " findings in groups too small to send")),
                Layout.section("worth a letter", report.claims(), limit),
                Layout.section("fares we do not hold, which is ours to fix", report.ourGaps(), limit));
    }

    private static String carrier(Offer offer) {
        if (offer.validatingCarrier().isPresent()
                && !offer.validatingCarrier().get().isEmpty()) {
            return offer.validatingCarrier().get();
        }
        return offer.carriers().isEmpty() ? "??" : offer.carriers().get(0);
    }

    private static String fareBasis(Offer offer) {
        return offer.fareBases().isEmpty() ? "??" : offer.fareBases().get(0);
    }

    /** The size of the disagreement, rounded so near misses group together. */
    private static BigDecimal gap(Audit item) {
        if (item.difference().isEmpty()) {
            return BigDecimal.ZERO;
        }
        return item.difference().get().abs().amount().setScale(GAP_STEP.scale(), RoundingMode.HALF_EVEN);
    }
}
