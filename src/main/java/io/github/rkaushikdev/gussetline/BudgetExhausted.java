package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The politeness or quota budget for this window is spent. */
public class BudgetExhausted extends GussetlineError {

    public BudgetExhausted(String message) {
        super(message);
    }

    public BudgetExhausted(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "collector.budget_exhausted";
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
