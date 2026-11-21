package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.distance.Distance;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.tariff.Market;
import io.github.rkaushikdev.gussetline.tariff.Tariff;
import io.github.rkaushikdev.gussetline.tariff.TariffStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * One read of everything the pricing engine needs, taken at one moment.
 *
 * <p>Everything the engine reads travels together by design, because a price built from a fare store
 * read at nine and a routing store read at ten cannot be reproduced by anybody, and this is the only
 * place that decides what one moment means. Every command that prices reads through here, so a fare
 * found by one is found by all of them: a city pair folded by one command and not by another would
 * make the desk's own tariff look like a market with nothing filed.
 *
 * <p>The mileage table is not read from the database. Maximum permitted mileages change about once a
 * decade and ship with the desk, and an empty table is not a table with no rows in it, it is a
 * mileage check that fails on a city pair it has never heard of. That is a different failure from a
 * market with no fare and it must not be reachable by forgetting to load something.
 */
public final class Snapshot {

    private Snapshot() {}

    /**
     * Everything a price over these carriers and points could possibly read, on sale on a day: the
     * fares filed between every two of the points' cities in either direction, and the combinability
     * and rules the carriers filed. Carriers are matched in either case; an empty one is ignored.
     *
     * <p>Narrow on purpose. Reading the whole fares table works on a laptop and stops working the week
     * the second carrier is loaded, and the queries here are the ones the indexes were built for.
     */
    public static Tariffs take(UnitOfWork work, List<String> carriers, List<String> points, LocalDate on) {
        List<String> wanted = carriers.stream()
                .filter(code -> !code.isEmpty())
                .map(code -> code.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        List<Market> pairs = Tariff.filingPairs(points);

        TariffStore store = new TariffStore();
        for (String carrier : wanted) {
            for (Market pair : pairs) {
                store.addMany(work.tariff().search(carrier, pair.origin(), pair.destination(), on));
            }
        }

        return Tariffs.builder(store)
                .miles(Distance.defaultMileageTable())
                .combinability(work.combinability().storeFor(wanted))
                .rules(work.rules().storeFor(wanted))
                .build();
    }

    /** {@link #forItinerary(UnitOfWork, Itinerary, LocalDate, Optional)} over the itinerary's own carriers. */
    public static Tariffs forItinerary(UnitOfWork work, Itinerary itinerary, LocalDate on) {
        return forItinerary(work, itinerary, on, Optional.empty());
    }

    /** {@link #forItinerary(UnitOfWork, Itinerary, LocalDate, Optional)} for one carrier. */
    public static Tariffs forItinerary(UnitOfWork work, Itinerary itinerary, LocalDate on, String carrier) {
        return forItinerary(work, itinerary, on, Optional.of(carrier));
    }

    /**
     * The snapshot one itinerary needs, over every point it touches.
     *
     * <p>A carrier may be named, which is what an agent pricing against one airline wants; otherwise
     * the itinerary's own carriers are read. Naming one does not narrow the points: a component can
     * still end anywhere the journey touches.
     *
     * @param carrier the one carrier to read, when one is named; an empty name names none
     */
    public static Tariffs forItinerary(UnitOfWork work, Itinerary itinerary, LocalDate on, Optional<String> carrier) {
        List<String> carriers =
                carrier.filter(code -> !code.isEmpty()).map(List::of).orElseGet(itinerary::carriers);
        List<String> points = new ArrayList<>();
        points.add(itinerary.origin());
        for (Element element : itinerary.elements()) {
            points.add(element.destination());
        }
        return take(work, carriers, points, on);
    }

    /**
     * One snapshot for a whole run of offers, over every carrier they fly or are validated on and
     * every end they have.
     *
     * <p>Read once for the run and not once per offer. An audit over a day touches a few thousand
     * offers across a handful of carriers, and the cost of the single snapshot is that a fare filed
     * halfway through is not seen until the next run. That is the right trade: an audit has to price
     * everything against one tariff or its findings cannot be compared with each other.
     */
    public static Tariffs forOffers(UnitOfWork work, List<Offer> offers, LocalDate on) {
        List<String> carriers = new ArrayList<>();
        List<String> points = new ArrayList<>();
        for (Offer offer : offers) {
            carriers.addAll(offer.carriers());
            offer.validatingCarrier().ifPresent(carriers::add);
            points.add(offer.origin());
            points.add(offer.destination());
        }
        return take(work, carriers, points, on);
    }
}
