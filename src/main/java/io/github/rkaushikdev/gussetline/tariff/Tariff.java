package io.github.rkaushikdev.gussetline.tariff;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Published fares, and the four dates every one of them carries.
 *
 * <p>A fare's sale window says when it may be sold and its travel window says when it may be flown,
 * and the two are read against two different dates ({@link Fare}). Every fare also carries a one
 * way round trip indicator ({@link OwRt}), which says what its amount means. The fares themselves
 * are held in a {@link TariffStore}.
 */
public final class Tariff {

    /** The day a window closes when nobody filed a closing date: as good as never. */
    public static final LocalDate FAR_FUTURE = LocalDate.of(2999, 12, 31);

    private Tariff() {}

    /**
     * Every city pair a fare could be filed for over a set of points.
     *
     * <p>Cities and not airports, because the tariff files FRA to NYC and an itinerary says JFK.
     * Asking for the airport finds nothing and the caller reads that as a market with no fare
     * published, which is the same answer a genuinely uncovered market gives and looks nothing like a
     * bug. Two commands got that wrong separately, which is why it lives here.
     *
     * <p>Both directions, and every point against every other, because a fare component can end
     * anywhere the journey touches and the tariff files each direction on its own. The pairs follow
     * the order the cities first appear in; two points in one city are one point.
     *
     * @throws ValidationError when a point is neither a city nor an airport
     */
    public static List<Market> filingPairs(Collection<String> points) {
        Set<String> cities = new LinkedHashSet<>();
        points.forEach(point -> cities.add(Geography.cityOf(point)));
        List<Market> pairs = new ArrayList<>();
        for (String left : cities) {
            for (String right : cities) {
                if (!left.equals(right)) {
                    pairs.add(new Market(left, right));
                }
            }
        }
        return List.copyOf(pairs);
    }
}
