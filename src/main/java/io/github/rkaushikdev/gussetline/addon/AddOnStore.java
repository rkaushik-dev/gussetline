package io.github.rkaushikdev.gussetline.addon;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.geography.Geography;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Filed arbitraries, by carrier and by the point they reach.
 *
 * <p>Arbitraries for the same carrier, point and gateway are held in the order they were added, so
 * two that tie on amount come back in that order.
 */
public final class AddOnStore {

    private static final Comparator<AddOn> CHEAPEST =
            Comparator.comparing((AddOn addOn) -> addOn.amount().amount()).thenComparing(AddOn::point);

    private final Map<Key, List<AddOn>> byPoint = new LinkedHashMap<>();

    /** Hold an arbitrary after the others filed for its carrier, point and gateway, and hand it back. */
    public AddOn add(AddOn addOn) {
        byPoint.computeIfAbsent(new Key(addOn.carrier(), addOn.point(), addOn.gateway()), key -> new ArrayList<>())
                .add(addOn);
        return addOn;
    }

    /** Hold each arbitrary in turn. */
    public void addMany(Iterable<AddOn> addOns) {
        for (AddOn addOn : addOns) {
            add(addOn);
        }
    }

    /**
     * Every arbitrary that reaches a point from a gateway at one end, cheapest first. The carrier
     * may be given in any case and the points may be airports or cities; they are read as the cities
     * the tariff files.
     *
     * @throws ValidationError when the carrier is malformed or a point is unknown
     */
    public List<AddOn> forPoint(String carrier, String point, String gateway, End end) {
        Key key = new Key(Carriers.normaliseCode(carrier), Geography.cityOf(point), Geography.cityOf(gateway));
        return byPoint.getOrDefault(key, List.of()).stream()
                .filter(addOn -> addOn.end() == end)
                .sorted(CHEAPEST)
                .toList();
    }

    /**
     * Every gateway an arbitrary reaches this point from, at either end, sorted.
     *
     * @throws ValidationError when the carrier is malformed or the point is unknown
     */
    public List<String> gateways(String carrier, String point) {
        String code = Carriers.normaliseCode(carrier);
        String wanted = Geography.cityOf(point);
        return byPoint.keySet().stream()
                .filter(key -> key.carrier().equals(code) && key.point().equals(wanted))
                .map(Key::gateway)
                .distinct()
                .sorted()
                .toList();
    }

    /** How many arbitraries are held, across every carrier and point. */
    public int size() {
        return byPoint.values().stream().mapToInt(List::size).sum();
    }

    private record Key(String carrier, String point, String gateway) {}
}
