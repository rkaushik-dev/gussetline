package io.github.rkaushikdev.gussetline.cli;

/**
 * A command declining to go on, for a reason it can say in one line: bytes that are already
 * loaded, a desk that already holds something. The operator reads {@code Error: <message>} and the
 * run exits {@link Desk#OPERATOR_CAN_FIX}.
 */
final class CommandError extends RuntimeException {

    CommandError(String message) {
        super(message);
    }
}
