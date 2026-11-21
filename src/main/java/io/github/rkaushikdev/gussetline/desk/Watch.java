package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.series.Series;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A standing instruction to keep looking at one ticket's market, and the difference between a price
 * moving and a price dropping.
 *
 * <p>A price series is noisy. A market that sits at eight hundred and forty will show seven hundred
 * and ninety for one poll and go back, because a supplier cached a stale answer or an inventory bucket
 * opened for an hour and closed. Acting on that costs a change fee for a fare that is no longer there
 * by the time the agent picks up the phone, and it has happened enough that the desk now wants a drop
 * to hold before it is told about it.
 *
 * <p>So a drop is not the lowest price seen. It is a price below the threshold that has been seen on
 * the last several polls in a row, and the count is the hysteresis. Two is the number this desk landed
 * on ({@link #DEFAULT_HYSTERESIS}): one is noise and three is too slow on a departure a week away.
 *
 * <p>The threshold itself is not a fixed number either. It is the change fee plus what the desk needs
 * to make the phone call worth making ({@link #thresholdFor}), so a ticket with a fifty euro fee and a
 * ticket with a three hundred euro fee want different drops, and the watch carries its own.
 *
 * <p>A watch is mutable: each poll is {@linkplain #record recorded} on it and it is {@linkplain
 * #retire retired} once it stops being worth anything. Build one with {@link #Watch(Ticket, String,
 * String, LocalDate, Money)} or, for anything beyond the five things every watch needs, {@link
 * #builder()}.
 */
public final class Watch {

    /** How many polls in a row a drop has to hold before the desk hears about it. */
    public static final int DEFAULT_HYSTERESIS = 2;

    /** What the desk needs on top of the fee, in whole units of any currency. */
    private static final long MARGIN = 25;

    private final Ticket ticket;
    private final String origin;
    private final String destination;
    private final LocalDate departure;
    private final Money threshold;
    private final Cabin cabin;
    private final int hysteresis;
    private final Optional<LocalDateTime> lastChecked;
    private final List<Observation> history;
    private boolean active;
    private Optional<Retired> retiredReason;
    private int checks;

    /**
     * A live watch in economy with the default hysteresis and nothing seen yet.
     *
     * @throws ValidationError when an end is neither a city nor an airport, or the threshold is
     *     negative
     */
    public Watch(Ticket ticket, String origin, String destination, LocalDate departure, Money threshold) {
        this(builder()
                .ticket(ticket)
                .origin(origin)
                .destination(destination)
                .departure(departure)
                .threshold(threshold));
    }

    private Watch(Builder made) {
        this.ticket = Objects.requireNonNull(made.ticket, "ticket");
        this.origin = Geography.cityOf(Objects.requireNonNull(made.origin, "origin"));
        this.destination = Geography.cityOf(Objects.requireNonNull(made.destination, "destination"));
        this.departure = Objects.requireNonNull(made.departure, "departure");
        this.threshold = Objects.requireNonNull(made.threshold, "threshold");
        this.cabin = Objects.requireNonNull(made.cabin, "cabin");
        this.hysteresis = made.hysteresis;
        this.active = made.active;
        this.retiredReason = Objects.requireNonNull(made.retiredReason, "retiredReason");
        this.checks = made.checks;
        this.lastChecked = Objects.requireNonNull(made.lastChecked, "lastChecked");
        this.history = new ArrayList<>(made.history);
        if (threshold.isNegative()) {
            throw new ValidationError("a threshold is not negative", Details.of("value", threshold.toString()));
        }
        if (hysteresis < 1) {
            throw new ValidationError("a drop has to hold at least once", Details.of("value", hysteresis));
        }
    }

    /**
     * A builder for a watch in economy with the default hysteresis, live and with nothing seen. The
     * ticket, both ends, the departure and the threshold have to be given.
     */
    public static Builder builder() {
        return new Builder();
    }

    /** The ticket being watched. */
    public Ticket ticket() {
        return ticket;
    }

    /** The city the watched market leaves from. */
    public String origin() {
        return origin;
    }

    /** The city the watched market goes to. */
    public String destination() {
        return destination;
    }

    /** The day of the watched departure. */
    public LocalDate departure() {
        return departure;
    }

    /** How far below what was paid a reading has to sit to count as a drop. */
    public Money threshold() {
        return threshold;
    }

    /** The cabin watched. */
    public Cabin cabin() {
        return cabin;
    }

    /** How many readings in a row a drop has to hold. */
    public int hysteresis() {
        return hysteresis;
    }

    /** Whether the watch is still looking. */
    public boolean isActive() {
        return active;
    }

    /** Why the watch was closed, while it is closed with a reason. */
    public Optional<Retired> retiredReason() {
        return retiredReason;
    }

    /** How many polls have been recorded, including any made before this watch was loaded. */
    public int checks() {
        return checks;
    }

    /** When the market was last polled, if it has been. */
    public Optional<LocalDateTime> lastChecked() {
        return lastChecked;
    }

    /** Every reading recorded, oldest first. Read only. */
    public List<Observation> history() {
        return Collections.unmodifiableList(history);
    }

    /** The series the watch reads: {@code FRA-NYC-2025-07-04-Y}. */
    public String seriesKey() {
        return Series.key(origin, destination, departure, cabin);
    }

    /** What was paid, which is the ticket's base fare. */
    public Money paid() {
        return ticket.baseFare();
    }

    /** Note what a poll found, whether or not it was interesting. */
    public void record(Observation observation) {
        history.add(Objects.requireNonNull(observation, "observation"));
        checks += 1;
    }

    /** Close the watch, for a reason. */
    public void retire(Retired reason) {
        active = false;
        retiredReason = Optional.of(reason);
    }

    /**
     * Whether this watch has stopped being worth anything: its departure is before today, or every
     * coupon on its ticket has been flown. Empty while it is still worth keeping.
     */
    public Optional<Retired> shouldRetire(LocalDate today) {
        if (departure.isBefore(today)) {
            return Optional.of(Retired.FLOWN);
        }
        if (ticket.isFinished(today)) {
            return Optional.of(Retired.TICKET_FINISHED);
        }
        return Optional.empty();
    }

    /** The cheapest reading on or after a day, the first of equals; empty when there is none. */
    public Optional<Observation> bestSince(LocalDate when) {
        Observation best = null;
        for (Observation observation : history) {
            if (observation.seenOn().isBefore(when)) {
                continue;
            }
            if (best == null
                    || observation.amount().amount().compareTo(best.amount().amount()) < 0) {
                best = observation;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * How far below what was paid one reading sits. Never negative.
     *
     * @throws ValidationError when the reading is in another currency than what was paid, naming the
     *     {@code paid} and the {@code seen} currency
     */
    public Money dropAgainst(Observation observation) {
        Money seen = observation.amount();
        if (!seen.currency().equals(paid().currency())) {
            throw new ValidationError(
                    "a watch compares one currency", Details.of("paid", paid().currency(), "seen", seen.currency()));
        }
        Money gap = paid().minus(seen);
        return gap.isNegative() ? Money.zero(paid().currency()) : gap;
    }

    /** Whether a reading sits at least the threshold below what was paid. */
    public boolean clearsThreshold(Observation observation) {
        return dropAgainst(observation).isAtLeast(threshold);
    }

    /**
     * The drop the desk should hear about, or empty.
     *
     * <p>Nothing on a retired watch. Otherwise the last {@link #hysteresis()} readings all have to
     * clear the threshold, and the one reported is the dearest of them rather than the cheapest, the
     * first of equals. That is deliberate: the cheapest of a run is the one most likely to have been the
     * outlier, and quoting it to a customer who then cannot book it is worse than quoting a number that
     * holds.
     */
    public Optional<Observation> heldDrop() {
        if (!active || history.size() < hysteresis) {
            return Optional.empty();
        }
        List<Observation> recent = history.subList(history.size() - hysteresis, history.size());
        for (Observation observation : recent) {
            if (!clearsThreshold(observation)) {
                return Optional.empty();
            }
        }
        Observation dearest = recent.get(0);
        for (Observation observation : recent) {
            if (observation.amount().amount().compareTo(dearest.amount().amount()) > 0) {
                dearest = observation;
            }
        }
        return Optional.of(dearest);
    }

    /** How many of the readings before the latest found the same amount as it, counting back. */
    public int quietRun() {
        if (history.size() < 2) {
            return 0;
        }
        Money last = history.get(history.size() - 1).amount();
        int run = 0;
        for (int index = history.size() - 2; index >= 0; index--) {
            if (!history.get(index).amount().equals(last)) {
                break;
            }
            run += 1;
        }
        return run;
    }

    /**
     * {@code FRA-NYC-2025-07-04-Y live after 3 checks}, or {@code FRA-NYC-2025-07-04-Y retired, a
     * reissue was made}.
     */
    @Override
    public String toString() {
        if (!active) {
            String reason = retiredReason.map(Retired::value).orElse("no reason given");
            return seriesKey() + " retired, " + reason;
        }
        return seriesKey() + " live after " + checks + " checks";
    }

    /**
     * What a drop has to beat before anybody picks up the phone: the fee plus the margin, because a
     * drop that exactly covers the fee is worth nothing to the customer and an hour of the desk's time.
     *
     * @throws ValidationError when the fee and the margin are in different currencies
     */
    public static Money thresholdFor(Money changeFee, Money margin) {
        if (!changeFee.currency().equals(margin.currency())) {
            throw new ValidationError("a threshold is in one currency");
        }
        return changeFee.plus(margin);
    }

    /** One offer as the reading a watch keeps: its base fare, fingerprint, source and allowance. */
    public static Observation observationFrom(Offer offer, LocalDate seenOn) {
        return new Observation(seenOn, offer.breakdown().base(), offer.fingerprint(), offer.source(), offer.baggage());
    }

    /**
     * The key a watch on this market would have.
     *
     * @throws ValidationError when an end is neither a city nor an airport
     */
    public static String seriesKeyFor(String origin, String destination, LocalDate departure, Cabin cabin) {
        return Series.key(origin, destination, departure, cabin);
    }

    /** What every held drop across a set of watches adds up to. */
    public static Money totalDropped(List<Watch> watches, String currency) {
        Money running = Money.zero(currency);
        for (Watch watch : watches) {
            Optional<Observation> held = watch.heldDrop();
            if (held.isPresent()) {
                running = running.plus(watch.dropAgainst(held.get()));
            }
        }
        return running;
    }

    /** What the desk needs on top of the fee. Twenty five, everywhere. */
    public static Money defaultMargin(String currency) {
        return Money.of(MARGIN, currency);
    }

    /** Collects a watch. Every setter returns the builder. */
    public static final class Builder {

        private Ticket ticket;
        private String origin;
        private String destination;
        private LocalDate departure;
        private Money threshold;
        private Cabin cabin = Cabin.ECONOMY;
        private int hysteresis = DEFAULT_HYSTERESIS;
        private boolean active = true;
        private Optional<Retired> retiredReason = Optional.empty();
        private int checks;
        private Optional<LocalDateTime> lastChecked = Optional.empty();
        private List<Observation> history = List.of();

        private Builder() {}

        public Builder ticket(Ticket value) {
            ticket = value;
            return this;
        }

        /** The market's origin, an airport or a city; the watch keeps the city. */
        public Builder origin(String value) {
            origin = value;
            return this;
        }

        /** The market's destination, an airport or a city; the watch keeps the city. */
        public Builder destination(String value) {
            destination = value;
            return this;
        }

        public Builder departure(LocalDate value) {
            departure = value;
            return this;
        }

        public Builder threshold(Money value) {
            threshold = value;
            return this;
        }

        public Builder cabin(Cabin value) {
            cabin = value;
            return this;
        }

        public Builder hysteresis(int value) {
            hysteresis = value;
            return this;
        }

        public Builder active(boolean value) {
            active = value;
            return this;
        }

        public Builder retiredReason(Retired value) {
            return retiredReason(Optional.of(value));
        }

        /** Why the watch was closed, or that no reason was given. */
        public Builder retiredReason(Optional<Retired> value) {
            retiredReason = value;
            return this;
        }

        public Builder checks(int value) {
            checks = value;
            return this;
        }

        public Builder lastChecked(LocalDateTime value) {
            return lastChecked(Optional.of(value));
        }

        /** When the market was last polled, or that it has not been. */
        public Builder lastChecked(Optional<LocalDateTime> value) {
            lastChecked = value;
            return this;
        }

        /** The readings already taken, oldest first. They are copied. */
        public Builder history(List<Observation> value) {
            history = List.copyOf(value);
            return this;
        }

        /**
         * The watch.
         *
         * @throws ValidationError when an end is neither a city nor an airport, the threshold is
         *     negative, naming the {@code value}, or the hysteresis is below one, naming the {@code
         *     value}
         */
        public Watch build() {
            return new Watch(this);
        }
    }
}
