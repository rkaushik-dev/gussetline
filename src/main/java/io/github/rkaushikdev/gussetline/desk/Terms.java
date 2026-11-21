package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.util.List;
import java.util.Objects;

/**
 * What a night's run needs to know about a watch that only the tariff can say: the fare's change rule,
 * its fees, and the context they are read in.
 *
 * @param changeRule the fare's category 31 change rule
 * @param penalties the fare's category 16 filings
 * @param context the ticket and the day the rules are read against
 */
public record Terms(VoluntaryChange changeRule, List<Penalty> penalties, Context context) {

    public Terms {
        Objects.requireNonNull(changeRule, "changeRule");
        penalties = List.copyOf(penalties);
        Objects.requireNonNull(context, "context");
    }
}
