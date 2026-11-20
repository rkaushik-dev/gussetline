package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** A transport level failure: nothing was learned about the market. */
public class SupplierUnavailable extends SupplierError {

    public SupplierUnavailable(String message) {
        super(message);
    }

    public SupplierUnavailable(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "supplier.unavailable";
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
