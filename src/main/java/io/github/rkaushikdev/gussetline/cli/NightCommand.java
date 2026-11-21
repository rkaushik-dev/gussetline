package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.desk.Coupon;
import io.github.rkaushikdev.gussetline.desk.Night;
import io.github.rkaushikdev.gussetline.desk.Observation;
import io.github.rkaushikdev.gussetline.desk.Recommendation;
import io.github.rkaushikdev.gussetline.desk.Retired;
import io.github.rkaushikdev.gussetline.desk.RetiredSeries;
import io.github.rkaushikdev.gussetline.desk.Session;
import io.github.rkaushikdev.gussetline.desk.State;
import io.github.rkaushikdev.gussetline.desk.Terms;
import io.github.rkaushikdev.gussetline.desk.Watch;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.logs.Logs;
import io.github.rkaushikdev.gussetline.persistence.Book;
import io.github.rkaushikdev.gussetline.persistence.DeskMap;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.Context;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import io.github.rkaushikdev.gussetline.util.Text;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * The night command, which is the run the desk is actually paid for.
 *
 * <p>Everything before this collects and prices. This is where a price becomes something a person
 * will phone a customer about, and it is the only command that both reads and writes the desk's own
 * tables in one transaction.
 *
 * <p>One transaction on purpose. The session expires stale recommendations, retires watches,
 * withdraws superseded recommendations and raises new ones, and those four have to land together. A
 * run that expired forty and then failed before raising anything leaves the desk with an empty queue
 * and no way to tell that is what happened.
 *
 * <p>Where the rules come from is the part worth stating. A ticket carries fare bases and not rule
 * numbers, so the fares have to be read to get from one to the other, and a ticket whose fare basis
 * has rolled out of the tariff gets no rule at all. That is not a failure and it is not a permission
 * either: the watch is left alone, counted, and reported, because guessing that a change is permitted
 * is how a desk quotes a reissue the carrier refuses at the counter.
 *
 * <p>The dry run reads everything and writes nothing. It is what runs before the first real night
 * against a new tariff, and the number it prints is what the desk would have been told.
 */
@Command(name = "night", description = "Reprice every live watch and raise what is worth raising.")
final class NightCommand implements DeskCommand {

    /**
     * A made up block time for a coupon, in hours, because a coupon carries a departure and no
     * arrival. Nothing in the reprice reads it; it exists so the segment will build.
     */
    static final int NOMINAL_BLOCK_HOURS = 2;

    /** How many lines of each kind the run prints before it stops. */
    static final int SHOWN = 20;

    @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
    private LocalDate today;

    @Option(names = "--dry-run", description = "Work it all out and write nothing.")
    private boolean dryRun;

    private final Desk desk;

    NightCommand(Desk desk) {
        this.desk = desk;
    }

    @Override
    public Integer call() {
        LocalDate when = today != null ? today : LocalDate.now();

        Night report;
        try (UnitOfWork work = desk.database().unit()) {
            List<WatchRow> rows = live(work, when);
            if (rows.isEmpty()) {
                desk.say("no live watches");
                return Desk.OK;
            }

            Map<String, List<Observation>> histories = Book.readingsFor(
                    work, rows.stream().map(WatchRow::getSeriesKey).toList());
            List<Watch> watches = rows.stream()
                    .map(row -> DeskMap.rowToWatch(row, histories.getOrDefault(row.getSeriesKey(), List.of())))
                    .toList();
            List<Recommendation> book = new ArrayList<>(Book.openBook(work).recommendations());
            Rules rules = rulesFor(work, watches, when);
            report = Session.run(
                    watches, book, rules::terms, when, desk.settings().sellingCurrency());

            say(report, rules.missing().size());
            Logs.event(
                    "night.priced",
                    Map.of(
                            "on", when,
                            "looked_at", report.lookedAt(),
                            "raised", report.raised().size(),
                            "withdrawn", report.withdrawn().size(),
                            "retired", report.retired().size(),
                            "expired", report.expired(),
                            "held_back", report.heldBack(),
                            "no_rule", rules.missing().size(),
                            "worth", report.benefit().amount(),
                            "dry", dryRun));
            if (dryRun) {
                desk.say("dry run, nothing written");
                return Desk.OK;
            }

            write(work, rows, watches, report);
            work.commit();
        }

        for (Recommendation made : report.raised()) {
            Logs.event(
                    "night.raised",
                    Details.of(
                            "ticket", made.ticketNumber(),
                            "series", made.seriesKey(),
                            "worth", made.benefit().amount(),
                            "currency", made.currency()));
        }
        desk.say("wrote " + report.raised().size() + " recommendations");
        return Desk.OK;
    }

