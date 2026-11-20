package io.github.rkaushikdev.gussetline.indicators;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.geography.Area;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.geography.SubArea;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Global indicators, worked out from the journey.
 *
 * <p>Frankfurt to Tokyo has one published fare eastbound over Siberia and another one westbound over
 * the pole and North America, and they are different fares with different rules and different
 * prices. The tariff distinguishes them with a two letter {@link GlobalIndicator}, and a pricing
 * engine that ignores it will cheerfully apply the wrong one and then argue about rounding.
 *
 * <p>The indicator is decided by the areas the journey touches and by the points it routes through,
 * so it cannot be computed from origin and destination alone. A journey with no via points gets the
 * default for the area pair, which is what the tariff calls the shortest sector indicator.
 */
public final class Indicators {

    /**
     * The points a Trans Siberian routing has to touch to earn TS. A journey that touches Moscow on
     * the way from Europe to Japan is TS, and the same journey through Dubai is EH, and the fares
     * differ by several hundred.
     */
    private static final Set<String> SIBERIAN_POINTS = Set.of("MOW", "SVO", "DME", "VKO", "LED");

    private static final Set<SubArea> SOUTH_ATLANTIC_LEFT = Set.of(SubArea.SOUTH_AMERICA);
    private static final Set<SubArea> SOUTH_ATLANTIC_RIGHT = Set.of(SubArea.AFRICA);

    private static final Set<SubArea> FAR_EAST =
            Set.of(SubArea.JAPAN_KOREA, SubArea.SOUTH_EAST_ASIA, SubArea.SOUTH_WEST_PACIFIC);

    /** The oceans a leg can cross. */
    private enum Ocean {
        ATLANTIC,
        PACIFIC
    }

    private Indicators() {}

    /** {@link #globalIndicator(String, String, List)} for a journey with no via points. */
    public static GlobalIndicator globalIndicator(String origin, String destination) {
        return globalIndicator(origin, destination, List.of());
    }

    /**
     * Which published fare family a journey belongs to.
     *
     * <p>The journey is walked leg by leg rather than read off the set of areas it touches, because a
     * set cannot tell a journey that crosses one ocean from one that crosses two. New York to Delhi
     * over Frankfurt touches all three areas and crosses only the Atlantic.
     *
     * @param via the points between the two ends, in order
     * @throws ValidationError when either end is empty, or any point is neither a city nor an
     *     airport
     */
    public static GlobalIndicator globalIndicator(String origin, String destination, List<String> via) {
        if (origin == null || origin.isEmpty() || destination == null || destination.isEmpty()) {
            throw new ValidationError(
                    "a journey needs two ends", Details.of("origin", origin, "destination", destination));
        }

        List<String> points = new ArrayList<>();
        points.add(origin);
        points.addAll(via);
        points.add(destination);
        Set<Area> touched = points.stream()
                .map(Geography::areaOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(Area.class)));
        Set<Ocean> oceans = EnumSet.noneOf(Ocean.class);
        for (int index = 0; index < points.size() - 1; index++) {
            ocean(points.get(index), points.get(index + 1)).ifPresent(oceans::add);
        }

        if (Geography.sameCountry(origin, destination)
                && via.stream().allMatch(point -> Geography.sameCountry(origin, point))) {
            return GlobalIndicator.DO;
        }

        if (oceans.equals(EnumSet.of(Ocean.ATLANTIC, Ocean.PACIFIC))) {
            return GlobalIndicator.AP;
        }
        if (oceans.equals(EnumSet.of(Ocean.PACIFIC))) {
            return GlobalIndicator.PA;
        }
        if (oceans.equals(EnumSet.of(Ocean.ATLANTIC))) {
            return isSouthAtlantic(origin, destination) ? GlobalIndicator.SA : GlobalIndicator.AT;
        }

        if (touched.equals(EnumSet.of(Area.ONE))) {
            return GlobalIndicator.WH;
        }

        if (points.stream().anyMatch(point -> SIBERIAN_POINTS.contains(point.toUpperCase(Locale.ROOT)))) {
            return GlobalIndicator.TS;
        }
        if (touched.equals(EnumSet.of(Area.THREE)) && bothFarEast(origin, destination)) {
            return GlobalIndicator.FE;
        }
        return GlobalIndicator.EH;
    }

    /** Whether two points are in different countries. The area does not come into it. */
    public static boolean isInternational(String origin, String destination) {
        return !Geography.sameCountry(origin, destination);
    }

    /**
     * Whether a fare published under one indicator may price a journey.
     *
     * <p>A fare filed EH prices an EH journey and also a TS, RU or FE one, because those are all
     * eastern hemisphere journeys with a narrower name. A fare filed AT likewise prices an SA one.
     * The reverse is not true, and that asymmetry is the whole reason this is not an equality check
     * at the call site.
     */
    public static boolean indicatorMatches(GlobalIndicator published, GlobalIndicator journey) {
        if (published == journey) {
            return true;
        }
        if (published == GlobalIndicator.EH) {
            return journey == GlobalIndicator.TS || journey == GlobalIndicator.RU || journey == GlobalIndicator.FE;
        }
        if (published == GlobalIndicator.AT) {
            return journey == GlobalIndicator.SA;
        }
        return false;
    }

    /**
     * Which ocean one leg crosses, if it crosses one at all.
     *
     * <p>A leg between Area 1 and Area 3 goes over the Pacific unless the Area 3 end is in South Asia,
     * which is reached the other way round over the pole and prices as an Atlantic fare. That single
     * exception is why this is a question asked of a leg and not a lookup on the area pair.
     */
    private static Optional<Ocean> ocean(String from, String to) {
        Area left = Geography.areaOf(from);
        Area right = Geography.areaOf(to);
        Set<Area> pair = EnumSet.of(left, right);
        if (pair.equals(EnumSet.of(Area.ONE, Area.TWO))) {
            return Optional.of(Ocean.ATLANTIC);
        }
        if (pair.equals(EnumSet.of(Area.ONE, Area.THREE))) {
            String far = left == Area.ONE ? to : from;
            return Optional.of(Geography.subAreaOf(far) == SubArea.SOUTH_ASIA ? Ocean.ATLANTIC : Ocean.PACIFIC);
        }
        return Optional.empty();
    }

    private static boolean isSouthAtlantic(String origin, String destination) {
        SubArea left = Geography.subAreaOf(origin);
        SubArea right = Geography.subAreaOf(destination);
        return (SOUTH_ATLANTIC_LEFT.contains(left) && SOUTH_ATLANTIC_RIGHT.contains(right))
                || (SOUTH_ATLANTIC_LEFT.contains(right) && SOUTH_ATLANTIC_RIGHT.contains(left));
    }

    private static boolean bothFarEast(String origin, String destination) {
        return FAR_EAST.contains(Geography.subAreaOf(origin)) && FAR_EAST.contains(Geography.subAreaOf(destination));
    }
}
