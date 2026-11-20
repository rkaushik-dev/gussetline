package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** A fare names a rule the rule store does not hold. */
public class NoRuleFound extends TariffError {

    public NoRuleFound(String message) {
        super(message);
    }

    public NoRuleFound(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "tariff.no_rule";
    }
}
