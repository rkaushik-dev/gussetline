package io.github.rkaushikdev.gussetline.taxes;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The filed taxes, looked up by the country a point sits in.
 *
 * <p>Carrier surcharges are kept apart from the country index, because they are owed on the
 * journey whatever country it touches, and because a surcharge that strayed into a country's taxes
 * would be counted in the government subtotal. Rules come back in the order they were filed.
 */
public final class TaxTable {

    private final Map<String, List<TaxRule>> byCountry = new LinkedHashMap<>();
    private final List<TaxRule> carrier = new ArrayList<>();

    /** File one rule. Returns the rule it was given. */
    public TaxRule add(TaxRule rule) {
        if (rule.isCarrierImposed()) {
            carrier.add(rule);
        } else {
            byCountry
                    .computeIfAbsent(rule.country(), country -> new ArrayList<>())
                    .add(rule);
        }
        return rule;
    }

    /** File several rules, in order. */
    public void addMany(Iterable<TaxRule> rules) {
        for (TaxRule rule : rules) {
            add(rule);
        }
    }

    /**
     * The rules a country files, read in any case and with white space around it. Empty when it
     * files none.
     */
    public List<TaxRule> forCountry(String code) {
        return List.copyOf(byCountry.getOrDefault(Text.code(code), List.of()));
    }

    /** The carrier surcharges, owed on every journey. */
    public List<TaxRule> carrierRules() {
        return List.copyOf(carrier);
    }

    /** How many rules are filed, carrier surcharges included. */
    public int size() {
        return byCountry.values().stream().mapToInt(List::size).sum() + carrier.size();
    }
}
