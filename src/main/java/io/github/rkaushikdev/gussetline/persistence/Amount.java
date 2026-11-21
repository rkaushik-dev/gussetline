package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.util.Numbers;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.math.BigDecimal;

/**
 * A decimal stored as text, because floats lose fares.
 *
 * <p>The text is the amount as written, scale included, so {@code 420.00} reads back as {@code
 * 420.00} and a rate of {@code 0.30675} keeps its fifth decimal. Every amount column names this
 * converter and is {@link #LENGTH} characters wide.
 */
@Converter
public final class Amount implements AttributeConverter<BigDecimal, String> {

    /** How many characters an amount column holds. */
    public static final int LENGTH = 24;

    @Override
    public String convertToDatabaseColumn(BigDecimal value) {
        return value == null ? null : value.toString();
    }

    @Override
    public BigDecimal convertToEntityAttribute(String text) {
        return text == null ? null : Numbers.parseDecimal(text);
    }
}
