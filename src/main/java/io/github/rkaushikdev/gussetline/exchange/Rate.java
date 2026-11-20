package io.github.rkaushikdev.gussetline.exchange;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;

/**
 * One currency's rate against the neutral unit, in force from a given date.
 *
 * @param code the currency's code
 * @param effective the first day the rate is in force
 * @param unitsPerNuc how many units of the currency one neutral unit buys
 */
public record Rate(String code, LocalDate effective, BigDecimal unitsPerNuc) {

    /**
     * Refuses a rate that buys nothing.
     *
     * @throws ValidationError when the rate is zero or negative
     */
    public Rate {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(effective, "effective");
        Objects.requireNonNull(unitsPerNuc, "unitsPerNuc");
        if (unitsPerNuc.signum() <= 0) {
            throw new ValidationError("a rate has to be positive", Details.of("code", code));
        }
    }
}
