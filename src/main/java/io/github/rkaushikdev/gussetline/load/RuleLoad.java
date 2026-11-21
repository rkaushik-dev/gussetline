package io.github.rkaushikdev.gussetline.load;

import io.github.rkaushikdev.gussetline.collect.Problem;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * A rule file, read but not yet applied.
 *
 * @param rules every rule the file files, each with its provisions, in file order
 * @param deleted every rule the file withdraws, in file order
 * @param problems every line that did not read, the layout's refusals first and then the records
 *     refused by themselves and the orphans, in file order
 * @param orphans how many category records came before any rule header, each of them also a problem
 */
public record RuleLoad(List<RuleSet> rules, List<Withdrawn> deleted, List<Problem> problems, int orphans) {

    public RuleLoad {
        rules = List.copyOf(rules);
        deleted = List.copyOf(deleted);
        problems = List.copyOf(problems);
    }

    /** Whether every line read. */
    public boolean clean() {
        return problems.isEmpty();
    }

    /**
     * The first rule the file files for a carrier, given in any case, and a number, given exactly as
     * the rule holds it, or empty when it files none.
     */
    public Optional<RuleSet> get(String carrier, String number) {
        String wanted = carrier.toUpperCase(Locale.ROOT);
        return rules.stream()
                .filter(rule -> rule.carrier().equals(wanted) && rule.number().equals(number))
                .findFirst();
    }

    /** How many rules the file files. */
    public int size() {
        return rules.size();
    }

    /** The counts: {@code 1 rules, 1 deleted, 0 problems}. */
    @Override
    public String toString() {
        return rules.size() + " rules, " + deleted.size() + " deleted, " + problems.size() + " problems";
    }

    /**
     * A rule the carrier has withdrawn, under the key a rule store holds it by.
     *
     * @param carrier the carrier, upper case
     * @param number the rule number, without surrounding white space
     * @param tariff the tariff number, without surrounding white space
     */
    public record Withdrawn(String carrier, String number, String tariff) {

        public Withdrawn {
            Objects.requireNonNull(carrier, "carrier");
            Objects.requireNonNull(number, "number");
            Objects.requireNonNull(tariff, "tariff");
        }
    }
}
