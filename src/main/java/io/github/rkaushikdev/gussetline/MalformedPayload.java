package io.github.rkaushikdev.gussetline;

import java.util.Map;

/** A response arrived and did not parse as the format it claimed. */
public class MalformedPayload extends SupplierError {

    public MalformedPayload(String message) {
        super(message);
    }

    public MalformedPayload(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "supplier.malformed";
    }
}
