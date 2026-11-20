package io.github.rkaushikdev.gussetline.routing;

import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.carriers.Carriers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Routing maps by carrier and number, which is how a fare names one. */
public final class RoutingStore {

    private final Map<Key, RoutingMap> maps = new HashMap<>();

    /**
     * File a map under a carrier, replacing any map already filed under the same carrier and number.
     *
     * @return the map filed
     * @throws ValidationError when the carrier is not a well formed designator
     */
    public RoutingMap add(String carrier, RoutingMap routing) {
        maps.put(new Key(Carriers.normaliseCode(carrier), routing.number()), routing);
        return routing;
    }

    /** The map a carrier filed under a number, in whatever case and padding the two are written. */
    public Optional<RoutingMap> get(String carrier, String number) {
        return Optional.ofNullable(maps.get(new Key(Carriers.normaliseCode(carrier), Text.strip(number))));
    }

    /** {@link #permits(String, String, List, List)} with no carriers given. */
    public boolean permits(String carrier, String number, List<String> cities) {
        return permits(carrier, number, cities, List.of());
    }

    /**
     * Whether a fare's routing permits the path.
     *
     * <p>A fare whose map is not held prices on mileage rather than failing. This is deliberate and
     * it is the conservative choice. Refusing would drop every fare on a carrier whose maps have not
     * loaded yet, and the mileage check that runs instead is stricter than most maps are. The mileage
     * routing passes without a look at the carrier.
     */
    public boolean permits(String carrier, String number, List<String> cities, List<String> carriers) {
        if (Text.strip(number).equals(RoutingMap.MILEAGE_ROUTING)) {
            return true;
        }
        return get(carrier, number)
                .map(found -> found.permits(cities, carriers))
                .orElse(true);
    }

    /** How many maps are filed. */
    public int size() {
        return maps.size();
    }

    private record Key(String carrier, String number) {}
}
