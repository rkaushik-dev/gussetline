package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.desk.Retired;
import io.github.rkaushikdev.gussetline.desk.State;
import io.github.rkaushikdev.gussetline.desk.Watch;
import io.github.rkaushikdev.gussetline.flights.Cabin;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.Rows;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.CouponRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.series.Series;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * The watch commands, which are how a ticket gets onto the desk at all.
 *
 * <p>Adding a watch is the only write in this whole command line that a person does by hand rather
 * than a feed doing it, and that makes it the one place where the input has to be argued with rather
 * than trusted.
 *
 * <p>Two arguments in particular.
 *
 * <p>A watch on a departure that has already gone is not an error worth refusing, it is a ticket
 * somebody typed the wrong year on, and the difference matters: refusing it makes the agent retype the
 * whole entry, while accepting it puts a watch on the schedule that will never be polled and never be
 * looked at again. So it is refused, and the refusal says what the date was read as.
 *
 * <p>And the threshold is worked out rather than asked for. What a drop has to beat is the change fee
 * plus a margin, and an agent who is asked for a number will type fifty every time, which is right for
 * a short haul economy ticket and absurd for a business class transatlantic one. The fee comes off the
 * ticket's own rule when there is one, and the margin off the settings.
 *
 * <p>Retiring is by hand and it records that. A watch closed by a person and one closed because the
 * departure went are different readings of the desk, and a coverage report that cannot tell them apart
 * shows the desk giving up.
 */
final class WatchCommands {

    /** How many rows a listing prints before it stops. */
    static final int SHOWN = 40;

    private WatchCommands() {}

    /** Put a ticket on the desk, or take it off. */
    @Command(
            name = "watch",
            description = "Put a ticket on the desk, or take it off.",
            subcommands = {AddWatch.class, ListWatches.class, RetireWatch.class})
    static final class Watching {}

    /** Start watching a ticket's market. */
    @Command(name = "add", description = "Start watching a ticket's market.")
    static final class AddWatch implements DeskCommand {

        @Parameters(paramLabel = "TICKET_NUMBER")
        private String ticketNumber;

        @Option(names = "--threshold", paramLabel = "TEXT", description = "Override what a drop has to beat.")
        private String threshold;

        @Option(names = "--cabin", paramLabel = "TEXT", description = "Which cabin to watch.")
        private String cabin = "Y";

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        private final Desk desk;

        AddWatch(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();

            String series;
            Money beat;
            try (UnitOfWork work = desk.database().unit()) {
                TicketRow ticket = ticket(work, ticketNumber);
                if (ticket.getDeparture().isBefore(when)) {
                    throw new ValidationError(
                            "that departure has gone",
                            Details.of(
                                    "ticket", ticketNumber,
                                    "departure", ticket.getDeparture().toString(),
                                    "today", when.toString()));
                }

                Cabin wanted = Cabin.of(Text.code(cabin));
                series = Series.key(ticket.getOrigin(), ticket.getDestination(), ticket.getDeparture(), wanted);
                if (already(work, series)) {
                    throw new CommandError(series + " is already watched");
                }

                beat = threshold != null && !threshold.isEmpty()
                        ? Money.of(threshold, ticket.getBaseCurrency())
                        : threshold(desk, work, ticket);
                WatchRow row = new WatchRow();
                row.setTicketId(ticket.getId());
                row.setSeriesKey(series);
                row.setOrigin(Geography.cityOf(ticket.getOrigin()));
                row.setDestination(Geography.cityOf(ticket.getDestination()));
                row.setDeparture(ticket.getDeparture());
                row.setCabin(wanted.value());
                row.setThresholdAmount(beat.amount());
                row.setThresholdCurrency(beat.currency());
                work.session().persist(row);
                work.commit();
            }

            desk.say("watching " + series + ", a drop has to beat " + beat);
            return Desk.OK;
        }
    }

    /** What the desk is watching, closest departure first. */
    @Command(name = "list", description = "What the desk is watching, closest departure first.")
    static final class ListWatches implements DeskCommand {

        @Option(names = "--all", description = "Retired ones as well.")
        private boolean everything;

        @Option(names = "--on", paramLabel = "DATE", converter = Values.Day.class)
        private LocalDate today;

        private final Desk desk;

        ListWatches(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            LocalDate when = today != null ? today : LocalDate.now();

            List<String> lines;
            int total;
            try (UnitOfWork work = desk.database().unit()) {
                List<WatchRow> rows = everything
                        ? work.session()
                                .createQuery(
                                        "select w from WatchRow w join fetch w.ticket order by w.departure, w.id",
                                        WatchRow.class)
                                .getResultList()
                        : work.session()
                                .createQuery(
                                        "select w from WatchRow w join fetch w.ticket"
                                                + " where w.active = true and w.departure >= ?1"
                                                + " order by w.departure, w.id",
                                        WatchRow.class)
                                .setParameter(1, when)
                                .getResultList();
                lines = rows.stream().limit(SHOWN).map(row -> line(work, row)).toList();
                total = rows.size();
            }

            if (total == 0) {
                desk.say("nothing watched");
                return Desk.OK;
            }
            lines.forEach(desk::say);
            if (total > SHOWN) {
                desk.say("and " + (total - SHOWN) + " more");
            }
            return Desk.OK;
        }
    }

