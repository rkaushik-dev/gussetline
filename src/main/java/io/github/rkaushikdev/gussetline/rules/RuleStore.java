package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NoRuleFound;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Rules by carrier, number and tariff, which is how a fare names one. Filing a rule under a key
 * already held replaces it.
 *
 * <p>The lookups take the carrier in any case and the number and tariff with surrounding white
 * space, and the tariff defaults to 1.
 */
public final class RuleStore {

    private final Map<Key, RuleSet> rules = new LinkedHashMap<>();

    /** Hold a rule, replacing any held under its carrier, number and tariff, and hand it back. */
    public RuleSet add(RuleSet rule) {
        rules.put(new Key(rule.carrier(), rule.number(), rule.tariff()), rule);
        return rule;
    }

    /** {@link #get(String, String, String)} in tariff 1. */
    public RuleSet get(String carrier, String number) {
        return get(carrier, number, "1");
    }

    /**
     * The rule filed under a carrier, number and tariff.
     *
     * @throws NoRuleFound when none is filed, naming the {@code carrier}, {@code number} and {@code
     *     tariff} asked for
     * @throws ValidationError when the carrier designator is malformed
     */
    public RuleSet get(String carrier, String number, String tariff) {
        Key key = keyOf(carrier, number, tariff);
        RuleSet found = rules.get(key);
        if (found == null) {
            throw new NoRuleFound(
                    "no rule filed",
                    Details.of("carrier", key.carrier(), "number", key.number(), "tariff", key.tariff()));
        }
        return found;
    }

    /** {@link #find(String, String, String)} in tariff 1. */
    public Optional<RuleSet> find(String carrier, String number) {
        return find(carrier, number, "1");
    }

    /**
     * The rule filed under a carrier, number and tariff, or empty when none is.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public Optional<RuleSet> find(String carrier, String number, String tariff) {
        return Optional.ofNullable(rules.get(keyOf(carrier, number, tariff)));
    }

    /** {@link #remove(String, String, String)} in tariff 1. */
    public boolean remove(String carrier, String number) {
        return remove(carrier, number, "1");
    }

    /**
     * Drop a rule the carrier has withdrawn. Says whether it was there.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public boolean remove(String carrier, String number, String tariff) {
        return rules.remove(keyOf(carrier, number, tariff)) != null;
    }

    /**
     * Every rule number held for one carrier, sorted, for a dump or a check.
     *
     * @throws ValidationError when the carrier designator is malformed
     */
    public List<String> numbers(String carrier) {
        String code = Carriers.normaliseCode(carrier);
        return rules.keySet().stream()
                .filter(key -> key.carrier().equals(code))
                .map(Key::number)
                .sorted()
                .toList();
    }

    /** How many rules are held. */
    public int size() {
        return rules.size();
    }

    private static Key keyOf(String carrier, String number, String tariff) {
        return new Key(Carriers.normaliseCode(carrier), Text.strip(number), Text.strip(tariff));
    }

    private record Key(String carrier, String number, String tariff) {}
}
