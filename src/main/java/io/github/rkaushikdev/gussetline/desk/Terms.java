package io.github.rkaushikdev.gussetline.desk;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Baggage;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a night's run needs to know about a watch that only the tariff can say: the fare's change rule,
 * its fees, and the context they are read in; and what the ticket held carries in the hold, with
 * what the desk counts a checked piece as being worth.
 *
 * @param changeRule the fare's category 31 change rule
 * @param penalties the fare's category 16 filings
 * @param context the ticket and the day the rules are read against
 * @param carried the allowance on the fare the passenger holds; unstated when nobody recorded it
 * @param perPiece what a lost checked piece costs the passenger, when the desk prices one
 */
public record Terms(
        VoluntaryChange changeRule,
        List<Penalty> penalties,
        Context context,
        Baggage carried,
        Optional<Money> perPiece) {

    public Terms {
        Objects.requireNonNull(changeRule, "changeRule");
        penalties = List.copyOf(penalties);
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(carried, "carried");
        Objects.requireNonNull(perPiece, "perPiece");
    }

    /** Terms with no allowance recorded on the ticket held. */
    public Terms(VoluntaryChange changeRule, List<Penalty> penalties, Context context) {
        this(changeRule, penalties, context, new Baggage(), Optional.empty());
    }

    /** A copy recording what the ticket held carries, and what a lost piece is worth. */
    public Terms withBaggage(Baggage allowance, Money pieceWorth) {
        return new Terms(changeRule, penalties, context, allowance, Optional.of(pieceWorth));
    }
}
