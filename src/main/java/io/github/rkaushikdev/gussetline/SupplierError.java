package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The far side did not give us an answer we can use. */
public class SupplierError extends GussetlineError {

    public SupplierError(String message) {
        super(message);
    }

    public SupplierError(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "supplier.error";
    }
}
