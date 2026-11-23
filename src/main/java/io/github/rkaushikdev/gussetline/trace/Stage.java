package io.github.rkaushikdev.gussetline.trace;

import io.github.rkaushikdev.gussetline.util.Text;

/** The parts of a price that can each pass or refuse on their own. */
public enum Stage {
    /**
     * What kind of journey this is, which everything after it reads. First because a shape read
     * wrong breaks the fare in the wrong place and every later stage then argues about the wrong
     * component.
     */
    SHAPE("shape"),
    SELECTION("selection"),
    ROUTING("routing"),
    MILEAGE("mileage"),
    MINIMUM("minimum"),
    RULE("rule"),
    COMBINABILITY("combinability"),
    SURCHARGE("surcharge"),
    CONVERSION("conversion"),
    TAX("tax"),
    AUDIT("audit");

    private final String value;

    Stage(String value) {
        this.value = value;
    }

    /** The name a stage is stored and printed under. */
    public String value() {
        return value;
    }

    /**
     * The stage stored under a name.
     *
     * @throws IllegalArgumentException when the name is no stage
     */
    public static Stage of(String value) {
        return Text.named(values(), Stage::value, value, "Stage");
    }
}
