package io.github.rkaushikdev.gussetline.load;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Field;
import io.github.rkaushikdev.gussetline.collect.Field.Kind;
import io.github.rkaushikdev.gussetline.collect.Layout;
import io.github.rkaushikdev.gussetline.collect.Problem;
import io.github.rkaushikdev.gussetline.collect.Reading;
import io.github.rkaushikdev.gussetline.collect.Record;
import io.github.rkaushikdev.gussetline.collect.RecordSpec;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.AdvancePurchase;
import io.github.rkaushikdev.gussetline.rules.Applies;
import io.github.rkaushikdev.gussetline.rules.Category;
import io.github.rkaushikdev.gussetline.rules.FormOfPayment;
import io.github.rkaushikdev.gussetline.rules.MaximumStay;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.RuleSet;
import io.github.rkaushikdev.gussetline.rules.RuleStore;
import io.github.rkaushikdev.gussetline.rules.SalesRestriction;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import io.github.rkaushikdev.gussetline.timelimit.TicketingTerms.LateBooking;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Loading rule records, which arrive as a stream and not as a list.
 *
 * <p>The fare feed is a bag of independent records. The rule feed is not. A rule header opens a rule
 * and every category record after it belongs to that rule until the next header, so the order of the
 * lines is part of the data and a loader that sorts them or reads them in parallel gets the wrong
 * answer without ever failing.
 *
 * <p>That has two consequences worth writing down.
 *
 * <p>A category record before any header is an orphan. It cannot be attached to anything, and it
 * usually means the file was cut in the middle by whatever moved it, so it is recorded as a problem
 * rather than dropped. A file that starts with forty orphans is a truncated file and somebody needs
 * to know that rather than getting a very quiet partial load.
 *
 * <p>And a rule is replaced whole, never amended in place. There is no record that says remove
 * category 6 from rule 100, because the carrier does not think of it that way: it refiles the rule
 * with the categories it now has. So a header with no category records after it is not a mistake and
 * not an empty file. It is a rule with no conditions, which is a real thing an unrestricted fare
 * points at, and a loader that skips it leaves the previous version of that rule in place with all
 * its restrictions still attached.
 *
 * <p>Every category is optional and an absent one means unrestricted. That is the tariff's own
 * convention and it is the reason nothing here fills in a default: a category 5 with no days in it
 * says the fare may be bought at any time, and writing a zero there instead would say it has to be
 * bought on the day. A one letter code left blank means the tariff's own default for it.
 */
public final class RuleFeed {

    /** The tag of the record that opens a rule. */
    public static final String HEADER = "RHD";

    /** The action letter of a header that files its rule. A blank action does too. */
    public static final String ADD = "A";

    /** The action letter of a header that refiles its rule, which files it the same way an add does. */
    public static final String REPLACE = "R";

    /** The action letter of a header that withdraws its rule. Records after it are not kept. */
    public static final String DELETE = "D";

    /** Opens a rule: the carrier, the rule number, the tariff number, and the action. */
    public static final RecordSpec RULE_HEADER = new RecordSpec(
            HEADER,
            new Field("carrier", 3, 2),
            new Field("number", 5, 4),
            new Field("tariff", 9, 3),
            new Field("action", 12, 1));

    /**
     * Category 5: the reservation period, the hold, and the backstop, in days; then the late booking
     * window and the hold a booking made inside it gets.
     */
    public static final RecordSpec ADVANCE = new RecordSpec(
            "R05",
            optional("reservation_days", 3, 3, Kind.INT),
            optional("ticketing_after", 6, 3, Kind.INT),
            optional("ticketing_before", 9, 3, Kind.INT),
            optional("late_within", 12, 3, Kind.INT),
            optional("late_ticket", 15, 3, Kind.INT));

    /** Category 6: a number of nights, a weekday the stay has to cross, and a number of months. */
    public static final RecordSpec MINIMUM = new RecordSpec(
            "R06",
            optional("days", 3, 3, Kind.INT),
            optional("weekday", 6, 3, Kind.TEXT),
            optional("months", 9, 2, Kind.INT));

    /** Category 7: days, months, and a {@code Y} when the limit runs from the turnaround. */
    public static final RecordSpec MAXIMUM = new RecordSpec(
            "R07",
            optional("days", 3, 3, Kind.INT),
            optional("months", 6, 2, Kind.INT),
            optional("from_turnaround", 8, 1, Kind.TEXT));

