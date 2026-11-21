package io.github.rkaushikdev.gussetline.load;

import io.github.rkaushikdev.gussetline.tariff.Fare;
import java.util.Objects;

/**
 * One record of a fare filing file, read and turned into something the tariff store can take.
 *
 * @param action what the filing says to do with its fare
 * @param fare the fare it carries
 * @param lineNumber the line it was read from, counting from one, or zero for one built by hand
 */
public record Filing(Action action, Fare fare, int lineNumber) {

    public Filing {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(fare, "fare");
    }

    /** A filing that was not read from a line. */
    public Filing(Action action, Fare fare) {
        this(action, fare, 0);
    }

    /** The action and the fare: {@code added LH FRANYC KLWAP7RT 420.00 EUR}. */
    @Override
    public String toString() {
        return action.value() + " " + fare;
    }

    /** What a filing says to do with the fare it carries. */
    public enum Action {
        /** A fare the tariff did not have before. */
        ADDED("added"),
        /** An amendment to a fare the tariff already has. */
        CHANGED("changed"),
        /** A fare the carrier has withdrawn. */
        DELETED("deleted");

        private final String value;

        Action(String value) {
            this.value = value;
        }

        /** The word the load report prints for this action. */
        public String value() {
            return value;
        }

        /**
         * The action a word names.
         *
         * @throws IllegalArgumentException when the word names no action
         */
        public static Action of(String value) {
            for (Action action : values()) {
                if (action.value.equals(value)) {
                    return action;
                }
            }
            throw new IllegalArgumentException("'" + value + "' is not a valid Action");
        }
    }
}
