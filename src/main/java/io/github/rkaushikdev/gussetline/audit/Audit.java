package io.github.rkaushikdev.gussetline.audit;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.engine.Engine;
import io.github.rkaushikdev.gussetline.engine.Priced;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.fcl.Fcl;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.trace.Stage;
import io.github.rkaushikdev.gussetline.trace.Trace;
import io.github.rkaushikdev.gussetline.trace.Verdict;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One offer, repriced from the tariff, with the disagreement named.
 *
 * <p>The audit is the whole reason the desk exists. A supplier sends an offer, we reprice the same
 * itinerary from the tariff ({@link #audit}) and compare. The interesting output is not whether the
 * numbers match, because they usually do, it is the shape of the disagreement when they do not.
 *
 * <p>Four shapes come up and they need different letters ({@link Finding}). A supplier that is too
 * high is money the customer should not have paid, and it is worth a claim. A supplier that is too
 * low is usually a fare we do not hold, which means our tariff is stale rather than their price being
 * wrong, and sending that as a finding is how a desk loses a supplier relationship. A tax difference
 * is neither, it is a filing we have not loaded. And a difference that lands exactly on a minimum
 * check is nearly always us: our check fired and theirs did not, or the other way round. So every
 * finding carries a class as well as an amount, and the class decides who reads it.
 *
 * @param offer the offer audited
 * @param finding what kind of disagreement it is
 * @param ours our base fare, when we could price it
 * @param theirs the supplier's base fare, or its total when we could not price it
 * @param difference theirs minus ours, when both are known
 * @param lineDifferences how the fare calculation lines disagree, in words
 * @param reasons why it could not be priced, or what refused the price we built
 * @param priced the price we built, kept for the letter
 */
public record Audit(
        Offer offer,
        Finding finding,
        Optional<Money> ours,
        Optional<Money> theirs,
        Optional<Money> difference,
        List<String> lineDifferences,
        List<String> reasons,
        Optional<Priced> priced) {

    /**
     * Below this the desk does not write to anybody. It is one rounding unit in most currencies and
     * chasing it costs more than it recovers.
     */
    public static final BigDecimal MATERIAL = new BigDecimal("1.00");

    public Audit {
        Objects.requireNonNull(offer, "offer");
        Objects.requireNonNull(finding, "finding");
        Objects.requireNonNull(ours, "ours");
        Objects.requireNonNull(theirs, "theirs");
        Objects.requireNonNull(difference, "difference");
        lineDifferences = List.copyOf(lineDifferences);
        reasons = List.copyOf(reasons);
        Objects.requireNonNull(priced, "priced");
    }

    /** A finding with nothing else known about it. */
    public Audit(Offer offer, Finding finding) {
        this(
                offer,
                finding,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                List.of(),
                List.of(),
                Optional.empty());
    }

    /** A builder for a finding, every other part starting empty. */
    public static Builder builder(Offer offer, Finding finding) {
        return new Builder(offer, finding);
    }

    /** {@link #audit(Offer, Tariffs, Converter, LocalDate, Optional, Optional)} broken where the engine breaks it, on a trace of its own. */
    public static Audit audit(Offer offer, Tariffs tariffs, Converter converter, LocalDate ticketing) {
        return audit(offer, tariffs, converter, ticketing, Optional.empty(), Optional.empty());
    }

    /** {@link #audit(Offer, Tariffs, Converter, LocalDate, Optional, Optional)} broken where the supplier broke it, on a trace of its own. */
    public static Audit audit(
            Offer offer, Tariffs tariffs, Converter converter, LocalDate ticketing, List<Integer> breaks) {
        return audit(offer, tariffs, converter, ticketing, Optional.of(breaks), Optional.empty());
    }

    /** {@link #audit(Offer, Tariffs, Converter, LocalDate, Optional, Optional)} broken where the engine breaks it, recording on the trace given. */
    public static Audit audit(Offer offer, Tariffs tariffs, Converter converter, LocalDate ticketing, Trace trace) {
        return audit(offer, tariffs, converter, ticketing, Optional.empty(), Optional.of(trace));
    }

    /**
     * Reprice an offer from the tariff, in the offer's currency, and name the disagreement.
     *
     * <p>{@code breaks} is where the supplier broke the fare, which comes off the coupons and is not
     * something to guess at. Reprice against a different split and every finding is about the split
     * rather than about the price.
     *
     * <p>Our base fare is compared with the supplier's base fare. A gap under {@link #MATERIAL}
     * agrees; otherwise a minimum check we added, a breakdown that does not add up, and then the sign
     * of the gap decide the finding. The finding is recorded as an {@link Stage#AUDIT} step naming
     * {@code ours} and {@code theirs}: a pass when it agrees, a note for a low supplier or a minimum
     * check, a failure otherwise. An itinerary the tariff has no fare for is {@link
     * Finding#NOT_PRICEABLE}, recorded as a failed step with the refusal's details; any other refusal
     * is thrown. Without a trace, one named for the first eight characters of the offer's fingerprint
     * is started.
     *
     * @param breaks where the components split, when the coupons say
     * @param trace the trace to record on, when the caller keeps one
     * @throws io.github.rkaushikdev.gussetline.GussetlineError for any refusal the engine raises other
     *     than a missing fare
     */
    public static Audit audit(
            Offer offer,
            Tariffs tariffs,
            Converter converter,
            LocalDate ticketing,
            Optional<List<Integer>> breaks,
            Optional<Trace> trace) {
        Trace trail = trace.orElseGet(() -> new Trace(offer.fingerprint().substring(0, 8)));
        Priced priced;
        try {
            priced = Engine.price(
                    offer.itinerary(), tariffs, converter, ticketing, offer.currency(), breaks, Optional.of(trail));
        } catch (NoFareFound refused) {
            trail.failedStep(Stage.AUDIT, "could not reprice from the tariff", refused.details());
            return builder(offer, Finding.NOT_PRICEABLE)
                    .theirs(offer.total())
                    .reasons(List.of(refused.getMessage()))
                    .build();
        }

        Money ours = priced.baseFare();
        Money theirs = offer.breakdown().base();
        Money difference = theirs.minus(ours);

        Finding finding = classify(difference, priced, offer);
        trail.record(
                Stage.AUDIT,
                verdict(finding),
                finding.value() + " by " + difference.abs(),
                Details.of("ours", ours.toString(), "theirs", theirs.toString()));
        return builder(offer, finding)
                .ours(ours)
                .theirs(theirs)
                .difference(difference)
                .reasons(priced.refusals())
                .priced(priced)
                .build();
    }

    /**
     * Compare a supplier's fare calculation line against ours, every disagreement in words. Empty when
     * they agree.
     *
     * <p>Worth running even when the totals agree. Two lines that reach the same number by different
     * routes mean one of us has the wrong fare and the other has a compensating error, and that pair
     * will not agree next month.
     *
     * @throws io.github.rkaushikdev.gussetline.ConstructionError when the supplier's line carries no
     *     neutral total or no rate of exchange
     */
    public static List<String> auditLine(String offerLine, Priced priced) {
        return Fcl.compare(priced.line(), offerLine);
    }

    /**
     * What the claimable findings in a run are worth together, zero when there are none.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a claim is in another currency
     */
    public static Money recoveredTotal(List<Audit> audits, String currency) {
        Money running = Money.zero(currency);
        for (Audit item : audits) {
            if (item.claimable()) {
                running = running.plus(item.recovered());
            }
        }
        return running;
    }

    /** How a run came out, which is the first line of the daily report: each finding's name and count, in the order first met. */
    public static Map<String, Integer> byFinding(List<Audit> audits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Audit item : audits) {
            counts.merge(item.finding().value(), 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Whether the gap is worth anybody's time: at least {@link #MATERIAL} either way. */
    public boolean material() {
        return difference
                .map(gap -> gap.abs().amount().compareTo(MATERIAL) >= 0)
                .orElse(false);
    }

    /** A finding the desk will actually send to the supplier: a material supplier high. */
    public boolean claimable() {
        return material() && finding == Finding.SUPPLIER_HIGH;
    }

    /** What a successful claim would be worth. Zero, in the offer's currency, unless claimable. */
    public Money recovered() {
        if (!claimable() || difference.isEmpty()) {
            return Money.zero(offer.currency());
        }
        return difference.get().abs();
    }

    /** {@code gds agrees at 840.00 EUR}, {@code gds supplier_high by 60.00 EUR}, or the finding alone. */
    @Override
    public String toString() {
        if (finding == Finding.AGREES) {
            return offer.source() + " agrees at " + Text.plain(theirs.orElse(null));
        }
        if (difference.isEmpty()) {
            return offer.source() + " " + finding.value();
        }
        return offer.source() + " " + finding.value() + " by "
                + difference.get().abs();
    }

    private static Finding classify(Money difference, Priced priced, Offer offer) {
        if (difference.abs().amount().compareTo(MATERIAL) < 0) {
            return Finding.AGREES;
        }
        if (priced.construction().minimumAdded().signum() > 0) {
            return Finding.MINIMUM_CHECK;
        }
        if (!offer.breakdown().addsUp()) {
            return Finding.TAX_DIFFERENCE;
        }
        if (difference.amount().signum() > 0) {
            return Finding.SUPPLIER_HIGH;
        }
        return Finding.SUPPLIER_LOW;
    }

    private static Verdict verdict(Finding finding) {
        return switch (finding) {
            case AGREES -> Verdict.PASS;
            case SUPPLIER_LOW, MINIMUM_CHECK -> Verdict.INFO;
            default -> Verdict.FAIL;
        };
    }

    /** Collects the parts of an {@link Audit}. Every setter refuses null and returns the builder. */
    public static final class Builder {

        private final Offer offer;
        private final Finding finding;
        private Optional<Money> ours = Optional.empty();
        private Optional<Money> theirs = Optional.empty();
        private Optional<Money> difference = Optional.empty();
        private List<String> lineDifferences = List.of();
        private List<String> reasons = List.of();
        private Optional<Priced> priced = Optional.empty();

        private Builder(Offer offer, Finding finding) {
            this.offer = Objects.requireNonNull(offer, "offer");
            this.finding = Objects.requireNonNull(finding, "finding");
        }

        public Builder ours(Money amount) {
            this.ours = Optional.of(amount);
            return this;
        }

        public Builder theirs(Money amount) {
            this.theirs = Optional.of(amount);
            return this;
        }

        public Builder difference(Money amount) {
            this.difference = Optional.of(amount);
            return this;
        }

        public Builder lineDifferences(List<String> found) {
            this.lineDifferences = List.copyOf(found);
            return this;
        }

        public Builder reasons(List<String> found) {
            this.reasons = List.copyOf(found);
            return this;
        }

        public Builder priced(Priced result) {
            this.priced = Optional.of(result);
            return this;
        }

        public Audit build() {
            return new Audit(offer, finding, ours, theirs, difference, lineDifferences, reasons, priced);
        }
    }
}
