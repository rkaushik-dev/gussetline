package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The order an agent works the open recommendations in.
 *
 * <p>Sorting by what they are worth is the obvious answer and it is wrong. A four hundred euro saving
 * on a March departure will still be four hundred euros tomorrow. A ninety euro one that expires
 * tonight is worth ninety euros today and nothing at all in the morning, and an agent who works the
 * list by value loses the second one every time.
 *
 * <p>So the order is what is at risk. Three things put a recommendation at risk and they multiply
 * rather than add, because a recommendation that is nearly expired on a departure next week is not
 * twice as urgent as one of those, it is a different kind of problem.
 *
 * <p>The lifetime is the first. A recommendation is raised against a fare that was there when it was
 * raised, and every day it sits the odds of that fare still being there fall. On its last day it is
 * nearly gone.
 *
 * <p>The departure is the second. A reissue three days before travel is a phone call to a passenger
 * who is packing, and it stops being possible at all once they have flown.
 *
 * <p>And the fare having moved is the third. The collectors keep watching after a recommendation is
 * raised, so if the series has been seen dearer since, the number the desk is about to quote may not
 * be there. That demotes rather than removes: a fare seen dearer this morning is often still available
 * and the passenger is the one who finds out.
 *
 * <p>The order is stable between refreshes. An agent halfway down a list that reorders under them
 * loses their place, which costs more than any ordering gains, so equal scores break on the ticket
 * number and never on anything that moves.
 */
public final class Worklist {

    /**
     * How much a recommendation on its last day is worth chasing over one raised this morning. Read off
     * nothing but the desk's own preference.
     */
    public static final BigDecimal EXPIRY_WEIGHT = new BigDecimal("3.0");

    /** The same, for a departure that is nearly here. */
    public static final BigDecimal DEPARTURE_WEIGHT = new BigDecimal("2.0");

    /** Departures further out than this many days are all equally far away. */
    public static final int FAR_OUT_DAYS = 60;

    /** What a recommendation is worth when the market has been seen dearer since. */
    public static final BigDecimal MOVED_ON = new BigDecimal("0.4");

    /** How many days the expiry clock runs over before it carries its whole weight. */
    private static final int EXPIRY_WINDOW_DAYS = 3;

    /** How many places a rendered queue shows unless told otherwise. */
    private static final int SHOWN = 20;

    private Worklist() {}

    /**
     * How much the clock is worth on one recommendation.
     *
     * <p>One means no hurry at all. It climbs towards {@link #EXPIRY_WEIGHT} as the last three days run
     * out and towards {@link #DEPARTURE_WEIGHT} as the last {@link #FAR_OUT_DAYS} before the departure
     * do, and the two multiply because being nearly out of time on both is a different problem from
     * being nearly out of time on one.
     */
    public static BigDecimal urgency(int daysLeft, int daysOut) {
        return towards(EXPIRY_WEIGHT, daysLeft, EXPIRY_WINDOW_DAYS)
                .multiply(towards(DEPARTURE_WEIGHT, daysOut, FAR_OUT_DAYS), Numbers.CONTEXT);
    }

    /** {@link #score(Recommendation, LocalDate, LocalDate, boolean)} on a fare that has not moved. */
    public static BigDecimal score(Recommendation recommendation, LocalDate today, LocalDate departure) {
        return score(recommendation, today, departure, false);
    }

    /**
     * What this recommendation is worth chasing right now: its benefit times the {@linkplain #urgency
     * urgency}, times {@link #MOVED_ON} when the fare has moved, to the cent, half to even.
     */
    public static BigDecimal score(
            Recommendation recommendation, LocalDate today, LocalDate departure, boolean fareMoved) {
        int left = daysBetween(today, recommendation.expiresOn());
        int out = daysBetween(today, departure);
        BigDecimal running = recommendation.benefit().amount().multiply(urgency(left, out), Numbers.CONTEXT);
        if (fareMoved) {
            running = running.multiply(MOVED_ON, Numbers.CONTEXT);
        }
        return running.setScale(2, RoundingMode.HALF_EVEN);
    }

