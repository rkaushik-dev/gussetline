package io.github.rkaushikdev.gussetline.itinerary;

import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.time.Duration;
import java.util.Map;

/**
 * The shortest connection an airport will check a bag through on, which is not a fare rule and
 * overrides every fare rule.
 *
 * <p>A connection under the minimum is not a tight connection, it is a ticket the carrier will not
 * issue and the airport will not honour: the passenger misses the second flight and the fare that
 * priced it has no answer. So a price built on one is refused rather than quoted with a warning.
 *
 * <p>The minimum depends on the airport and on whether the connection crosses a border, because a
 * border means passport control and usually another terminal. A connection is international when
 * either of its two sectors is. An airport the desk has no figure for gets the common figures of an
 * hour, or an hour and a half across a border.
 */
public final class MinimumConnectingTime {

    /** The minimum at an airport with nothing filed, between two domestic sectors. */
    public static final Duration DOMESTIC_DEFAULT = Duration.ofMinutes(60);

    /** The minimum at an airport with nothing filed, when either sector crosses a border. */
    public static final Duration INTERNATIONAL_DEFAULT = Duration.ofMinutes(90);

    /** The airports the desk connects through most, in minutes: domestic, then international. */
    private static final Map<String, int[]> FILED = Map.ofEntries(
            Map.entry("FRA", new int[] {45, 45}),
            Map.entry("MUC", new int[] {30, 45}),
            Map.entry("ZRH", new int[] {40, 40}),
            Map.entry("VIE", new int[] {30, 30}),
            Map.entry("AMS", new int[] {40, 50}),
            Map.entry("CDG", new int[] {60, 90}),
            Map.entry("LHR", new int[] {60, 90}),
            Map.entry("JFK", new int[] {60, 90}),
            Map.entry("ORD", new int[] {50, 90}),
            Map.entry("ATL", new int[] {45, 90}));

    private MinimumConnectingTime() {}

    /** The minimum between landing on one sector and leaving on the next, at the airport they share. */
    public static Duration between(Segment landed, Segment next) {
        boolean international = !Geography.sameCountry(landed.origin(), landed.destination())
                || !Geography.sameCountry(next.origin(), next.destination());
        int[] filed = FILED.get(landed.destination());
        if (filed == null) {
            return international ? INTERNATIONAL_DEFAULT : DOMESTIC_DEFAULT;
        }
        return Duration.ofMinutes(international ? filed[1] : filed[0]);
    }
}
