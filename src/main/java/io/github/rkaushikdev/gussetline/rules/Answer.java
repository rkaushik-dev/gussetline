package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.util.Text;

/**
 * The three answers a provision can give.
 *
 * <p>Failing and not applying are different answers. A fare that fails a provision may not be sold;
 * a fare whose rule files no such provision has no such condition, and reading that as a failure
 * would refuse every unrestricted fare in the tariff.
 */
public enum Answer {
    PASSES("passes"),
    FAILS("fails"),
    NOT_APPLICABLE("not_applicable");

    private final String value;

    Answer(String value) {
        this.value = value;
    }

    /** The name the answer is printed and stored under. */
    public String value() {
        return value;
    }

    /**
     * The answer stored under a name.
     *
     * @throws IllegalArgumentException when the name is no answer
     */
    public static Answer of(String value) {
        return Text.named(values(), Answer::value, value, "Answer");
    }
}
