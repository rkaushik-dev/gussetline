package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.engine.Engine;
import io.github.rkaushikdev.gussetline.engine.Priced;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.exchange.Exchange;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.persistence.Snapshot;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.spec.Spec;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.trace.Step;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The price command, which is the one that has to be right in front of people.
 *
 * <p>An agent types a sell entry, this prices it out of the tariff the desk holds, and prints the
 * fare calculation line. That line is the whole point. It is what goes on the ticket, it is what a
 * carrier's audit department reads back, and it is the only output here that anybody else can check.
 *
 * <p>Two decisions in how it prints.
 *
 * <p>A price with a combinability refusal on it is still printed. It cannot be sold, and it says so,
 * but hiding it is the wrong call: the desk is looking at it because a supplier quoted something and
 * the question is what the supplier must have done differently. A blank screen answers nothing.
 *
 * <p>And the trail is off by default. Every price records twenty or thirty steps and an agent
 * pricing forty entries an hour does not want them. {@code --why} turns them on, and the failures are
 * printed either way, because a refusal with no reason next to it is the one thing nobody can act on.
 *
 * <p>Where the fares are broken is asked for rather than searched for. Finding the cheapest split is
 * a search over every combination and it belongs above this, so the command takes the breaks it is
 * given and prices that split.
 */
@Command(name = "price", description = "Price a sell entry out of the tariff.")
final class PriceCommand implements DeskCommand {

    @Parameters(paramLabel = "ENTRY")
    private String entry;

    @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
    private LocalDate today;

    @Option(names = "--carrier", paramLabel = "TEXT", description = "Price against one carrier's fares.")
    private String carrier;

    @Option(names = "--breaks", paramLabel = "TEXT", description = "Where the components split, as 1,3.")
    private String breaks = "";

    @Option(names = "--why", description = "Print every step the price took.")
    private boolean why;

    @CommandLine.Spec
    private CommandSpec spec;

    private final Desk desk;

    PriceCommand(Desk desk) {
        this.desk = desk;
    }

    @Override
    public Integer call() {
        LocalDate when = today != null ? today : LocalDate.now();
        Itinerary itinerary = Spec.parse(entry, when);
        desk.say(Spec.render(itinerary));

        Tariffs tariffs;
        try (UnitOfWork work = desk.database().unit()) {
            tariffs = Snapshot.forItinerary(work, itinerary, when, Optional.ofNullable(carrier));
        }
        Converter converter = new Converter(Exchange.defaultRoeTable());

        Priced priced = Engine.price(
                itinerary,
                tariffs,
                converter,
                when,
                desk.settings().sellingCurrency(),
                breaksFrom(breaks),
                Optional.empty());

        show(priced);
        return priced.issuable() ? Desk.OK : Desk.OPERATOR_CAN_FIX;
    }

    /** The element numbers the components split after, or none when nothing was given. */
    private Optional<List<Integer>> breaksFrom(String raw) {
        if (Text.isBlank(raw)) {
            return Optional.empty();
        }
        List<Integer> points = new ArrayList<>();
        try {
            for (String part : Text.words(raw.replace(",", " "))) {
                points.add(Numbers.parseInt(part));
            }
        } catch (NumberFormatException notANumber) {
            throw Usage.badParameter(spec, "breaks are element numbers, as 1,3");
        }
        return Optional.of(List.copyOf(points));
    }

    private void show(Priced priced) {
        desk.say("");
        for (FareComponent component : priced.construction().components()) {
            String basis = component.fare().map(Fare::fareBasis).orElse("no fare");
            desk.say(String.format(
                    Locale.ROOT,
                    "  %s%-4s %-10s %s",
                    component.origin(),
                    component.destination(),
                    basis,
                    Text.plain(component.nuc().orElse(null))));
        }

        desk.say("");
        desk.say(priced.line());
        desk.say("base fare " + priced.baseFare());
        desk.say("total nuc " + priced.totalNuc());

        if (why) {
            for (Step step : priced.trace()) {
                desk.say("  " + step);
            }
        } else {
            for (Step step : priced.trace().failures()) {
                desk.warn("  " + step);
            }
        }

        for (String refusal : priced.refusals()) {
            desk.warn("cannot be issued: " + refusal);
        }
    }

    /**
     * Price and print, turning a refusal into a line rather than a stack.
     *
     * <p>For a command that prices a batch, where one entry the tariff cannot cover must not take the
     * other thirty with it.
     */
    static void priceOrSay(Desk desk, String entry, LocalDate when) {
        Priced priced;
        try {
            Itinerary itinerary = Spec.parse(entry, when);
            Tariffs tariffs;
            try (UnitOfWork work = desk.database().unit()) {
                tariffs = Snapshot.forItinerary(work, itinerary, when);
            }
            priced = Engine.price(
                    itinerary,
                    tariffs,
                    new Converter(Exchange.defaultRoeTable()),
                    when,
                    desk.settings().sellingCurrency());
        } catch (GussetlineError refused) {
            desk.warn(entry + ": " + refused.message());
            return;
        }
        desk.say(entry + ": " + priced);
    }
}