    /**
     * Every watch still worth checking, closest departure first, with its ticket already in. Two
     * watches leaving on the same day come in the order they were added.
     */
    private static List<WatchRow> live(UnitOfWork work, LocalDate when) {
        return work.session()
                .createQuery(
                        "select w from WatchRow w join fetch w.ticket"
                                + " where w.active = true and w.departure >= ?1 order by w.departure, w.id",
                        WatchRow.class)
                .setParameter(1, when)
                .getResultList();
    }

    /**
     * A fare as a filing carrier names it, by the fare basis a ticket carries.
     *
     * @param carrier the validating carrier
     * @param basis the fare basis
     */
    private record FiledFare(String carrier, String basis) {}

    /**
     * Where a fare's rule is filed: its number, in the fare's own tariff.
     *
     * @param number the rule number
     * @param tariff the tariff the rule is filed in
     */
    private record FiledRule(String number, String tariff) {}

    /**
     * What the session asks for, answered out of the rule store.
     *
     * <p>Holds the watches it could not answer for rather than refusing, because one ticket whose fare
     * basis has left the tariff must not stop the other eight thousand from being priced.
     */
    private static final class Rules {

        private final RuleStore store;
        private final Map<FiledFare, FiledRule> numbers;
        private final List<String> missing = new ArrayList<>();

        Rules(RuleStore store, Map<FiledFare, FiledRule> numbers) {
            this.store = store;
            this.numbers = numbers;
        }

        /** The series of every watch no rule was found for, in the order they were asked about. */
        List<String> missing() {
            return missing;
        }

        /** The change rule, the fees and the context for one watch. */
        Terms terms(Watch watch) {
            Optional<RuleSet> rule = ruleFor(watch);
            Context context = context(watch);
            if (rule.isEmpty()) {
                missing.add(watch.seriesKey());
                // Not permitted rather than permitted. Guessing the other way quotes a reissue the
                // carrier refuses at the counter.
                return new Terms(VoluntaryChange.notPermitted(), List.of(), context);
            }

            List<VoluntaryChange> changes = rule.get().forCategory(Category.VOLUNTARY_CHANGES).stream()
                    .filter(VoluntaryChange.class::isInstance)
                    .map(VoluntaryChange.class::cast)
                    .toList();
            List<Penalty> penalties = rule.get().forCategory(Category.PENALTIES).stream()
                    .filter(Penalty.class::isInstance)
                    .map(Penalty.class::cast)
                    .toList();
            VoluntaryChange change = changes.isEmpty() ? VoluntaryChange.notPermitted() : changes.getFirst();
            return new Terms(change, penalties, context);
        }

        /**
         * The rule this ticket's fare points at, in that fare's own tariff.
         *
         * <p>The tariff number comes off the fare and is not defaulted. A carrier files rule 100 in
         * tariff 1 and a different rule 100 in tariff 3, and looking the first one up under the default
         * finds nothing, which reads as a fare with no rule and retires the watch.
         */
        Optional<RuleSet> ruleFor(Watch watch) {
            String carrier = watch.ticket().validatingCarrier();
            for (String basis : watch.ticket().fareBases()) {
                FiledRule filed = numbers.get(new FiledFare(carrier, basis));
                if (filed == null) {
                    continue;
                }
                Optional<RuleSet> found = store.find(carrier, filed.number(), filed.tariff());
                if (found.isPresent()) {
                    return found;
                }
            }
            return Optional.empty();
        }
    }