    /**
     * Category 15: the permitted and the excluded countries, each a list joined with {@code /}, a
     * payment code, and the sale window.
     */
    public static final RecordSpec SALES = new RecordSpec(
            "R15",
            optional("countries", 3, 12, Kind.TEXT),
            optional("excluded", 15, 12, Kind.TEXT),
            optional("payment", 27, 1, Kind.TEXT),
            optional("first_sale", 28, 8, Kind.DATE),
            optional("last_sale", 36, 8, Kind.DATE));

    /**
     * Category 16: the action code, {@code N} when the action is not permitted, the currency, the
     * amounts before and after departure, the scope code, a {@code Y} when a no show forfeits the
     * ticket, and the fee as a percentage of the fare.
     */
    public static final RecordSpec PENALTY = new RecordSpec(
            "R16",
            new Field("action", 3, 1),
            new Field("permitted", 4, 1),
            optional("currency", 5, 3, Kind.TEXT),
            optional("before", 8, 9, Kind.AMOUNT),
            optional("after", 17, 9, Kind.AMOUNT),
            optional("applies", 26, 1, Kind.TEXT),
            optional("no_show_forfeits", 27, 1, Kind.TEXT),
            optional("percent", 28, 6, Kind.AMOUNT));

    /**
     * Category 31: {@code N} when changes are not permitted, the basis and residual codes, and a
     * {@code Y} for each of keeping the fare breaks, staying on the carrier, and changing only once.
     */
    public static final RecordSpec CHANGES = new RecordSpec(
            "R31",
            new Field("permitted", 3, 1),
            new Field("basis", 4, 1),
            new Field("residual", 5, 1),
            optional("keep_breaks", 6, 1, Kind.TEXT),
            optional("same_carrier", 7, 1, Kind.TEXT),
            optional("once_only", 8, 1, Kind.TEXT));

    /** The records a rule file is made of, under three character tags. */
    public static final Layout LAYOUT =
            new Layout(List.of(RULE_HEADER, ADVANCE, MINIMUM, MAXIMUM, SALES, PENALTY, CHANGES), 3);

    /** Penalty actions by the letter the feed spells them with. */
    public static final Map<String, Action> ACTIONS =
            codes(Map.entry("C", Action.CHANGE), Map.entry("X", Action.CANCEL), Map.entry("N", Action.NO_SHOW));

    /** Penalty scopes by the letter the feed spells them with. */
    public static final Map<String, Applies> APPLIES = codes(
            Map.entry("T", Applies.PER_TICKET),
            Map.entry("C", Applies.PER_COMPONENT),
            Map.entry("H", Applies.HIGHEST_OF));

    /** Repricing bases by the letter the feed spells them with. */
    public static final Map<String, RepricingBasis> BASES =
            codes(Map.entry("C", RepricingBasis.CURRENT), Map.entry("H", RepricingBasis.HISTORICAL));

    /** Residual handling by the letter the feed spells it with. */
    public static final Map<String, Residual> RESIDUALS = codes(
            Map.entry("R", Residual.REFUNDABLE), Map.entry("C", Residual.RETAINED), Map.entry("F", Residual.FORFEITED));

    /** Forms of payment by the letter the feed spells them with. */
    public static final Map<String, FormOfPayment> PAYMENTS = codes(
            Map.entry("A", FormOfPayment.ANY), Map.entry("C", FormOfPayment.CARD), Map.entry("H", FormOfPayment.CASH));

    private RuleFeed() {}

    /** {@link #read(String)} for a file as it arrived, in UTF-8. */
    public static RuleLoad read(byte[] payload) {
        return read(LAYOUT.read(payload));
    }

    /**
     * Walk the records in order, closing each rule when the next header opens and at the end of the
     * file, without touching a store.
     *
     * <p>A category record the provisions refuse, or with a code nobody files, is a problem against
     * its own line and the rest of the rule still reads. A header whose action is not a delete files
     * its rule.
     *
     * @throws ValidationError when a header that files a rule names a malformed carrier or no number
     */
    public static RuleLoad read(String payload) {
        return read(LAYOUT.read(payload));
    }

