package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.audit.Audit;
import io.github.rkaushikdev.gussetline.audit.Finding;
import io.github.rkaushikdev.gussetline.engine.Tariffs;
import io.github.rkaushikdev.gussetline.exchange.Converter;
import io.github.rkaushikdev.gussetline.exchange.Exchange;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.persistence.Snapshot;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.reports.Discrepancy;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The audit command, which is the one that pays for the rest of this.
 *
 * <p>Every offer a collector stored is repriced against the tariff the desk holds, the disagreements
 * are grouped, and what comes out is a short list of letters worth writing. That is the whole product.
 *
 * <p>The part that needed deciding is what an offer the tariff cannot price at all should do. It is
 * not a finding. A market with no fare loaded, a fare basis the carrier withdrew last week, a
 * construction the engine has no rule for: all of those say something about us and nothing about the
 * supplier, and putting them in with the real disagreements buries thirty letters under nine hundred
 * rows of our own gaps. So they are counted, kept apart, and printed under their own heading.
 *
 * <p>The tariff is read once for the whole run and not once per offer. An audit over a day touches a
 * few thousand offers across a handful of carriers, and loading the fares for each one turns a two
 * second job into a twenty minute one. The cost is that a fare filed halfway through the run is not
 * seen until the next one, which is right: an audit has to price everything against one snapshot or
 * the findings cannot be compared with each other.
 */
@Command(name = "audit", description = "Reprice what the collectors stored and group the disagreements.")
final class AuditCommand implements DeskCommand {

    /** How far back an audit looks, in days, when nobody says. */
    static final int DEFAULT_WINDOW_DAYS = 1;

    @Option(names = "--since", paramLabel = "DATE", converter = Values.Day.class)
    private LocalDate since;

    @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
    private LocalDate today;

    @Option(names = "--source", paramLabel = "TEXT", description = "Only this supplier's offers.")
    private String source;

    @Option(
            names = "--limit",
            paramLabel = "INTEGER",
            converter = Values.WholeNumber.class,
            description = "Stop after this many offers.")
    private Integer limit;

    @Option(
            names = "--show",
            paramLabel = "INTEGER",
            converter = Values.WholeNumber.class,
            description = "How many cases to print.")
    private int show = 10;

    private final Desk desk;

    AuditCommand(Desk desk) {
        this.desk = desk;
    }

    /**
     * An offer the tariff could not price, and the refusal's sentence.
     *
     * @param offer the offer
     * @param reason why it could not be priced
     */
    private record Unpriceable(Offer offer, String reason) {}

    @Override
    public Integer call() {
        LocalDate when = today != null ? today : LocalDate.now();
        LocalDateTime first =
                since != null ? since.atStartOfDay() : when.atStartOfDay().minusDays(DEFAULT_WINDOW_DAYS);

        List<Offer> offers;
        try (UnitOfWork work = desk.database().unit()) {
            offers = work.observations().offersSince(first, Optional.ofNullable(source), Optional.ofNullable(limit));
        }

        if (offers.isEmpty()) {
            desk.say("nothing stored since " + first.toLocalDate());
            return Desk.OK;
        }

        desk.say(offers.size() + " offers since " + first.toLocalDate());
        Tariffs tariffs;
        try (UnitOfWork work = desk.database().unit()) {
            tariffs = Snapshot.forOffers(work, offers, when);
        }
        Converter converter = new Converter(Exchange.defaultRoeTable());

        List<Audit> audits = new ArrayList<>();
        List<Unpriceable> unpriceable = new ArrayList<>();
        for (Offer offer : offers) {
            try {
                audits.add(Audit.audit(offer, tariffs, converter, when));
            } catch (GussetlineError refused) {
                unpriceable.add(new Unpriceable(offer, refused.message()));
            }
        }

        Discrepancy report = Discrepancy.build(audits, desk.settings().sellingCurrency());
        desk.say("");
        desk.say(Discrepancy.render(report, show));
        ourGaps(unpriceable);
        return Desk.OK;
    }

    /** The offers we could not price, which are a reading about us: each reason, the commonest first. */
    private void ourGaps(List<Unpriceable> unpriceable) {
        if (unpriceable.isEmpty()) {
            return;
        }
        desk.say("");
        desk.say(unpriceable.size() + " offers the tariff could not price, which is ours to fix");
        Map<String, Integer> reasons = new LinkedHashMap<>();
        for (Unpriceable gap : unpriceable) {
            reasons.merge(gap.reason(), 1, Integer::sum);
        }
        // A negative number leaves that many off the end.
        int shown = show >= 0 ? show : Math.max(0, reasons.size() + show);
        reasons.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed())
                .limit(shown)
                .forEach(reason ->
                        desk.say(String.format(Locale.ROOT, "  %5d  %s", reason.getValue(), reason.getKey())));
    }

    /**
     * How a run came out, in the words the daily mail uses: how many offers came to each finding, an
     * offer that agrees counted as {@code agreed}, in the order each first came up.
     */
    static Map<String, Integer> summarise(List<Audit> audits) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Audit item : audits) {
            String key = item.finding() != Finding.AGREES ? item.finding().value() : "agreed";
            counts.merge(key, 1, Integer::sum);
        }
        return Collections.unmodifiableMap(counts);
    }
}
