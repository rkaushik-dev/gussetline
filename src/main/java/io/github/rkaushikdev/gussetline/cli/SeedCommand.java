package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.combinability.Combinability;
import io.github.rkaushikdev.gussetline.desk.Coupon;
import io.github.rkaushikdev.gussetline.desk.Ticket;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.logs.Logs;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.DeskMap;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import io.github.rkaushikdev.gussetline.persistence.models.ObservationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Enough of a desk to run every other command against, on a laptop.
 *
 * <p>Somebody new clones this, builds a schema, and every command they try answers with nothing at
 * all, because a desk with no tariff and no tickets is a correct empty desk. That is a bad first ten
 * minutes and it is entirely avoidable.
 *
 * <p>So this writes one carrier's fares, the rules they point at, the combinability that lets a round
 * trip be sold, two tickets and the watches on them, and a week of readings that end in a drop worth
 * telling somebody about. The night run then raises a recommendation and every report has something
 * in it.
 *
 * <p>Everything it writes is obviously fake. The carrier is a real code because the tariff refuses one
 * that is not, but the amounts are round numbers, the passenger is a placeholder, and the ticket
 * numbers are sequential from one. Nobody is going to mistake this for production data, which is the
 * point: seed data that looks real ends up quoted in a meeting.
 *
 * <p>It refuses to run against a desk that already holds anything. A seed is for an empty database
 * and merging it into a real one puts invented fares next to filed ones with nothing to tell them
 * apart afterwards.
 */
@Command(name = "seed", description = "Fill an empty desk with enough to run everything else against.")
final class SeedCommand implements DeskCommand {

    /**
     * What the invented market did over the week. Steady, then a drop that holds, which is the one
     * shape every command downstream has something to say about.
     */
    static final List<String> PRICES = List.of("840.00", "840.00", "690.00", "690.00", "690.00");

    /** The one carrier the seed files for. */
    static final String CARRIER = "LH";

    /** The two markets the seed sells and watches. */
    static final List<Route> MARKETS = List.of(new Route("FRA", "JFK"), new Route("FRA", "ORD"));

    /**
     * What a seeded reading claims to have come from. Its own name, so forcing a second seed can find
     * the first one's readings without guessing at series.
     */
    static final String SOURCE = "seed";

    /**
     * A restricted half, a flexible half, and a one way, so a seeded desk can price all three shapes an
     * agent actually types. Without the one way, the first entry anybody tries comes back saying a round
     * trip half cannot stand on its own, which reads as a broken desk rather than a fare it has not got.
     */
    static final List<Filed> FILED = List.of(
            new Filed("KLWAP7RT", "420.00", "0100", OwRt.ROUND_TRIP_HALF),
            new Filed("KFLEXRT", "620.00", "0200", OwRt.ROUND_TRIP_HALF),
            new Filed("KOW", "560.00", "0100", OwRt.ONE_WAY_ONLY));

    private static final DateTimeFormatter DAY_AND_MONTH = DateTimeFormatter.ofPattern("ddMMM", Locale.ENGLISH);

    @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
    private LocalDate today;

    @Option(names = "--force", description = "Write it even though the desk holds something.")
    private boolean force;

    private final Desk desk;

    SeedCommand(Desk desk) {
        this.desk = desk;
    }

    /** One market the seed sells and watches, from one airport to another. */
    record Route(String origin, String destination) {}

    /**
     * One fare the seed files in each direction of each market.
     *
     * @param basis the fare basis
     * @param amount what it costs, in euros
     * @param rule the rule it points at
     * @param owrt whether it is a round trip half or a one way
     */
    record Filed(String basis, String amount, String rule, OwRt owrt) {}

