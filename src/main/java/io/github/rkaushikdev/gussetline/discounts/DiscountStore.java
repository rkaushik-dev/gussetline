package io.github.rkaushikdev.gussetline.discounts;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Filed discounts by carrier, in the order they were loaded. */
public final class DiscountStore {

    private final Map<String, List<Discount>> byCarrier = new LinkedHashMap<>();

    /** File a discount after those its carrier already has, and hand it back. */
    public Discount add(Discount discount) {
        byCarrier
                .computeIfAbsent(discount.carrier(), carrier -> new ArrayList<>())
                .add(discount);
        return discount;
    }

    /**
     * A carrier's discounts in the order they were loaded, given its designator in any case.
     *
     * @throws ValidationError when the designator is malformed
     */
    public List<Discount> forCarrier(String carrier) {
        return List.copyOf(byCarrier.getOrDefault(Carriers.normaliseCode(carrier), List.of()));
    }

    /**
     * The first filing that covers this passenger, or empty.
     *
     * <p>First and not best, because the tariff is ordered and a carrier that files a general child
     * discount and then a narrower one for a specific market means the narrower one to be read
     * first.
     *
     * @throws ValidationError when the designator is malformed
     */
    public Optional<Discount> find(String carrier, Passenger passenger, LocalDate departure) {
        return forCarrier(carrier).stream()
                .filter(discount -> discount.covers(passenger, departure))
                .findFirst();
    }

    /** How many discounts are filed, across every carrier. */
    public int size() {
        return byCarrier.values().stream().mapToInt(List::size).sum();
    }
}
