package io.github.rkaushikdev.gussetline.surcharges;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Filed surcharges, looked up by carrier, each carrier's in the order they were filed. */
public final class SurchargeTable {

    private final Map<String, List<QSurcharge>> byCarrier = new LinkedHashMap<>();

    /** Hold a filing after the others of its carrier, and hand it back. */
    public QSurcharge add(QSurcharge surcharge) {
        byCarrier
                .computeIfAbsent(surcharge.carrier(), carrier -> new ArrayList<>())
                .add(surcharge);
        return surcharge;
    }

    /** Hold each filing in turn. */
    public void addMany(Iterable<QSurcharge> surcharges) {
        for (QSurcharge surcharge : surcharges) {
            add(surcharge);
        }
    }

    /**
     * Every filing of one carrier, given in any case, in the order filed. Empty when it files none.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public List<QSurcharge> forCarrier(String carrier) {
        return List.copyOf(byCarrier.getOrDefault(Carriers.normaliseCode(carrier), List.of()));
    }

    /** How many filings are held, across every carrier. */
    public int size() {
        return byCarrier.values().stream().mapToInt(List::size).sum();
    }
}
