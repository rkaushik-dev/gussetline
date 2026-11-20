package io.github.rkaushikdev.gussetline.distance;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MileageUnavailable;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Airport;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.List;

/**
 * Distances, and why the published one is not the one you can compute.
 *
 * <p>Mileage in the tariff is ticketed point mileage, which is published per city pair and per
 * global indicator and is not the great circle distance. It is close to it, it is usually a little
 * longer, and it is occasionally very different because it follows a corridor that aircraft are
 * actually allowed to fly. Two cities can also have two different ticketed point mileages depending
 * on which way round the world the fare goes, which is why the indicator is part of the key.
 *
 * <p>Great circle is still here because a pair with no published mileage has to price somehow, and
 * falling back to it with the fallback recorded is better than refusing. Everything downstream reads
 * the flag, because a mileage surcharge computed off a fallback is a finding the desk will not send
 * to a supplier without checking.
 */
public final class Distance {

    /** The mean radius of the earth in statute miles. */
    public static final double EARTH_RADIUS_MILES = 3958.7613;

    private Distance() {}

    /**
     * An airport to measure from, given a code that may name a city.
     *
     * <p>A metro code has no coordinates of its own, so measuring NYC to MUC by great circle fails
     * with an unknown airport, and the caller reads that as a bad itinerary rather than as an
     * unpublished mileage. The first airport of the city, in sorted order, stands in, which is within
     * a few miles of any of them and is only ever used where nothing is published anyway.
     *
     * @throws ValidationError when the code is neither a city nor an airport
     * @throws MileageUnavailable when a city has no airport to stand in for it
     */
    public static String pointOf(String code) {
        String key = Text.code(code);
        if (Geography.AIRPORTS.containsKey(key)) {
            return key;
        }
        List<String> found = Geography.airportsIn(key);
        if (found.isEmpty()) {
            throw new MileageUnavailable("nothing to measure from", Details.of("point", key));
        }
        return found.get(0);
    }

    /**
     * Statute miles between two airports along the great circle, rounded to the nearest whole mile
     * with a tie going to the even one.
     *
     * <p>The tariff works in statute miles and rounds, so this rounds too. Doing the rounding later
     * means two call sites disagree by a mile and the difference surfaces as a surcharge band change
     * on exactly one itinerary. The trigonometry is the strict, platform independent kind, so a
     * distance never moves by a mile from one machine to the next.
     *
     * @throws ValidationError when either code is not an airport; a city is refused, see {@link
     *     #pointOf}
     */
    public static int greatCircle(String left, String right) {
        Airport one = Geography.airport(left);
        Airport two = Geography.airport(right);
        double lat1 = StrictMath.toRadians(one.latitude());
        double lon1 = StrictMath.toRadians(one.longitude());
        double lat2 = StrictMath.toRadians(two.latitude());
        double lon2 = StrictMath.toRadians(two.longitude());
        double dlat = lat2 - lat1;
        double dlon = lon2 - lon1;
        double sinLat = StrictMath.sin(dlat / 2);
        double sinLon = StrictMath.sin(dlon / 2);
        double sinLonSquared = sinLon * sinLon;
        double a = sinLat * sinLat + StrictMath.cos(lat1) * StrictMath.cos(lat2) * sinLonSquared;
        return (int) Math.rint(2 * EARTH_RADIUS_MILES * StrictMath.asin(StrictMath.sqrt(a)));
    }

    /**
     * The pairs the desk sells often enough to have keyed in by hand.
     *
     * <p>Everything else falls back to great circle, and the coverage report says how often that
     * happened so the list can grow where it matters. A new table each call, so a caller may add to
     * it.
     */
    public static MileageTable defaultMileageTable() {
        MileageTable table = new MileageTable();
        table.addMany(List.of(
                row("FRA", "JFK", 3851),
                row("FRA", "ORD", 4342),
                row("FRA", "LAX", 5791),
                row("FRA", "SIN", 6383),
                row("FRA", "NRT", 5928),
                row("FRA", "DXB", 2984),
                row("FRA", "DEL", 3812),
                row("FRA", "JNB", 5388),
                row("FRA", "GRU", 5972),
                row("LON", "JFK", 3451),
                row("LON", "ORD", 3947),
                row("LON", "LAX", 5439),
                row("LON", "SIN", 6754),
                row("LON", "TYO", 5941),
                row("LON", "DXB", 3402),
                row("LON", "DEL", 4172),
                row("LON", "JNB", 5622),
                row("LON", "GRU", 5892),
                row("PAR", "JFK", 3628),
                row("PAR", "SIN", 6673),
                row("PAR", "DXB", 3253),
                row("AMS", "JFK", 3639),
                row("AMS", "SIN", 6524),
                row("ZRH", "JFK", 3925),
                row("MAD", "JFK", 3585),
                row("FCO", "JFK", 4271),
                row("IST", "JFK", 5009),
                row("DXB", "SIN", 3634),
                row("DXB", "TYO", 4915),
                row("DXB", "SYD", 7481),
                row("SIN", "SYD", 3915),
                row("SIN", "TYO", 3312),
                row("TYO", "LAX", 5451),
                row("TYO", "SFO", 5130),
                row("HKG", "LAX", 7260),
                row("SYD", "LAX", 7488),
                row("JFK", "LAX", 2475),
                row("JFK", "ORD", 740),
                row("JFK", "MIA", 1090),
                row("LON", "FRA", 406),
                row("LON", "PAR", 215),
                row("LON", "MAD", 785),
                row("LON", "FCO", 895),
                row("FRA", "IST", 1160),
                row("FRA", "MAD", 882),
                row("FRA", "ARN", 741),
                row("DEL", "SIN", 2571),
                row("DEL", "DXB", 1372)));
        // Frankfurt to Tokyo is published twice, because the eastbound Siberian routing and the
        // westbound polar one are not the same distance and the surcharge bands land differently on
        // each.
        table.add("FRA", "TYO", 5928, GlobalIndicator.TS);
        table.add("FRA", "TYO", 7169, GlobalIndicator.PA);
        return table;
    }

    private static MileageTable.Row row(String left, String right, int miles) {
        return new MileageTable.Row(left, right, miles);
    }
}
