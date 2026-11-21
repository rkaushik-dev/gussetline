package io.github.rkaushikdev.gussetline.fcl;

import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * A whole fare calculation line read back, with everything it claimed.
 *
 * <p>Two read lines are equal when their components and their text match and the total and the
 * rate are the same numbers, whatever their scale.
 *
 * @param components the components, in the order written
 * @param total the neutral total the line claims
 * @param roe the rate of exchange the line claims
 * @param raw the line as it was given
 */
public record ParsedLine(List<ParsedComponent> components, BigDecimal total, BigDecimal roe, String raw) {

    public ParsedLine {
        components = List.copyOf(components);
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(roe, "roe");
        Objects.requireNonNull(raw, "raw");
    }

    /** Whether the components' amounts and Q surcharges add to the total the line claims. */
    public boolean addsUp() {
        return parts().compareTo(total) == 0;
    }

    /** Total minus the parts. A minimum check shows up here and should. */
    public BigDecimal unexplained() {
        return total.subtract(parts(), Numbers.CONTEXT);
    }

    private BigDecimal parts() {
        return Numbers.sum(components.stream()
                .map(component -> component.amount().add(component.qSurcharge(), Numbers.CONTEXT))
                .toList());
    }

    /** Equal when the components and the text match and the numbers are the same. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ParsedLine that
                && components.equals(that.components)
                && total.compareTo(that.total) == 0
                && roe.compareTo(that.roe) == 0
                && raw.equals(that.raw);
    }

    @Override
    public int hashCode() {
        return Objects.hash(components, total.stripTrailingZeros(), roe.stripTrailingZeros(), raw);
    }
}
