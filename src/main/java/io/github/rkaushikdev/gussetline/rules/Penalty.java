package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;

/**
 * Category 16 as it is filed, for one action: what it costs to change, cancel or not show up, and
 * when.
 *
 * <p>Two amounts are filed for every action, one before departure and one after, and which one
 * applies depends on whether the passenger has flown anything yet. That is a reading of the
 * itinerary against the day asked about and not a property of the ticket, so the same ticket carries
 * a fifty euro change fee in the morning and a two hundred euro one after the outbound has gone.
 * Without an after departure amount the before departure one stands.
 *
 * <p>The second thing filed is how many times the fee is charged ({@link Applies}). Adding up several
 * filings is {@link Penalties#totalPenalty}.
 *
 * @param action what the fee is for
 * @param beforeDeparture the fee until the journey's first departure, when filed
 * @param afterDeparture the fee once travel has started, when filed
 * @param applies how many times the fee is charged
 * @param permitted whether the action is allowed at all
 * @param noShowForfeits whether a no show forfeits the ticket
 * @param percent the fee as a percentage of the fare paid, when filed that way; with an amount filed
 *     too, the higher of the two is charged
 */
public record Penalty(
        Action action,
        Optional<Money> beforeDeparture,
        Optional<Money> afterDeparture,
        Applies applies,
        boolean permitted,
        boolean noShowForfeits,
        Optional<BigDecimal> percent)
        implements Provision {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    /**
     * Checks the amounts.
     *
     * @throws ValidationError when an amount is negative, naming the {@code amount}, or the percentage
     *     is outside nought to a hundred, naming the {@code percent}
     */
    public Penalty {
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(beforeDeparture, "beforeDeparture");
        Objects.requireNonNull(afterDeparture, "afterDeparture");
        Objects.requireNonNull(applies, "applies");
        Objects.requireNonNull(percent, "percent");
        refuseNegative(beforeDeparture);
        refuseNegative(afterDeparture);
        if (percent.isPresent() && (percent.get().signum() < 0 || percent.get().compareTo(HUNDRED) > 0)) {
            throw new ValidationError(
                    "a penalty percentage is between nought and a hundred",
                    Details.of("percent", percent.get().toString()));
        }
    }

    /** A filing with no percentage. */
    public Penalty(
            Action action,
            Optional<Money> beforeDeparture,
            Optional<Money> afterDeparture,
            Applies applies,
            boolean permitted,
            boolean noShowForfeits) {
        this(action, beforeDeparture, afterDeparture, applies, permitted, noShowForfeits, Optional.empty());
    }

    /** Nothing filed: a change, permitted, with no amount. */
    public Penalty() {
        this(Action.CHANGE, Optional.empty(), Optional.empty(), Applies.PER_TICKET, true, false);
    }

    /** A fee before departure alone, charged once per ticket. */
    public Penalty(Action action, Money beforeDeparture) {
        this(action, Optional.of(beforeDeparture), Optional.empty(), Applies.PER_TICKET, true, false);
    }

    /** A fee before departure and one after, charged once per ticket. */
    public Penalty(Action action, Money beforeDeparture, Money afterDeparture) {
        this(action, Optional.of(beforeDeparture), Optional.of(afterDeparture), Applies.PER_TICKET, true, false);
    }

    /** An action the fare does not permit at all. */
    public static Penalty notPermitted(Action action) {
        return builder().action(action).permitted(false).build();
    }

    /** A builder starting from {@link #Penalty()}. */
    public static Builder builder() {
        return new Builder();
    }

    @Override
    public Category category() {
        return Category.PENALTIES;
    }

    /** Whether nothing is filed: the action is permitted and no amount is given. */
    public boolean isEmpty() {
        return permitted && beforeDeparture.isEmpty() && afterDeparture.isEmpty() && percent.isEmpty();
    }

    /** {@link #travelStarted(Context, Optional)} on the context's own day. */
    public boolean travelStarted(Context context) {
        return travelStarted(context, Optional.empty());
    }

    /** {@link #travelStarted(Context, Optional)} on a given day. */
    public boolean travelStarted(Context context, LocalDate on) {
        return travelStarted(context, Optional.of(on));
    }

    /**
     * Whether anything has been flown by the day asked about: whether it is after the journey's first
     * departure. With no day given, the context's day stands, and then its ticketing date.
     */
    public boolean travelStarted(Context context, Optional<LocalDate> on) {
        LocalDate today = on.or(context::today).orElse(context.ticketing());
        return today.isAfter(context.journeyDeparture());
    }

    /** {@link #amountOn(Context, Optional)} on the context's own day. */
    public Optional<Money> amountOn(Context context) {
        return amountOn(context, Optional.empty());
    }

    /** {@link #amountOn(Context, Optional)} on a given day. */
    public Optional<Money> amountOn(Context context, LocalDate on) {
        return amountOn(context, Optional.of(on));
    }

    /** {@link #amountOn(Context, Optional, Optional)} with no fare to read a percentage off. */
    public Optional<Money> amountOn(Context context, Optional<LocalDate> on) {
        return amountOn(context, on, Optional.empty());
    }

    /**
     * The fee that applies on a day, before multiplying: the after departure amount once travel has
     * started, when one is filed, and the before departure amount otherwise. A filed percentage of
     * the fare is worked out on the fare given, to the minor unit with halves away from zero, and
     * when an amount applies as well the higher of the two stands, which is how a carrier writes
     * {@code 10 percent, minimum 50 EUR}. Empty when nothing applies, or when only a percentage is
     * filed and no fare was given.
     *
     * @throws ValidationError when the fare and the filed amount are in different currencies
     */
    public Optional<Money> amountOn(Context context, Optional<LocalDate> on, Optional<Money> fare) {
        Optional<Money> filed = travelStarted(context, on) ? afterDeparture.or(() -> beforeDeparture) : beforeDeparture;
        Optional<Money> share = percent.flatMap(rate -> fare.map(paid -> Money.roundTax(
                new Money(Numbers.divide(paid.amount().multiply(rate, Numbers.CONTEXT), HUNDRED), paid.currency()))));
        if (filed.isPresent() && share.isPresent()) {
            return Optional.of(share.get().isGreaterThan(filed.get()) ? share.get() : filed.get());
        }
        return filed.or(() -> share);
    }

    /** {@link #charge(Context, int, Optional)} for one component on the context's own day. */
    public Optional<Money> charge(Context context) {
        return charge(context, 1, Optional.empty());
    }

    /** {@link #charge(Context, int, Optional)} on the context's own day. */
    public Optional<Money> charge(Context context, int components) {
        return charge(context, components, Optional.empty());
    }

    /** {@link #charge(Context, int, Optional)} on a given day. */
    public Optional<Money> charge(Context context, int components, LocalDate on) {
        return charge(context, components, Optional.of(on));
    }

    /**
     * What the action actually costs on a ticket of that many components, with the multiplier
     * applied: per component multiplies, anything else is charged once.
     *
     * <p>Empty means the action is not permitted at all, or no amount applies, which is a different
     * answer from a fee of zero and has to stay different, because a fare with a zero change fee is
     * the most valuable thing on the desk and a fare that cannot be changed is the least.
     */
    public Optional<Money> charge(Context context, int components, Optional<LocalDate> on) {
        return charge(context, components, on, Optional.empty());
    }

    /** {@link #charge(Context, int, Optional)} with a fare to read a filed percentage off. */
    public Optional<Money> charge(Context context, int components, Optional<LocalDate> on, Optional<Money> fare) {
        if (!permitted) {
            return Optional.empty();
        }
        return amountOn(context, on, fare)
                .map(base -> applies == Applies.PER_COMPONENT ? base.times(components) : base);
    }

    /**
     * Fails when the action is not permitted, naming the {@code action}. Otherwise passes with the
     * {@code action} and the {@code amount} that applies on the context's day, {@code 0} when none
     * does.
     */
    @Override
    public Outcome evaluate(Context context) {
        if (isEmpty()) {
            return silent(category());
        }
        if (!permitted) {
            return fails(
                    category(), "the fare does not permit a " + action.value(), Details.of("action", action.value()));
        }
        Optional<Money> amount = amountOn(context);
        return passes(
                category(),
                amount.map(fee -> action.value() + " costs " + fee).orElse(action.value() + " is free"),
                Details.of(
                        "action",
                        action.value(),
                        "amount",
                        amount.map(Money::toString).orElse("0")));
    }

    /**
     * The filing in one line: {@code change 50.00 EUR before, 200.00 EUR after, per_ticket}, an
     * amount not filed reading {@code None} and a percentage added at the end; or {@code change not
     * permitted}.
     */
    @Override
    public String toString() {
        if (!permitted) {
            return action.value() + " not permitted";
        }
        return action.value() + " " + Text.plain(beforeDeparture.orElse(null)) + " before, "
                + Text.plain(afterDeparture.orElse(null)) + " after, " + applies.value()
                + percent.map(rate -> ", " + rate.toPlainString() + " percent").orElse("");
    }

    private static void refuseNegative(Optional<Money> amount) {
        if (amount.isPresent() && amount.get().isNegative()) {
            throw new ValidationError(
                    "a penalty is not negative",
                    Details.of("amount", amount.get().toString()));
        }
    }

    /** Collects a filing, starting from {@link #Penalty()}. Every setter returns the builder. */
    public static final class Builder {

        private Action action = Action.CHANGE;
        private Optional<Money> beforeDeparture = Optional.empty();
        private Optional<Money> afterDeparture = Optional.empty();
        private Applies applies = Applies.PER_TICKET;
        private boolean permitted = true;
        private boolean noShowForfeits;
        private Optional<BigDecimal> percent = Optional.empty();

        private Builder() {}

        public Builder action(Action value) {
            action = value;
            return this;
        }

        public Builder beforeDeparture(Money value) {
            return beforeDeparture(Optional.of(value));
        }

        /** The fee before departure, or that none is filed. */
        public Builder beforeDeparture(Optional<Money> value) {
            beforeDeparture = value;
            return this;
        }

        public Builder afterDeparture(Money value) {
            return afterDeparture(Optional.of(value));
        }

        /** The fee after departure, or that none is filed. */
        public Builder afterDeparture(Optional<Money> value) {
            afterDeparture = value;
            return this;
        }

        public Builder applies(Applies value) {
            applies = value;
            return this;
        }

        public Builder permitted(boolean value) {
            permitted = value;
            return this;
        }

        public Builder noShowForfeits(boolean value) {
            noShowForfeits = value;
            return this;
        }

        public Builder percent(BigDecimal value) {
            return percent(Optional.of(value));
        }

        /** The fee as a percentage of the fare, or that none is filed. */
        public Builder percent(Optional<BigDecimal> value) {
            percent = value;
            return this;
        }

        /**
         * The filing.
         *
         * @throws ValidationError when an amount is negative
         */
        public Penalty build() {
            return new Penalty(action, beforeDeparture, afterDeparture, applies, permitted, noShowForfeits, percent);
        }
    }
}
