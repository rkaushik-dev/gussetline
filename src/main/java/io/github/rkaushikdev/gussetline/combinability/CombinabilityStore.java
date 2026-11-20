package io.github.rkaushikdev.gussetline.combinability;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Combinability records by carrier and fare basis, one record for each pair. Filing a record for a
 * pair already held replaces it.
 */
public final class CombinabilityStore {

    private final Map<Key, Combinability> records = new LinkedHashMap<>();

    /** Hold a record, replacing any held for its carrier and fare basis, and hand it back. */
    public Combinability add(Combinability filed) {
        records.put(new Key(filed.carrier(), filed.fareBasis()), filed);
        return filed;
    }

    /**
     * The record for a carrier and fare basis, both given in any case and with surrounding white
     * space, or empty when none is filed.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public Optional<Combinability> get(String carrier, String fareBasis) {
        return Optional.ofNullable(records.get(keyOf(carrier, fareBasis)));
    }

    /**
     * Drop a record the carrier has withdrawn. Says whether it was there.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public boolean remove(String carrier, String fareBasis) {
        return records.remove(keyOf(carrier, fareBasis)) != null;
    }

    /**
     * Every fare basis held for one carrier, sorted, for a dump or a check.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public List<String> bases(String carrier) {
        String code = Carriers.normaliseCode(carrier);
        return records.keySet().stream()
                .filter(key -> key.carrier().equals(code))
                .map(Key::fareBasis)
                .sorted()
                .toList();
    }

    /** How many records are held. */
    public int size() {
        return records.size();
    }

    private static Key keyOf(String carrier, String fareBasis) {
        return new Key(Carriers.normaliseCode(carrier), Text.code(fareBasis));
    }

    private record Key(String carrier, String fareBasis) {}
}
