package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.GussetlineError;
import java.util.Map;

/** No key, or one we do not hold. */
public class Unauthorised extends GussetlineError {

    public Unauthorised(String message) {
        super(message);
    }

    public Unauthorised(String message, Map<String, ?> details) {
        super(message, details);
    }

    @Override
    public String code() {
        return "auth.unauthorised";
    }
}
