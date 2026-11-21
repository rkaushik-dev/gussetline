package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.flights.Segment;
import java.time.LocalDate;
import java.util.List;

/**
 * Category 11: no sector of the journey may fly on these dates, the way home included. A journey
 * clear of the blackout on the way out and inside it on the way home is blacked out.
 *
 * @param ranges the dates no sector may fly on
 */
public record Blackout(List<DateRange> ranges) implements Provision {

    public Blackout {
        ranges = List.copyOf(ranges);
    }

    /** Nothing filed. */
    public Blackout() {
        this(List.of());
    }

    @Override
    public Category category() {
        return Category.BLACKOUTS;
    }

    @Override
    public Outcome evaluate(Context context) {
        if (ranges.isEmpty()) {
            return silent(category());
        }
        for (Segment segment : context.itinerary().segments()) {
            LocalDate when = segment.departure().toLocalDate();
            for (DateRange window : ranges) {
                if (window.covers(when)) {
                    return fails(
                            category(),
                            segment.designator() + " flies " + when + " inside a blackout",
                            Details.of("sector", segment.designator(), "blackout", window.toString()));
                }
            }
        }
        return passes(category(), "no sector flies in a blackout");
    }
}
