package io.github.rkaushikdev.gussetline.rules;

import io.github.rkaushikdev.gussetline.geography.Geography;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** The counting categories 8 and 9 share: which of a journey's break points a filing sees. */
final class Breaks {

    private Breaks() {}

    /**
     * The break points a filing counts, each folded to its city, in journey order.
     *
     * @param points the break points of one kind, stopovers or connections, as the itinerary reads
     *     them
     * @param context the journey and the component being priced
     * @param perDirection whether only the points inside the component count; otherwise every point
     *     on the journey does
     */
    static List<String> counted(List<String> points, Context context, boolean perDirection) {
        List<String> cities = points.stream().map(Geography::cityOf).toList();
        if (!perDirection) {
            return cities;
        }
        Set<String> inside =
                context.component().viaPoints().stream().map(Geography::cityOf).collect(Collectors.toSet());
        return cities.stream().filter(inside::contains).toList();
    }
}
