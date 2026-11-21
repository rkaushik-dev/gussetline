package io.github.rkaushikdev.gussetline.negotiated;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Objects;

/**
 * Who a negotiated fare is filed for: the agency or corporate account whose code a caller presents
 * to see it.
 *
 * @param code the account code, upper case without surrounding white space
 * @param name the account's name, which only a reader needs
 */
public record Account(String code, String name) {

    /** The longest account code anybody types. */
    public static final int MAX_CODE = 20;

    /**
     * Normalises the code to upper case without surrounding white space.
     *
     * @throws ValidationError when the code is blank, or longer than {@link #MAX_CODE} (naming the
     *     {@code code} as given)
     */
    public Account {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        String cleaned = Text.code(code);
        if (cleaned.isEmpty()) {
            throw new ValidationError("an account has a code");
        }
        if (Text.length(cleaned) > MAX_CODE) {
            throw new ValidationError("that is not an account code", Details.of("code", code));
        }
        code = cleaned;
    }

    /** An account known by its code alone. */
    public Account(String code) {
        this(code, "");
    }

    /** The code and the name: {@code ACME Acme Industries}, or the code alone when there is no name. */
    @Override
    public String toString() {
        return Text.strip(code + " " + name);
    }
}