    /** Build the fare basis to rule number lookup once for the whole run. */
    private static Rules rulesFor(UnitOfWork work, List<Watch> watches, LocalDate when) {
        List<String> carriers = watches.stream()
                .map(watch -> watch.ticket().validatingCarrier())
                .distinct()
                .toList();
        Map<FiledFare, FiledRule> numbers = new HashMap<>();
        for (Watch watch : watches) {
            String carrier = watch.ticket().validatingCarrier();
            for (String basis : watch.ticket().fareBases()) {
                FiledFare key = new FiledFare(carrier, basis);
                if (numbers.containsKey(key)) {
                    continue;
                }
                work.tariff()
                        .byBasis(carrier, basis, when)
                        .ifPresent(fare -> numbers.put(key, new FiledRule(fare.ruleNumber(), fare.tariffNumber())));
            }
        }
        return new Rules(work.rules().storeFor(carriers), numbers);
    }

    /** The context a category 16 fee is priced against: the ticket as it was issued, read today, for a reissue. */
    private static Context context(Watch watch) {
        List<Segment> segments =
                watch.ticket().coupons().stream().map(NightCommand::segment).toList();
        return new Context(
                        new Itinerary(segments),
                        new FareComponent(segments),
                        watch.ticket().issuedOn())
                .withToday(LocalDate.now())
                .withReissue(true);
    }

    /**
     * A coupon as the segment the rules read.
     *
     * <p>A coupon carries a departure and no arrival, because a ticket does not record one. The arrival
     * here is made up and marked as such: nothing in a reprice reads it, and a segment will not build
     * without one.
     */
    private static Segment segment(Coupon coupon) {
        return new Segment(
                        coupon.carrier(),
                        coupon.flightNumber(),
                        coupon.origin(),
                        coupon.destination(),
                        coupon.departure(),
                        coupon.departure().plusHours(NOMINAL_BLOCK_HOURS),
                        coupon.bookingClass(),
                        coupon.cabin())
                .withEstimated(true);
    }

    /** Put the night back, matching each watch to the row it came from. */
    private static void write(UnitOfWork work, List<WatchRow> rows, List<Watch> watches, Night report) {
        Map<String, WatchRow> byKey = new HashMap<>();
        for (WatchRow row : rows) {
            byKey.put(row.getSeriesKey(), row);
        }
        for (Watch watch : watches) {
            WatchRow row = byKey.get(watch.seriesKey());
            if (row == null) {
                continue;
            }
            row.setActive(watch.isActive());
            row.setRetiredReason(watch.retiredReason().map(Retired::value).orElse(""));
        }

        Map<String, Recommendation> settled = new HashMap<>();
        for (Recommendation made : report.withdrawn()) {
            settled.put(made.fingerprint(), made);
        }
        if (!settled.isEmpty()) {
            List<RecommendationRow> open = work.session()
                    .createQuery("select r from RecommendationRow r where r.state = ?1", RecommendationRow.class)
                    .setParameter(1, State.OPEN.value())
                    .getResultList();
            for (RecommendationRow row : open) {
                Recommendation moved = settled.get(row.getObservationFingerprint());
                if (moved != null) {
                    DeskMap.applyRecommendation(row, moved);
                }
            }
        }

        for (Recommendation made : report.raised()) {
            WatchRow row = byKey.get(made.seriesKey());
            if (row == null) {
                continue;
            }
            work.session().persist(DeskMap.recommendationToRow(made, row.getId()));
        }
    }

    /**
     * The night as the operator reads it: the totals, what was raised and retired, why the rest was
     * not, and how many watches no rule could be found for.
     */
    private void say(Night report, int missing) {
        desk.say(report.toString());
        for (Recommendation made : report.raised().stream().limit(SHOWN).toList()) {
            desk.say("  " + made);
        }
        for (RetiredSeries gone : report.retired().stream().limit(SHOWN).toList()) {
            desk.say("  retired " + gone.seriesKey() + ", " + gone.reason().value());
        }
        Map<String, Integer> whyNot = new TreeMap<>(Text.CODE_POINT_ORDER);
        whyNot.putAll(report.whyNot());
        whyNot.forEach((verdict, count) -> desk.say(String.format(Locale.ROOT, "  %-22s %d", verdict, count)));
        if (report.heldBack() > 0) {
            desk.say("  " + report.heldBack() + " held back as too small an improvement");
        }
        if (missing > 0) {
            desk.warn(missing + " watches have no rule filed and were left alone");
        }
    }
}
