package io.github.rkaushikdev.gussetline.engine;

import io.github.rkaushikdev.gussetline.construction.Construction;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.trace.Trace;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A finished price and everything needed to argue about it.
 *
 * @param construction the components, units and minimums the price was built from, with its trace
 * @param baseFare the base fare in the selling currency, converted once and rounded up
 * @param line the fare calculation line a ticket would carry
 * @param refusals every rule and combinability refusal, each in one line; empty when it can be sold
 */
public record Priced(Construction construction, Money baseFare, String line, List<String> refusals) {

    public Priced {
        Objects.requireNonNull(construction, "construction");
        Objects.requireNonNull(baseFare, "baseFare");
        Objects.requireNonNull(line, "line");
        refusals = List.copyOf(refusals);
    }

    /** A price nothing refused. */
    public Priced(Construction construction, Money baseFare, String line) {
        this(construction, baseFare, line, List.of());
    }

    /** The trail the price left, which is the construction's. */
    public Trace trace() {
        return construction.trace();
    }

    /** The neutral total, minimums included. */
    public BigDecimal totalNuc() {
        return construction.totalNuc();
    }

    /**
     * Whether this could actually be ticketed.
     *
     * <p>A price with refusals on it is still a price and is still worth showing, because the desk
     * needs to see what the supplier must have done differently. It just cannot be sold.
     */
    public boolean issuable() {
        return refusals.isEmpty();
    }

    /** The fare and the line: {@code 840 EUR on FRA LH NYC 420.00 LH FRA 420.00 NUC840.00END ROE1.000000}. */
    @Override
    public String toString() {
        return baseFare + " on " + line;
    }
}
