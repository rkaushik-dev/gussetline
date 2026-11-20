package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** The supplier refused us rather than the request. Stop asking. */
public class SupplierBlocked extends SupplierError {

    public SupplierBlocked(String message) {
        super(message);
    }

    public SupplierBlocked(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "supplier.blocked";
    }
}