    @Override
    public Integer call() {
        LocalDate when = today != null ? today : LocalDate.now();
        LocalDate departure = when.plusDays(45);

        int fares;
        int readings;
        try (UnitOfWork work = desk.database().unit()) {
            String held = whatIsHere(work);
            if (!held.isEmpty() && !force) {
                throw new CommandError("this desk already holds " + held + ", so nothing was written");
            }

            if (force) {
                clearPrevious(work);
            }

            fares = fares(work, when);
            rules(work);
            ticketsAndWatches(work, when, departure);
            readings = readings(work, when, departure);
            work.commit();
        }

        desk.say("wrote " + fares + " fares, " + MARKETS.size() + " tickets, " + readings + " readings");
        desk.say("");
        desk.say("try:");
        desk.say("  gussetline price 'FRA LH400 K " + DAY_AND_MONTH.format(departure) + " JFK' --on " + when);
        desk.say("  gussetline night --on " + when + " --dry-run");
        desk.say("  gussetline report queue --on " + when);
        Logs.event("seed.written", Details.of("on", when, "fares", fares, "readings", readings));
        return Desk.OK;
    }

    /**
     * Take out what an earlier seed wrote, so forcing it can write again.
     *
     * <p>Only what a seed writes, found by the numbers a seed invents. Anything else on the desk is
     * somebody's real data and forcing a seed is not a licence to take it out.
     */
    private static void clearPrevious(UnitOfWork work) {
        List<String> numbers = IntStream.rangeClosed(1, MARKETS.size())
                .mapToObj(SeedCommand::ticketNumber)
                .toList();
        work.session()
                .createQuery("select t from TicketRow t where t.number in :numbers", TicketRow.class)
                .setParameter("numbers", numbers)
                .getResultList()
                .forEach(work.session()::remove);

        // The readings go too, found by their source rather than by their series. A seeded reading is
        // the only thing on the desk that claims to have come from a supplier without anybody having
        // asked one, so the source is the honest way to find them again.
        work.session()
                .createQuery("select o from ObservationRow o where o.source = :source", ObservationRow.class)
                .setParameter("source", SOURCE)
                .getResultList()
                .forEach(work.session()::remove);
        work.flush();
    }