    /** Stop watching a series, and record that a person said so. */
    @Command(
            name = "retire",
            description = {
                "Stop watching a series, and record that a person said so.",
                "",
                "The key is read before it is looked for, so a typo says which argument is wrong instead of"
                        + " reporting that no such watch exists."
            })
    static final class RetireWatch implements DeskCommand {

        @Parameters(paramLabel = "SERIES_KEY")
        private String seriesKey;

        private final Desk desk;

        RetireWatch(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            Series.read(Text.strip(seriesKey));
            try (UnitOfWork work = desk.database().unit()) {
                WatchRow row = Rows.oneOrNone(work.session()
                                .createQuery("select w from WatchRow w where w.seriesKey = ?1", WatchRow.class)
                                .setParameter(1, Text.strip(seriesKey)))
                        .orElseThrow(() -> new NotFoundError("no such watch", Details.of("series", seriesKey)));
                if (!row.isActive()) {
                    throw new CommandError(seriesKey + " is already retired, " + row.getRetiredReason());
                }
                row.setActive(false);
                row.setRetiredReason(Retired.BY_HAND.value());
                work.commit();
            }

            desk.say("retired " + seriesKey);
            return Desk.OK;
        }
    }

    /**
     * The ticket under a number, its coupons with it.
     *
     * @throws NotFoundError naming the {@code ticket} when there is none
     */
    private static TicketRow ticket(UnitOfWork work, String number) {
        return Rows.oneOrNone(work.session()
                        .createQuery("select t from TicketRow t where t.number = ?1", TicketRow.class)
                        .setParameter(1, Text.strip(number)))
                .orElseThrow(() -> new NotFoundError("no such ticket", Details.of("ticket", number)));
    }

    /** Whether a live watch already reads this series. */
    private static boolean already(UnitOfWork work, String series) {
        return Rows.first(work.session()
                        .createQuery(
                                "select w from WatchRow w where w.seriesKey = ?1 and w.active = true order by w.id",
                                WatchRow.class)
                        .setParameter(1, series))
                .isPresent();
    }

    /**
     * What a drop has to beat, worked out from the ticket's own rule.
     *
     * <p>The change fee plus a margin. An agent asked for a number types fifty every time, which is right
     * for a short haul economy ticket and absurd for a business class transatlantic one.
     */
    private static Money threshold(Desk desk, UnitOfWork work, TicketRow ticket) {
        String currency = ticket.getBaseCurrency();
        Money margin = Watch.defaultMargin(currency);
        Optional<Money> fee = changeFee(work, ticket, currency);
        // No rule loaded for this fare. The settings figure is a stated default rather than a guess at the
        // fee, and the agent can override.
        return fee.map(filed -> Watch.thresholdFor(filed, margin))
                .orElseGet(() -> new Money(desk.settings().watchThreshold(), currency));
    }

    /**
     * The first change fee before departure, in the ticket's own currency, that the rule behind one of
     * its coupons' fares files, coupon by coupon; nothing when no rule files one.
     */
    private static Optional<Money> changeFee(UnitOfWork work, TicketRow ticket, String currency) {
        for (CouponRow coupon : ticket.getCoupons()) {
            Optional<Fare> fare = work.tariff().byBasis(ticket.getValidatingCarrier(), coupon.getFareBasis());
            if (fare.isEmpty()) {
                continue;
            }
            // The tariff number comes off the fare. Defaulting it finds nothing for a carrier that files in
            // tariff 3, and the threshold then falls back to the settings figure without saying it did.
            Optional<RuleSet> rule = work.rules()
                    .get(
                            ticket.getValidatingCarrier(),
                            fare.get().ruleNumber(),
                            fare.get().tariffNumber());
            if (rule.isEmpty()) {
                continue;
            }
            for (Provision provision : rule.get().forCategory(Category.PENALTIES)) {
                if (!(provision instanceof Penalty penalty) || penalty.action() != Action.CHANGE) {
                    continue;
                }
                Optional<Money> filed = penalty.beforeDeparture();
                if (filed.isPresent() && filed.get().currency().equals(currency)) {
                    return filed;
                }
            }
        }
        return Optional.empty();
    }

    /** One watch, with the two numbers somebody actually wants next to it. */
    private static String line(UnitOfWork work, WatchRow row) {
        Optional<BigDecimal> seen = Rows.first(work.session()
                .createQuery(
                        "select o.totalAmount from ObservationRow o where o.seriesKey = ?1 order by o.totalAmount",
                        BigDecimal.class)
                .setParameter(1, row.getSeriesKey()));
        long open = work.session()
                .createQuery(
                        "select count(r) from RecommendationRow r where r.watchId = ?1 and r.state = ?2", Long.class)
                .setParameter(1, row.getId())
                .setParameter(2, State.OPEN.value())
                .getSingleResult();
        String state = row.isActive() ? "live" : "retired, " + row.getRetiredReason();
        String cheapest =
                seen.map(amount -> amount + " " + row.getThresholdCurrency()).orElse("nothing seen");
        return Text.padEnd(row.getSeriesKey(), 28) + " " + Text.padEnd(state, 34) + " paid "
                + row.getTicket().getBaseAmount() + " cheapest " + cheapest + (open > 0 ? ", open" : "");
    }
}
