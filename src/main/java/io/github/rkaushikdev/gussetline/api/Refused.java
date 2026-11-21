package io.github.rkaushikdev.gussetline.api;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;

/**
 * A value in a request body that does not read as the field's type. The reader adds where in the
 * body it was, and {@link Errors} turns it into one entry of the refusal.
 */
final class Refused extends JsonMappingException {

    private final transient Unfit unfit;

    Refused(JsonParser parser, Unfit unfit) {
        super(parser, unfit.getMessage());
        this.unfit = unfit;
    }

    /** What was wrong with the value. */
    Unfit unfit() {
        return unfit;
    }
}
