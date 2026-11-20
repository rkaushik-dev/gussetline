package io.github.rkaushikdev.gussetline.offers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/**
 * What the desk asks of a market's worth of offers: the cheapest, the distinct ones, and how far
 * apart they are.
 */
public final class Offers {

    /** The lowest total first, then the earlier sighting, then the source in alphabetical order. */
    private static final Comparator<Offer> CHEAPEST_FIRST = Comparator.comparing(
                    (Offer offer) -> offer.total().amount())
            .thenComparing(Offer::observedAt)
            .thenComparing(Offer::source);

    private Offers() {}

    /**
     * The lowest total. Ties break on the earlier observation, then the source; offers that tie on all
     * three give the first of them.
     *
     * @throws ValidationError when there are no offers, or they are in more than one currency, naming
     *     the {@code currencies} in sorted order
     */
    public static Offer cheapest(List<Offer> offers) {
        if (offers.isEmpty()) {
            throw new ValidationError("no offers to compare");
        }
        Set<String> currencies = new TreeSet<>();
        offers.forEach(offer -> currencies.add(offer.currency()));
        if (currencies.size() > 1) {
            throw new ValidationError(
                    "offers in more than one currency", Details.of("currencies", List.copyOf(currencies)));
        }
        return Collections.min(offers, CHEAPEST_FIRST);
    }

    /**
     * Keep the first sighting of each distinct priced itinerary, by {@linkplain Offer#fingerprint()
     * fingerprint}. The offers come back in the order they were seen; offers seen at the same moment
     * keep the order they were given in.
     */
    public static List<Offer> deduplicate(List<Offer> offers) {
        List<Offer> bySighting = new ArrayList<>(offers);
        bySighting.sort(Comparator.comparing(Offer::observedAt));
        Set<String> seen = new HashSet<>();
        List<Offer> kept = new ArrayList<>();
        for (Offer offer : bySighting) {
            if (seen.add(offer.fingerprint())) {
                kept.add(offer);
            }
        }
        return List.copyOf(kept);
    }

    /**
     * How far apart the market is, which is the desk's first sanity check: the highest total's
     * amount minus the lowest's. Zero for fewer than two offers.
     */
    public static BigDecimal spread(List<Offer> offers) {
        if (offers.size() < 2) {
            return BigDecimal.ZERO;
        }
        List<BigDecimal> amounts =
                offers.stream().map(offer -> offer.total().amount()).toList();
        return Collections.max(amounts).subtract(Collections.min(amounts), Numbers.CONTEXT);
    }
}
