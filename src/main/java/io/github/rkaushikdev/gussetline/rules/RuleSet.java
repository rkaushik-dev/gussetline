package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * One numbered rule and everything filed inside it.
 *
 * <p>A rule is refiled whole, never amended, so a rule with no provisions is a real thing: the rule
 * an unrestricted fare points at.
 *
 * @param carrier the filing carrier, upper case
 * @param number the rule number, without surrounding white space
 * @param provisions what is filed in the rule, in filing order
 * @param tariff the tariff number the rule is filed under, as given
 */
public record RuleSet(String carrier, String number, List<Provision> provisions, String tariff) {

    /**
     * Normalises the carrier and trims the number.
     *
     * @throws ValidationError when the carrier designator is malformed, or the number is blank
     */
    public RuleSet {
        carrier = Carriers.normaliseCode(Objects.requireNonNull(carrier, "carrier"));
        number = Text.strip(Objects.requireNonNull(number, "number"));
        provisions = List.copyOf(provisions);
        Objects.requireNonNull(tariff, "tariff");
        if (number.isEmpty()) {
            throw new ValidationError("a rule has a number");
        }
    }

    /** A rule with nothing filed in it, in tariff 1. */
    public RuleSet(String carrier, String number) {
        this(carrier, number, List.of(), "1");
    }

    /** A rule in tariff 1. */
    public RuleSet(String carrier, String number, List<? extends Provision> provisions) {
        this(carrier, number, List.<Provision>copyOf(provisions), "1");
    }

    /** The categories filed, each once, in the order they first appear. */
    public List<Category> categories() {
        return provisions.stream().map(Provision::category).distinct().toList();
    }

    /** Whether anything is filed under a category. */
    public boolean has(Category category) {
        return provisions.stream().anyMatch(provision -> provision.category() == category);
    }

    /** What is filed under one category, in filing order. */
    public List<Provision> forCategory(Category category) {
        return provisions.stream()
                .filter(provision -> provision.category() == category)
                .toList();
    }

    /** How many provisions are filed. */
    public int size() {
        return provisions.size();
    }

    /** The carrier, the number and the category numbers: {@code LH rule 1100 [5,6]}, or {@code [empty]}. */
    @Override
    public String toString() {
        String names = categories().stream()
                .map(category -> String.valueOf(category.value()))
                .collect(Collectors.joining(","));
        return carrier + " rule " + number + " [" + (names.isEmpty() ? "empty" : names) + "]";
    }
}
