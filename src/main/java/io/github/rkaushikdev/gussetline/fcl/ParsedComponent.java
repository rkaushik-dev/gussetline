package io.github.rkaushikdev.gussetline.fcl;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;

/**
 * One component read back off a supplier's fare calculation line.
 *
 * <p>Two read components are equal when they name the same points, carriers, band and connections
 * and their amounts are the same numbers, whatever their scale.
 *
 * @param points the cities it passes through, from the one it starts at to the one it ends at
 * @param carriers the carrier written before each hop, {@code //} for a surface sector
 * @param amount the amount written for it, in neutral units, a mileage band folded in
 * @param qSurcharge the Q surcharge written in front of the amount, zero when there is none
 * @param band the mileage band written, such as {@code 5M}, or empty
 * @param connections the points marked {@code X/}, where the passenger only connected
 */
public record ParsedComponent(
        List<String> points,
        List<String> carriers,
        BigDecimal amount,
        BigDecimal qSurcharge,
        String band,
        List<String> connections) {

    /** Keeps copies of the lists. */
    public ParsedComponent {
        points = List.copyOf(points);
        carriers = List.copyOf(carriers);
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(qSurcharge, "qSurcharge");
        Objects.requireNonNull(band, "band");
        connections = List.copyOf(connections);
    }

    /** A component with no Q surcharge, no band and no connection marked. */
    public ParsedComponent(List<String> points, List<String> carriers, BigDecimal amount) {
        this(points, carriers, amount, BigDecimal.ZERO, "", List.of());
    }

    /**
     * The city it starts at.
     *
     * @throws IndexOutOfBoundsException when it names no point
     */
    public String board() {
        return points.get(0);
    }

    /**
     * The city it ends at.
     *
     * @throws IndexOutOfBoundsException when it names no point
     */
    public String off() {
        return points.get(points.size() - 1);
    }

    /** Equal when everything matches and the amounts are the same numbers. */
    @Override
    public boolean equals(Object other) {
        return other instanceof ParsedComponent that
                && points.equals(that.points)
                && carriers.equals(that.carriers)
                && amount.compareTo(that.amount) == 0
                && qSurcharge.compareTo(that.qSurcharge) == 0
                && band.equals(that.band)
                && connections.equals(that.connections);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                points, carriers, amount.stripTrailingZeros(), qSurcharge.stripTrailingZeros(), band, connections);
    }

    /** Both ends and the amount as written: {@code FRANYC 441.64}. */
    @Override
    public String toString() {
        return board() + off() + " " + amount;
    }
}
