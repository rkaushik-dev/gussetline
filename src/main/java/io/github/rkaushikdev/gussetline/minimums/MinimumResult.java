package io.github.rkaushikdev.gussetline.minimums;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.Optional;

/**
 * What a minimum check found. Zero added is the usual answer.
 *
 * <p>Two results are equal when they add the same number, whatever its scale, for the same reason on
 * the same market, compared against the same number.
 *
 * @param added what the check raises the fare by, in neutral units; zero when it raises nothing
 * @param reason what the check found, in the words the desk reads
 * @param pair the market whose fare set the floor, when one did
 * @param compared that market's fare in neutral units, when one set the floor
 */
public record MinimumResult(BigDecimal added, String reason, Optional<CityPair> pair, Optional<BigDecimal> compared) {

    public MinimumResult {
        Objects.requireNonNull(added, "added");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(pair, "pair");
        Objects.requireNonNull(compared, "compared");
    }

    /** A finding with no market behind it. */
    public MinimumResult(BigDecimal added, String reason) {
        this(added, reason, Optional.empty(), Optional.empty());
    }

    /** A finding on a market, with the fare it was compared against left out. */
    public MinimumResult(BigDecimal added, String reason, CityPair pair) {
        this(added, reason, Optional.of(pair), Optional.empty());
    }

    /** A finding on a market, with the fare it was compared against. */
    public MinimumResult(BigDecimal added, String reason, CityPair pair, BigDecimal compared) {
        this(added, reason, Optional.of(pair), Optional.of(compared));
    }

    /** Whether the check raises the fare at all. */
    public boolean applied() {
        return added.signum() > 0;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof MinimumResult that
                && added.compareTo(that.added) == 0
                && reason.equals(that.reason)
                && pair.equals(that.pair)
                && compared.map(BigDecimal::stripTrailingZeros)
                        .equals(that.compared.map(BigDecimal::stripTrailingZeros));
    }

    @Override
    public int hashCode() {
        return Objects.hash(added.stripTrailingZeros(), reason, pair, compared.map(BigDecimal::stripTrailingZeros));
    }

    /**
     * The reason alone when nothing was added, otherwise the reason, the market and the amount: {@code
     * higher intermediate point on FRA-CHI, up 40.00 NUC}.
     */
    @Override
    public String toString() {
        if (!applied()) {
            return reason;
        }
        String where = pair.map(CityPair::toString).orElse("unknown");
        return reason + " on " + where + ", up " + added + " NUC";
    }
}
