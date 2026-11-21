package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.util.Text;
import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * How long a piece of text may be, counted in characters as a person counts them: a character outside
 * the Basic Multilingual Plane is one, not two. Nothing at all is not this constraint's business.
 */
@Documented
@Constraint(validatedBy = Length.Check.class)
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.PARAMETER})
public @interface Length {

    /** The fewest characters allowed. */
    int min() default 0;

    /** The most characters allowed. */
    int max() default Integer.MAX_VALUE;

    String message() default "length must be between {min} and {max}";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    /** The check behind the constraint. */
    final class Check implements ConstraintValidator<Length, String> {

        private int min;
        private int max;

        @Override
        public void initialize(Length length) {
            this.min = length.min();
            this.max = length.max();
        }

        @Override
        public boolean isValid(String text, ConstraintValidatorContext context) {
            if (text == null) {
                return true;
            }
            int size = Text.length(text);
            return size >= min && size <= max;
        }
    }
}
