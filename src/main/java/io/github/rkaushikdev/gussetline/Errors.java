package io.github.rkaushikdev.gussetline;

import java.util.NoSuchElementException;

/** Questions asked of a failure, whichever kind it is. */
public final class Errors {

    private Errors() {}

    /**
     * True when trying the same call again could plausibly succeed.
     *
     * <p>Anything that is not one of ours is treated as retryable, because an unrecognised failure
     * from the HTTP client or the runtime is far more often a socket than a logic error, and a
     * collector that gives up on the first unknown exception stops collecting the moment a supplier
     * changes a header. The exceptions that mean the code itself is wrong (a bad argument, a value
     * of the wrong type, a missing reference or a missing key) are never retried.
     */
    public static boolean isRetryable(Throwable failure) {
        if (failure instanceof GussetlineError ours) {
            return ours.retryable();
        }
        return !(failure instanceof IllegalArgumentException
                || failure instanceof ClassCastException
                || failure instanceof NullPointerException
                || failure instanceof NoSuchElementException);
    }
}
