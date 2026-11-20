package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The supplier said slow down, and it is entitled to. */
public class SupplierThrottled extends SupplierError {

    public SupplierThrottled(String message) {
        super(message);
    }

    public SupplierThrottled(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "supplier.throttled";
    }

    @Override
    public boolean retryable() {
        return true;
    }
}
