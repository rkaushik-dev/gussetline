package io.github.rkaushikdev.gussetline.addon;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * A fare built out of a gateway fare and at most one arbitrary at each end. {@link
 * AddOns#construct} builds one.
 *
 * @param fare the fare the construction gives, from the market's own origin to its own destination
 * @param gatewayFare the published fare it was built on
 * @param originAddOn the arbitrary at the origin end, if there is one
 * @param destinationAddOn the arbitrary at the destination end, if there is one
 * @param notes each arbitrary as the fare calculation notes it, the origin end first
 */
public record Constructed(
        Fare fare,
        Fare gatewayFare,
        Optional<AddOn> originAddOn,
        Optional<AddOn> destinationAddOn,
        List<String> notes) {

    public Constructed {
        Objects.requireNonNull(fare, "fare");
        Objects.requireNonNull(gatewayFare, "gatewayFare");
        Objects.requireNonNull(originAddOn, "originAddOn");
        Objects.requireNonNull(destinationAddOn, "destinationAddOn");
        notes = List.copyOf(notes);
    }

    /**
     * What the arbitraries added, which the fare calculation line shows: their amounts summed, then
     * cut to two places with anything beyond the second dropped rather than rounded.
     */
    public BigDecimal arbitraryTotal() {
        List<BigDecimal> amounts = Stream.of(originAddOn, destinationAddOn)
                .flatMap(Optional::stream)
                .map(addOn -> addOn.amount().amount())
                .toList();
        return Money.truncateNuc(Numbers.sum(amounts));
    }

    /** The ends an arbitrary attaches at, the origin first. */
    public List<End> ends() {
        List<End> ends = new ArrayList<>(2);
        originAddOn.ifPresent(addOn -> ends.add(End.ORIGIN));
        destinationAddOn.ifPresent(addOn -> ends.add(End.DESTINATION));
        return List.copyOf(ends);
    }

    /**
     * The fare basis, the gateway fare and what the arbitraries added: {@code KLWAP7RT built on LH
     * FRANYC KLWAP7RT 420.00 EUR plus 40.00}.
     */
    @Override
    public String toString() {
        return fare.fareBasis() + " built on " + gatewayFare + " plus " + arbitraryTotal();
    }
}
