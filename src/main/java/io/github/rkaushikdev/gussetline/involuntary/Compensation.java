package io.github.rkaushikdev.gussetline.involuntary;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import java.util.List;
import java.util.Objects;

/**
 * What the regulation owes a disrupted passenger on top of anything refunded, and why.
 *
 * @param amount the flat amount owed, in the governing law's currency; zero when nothing is owed
 * @param kilometres the great circle the band was read from
 * @param reasons why the amount is what it is, in the order the rules were read
 */
public record Compensation(Money amount, int kilometres, List<String> reasons) {

    /**
     * Keeps a copy of the reasons.
     *
     * @throws ValidationError when the amount is negative, naming the {@code amount}
     */
    public Compensation {
        Objects.requireNonNull(amount, "amount");
        reasons = List.copyOf(reasons);
        if (amount.isNegative()) {
            throw new ValidationError("compensation is not negative", Details.of("amount", amount.toString()));
        }
    }

    /** Nothing owed under either law, for one reason, in euro. */
    public static Compensation none(int kilometres, String reason) {
        return new Compensation(Money.zero("EUR"), kilometres, List.of(reason));
    }

    /** Nothing owed under a law, for one reason, in that law's currency. */
    public static Compensation none(Regime regime, int kilometres, String reason) {
        return new Compensation(Money.zero(regime.currency()), kilometres, List.of(reason));
    }

    /** Whether anything is owed. */
    public boolean owed() {
        return !amount.isZero();
    }

    /** {@code 600 EUR over 6204 km: long haul, 6204 km}. */
    @Override
    public String toString() {
        return amount + " over " + kilometres + " km: " + String.join("; ", reasons);
    }
}
