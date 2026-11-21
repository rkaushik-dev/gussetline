package io.github.rkaushikdev.gussetline.api.routers;

import io.github.rkaushikdev.gussetline.api.Deps;
import io.github.rkaushikdev.gussetline.api.Requires;
import io.github.rkaushikdev.gussetline.api.Role;
import io.github.rkaushikdev.gussetline.api.Schemas.MoneyOut;
import io.github.rkaushikdev.gussetline.api.Schemas.PlaceOut;
import io.github.rkaushikdev.gussetline.api.Schemas.QueueOut;
import io.github.rkaushikdev.gussetline.desk.Place;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.Worklist;
import io.github.rkaushikdev.gussetline.persistence.Book;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The agent's worklist, which is the one screen anybody looks at all day.
 *
 * <p>It reads and never writes. Taking a recommendation is a write and it already has an endpoint of
 * its own on the watches routes, and putting the two together would mean the screen an agent refreshes
 * every thirty seconds is a route that can change something.
 *
 * <p>The whole queue comes back in one response and is not paged. A desk has a few hundred open
 * recommendations, an agent works the top of the list, and paging would let them page past the urgent
 * ones on the first screen. The limit is there for a caller that wants the top ten, not to make the
 * response fit.
 *
 * <p>The scoring lives a layer down. This route reads the rows, hands them to the {@link Worklist},
 * and turns what comes back into JSON, which is all a route should ever be doing. The market check
 * looks back over the same handful of readings per series the night run uses ({@link Book#READINGS}),
 * for the same reason.
 */
@RestController
@RequestMapping("/queue")
@Requires(Role.READER)
@Tag(name = "queue")
public final class QueueRoutes {

    /** The currency an empty queue is worth nothing in. */
    private static final String EMPTY_CURRENCY = "EUR";

    private final Deps deps;

    public QueueRoutes(Deps deps) {
        this.deps = deps;
    }

    /** The open recommendations, in the order they should be worked. */
    @GetMapping
    @Operation(summary = "Read Queue", description = "The open recommendations, in the order they should be worked.")
    public QueueOut readQueue(
            @RequestParam @Parameter(description = "Read it as of this day.") Optional<LocalDate> on,
            @RequestParam(defaultValue = "200") @Min(1) @Max(1000) int limit) {
        LocalDate today = on.orElseGet(LocalDate::now);
        return deps.unit(work -> {
            Book book = Book.openBook(work, true);
            List<Recommendation> made = book.recommendations();
            List<Place> places = Worklist.build(made, today, book.departures(), book.histories());
            String currency = made.isEmpty() ? EMPTY_CURRENCY : made.get(0).currency();
            return new QueueOut(
                    today,
                    places.size(),
                    Worklist.urgent(places).size(),
                    new MoneyOut(Worklist.worthOf(places, currency).amount().toString(), currency),
                    places.stream().limit(limit).map(QueueRoutes::placeOut).toList());
        });
    }

    private static PlaceOut placeOut(Place place) {
        Recommendation made = place.recommendation();
        return new PlaceOut(
                made.ticketNumber(),
                made.seriesKey(),
                made.raisedOn(),
                made.expiresOn(),
                new MoneyOut(made.benefit().amount().toString(), made.currency()),
                place.score().toString(),
                place.daysLeft(),
                place.daysOut(),
                place.isUrgent(),
                place.fareMoved(),
                place.why());
    }
}
