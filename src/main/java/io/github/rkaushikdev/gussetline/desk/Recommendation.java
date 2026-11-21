package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.ConflictError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What the desk told a customer, frozen at the moment it was told, and the states a person moves it
 * through.
 *
 * <p>A decision is what the arithmetic says. A recommendation is what the desk told a customer, and
 * the two have to stay apart, because the arithmetic changes every time the market moves and what was
 * said does not.
 *
 * <p>So a recommendation is a snapshot. It copies every number it was made on, and from then on it
 * only changes state: somebody {@linkplain #take takes} it, or {@linkplain #kill kills} it, or it
 * {@linkplain #expire expires} because the fare it was based on has gone. The savings report reads the
 * taken ones and nothing else, which is why it gives the same answer today as it did last month.
 *
 * <p>Expiry is the part worth stating. A recommendation raised on Monday against a fare that was gone
 * by Wednesday is not a saving and is not a failure either, it is the ordinary case, and counting it as
 * either makes the desk look better or worse than it is.
 *
 * <p>Withdrawal is the other one. The market can drop again while a recommendation is still sitting in
 * somebody's queue, and the desk then has two open numbers for one ticket and one phone call to make.
 * The old one is {@linkplain #withdraw withdrawn} rather than killed, because killed means a person
 * looked at it and said no, and a report that cannot tell those apart will show a take rate falling
 * every time the collectors do their job well.
 *
 * <p>Two recommendations are equal when every field, the state included, is equal. Build one with
 * {@link #Recommendation(String, String, LocalDate, Money, Money, Money, Money, Residual)}, or with
 * {@link #builder()} for one read back in a later state.
 */
public final class Recommendation {

    /** How long a quoted fare is worth chasing before it is assumed gone, in days. */
    public static final int DEFAULT_LIFETIME_DAYS = 3;

    private final String ticketNumber;
    private final String seriesKey;
    private final LocalDate raisedOn;
    private final Money paid;
    private final Money newFare;
    private final Money penalty;
    private final Money benefit;
    private final Residual residual;
    private final List<String> fareBases;
    private final String fingerprint;
    private final String baggage;
    private final int lifetimeDays;
    private State state;
    private Optional<LocalDate> decidedOn;
    private String decidedBy;
    private String note;
    private String replacedBy;

    /**
     * An open recommendation with no fare bases, fingerprint or allowance, lasting the default
     * lifetime.
     *
     * @throws ValidationError when the amounts are not all in one currency
     */
    public Recommendation(
            String ticketNumber,
            String seriesKey,
            LocalDate raisedOn,
            Money paid,
            Money newFare,
            Money penalty,
            Money benefit,
            Residual residual) {
        this(builder()
                .ticketNumber(ticketNumber)
                .seriesKey(seriesKey)
                .raisedOn(raisedOn)
                .paid(paid)
                .newFare(newFare)
                .penalty(penalty)
                .benefit(benefit)
                .residual(residual));
    }

    private Recommendation(Builder made) {
        this.ticketNumber = Objects.requireNonNull(made.ticketNumber, "ticketNumber");
        this.seriesKey = Objects.requireNonNull(made.seriesKey, "seriesKey");
        this.raisedOn = Objects.requireNonNull(made.raisedOn, "raisedOn");
        this.paid = Objects.requireNonNull(made.paid, "paid");
        this.newFare = Objects.requireNonNull(made.newFare, "newFare");
        this.penalty = Objects.requireNonNull(made.penalty, "penalty");
        this.benefit = Objects.requireNonNull(made.benefit, "benefit");
        this.residual = Objects.requireNonNull(made.residual, "residual");
        this.fareBases = List.copyOf(made.fareBases);
        this.fingerprint = Objects.requireNonNull(made.fingerprint, "fingerprint");
        this.baggage = Objects.requireNonNull(made.baggage, "baggage");
        this.state = Objects.requireNonNull(made.state, "state");
        this.decidedOn = Objects.requireNonNull(made.decidedOn, "decidedOn");
        this.decidedBy = Objects.requireNonNull(made.decidedBy, "decidedBy");
        this.note = Objects.requireNonNull(made.note, "note");
        this.replacedBy = Objects.requireNonNull(made.replacedBy, "replacedBy");
        this.lifetimeDays = made.lifetimeDays;
        for (Money amount : List.of(paid, newFare, penalty, benefit)) {
            if (!amount.currency().equals(paid.currency())) {
                throw new ValidationError("a recommendation is in one currency");
            }
        }
        if (lifetimeDays < 1) {
            throw new ValidationError("a recommendation lasts at least a day");
        }
    }

    /**
     * A builder for an open recommendation with no fare bases, fingerprint or allowance, lasting the
     * default lifetime. The ticket number, the series key, the day it was raised, the four amounts and
     * the residual have to be given.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding every field of this recommendation as it stands. */
    public Builder toBuilder() {
        return new Builder()
                .ticketNumber(ticketNumber)
                .seriesKey(seriesKey)
                .raisedOn(raisedOn)
                .paid(paid)
                .newFare(newFare)
                .penalty(penalty)
                .benefit(benefit)
                .residual(residual)
                .fareBases(fareBases)
                .fingerprint(fingerprint)
                .baggage(baggage)
                .state(state)
                .decidedOn(decidedOn)
                .decidedBy(decidedBy)
                .note(note)
                .replacedBy(replacedBy)
                .lifetimeDays(lifetimeDays);
    }

    /** A copy raised on another day, in the same state. */
    public Recommendation withRaisedOn(LocalDate day) {
        return toBuilder().raisedOn(day).build();
    }

    /** A copy saying the fare includes another allowance, in the same state. */
    public Recommendation withBaggage(String allowance) {
        return toBuilder().baggage(allowance).build();
    }

    /** The ticket the recommendation is about. */
    public String ticketNumber() {
        return ticketNumber;
    }

    /** The series the drop was seen on. */
    public String seriesKey() {
        return seriesKey;
    }

    /** The day the desk was told. */
    public LocalDate raisedOn() {
        return raisedOn;
    }

    /** What was paid for the ticket. */
    public Money paid() {
        return paid;
    }

    /** The fare being recommended. */
    public Money newFare() {
        return newFare;
    }

    /** The change fee the reissue costs. */
    public Money penalty() {
        return penalty;
    }

    /** What the reissue is worth to the customer. */
    public Money benefit() {
        return benefit;
    }

    /** What the fare does with a difference. */
    public Residual residual() {
        return residual;
    }

    /** The fare bases on the ticket, each once. */
    public List<String> fareBases() {
        return fareBases;
    }

    /** The fingerprint of the reading the recommendation was raised on; empty when there was none. */
    public String fingerprint() {
        return fingerprint;
    }

    /**
     * What the fare being recommended includes: {@code 1PC}. Empty where the supplier did not say,
     * which is a different thing from no allowance and reads differently on the screen.
     */
    public String baggage() {
        return baggage;
    }

    /** Where the recommendation is now. */
    public State state() {
        return state;
    }

    /** The day it was settled, once it has been. */
    public Optional<LocalDate> decidedOn() {
        return decidedOn;
    }

    /** Who settled it: a person, {@code the clock} or {@code a better fare}. Empty while open. */
    public String decidedBy() {
        return decidedBy;
    }

    /** What the person who settled it said. */
    public String note() {
        return note;
    }

    /** The fingerprint of the fare that superseded it; empty unless it was superseded by one. */
    public String replacedBy() {
        return replacedBy;
    }

    /** How many days the quoted fare is worth chasing. At least one. */
    public int lifetimeDays() {
        return lifetimeDays;
    }

    /** The currency every amount is in. */
    public String currency() {
        return paid.currency();
    }

    /** The last day the recommendation is worth chasing: the lifetime after the day it was raised. */
    public LocalDate expiresOn() {
        return raisedOn.plusDays(lifetimeDays);
    }

    /** Whether it is still open after the day it expires on. */
    public boolean staleOn(LocalDate today) {
        return state == State.OPEN && today.isAfter(expiresOn());
    }

    /** {@link #take(LocalDate, String, String)} with nothing said. */
    public void take(LocalDate today, String by) {
        take(today, by, "");
    }

    /**
     * The desk made the reissue. This is the only state that saves money.
     *
     * @param by who made it
     * @param saying what they said about it
     * @throws ConflictError when it has already been settled, naming the {@code state} and who it was
     *     settled {@code by}
     * @throws ValidationError when nobody is named
     */
    public void take(LocalDate today, String by, String saying) {
        settle(State.TAKEN, today, by, saying);
    }

    /** {@link #kill(LocalDate, String, String)} with nothing said. */
    public void kill(LocalDate today, String by) {
        kill(today, by, "");
    }

    /**
     * The desk looked and decided not to. Not a saving and not a loss.
     *
     * @param by who decided
     * @param saying what they said about it
     * @throws ConflictError when it has already been settled, naming the {@code state} and who it was
     *     settled {@code by}
     * @throws ValidationError when nobody is named
     */
    public void kill(LocalDate today, String by, String saying) {
        settle(State.KILLED, today, by, saying);
    }

    /**
     * Nobody got to it in time. Recorded, with the clock as the decider, and counted as neither.
     *
     * @throws ConflictError when it is not open, naming the {@code state}
     */
    public void expire(LocalDate today) {
        if (state != State.OPEN) {
            throw new ConflictError("only an open recommendation expires", Details.of("state", state.value()));
        }
        state = State.EXPIRED;
        decidedOn = Optional.of(today);
        decidedBy = "the clock";
    }

    /** {@link #withdraw(LocalDate, String)} without naming the fare that replaced it. */
    public void withdraw(LocalDate today) {
        withdraw(today, "");
    }

    /**
     * A better one on the same series has replaced this.
     *
     * <p>Not killed, because nobody looked at it, and not expired, because the fare it was raised on may
     * still be there. It simply is not the number the desk should be quoting any more.
     *
     * @param replacement the fingerprint of the fare that replaced it
     * @throws ConflictError when it is not open, naming the {@code state}
     */
    public void withdraw(LocalDate today, String replacement) {
        if (state != State.OPEN) {
            throw new ConflictError("only an open recommendation is withdrawn", Details.of("state", state.value()));
        }
        state = State.SUPERSEDED;
        decidedOn = Optional.of(today);
        decidedBy = "a better fare";
        replacedBy = Objects.requireNonNull(replacement, "replacement");
    }

    /** What this actually saved. Zero unless somebody took it. */
    public Money realised() {
        if (!state.countsAsSaving()) {
            return Money.zero(currency());
        }
        return benefit;
    }

    /** {@code 220-1234567890 open, worth 90.00 EUR}, or {@code 220-1234567890 taken by anna}. */
    @Override
    public String toString() {
        if (state == State.OPEN) {
            return ticketNumber + " open, worth " + benefit;
        }
        return ticketNumber + " " + state.value() + " by " + decidedBy;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Recommendation that
                && ticketNumber.equals(that.ticketNumber)
                && seriesKey.equals(that.seriesKey)
                && raisedOn.equals(that.raisedOn)
                && paid.equals(that.paid)
                && newFare.equals(that.newFare)
                && penalty.equals(that.penalty)
                && benefit.equals(that.benefit)
                && residual == that.residual
                && fareBases.equals(that.fareBases)
                && fingerprint.equals(that.fingerprint)
                && baggage.equals(that.baggage)
                && state == that.state
                && decidedOn.equals(that.decidedOn)
                && decidedBy.equals(that.decidedBy)
                && note.equals(that.note)
                && replacedBy.equals(that.replacedBy)
                && lifetimeDays == that.lifetimeDays;
    }

    /** Hashes only what never moves, so a recommendation keeps its hash as it is settled. */
    @Override
    public int hashCode() {
        return Objects.hash(ticketNumber, seriesKey, fingerprint);
    }

    /**
     * Turn an actionable decision into a recommendation raised today, copying the numbers, lasting the
     * default lifetime.
     *
     * @throws ValidationError when the decision is not actionable, naming its {@code verdict}
     */
    public static Recommendation raiseFrom(Decision decision, LocalDate today) {
        return raiseFrom(decision, today, DEFAULT_LIFETIME_DAYS);
    }

    /**
     * Turn an actionable decision into a recommendation raised today, copying the numbers: what was
     * paid, the fare read, the fee and the benefit, the residual, the ticket's fare bases, the reading's
     * fingerprint, and its allowance when one was stated.
     *
     * @param lifetime how many days the recommendation lasts
     * @throws ValidationError when the decision is not actionable, naming its {@code verdict}, or the
     *     lifetime is under a day
     */
    public static Recommendation raiseFrom(Decision decision, LocalDate today, int lifetime) {
        if (!decision.actionable()) {
            throw new ValidationError(
                    "only an actionable decision becomes a recommendation",
                    Details.of("verdict", decision.verdict().value()));
        }
        Observation held = decision.observation().orElseThrow();
        Watch watch = decision.watch();
        return builder()
                .ticketNumber(watch.ticket().number())
                .seriesKey(watch.seriesKey())
                .raisedOn(today)
                .paid(watch.paid())
                .newFare(held.amount())
                .penalty(decision.penalty().orElseThrow())
                .benefit(decision.benefit().orElseThrow())
                .residual(decision.residual())
                .fareBases(watch.ticket().fareBases())
                .fingerprint(held.fingerprint())
                .baggage(held.baggage().isStated() ? held.baggage().toString() : "")
                .lifetimeDays(lifetime)
                .build();
    }

    /** Close the ones nobody got to by today. Returns how many. */
    public static int expireStale(List<Recommendation> recommendations, LocalDate today) {
        int closed = 0;
        for (Recommendation recommendation : recommendations) {
            if (recommendation.staleOn(today)) {
                recommendation.expire(today);
                closed += 1;
            }
        }
        return closed;
    }

    /** The open ones, in order. */
    public static List<Recommendation> openOnes(List<Recommendation> recommendations) {
        return recommendations.stream()
                .filter(recommendation -> recommendation.state == State.OPEN)
                .toList();
    }

    /**
     * The open recommendation for a series, if the desk already has one.
     *
     * <p>There is at most one by construction, and the session is what keeps that true. Returning the
     * newest rather than the first, the first of equals, means a run that somehow left two behind still
     * supersedes the one a person would be looking at.
     */
    public static Optional<Recommendation> openOn(List<Recommendation> recommendations, String seriesKey) {
        Recommendation newest = null;
        for (Recommendation recommendation : openOnes(recommendations)) {
            if (!recommendation.seriesKey.equals(seriesKey)) {
                continue;
            }
            if (newest == null || recommendation.raisedOn.isAfter(newest.raisedOn)) {
                newest = recommendation;
            }
        }
        return Optional.ofNullable(newest);
    }

    /** What the taken ones saved together, zero when none were. */
    public static Money realisedTotal(List<Recommendation> recommendations, String currency) {
        Money running = Money.zero(currency);
        for (Recommendation recommendation : recommendations) {
            if (recommendation.state.countsAsSaving()) {
                running = running.plus(recommendation.realised());
            }
        }
        return running;
    }

    /**
     * How often a recommendation a person decided was acted on, to the hundredth, half to even; zero
     * when nobody has decided any.
     *
     * <p>Measured against the ones a person decided and not against everything. An open recommendation
     * has not been decided yet, and counting it as a refusal makes the desk look worse every morning and
     * better every afternoon. An expired or withdrawn one was never a person's to decide.
     */
    public static BigDecimal takeRate(List<Recommendation> recommendations) {
        List<Recommendation> decided = recommendations.stream()
                .filter(recommendation -> recommendation.state.aPersonDecided())
                .toList();
        if (decided.isEmpty()) {
            return BigDecimal.ZERO;
        }
        long taken = decided.stream()
                .filter(recommendation -> recommendation.state.countsAsSaving())
                .count();
        return Numbers.divide(BigDecimal.valueOf(taken), BigDecimal.valueOf(decided.size()))
                .setScale(2, RoundingMode.HALF_EVEN);
    }

    private void settle(State settled, LocalDate today, String by, String saying) {
        if (state != State.OPEN) {
            throw new ConflictError(
                    "this recommendation has already been settled",
                    Details.of("state", state.value(), "by", decidedBy));
        }
        if (Text.isBlank(by)) {
            throw new ValidationError("a decision names who made it");
        }
        state = settled;
        decidedOn = Optional.of(today);
        decidedBy = Text.strip(by);
        note = Objects.requireNonNull(saying, "note");
    }

    /** Collects a recommendation. Every setter returns the builder. */
    public static final class Builder {

        private String ticketNumber;
        private String seriesKey;
        private LocalDate raisedOn;
        private Money paid;
        private Money newFare;
        private Money penalty;
        private Money benefit;
        private Residual residual;
        private List<String> fareBases = List.of();
        private String fingerprint = "";
        private String baggage = "";
        private State state = State.OPEN;
        private Optional<LocalDate> decidedOn = Optional.empty();
        private String decidedBy = "";
        private String note = "";
        private String replacedBy = "";
        private int lifetimeDays = DEFAULT_LIFETIME_DAYS;

        private Builder() {}

        public Builder ticketNumber(String value) {
            ticketNumber = value;
            return this;
        }

        public Builder seriesKey(String value) {
            seriesKey = value;
            return this;
        }

        public Builder raisedOn(LocalDate value) {
            raisedOn = value;
            return this;
        }

        public Builder paid(Money value) {
            paid = value;
            return this;
        }

        public Builder newFare(Money value) {
            newFare = value;
            return this;
        }

        public Builder penalty(Money value) {
            penalty = value;
            return this;
        }

        public Builder benefit(Money value) {
            benefit = value;
            return this;
        }

        public Builder residual(Residual value) {
            residual = value;
            return this;
        }

        /** The fare bases, in order. They are copied. */
        public Builder fareBases(List<String> value) {
            fareBases = List.copyOf(value);
            return this;
        }

        public Builder fingerprint(String value) {
            fingerprint = value;
            return this;
        }

        public Builder baggage(String value) {
            baggage = value;
            return this;
        }

        public Builder state(State value) {
            state = value;
            return this;
        }

        public Builder decidedOn(LocalDate value) {
            return decidedOn(Optional.of(value));
        }

        /** The day it was settled, or that it has not been. */
        public Builder decidedOn(Optional<LocalDate> value) {
            decidedOn = value;
            return this;
        }

        public Builder decidedBy(String value) {
            decidedBy = value;
            return this;
        }

        public Builder note(String value) {
            note = value;
            return this;
        }

        public Builder replacedBy(String value) {
            replacedBy = value;
            return this;
        }

        public Builder lifetimeDays(int value) {
            lifetimeDays = value;
            return this;
        }

        /**
         * The recommendation.
         *
         * @throws ValidationError when the amounts are not all in one currency, or the lifetime is under
         *     a day
         */
        public Recommendation build() {
            return new Recommendation(this);
        }
    }
}