    /**
     * Swap whole rules into the store, in the order they were filed: a rule filed again replaces the
     * one held, conditions and all. The rules go in first, then the withdrawals come out.
     *
     * @throws ValidationError when a withdrawal names a carrier that is not a designator
     */
    public static RulesApplied apply(RuleStore store, RuleLoad load) {
        int loaded = 0;
        int replaced = 0;
        int deleted = 0;
        int unknownDeletes = 0;
        int emptyRules = 0;
        for (RuleSet rule : load.rules()) {
            if (store.find(rule.carrier(), rule.number(), rule.tariff()).isPresent()) {
                replaced++;
            }
            store.add(rule);
            loaded++;
            if (rule.provisions().isEmpty()) {
                emptyRules++;
            }
        }
        for (RuleLoad.Withdrawn gone : load.deleted()) {
            if (store.remove(gone.carrier(), gone.number(), gone.tariff())) {
                deleted++;
            } else {
                unknownDeletes++;
            }
        }
        return new RulesApplied(loaded, replaced, deleted, unknownDeletes, emptyRules);
    }

    /** {@link #loadInto(RuleStore, String)} for a file as it arrived, in UTF-8. */
    public static Loaded<RuleLoad, RulesApplied> loadInto(RuleStore store, byte[] payload) {
        return applied(store, read(payload));
    }

    /** Read and apply in one call, for the ordinary case. */
    public static Loaded<RuleLoad, RulesApplied> loadInto(RuleStore store, String payload) {
        return applied(store, read(payload));
    }