    /**
     * Whether the series has been seen dearer since this was raised: a reading after the day it was
     * raised, dearer than the reading it was raised on.
     *
     * <p>Compared against the reading it was raised on, found by its fingerprint. A recommendation whose
     * own reading is no longer in the history says nothing either way: the desk keeps a handful of
     * readings and an old recommendation falls off the end of them.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a later reading is in another
     *     currency than the one it was raised on
     */
    public static boolean hasMoved(LocalDate raisedOn, String fingerprint, List<Observation> history) {
        Optional<Observation> raisedAt = history.stream()
                .filter(observation -> observation.fingerprint().equals(fingerprint))
                .findFirst();
        if (raisedAt.isEmpty()) {
            return false;
        }
        Money then = raisedAt.get().amount();
        return history.stream()
                .filter(observation -> observation.seenOn().isAfter(raisedOn))
                .anyMatch(observation -> observation.amount().isGreaterThan(then));
    }

    /** {@link #build(List, LocalDate, Map, Map)} with no readings known for any series. */
    public static List<Place> build(
            List<Recommendation> recommendations, LocalDate today, Map<String, LocalDate> departures) {
        return build(recommendations, today, departures, Map.of());
    }

    /**
     * The open recommendations, in the order an agent should work them: the highest score first, then
     * the ticket number.
     *
     * <p>{@code departures} is keyed by series and comes from the caller rather than the recommendation,
     * because a recommendation is a frozen statement about money and carries no travel date of its own.
     * A recommendation whose series has no departure is left out, since nothing can be said about how
     * urgent it is. {@code histories} holds the readings kept for each series, oldest first; a series
     * with none has not moved.
     */
    public static List<Place> build(
            List<Recommendation> recommendations,
            LocalDate today,
            Map<String, LocalDate> departures,
            Map<String, List<Observation>> histories) {
        List<Place> places = new ArrayList<>();
        for (Recommendation made : recommendations) {
            if (made.state() != State.OPEN) {
                continue;
            }
            LocalDate departure = departures.get(made.seriesKey());
            if (departure == null) {
                continue;
            }
            boolean moved =
                    hasMoved(made.raisedOn(), made.fingerprint(), histories.getOrDefault(made.seriesKey(), List.of()));
            places.add(new Place(
                    made,
                    score(made, today, departure, moved),
                    daysBetween(today, made.expiresOn()),
                    daysBetween(today, departure),
                    moved));
        }
        // Highest score first, then the ticket number. Never on anything that
        // moves, so an agent halfway down the list keeps their place.
        places.sort(Comparator.comparing(Place::score).reversed().thenComparing(place -> place.recommendation()
                .ticketNumber()));
        return List.copyOf(places);
    }

    /** The ones that go today whatever else is on the list, in order. */
    public static List<Place> urgent(List<Place> places) {
        return places.stream().filter(Place::isUrgent).toList();
    }

    /** What the whole queue is worth if every one of them is taken. */
    public static Money worthOf(List<Place> places, String currency) {
        Money total = Money.zero(currency);
        for (Place place : places) {
            total = total.plus(place.worth());
        }
        return total;
    }

    /** {@link #render(List, int)} showing the first twenty places. */
    public static String render(List<Place> places) {
        return render(places, SHOWN);
    }

    /**
     * The queue as an agent reads it down the left of a screen: how many are open and how many are for
     * today, then one indented line per place up to the limit, then how many more there are.
     * {@code nothing open} when the queue is empty. A negative limit leaves that many places off the
     * end.
     */
    public static String render(List<Place> places, int limit) {
        if (places.isEmpty()) {
            return "nothing open";
        }
        List<String> lines = new ArrayList<>();
        lines.add(places.size() + " open, " + urgent(places).size() + " for today");
        int shown = limit < 0 ? Math.max(0, places.size() + limit) : Math.min(limit, places.size());
        for (Place place : places.subList(0, shown)) {
            lines.add("  " + place);
        }
        if (places.size() > limit) {
            lines.add("  and " + (places.size() - limit) + " more");
        }
        return String.join("\n", lines);
    }

    private static int daysBetween(LocalDate from, LocalDate to) {
        return Math.toIntExact(ChronoUnit.DAYS.between(from, to));
    }

    /** A multiplier that runs from the weight at nought days to one at the window. */
    private static BigDecimal towards(BigDecimal weight, int days, int window) {
        if (days >= window) {
            return BigDecimal.ONE;
        }
        if (days <= 0) {
            return weight;
        }
        BigDecimal left = Numbers.divide(BigDecimal.valueOf(days), BigDecimal.valueOf(window));
        return weight.subtract(
                weight.subtract(BigDecimal.ONE, Numbers.CONTEXT).multiply(left, Numbers.CONTEXT), Numbers.CONTEXT);
    }
}
