package io.github.rkaushikdev.gussetline.involuntary;

import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The flat compensation Regulation 261/2004 owes a passenger, which is not a refund and not a fare
 * question, and which the desk has to quote next to every involuntary reissue out of Europe.
 *
 * <p>The amount is set by distance and nothing else: the great circle from where the journey
 * started to where it was meant to end, not the sector that went wrong, so a missed connection in
 * Frankfurt on the way from Vienna to New York is a long haul claim. Three bands: up to 1500
 * kilometres, up to 3500, and beyond.
 *
 * <p>Which flights are covered is a second rule that decides whether the first one is read at all:
 * every departure from a covered state, and an arrival into one only on a carrier licensed there. A
 * New York to Frankfurt journey on a United metal owes nothing; on Lufthansa it owes the long haul
 * amount.
 *
 * <p>And a third rule halves what the first one produced. A carrier that gets the passenger to the
 * destination within two, three or four hours of the scheduled arrival, by band, pays half. A
 * cancellation notified a week or two ahead and rerouted close enough to the original times owes
 * nothing at all, and one notified a fortnight ahead owes nothing whatever the rerouting.
 */
public final class Eu261 {

    /** The states the regulation covers, by country code: the Union, Norway and Switzerland. */
    public static final Set<String> COVERED = Set.of(
            "AT", "BE", "CZ", "DE", "DK", "ES", "FI", "FR", "HU", "IE", "IT", "NL", "PL", "PT", "SE", "NO", "CH");

    /** A short haul journey is up to this many kilometres. */
    public static final int SHORT_KM = 1500;

    /** A medium haul journey is up to this many kilometres. */
    public static final int MEDIUM_KM = 3500;

    /** How late a passenger has to arrive before a delay is owed anything at all. */
    public static final Duration DELAY_THRESHOLD = Duration.ofHours(3);

    /** A cancellation notified at least this many days ahead owes no compensation. */
    public static final int FULL_NOTICE_DAYS = 14;

    /** A cancellation notified at least this many days ahead owes none when rerouted within four hours. */
    public static final int SHORT_NOTICE_DAYS = 7;

    private static final double KM_PER_MILE = 1.609344;
    private static final BigDecimal HALF = new BigDecimal("0.5");

    private Eu261() {}

    /**
     * What the regulation owes for one disruption on a journey, in euro, and why.
     *
     * <p>Nothing is owed when the journey is outside the regulation, when the cause was an
     * extraordinary circumstance (which never excuses denied boarding), when the passenger was only
     * downgraded (that is a refund of part of the ticket, not compensation), when a delay or a
     * missed connection arrives less than {@link #DELAY_THRESHOLD} late, or when a cancellation was
     * notified far enough ahead.
     */
    public static Compensation assess(Itinerary journey, Disruption disruption) {
        int kilometres = kilometres(journey);
        Band band = band(kilometres);
        if (!covers(journey)) {
            return Compensation.none(
                    kilometres, "outside the regulation: neither leaves nor flies into a covered state");
        }
        Cause cause = disruption.cause();
        if (cause == Cause.DOWNGRADED) {
            return Compensation.none(kilometres, "a downgrade is refunded in part, not compensated");
        }
        if (disruption.extraordinary() && cause != Cause.DENIED_BOARDING) {
            return Compensation.none(kilometres, "extraordinary circumstances");
        }
        Duration late = disruption.lateAtDestination();
        List<String> reasons = new ArrayList<>();
        reasons.add(band.label + ", " + kilometres + " km");
        if (cause == Cause.MISCONNECTED) {
            if (late.compareTo(DELAY_THRESHOLD) < 0) {
                return Compensation.none(kilometres, "arrived less than three hours late");
            }
            if (band == Band.LONG && late.compareTo(band.halvedWithin) < 0) {
                reasons.add("long haul arriving under four hours late is halved");
                return new Compensation(band.amount.times(HALF), kilometres, reasons);
            }
            return new Compensation(band.amount, kilometres, reasons);
        }
        if (cause == Cause.CANCELLED || cause == Cause.SCHEDULE_CHANGE) {
            int notice = disruption.daysNotice();
            if (notice >= FULL_NOTICE_DAYS) {
                return Compensation.none(kilometres, "notified " + notice + " days ahead");
            }
            Duration allowed = notice >= SHORT_NOTICE_DAYS ? Duration.ofHours(4) : Duration.ofHours(2);
            if (late.compareTo(allowed) <= 0) {
                return Compensation.none(kilometres, "rerouted close enough to the times booked");
            }
        }
        if (late.compareTo(band.halvedWithin) <= 0) {
            reasons.add("rerouted within " + band.halvedWithin.toHours() + " hours, halved");
            return new Compensation(band.amount.times(HALF), kilometres, reasons);
        }
        return new Compensation(band.amount, kilometres, reasons);
    }

    /**
     * What Article 10 refunds a passenger flown in a lower cabin than booked: thirty, fifty or
     * seventy five percent of what the ticket cost, by band, rounded down to the minor unit. Empty
     * when the regulation does not cover the journey.
     *
     * <p>A share of the price paid and not the difference between two cabins, which is why it needs
     * nothing priced: a business seat sold at a deep discount still refunds three quarters of what
     * was paid for it on a long haul.
     */
    public static Optional<Money> downgradeRefund(Itinerary journey, Money paid) {
        if (!covers(journey)) {
            return Optional.empty();
        }
        BigDecimal share = band(kilometres(journey)).downgradeShare;
        return Optional.of(Money.roundRefund(paid.times(share)));
    }

    /**
     * Whether the regulation reads this journey: it leaves a covered state, or it lands in one on a
     * carrier licensed in one. The carrier is whoever operates the first flown sector.
     */
    public static boolean covers(Itinerary journey) {
        if (COVERED.contains(Geography.airportOrCityCountry(journey.origin()))) {
            return true;
        }
        String operator = journey.segments().get(0).operatedBy();
        return COVERED.contains(Geography.airportOrCityCountry(journey.destination()))
                && Carriers.isKnown(operator)
                && COVERED.contains(Carriers.carrier(operator).country());
    }

    /** The great circle from the origin to the destination, in whole kilometres, rounded. */
    public static int kilometres(Itinerary journey) {
        return (int) Math.round(Distance.greatCircle(journey.origin(), journey.destination()) * KM_PER_MILE);
    }

    private static Band band(int kilometres) {
        if (kilometres <= SHORT_KM) {
            return Band.SHORT;
        }
        if (kilometres <= MEDIUM_KM) {
            return Band.MEDIUM;
        }
        return Band.LONG;
    }

    /** The three distance bands, each with its amount and the rerouting that halves it. */
    private enum Band {
        SHORT("short haul", 250, 2, "0.30"),
        MEDIUM("medium haul", 400, 3, "0.50"),
        LONG("long haul", 600, 4, "0.75");

        private final String label;
        private final Money amount;
        private final Duration halvedWithin;
        private final BigDecimal downgradeShare;

        Band(String label, int euro, int hours, String share) {
            this.label = label;
            this.amount = Money.of(euro, "EUR");
            this.halvedWithin = Duration.ofHours(hours);
            this.downgradeShare = new BigDecimal(share);
        }
    }
}