    /**
     * How many of each category the file carried, for the load report: category number to count, in
     * the order the categories first appear.
     */
    public static Map<Integer, Integer> categoriesOf(RuleLoad load) {
        Map<Integer, Integer> counts = new LinkedHashMap<>();
        for (RuleSet rule : load.rules()) {
            for (Provision provision : rule.provisions()) {
                counts.merge(provision.category().value(), 1, Integer::sum);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    /** Which of a set of categories a rule does not file, in the order asked. Absent is not zero. */
    public static List<Category> missingCategories(RuleSet rule, List<Category> wanted) {
        return wanted.stream().filter(category -> !rule.has(category)).toList();
    }

    private static Loaded<RuleLoad, RulesApplied> applied(RuleStore store, RuleLoad load) {
        return new Loaded<>(load, apply(store, load));
    }

    private static RuleLoad read(Reading reading) {
        List<RuleSet> rules = new ArrayList<>();
        List<RuleLoad.Withdrawn> deleted = new ArrayList<>();
        List<Problem> problems = new ArrayList<>(reading.problems());
        int orphans = 0;
        Building building = null;

        for (Record record : reading.records()) {
            if (record.tag().equals(HEADER)) {
                if (building != null) {
                    building.closeInto(rules, deleted);
                }
                building = new Building(record);
                continue;
            }

            if (building == null) {
                orphans++;
                problems.add(
                        new Problem(record.lineNumber(), record.toString(), "a category record with no rule above it"));
                continue;
            }

            try {
                building.provisions.add(provision(record));
            } catch (ValidationError | MalformedPayload refused) {
                problems.add(new Problem(record.lineNumber(), record.toString(), refused.message()));
            }
        }

        if (building != null) {
            building.closeInto(rules, deleted);
        }
        return new RuleLoad(rules, deleted, problems, orphans);
    }

    /** One category record as the provision it files, or refused by itself. */
    private static Provision provision(Record record) {
        return switch (record.tag()) {
            case "R05" ->
                AdvancePurchase.builder()
                        .reservationDays(number(record, "reservation_days"))
                        .ticketingDaysAfterReservation(number(record, "ticketing_after"))
                        .ticketingDaysBeforeDeparture(number(record, "ticketing_before"))
                        .lateBooking(number(record, "late_within").flatMap(within -> number(record, "late_ticket")
                                .map(hold -> new LateBooking(within, hold))))
                        .build();
            case "R06" -> new MinimumStay(number(record, "days"), text(record, "weekday"), number(record, "months"));
            case "R07" ->
                MaximumStay.builder()
                        .days(number(record, "days"))
                        .months(number(record, "months"))
                        .fromTurnaround(flag(record, "from_turnaround"))
                        .build();
            case "R15" -> sales(record);
            case "R16" -> penalty(record);
            case "R31" -> changes(record);
            default ->
                throw new MalformedPayload("no reader for that category record", Details.of("tag", record.tag()));
        };
    }

    private static SalesRestriction sales(Record record) {
        return SalesRestriction.builder()
                .countries(list(record, "countries"))
                .excludedCountries(list(record, "excluded"))
                .payment(lookedUp(PAYMENTS, record, "payment", FormOfPayment.ANY, "payment"))
                .firstSale(record.find("first_sale", LocalDate.class))
                .lastSale(record.find("last_sale", LocalDate.class))
                .build();
    }

    /** A penalty in the currency filed, or in euro when the currency is left blank. */
    private static Penalty penalty(Record record) {
        String currency = text(record, "currency").orElse("EUR");
        return Penalty.builder()
                .action(lookedUp(ACTIONS, record, "action", Action.CHANGE, "penalty action"))
                .permitted(!flag(record, "permitted", "N"))
                .beforeDeparture(money(record, "before", currency))
                .afterDeparture(money(record, "after", currency))
                .applies(lookedUp(APPLIES, record, "applies", Applies.PER_TICKET, "penalty scope"))
                .noShowForfeits(flag(record, "no_show_forfeits"))
                .percent(record.find("percent", BigDecimal.class))
                .build();
    }

    private static VoluntaryChange changes(Record record) {
        return VoluntaryChange.builder()
                .permitted(!flag(record, "permitted", "N"))
                .basis(lookedUp(BASES, record, "basis", RepricingBasis.CURRENT, "repricing basis"))
                .residual(lookedUp(RESIDUALS, record, "residual", Residual.FORFEITED, "residual"))
                .keepFareBreaks(flag(record, "keep_breaks"))
                .sameCarrierOnly(flag(record, "same_carrier"))
                .onceOnly(flag(record, "once_only"))
                .build();
    }

    /**
     * A one letter code read through its table, where blank means the tariff's own default.
     *
     * @throws MalformedPayload when the code is in no table, naming {@code what} it was and the
     *     {@code code}
     */
    private static <T> T lookedUp(Map<String, T> table, Record record, String name, T fallback, String what) {
        Optional<String> code = text(record, name);
        if (code.isEmpty()) {
            return fallback;
        }
        T found = table.get(code.get());
        if (found == null) {
            throw new MalformedPayload("no such code", Details.of("what", what, "code", code.get()));
        }
        return found;
    }

    private static Optional<Integer> number(Record record, String name) {
        return record.find(name, Integer.class);
    }

    /** A text field upper case without surrounding white space, or empty when it was left blank. */
    private static Optional<String> text(Record record, String name) {
        return record.find(name, String.class).map(Text::code).filter(value -> !value.isEmpty());
    }

    /** Whether a one letter field says {@code Y}. Anything else, blank included, is a no. */
    private static boolean flag(Record record, String name) {
        return flag(record, name, "Y");
    }

    private static boolean flag(Record record, String name, String yes) {
        return text(record, name).map(yes::equals).orElse(false);
    }

    /** A list joined with {@code /}, its empty parts dropped. */
    private static List<String> list(Record record, String name) {
        return text(record, name)
                .map(value -> Arrays.stream(value.split("/", -1))
                        .filter(part -> !part.isEmpty())
                        .toList())
                .orElse(List.of());
    }

    private static Optional<Money> money(Record record, String name, String currency) {
        return record.find(name, BigDecimal.class).map(amount -> new Money(amount, currency));
    }

    private static Field optional(String name, int start, int length, Kind kind) {
        return new Field(name, start, length, kind, true);
    }

    /** A read only table of one letter codes, in the order given. */
    @SafeVarargs
    private static <T> Map<String, T> codes(Map.Entry<String, T>... entries) {
        Map<String, T> table = new LinkedHashMap<>();
        for (Map.Entry<String, T> entry : entries) {
            table.put(entry.getKey(), entry.getValue());
        }
        return Collections.unmodifiableMap(table);
    }

    /** A rule being assembled out of the records following its header. */
    private static final class Building {

        private final String carrier;
        private final String number;
        private final String tariff;
        private final String action;
        private final List<Provision> provisions = new ArrayList<>();

        private Building(Record header) {
            carrier = Text.code(header.get("carrier", String.class));
            number = Text.strip(header.get("number", String.class));
            tariff = Text.strip(header.get("tariff", String.class));
            String filed = Text.code(header.get("action", String.class));
            action = filed.isEmpty() ? ADD : filed;
        }

        /**
         * Close the rule: a withdrawal when the header deletes it, and otherwise the rule itself.
         *
         * @throws ValidationError when a rule that files names a malformed carrier or no number
         */
        private void closeInto(List<RuleSet> rules, List<RuleLoad.Withdrawn> deleted) {
            if (action.equals(DELETE)) {
                deleted.add(new RuleLoad.Withdrawn(carrier, number, tariff));
                return;
            }
            rules.add(new RuleSet(carrier, number, provisions, tariff));
        }
    }
}