    /** What the desk already holds, in the words the refusal uses, or nothing. */
    private static String whatIsHere(UnitOfWork work) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("fares", work.tariff().countFares());
        counts.put("observations", work.observations().count());
        counts.put("runs", work.runs().count());
        counts.put("rules", work.rules().count());
        return counts.entrySet().stream()
                .filter(entry -> entry.getValue() > 0)
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", "));
    }

    /** Each filed fare in both directions of both markets, and the combinability that lets them be sold. */
    private static int fares(UnitOfWork work, LocalDate when) {
        LoadRow row = work.tariff().openLoad(CARRIER, "seed.dat", "0".repeat(32), when.atStartOfDay(), when);
        List<Fare> made = new ArrayList<>();
        for (Route market : MARKETS) {
            for (Filed filed : FILED) {
                made.add(fare(market.origin(), market.destination(), filed, when));
                made.add(fare(market.destination(), market.origin(), filed, when));
            }
        }
        work.tariff().addFares(row, made);

        for (Fare filed : made) {
            work.combinability()
                    .save(Combinability.builder(CARRIER, filed.fareBasis())
                            .roundTrip(true)
                            .circleTrip(true)
                            .openJaw(true)
                            .build());
        }
        return made.size();
    }

    private static Fare fare(String origin, String destination, Filed filed, LocalDate when) {
        return new Fare(
                CARRIER,
                origin,
                destination,
                filed.basis(),
                Money.of(filed.amount(), "EUR"),
                filed.owrt(),
                GlobalIndicator.AT,
                filed.rule(),
                "0000",
                "1",
                "",
                Cabin.ECONOMY,
                FareType.PUBLISHED,
                when.minusDays(30),
                when.plusDays(300),
                when.minusDays(30),
                when.plusDays(300));
    }

    /** One restricted rule and one flexible one, so a reprice has a choice. */
    private static void rules(UnitOfWork work) {
        work.rules()
                .save(new RuleSet(
                        CARRIER,
                        "0100",
                        List.of(
                                new MinimumStay("SUN"),
                                new Penalty(Action.CHANGE, Money.of("50.00", "EUR")),
                                new VoluntaryChange(RepricingBasis.CURRENT, Residual.REFUNDABLE)),
                        "1"));
        work.rules()
                .save(new RuleSet(
                        CARRIER,
                        "0200",
                        List.of(
                                new Penalty(Action.CHANGE, Money.zero("EUR")),
                                new VoluntaryChange(RepricingBasis.CURRENT, Residual.REFUNDABLE)),
                        "1"));
    }

    private static void ticketsAndWatches(UnitOfWork work, LocalDate when, LocalDate departure) {
        for (int index = 1; index <= MARKETS.size(); index++) {
            String origin = MARKETS.get(index - 1).origin();
            String destination = MARKETS.get(index - 1).destination();
            Ticket ticket = new Ticket(
                    ticketNumber(index),
                    "SEED0" + index,
                    "TEST/PASSENGER",
                    List.of(new Coupon(
                            0, CARRIER, "400", origin, destination, departure.atTime(10, 0), "K", "KLWAP7RT")),
                    when.minusDays(60),
                    CARRIER,
                    Money.of("840.00", "EUR"),
                    Money.of("1090.83", "EUR"));
            TicketRow row = DeskMap.ticketToRow(ticket);
            work.session().persist(row);
            work.flush();

            WatchRow watch = new WatchRow();
            watch.setTicketId(row.getId());
            watch.setSeriesKey(seriesOf(origin, destination, departure));
            watch.setOrigin(Geography.cityOf(origin));
            watch.setDestination(Geography.cityOf(destination));
            watch.setDeparture(departure);
            watch.setThresholdAmount(new BigDecimal("75.00"));
            watch.setThresholdCurrency("EUR");
            work.session().persist(watch);
        }
    }

    /**
     * A week of what the collectors would have seen, ending in a drop.
     *
     * <p>One row per distinct price and not one per morning, because that is what a real run produces:
     * the observation table is keyed on content, so a market that sat at the same number for two
     * mornings is one row with a sighting count of two. Seeding it the other way writes rows no run
     * could write, and the first thing anybody does with a seeded desk is compare it to a real one.
     */
    private static int readings(UnitOfWork work, LocalDate when, LocalDate departure) {
        int written = 0;
        for (Route market : MARKETS) {
            String origin = market.origin();
            String destination = market.destination();
            String series = seriesOf(origin, destination, departure);
            for (Morning morning : mornings(when)) {
                String fingerprint = series + "-" + morning.amount();
                ObservationRow row = new ObservationRow();
                row.setSeriesKey(series);
                row.setSource(SOURCE);
                row.setFingerprint((fingerprint + "0".repeat(Math.max(0, 32 - fingerprint.length()))).substring(0, 32));
                row.setOrigin(origin);
                row.setDestination(destination);
                row.setDeparture(departure);
                row.setCabin("Y");
                row.setFareBases("KLWAP7RT");
                row.setBaseAmount(new BigDecimal(morning.amount()));
                row.setBaseCurrency("EUR");
                row.setTaxAmount(new BigDecimal("0.00"));
                row.setSurchargeAmount(new BigDecimal("0.00"));
                row.setTotalAmount(new BigDecimal(morning.amount()));
                row.setFirstSeen(morning.first());
                row.setLastSeen(morning.last());
                row.setSightings(morning.times());
                work.session().persist(row);
                written++;
            }
        }
        return written;
    }

    /**
     * One price the market sat at over the week.
     *
     * @param amount the price
     * @param first the first morning it was seen
     * @param last the last morning it was seen
     * @param times how many mornings it was seen on
     */
    private record Morning(String amount, LocalDateTime first, LocalDateTime last, int times) {}

    /** The week's prices, collapsed the way a run of collections would, in the order each was first seen. */
    private static List<Morning> mornings(LocalDate when) {
        Map<String, List<LocalDateTime>> seen = new LinkedHashMap<>();
        for (int offset = 0; offset < PRICES.size(); offset++) {
            LocalDateTime morning = when.minusDays(PRICES.size() - offset).atStartOfDay();
            seen.computeIfAbsent(PRICES.get(offset), amount -> new ArrayList<>())
                    .add(morning);
        }
        return seen.entrySet().stream()
                .map(entry -> new Morning(
                        entry.getKey(),
                        Collections.min(entry.getValue()),
                        Collections.max(entry.getValue()),
                        entry.getValue().size()))
                .toList();
    }

    private static String ticketNumber(int index) {
        return "220-000000000" + index;
    }

    private static String seriesOf(String origin, String destination, LocalDate departure) {
        return Series.key(origin, destination, departure, Cabin.ECONOMY);
    }
}
