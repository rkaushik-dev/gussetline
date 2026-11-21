package io.github.rkaushikdev.gussetline.rules;

import static io.github.rkaushikdev.gussetline.rules.Outcome.fails;
import static io.github.rkaushikdev.gussetline.rules.Outcome.passes;
import static io.github.rkaushikdev.gussetline.rules.Outcome.silent;

import io.github.rkaushikdev.gussetline.Details;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * Category 3: the fare is priced for these dates and no others, read against the day the
 * component's first sector leaves, not against the rest of the journey.
 *
 * @param ranges the windows the fare is priced for
 * @param season the season's name, such as {@code low}, or empty when unnamed
 */
public record Seasonality(List<DateRange> ranges, String season) implements Provision {

    public Seasonality {
        ranges = List.copyOf(ranges);
        Objects.requireNonNull(season, "season");
    }

    /** Nothing filed. */
    public Seasonality() {
        this(List.of(), "");
    }

    /** An unnamed season over these windows. */
    public Seasonality(List<DateRange> ranges) {
        this(ranges, "");
    }

    @Override
    public Category category() {
        return Category.SEASONALITY;
    }

    @Override
    public Outcome evaluate(Context context) {
        if (ranges.isEmpty()) {
            return silent(category());
        }
        LocalDate when = context.departure();
        for (DateRange window : ranges) {
            if (window.covers(when)) {
                return passes(
                        category(),
                        (season.isEmpty() ? "season" : season) + " covers " + when,
                        Details.of("window", window.toString()));
            }
        }
        return fails(
                category(),
                when + " is outside the " + (season.isEmpty() ? "filed" : season) + " season",
                Details.of(
                        "departure",
                        when.toString(),
                        "windows",
                        ranges.stream().map(DateRange::toString).toList()));
    }
}
