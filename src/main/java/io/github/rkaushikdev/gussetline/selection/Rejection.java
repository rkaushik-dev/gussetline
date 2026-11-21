package io.github.rkaushikdev.gussetline.selection;

import java.util.Objects;

/**
 * One published fare the selection threw out, and why.
 *
 * @param fareBasis the fare basis of the fare refused
 * @param reason the sentence a desk reads, such as {@code no sector booked in Q}
 */
public record Rejection(String fareBasis, String reason) {

    public Rejection {
        Objects.requireNonNull(fareBasis, "fareBasis");
        Objects.requireNonNull(reason, "reason");
    }
}
