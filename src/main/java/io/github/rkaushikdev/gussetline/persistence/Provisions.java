package io.github.rkaushikdev.gussetline.persistence;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.ProvisionRow;
import io.github.rkaushikdev.gussetline.rules.Action;
import io.github.rkaushikdev.gussetline.rules.AdvancePurchase;
import io.github.rkaushikdev.gussetline.rules.Applies;
import io.github.rkaushikdev.gussetline.rules.FormOfPayment;
import io.github.rkaushikdev.gussetline.rules.InstantPurchase;
import io.github.rkaushikdev.gussetline.rules.MaximumStay;
import io.github.rkaushikdev.gussetline.rules.MinimumStay;
import io.github.rkaushikdev.gussetline.rules.Penalty;
import io.github.rkaushikdev.gussetline.rules.Provision;
import io.github.rkaushikdev.gussetline.rules.RepricingBasis;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.rules.SalesRestriction;
import io.github.rkaushikdev.gussetline.rules.VoluntaryChange;
import io.github.rkaushikdev.gussetline.timelimit.TicketingTerms.LateBooking;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Turning provisions into rows and back, one codec per kind.
 *
 * <p>A provision row has six named columns and a spill field, and every category uses them
 * differently: category 6 puts its days in the first number, category 16 puts its fee in the
 * amount, category 31 puts three flags in two booleans and the third in the spill. Nothing about
 * that is guessable from the row, so the mapping is written out per kind, in one place, in both
 * directions.
 *
 * <p>Both directions together is the whole point. A codec split across two files drifts within a
 * month: somebody adds a field, fills it on the way in, forgets the way out, and the value comes back
 * empty for a year because nothing reads it until the day something does. Here the pair sits in one
 * {@link Codec} and the round trip test walks every kind in {@link #CODECS}.
 *
 * <p>The spill field is a blob and it is meant to be the exception. A field goes there only when no
 * report will ever filter on it. Anything a report groups by gets a column, because the alternative
 * is a query that reads every row in the table and parses it. Category 15 is the one that needed it,
 * for the two sale dates and the form of payment. The country lists are in the text columns because
 * somebody does want to ask which fares may be sold in Austria.
 */
public final class Provisions {

    /** What separates one spilled field from the next. */
    public static final String PAIR = ";";

    /** What separates a spilled field's key from its value. */
    public static final String EQUALS = "=";

    /**
     * Every kind that can be stored, by the name a row's {@code kind} column holds. The name is what
     * a migration would have to rename, so it is spelled out rather than taken off the class.
     */
    public static final Map<String, Codec<?>> CODECS = codecs();

    private Provisions() {}

    /**
     * How one kind of provision is written onto a row and read back off it.
     *
     * @param type the provision's class
     * @param encode fills the row's columns from the provision
     * @param decode reads the provision back off a row this codec wrote
     * @param <P> the kind of provision
     */
    public record Codec<P extends Provision>(
            Class<P> type, BiConsumer<P, ProvisionRow> encode, Function<ProvisionRow, P> decode) {

        public Codec {
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(encode, "encode");
            Objects.requireNonNull(decode, "decode");
        }

        void write(Provision provision, ProvisionRow row) {
            encode.accept(type.cast(provision), row);
        }
    }

    /**
     * Write the spill field: each key and its value, in key order, joined by {@link #PAIR}.
     *
     * <p>Empty values are left out rather than stored: nothing, empty text and an empty list. A date
     * is written as {@code 2025-01-01}, which is how it reads back; anything else as its plain text,
     * so a zero is kept as {@code 0}.
     *
     * @throws ValidationError naming the {@code key} when a value carries either separator
     */
    public static String pack(Map<String, ?> values) {
        Map<String, Object> ordered = new TreeMap<>(Text.CODE_POINT_ORDER);
        ordered.putAll(values);
        List<String> parts = new ArrayList<>();
        for (Map.Entry<String, Object> entry : ordered.entrySet()) {
            Object value = entry.getValue();
            if (value == null
                    || value instanceof CharSequence text && text.isEmpty()
                    || value instanceof Collection<?> members && members.isEmpty()) {
                continue;
            }
            String rendered = value instanceof LocalDate day ? day.toString() : Text.plain(value);
            if (rendered.contains(PAIR) || rendered.contains(EQUALS)) {
                throw new ValidationError(
                        "a spilled value cannot carry a separator", Details.of("key", entry.getKey()));
            }
            parts.add(entry.getKey() + EQUALS + rendered);
        }
        return String.join(PAIR, parts);
    }

    /**
     * Read the spill field back. A part with no {@link #EQUALS} in it is skipped, a key is read
     * without surrounding white space, and a value keeps any further {@code =} it carries. A field
     * that is not there is not there.
     */
    public static Map<String, String> unpack(String raw) {
        Map<String, String> found = new LinkedHashMap<>();
        for (String part : raw.split(PAIR, -1)) {
            int split = part.indexOf(EQUALS);
            if (split < 0) {
                continue;
            }
            String key = Text.strip(part.substring(0, split));
            if (!key.isEmpty()) {
                found.put(key, part.substring(split + EQUALS.length()));
            }
        }
        return Collections.unmodifiableMap(found);
    }

    /**
     * The name this provision is stored under.
     *
     * @throws ValidationError naming the {@code provision}'s class when no codec stores it
     */
    public static String kindOf(Provision provision) {
        for (Map.Entry<String, Codec<?>> entry : CODECS.entrySet()) {
            if (entry.getValue().type() == provision.getClass()) {
                return entry.getKey();
            }
        }
        throw new ValidationError(
                "no codec for that provision",
                Details.of("provision", provision.getClass().getSimpleName()));
    }

    /** Whether this provision can be written at all. */
    public static boolean storable(Provision provision) {
        return CODECS.values().stream().anyMatch(codec -> codec.type() == provision.getClass());
    }

    /**
     * One provision as the row that holds it, its rule to be set by adding the row to one.
     *
     * @throws ValidationError when no codec stores it
     */
    public static ProvisionRow provisionToRow(Provision provision) {
        String name = kindOf(provision);
        // The spill is set even when it is empty, so every decoder can read it before the row has
        // been written.
        ProvisionRow row = new ProvisionRow();
        row.setCategory(provision.category().value());
        row.setKind(name);
        row.setDetail("");
        CODECS.get(name).write(provision, row);
        return row;
    }

    /**
     * One provision as the row that holds it, under the rule with this id.
     *
     * @throws ValidationError when no codec stores it
     */
    public static ProvisionRow provisionToRow(Provision provision, int ruleId) {
        ProvisionRow row = provisionToRow(provision);
        row.setRuleId(ruleId);
        return row;
    }

    /**
     * The row back out as the provision the rule holds.
     *
     * @throws ValidationError naming the {@code kind} when no codec reads it
     */
    public static Provision rowToProvision(ProvisionRow row) {
        Codec<?> codec = CODECS.get(row.getKind());
        if (codec == null) {
            throw new ValidationError("no codec for that row", Details.of("kind", row.getKind()));
        }
        return codec.decode().apply(row);
    }

    private static Map<String, Codec<?>> codecs() {
        Map<String, Codec<?>> byName = new LinkedHashMap<>();
        byName.put(
                "advance_purchase",
                new Codec<>(AdvancePurchase.class, Provisions::advanceToRow, Provisions::advanceFromRow));
        byName.put(
                "instant_purchase",
                new Codec<>(InstantPurchase.class, Provisions::instantToRow, Provisions::instantFromRow));
        byName.put(
                "minimum_stay", new Codec<>(MinimumStay.class, Provisions::minimumToRow, Provisions::minimumFromRow));
        byName.put(
                "maximum_stay", new Codec<>(MaximumStay.class, Provisions::maximumToRow, Provisions::maximumFromRow));
        byName.put(
                "sales_restriction",
                new Codec<>(SalesRestriction.class, Provisions::salesToRow, Provisions::salesFromRow));
        byName.put("penalty", new Codec<>(Penalty.class, Provisions::penaltyToRow, Provisions::penaltyFromRow));
        byName.put(
                "voluntary_change",
                new Codec<>(VoluntaryChange.class, Provisions::changesToRow, Provisions::changesFromRow));
        return Collections.unmodifiableMap(byName);
    }

    private static Optional<LocalDate> day(String raw) {
        return raw == null || raw.isEmpty() ? Optional.empty() : Optional.of(LocalDate.parse(raw));
    }

    private static List<String> parts(String joined, String separator) {
        return Arrays.stream(joined.split(Pattern.quote(separator), -1))
                .filter(part -> !part.isEmpty())
                .toList();
    }

    private static void advanceToRow(AdvancePurchase provision, ProvisionRow row) {
        row.setNumberOne(provision.reservationDays().orElse(null));
        row.setNumberTwo(provision.ticketingDaysAfterReservation().orElse(null));
        Map<String, Object> spilled = new LinkedHashMap<>();
        spilled.put("before", provision.ticketingDaysBeforeDeparture().orElse(null));
        provision.lateBooking().ifPresent(late -> {
            spilled.put("late_within", late.withinDays());
            spilled.put("late_ticket", late.ticketWithinDays());
        });
        row.setDetail(pack(spilled));
    }

    private static AdvancePurchase advanceFromRow(ProvisionRow row) {
        Map<String, String> spilled = unpack(row.getDetail());
        Optional<Integer> within = whole(spilled.get("late_within"));
        Optional<Integer> ticket = whole(spilled.get("late_ticket"));
        return AdvancePurchase.builder()
                .reservationDays(row.getNumberOne())
                .ticketingDaysAfterReservation(row.getNumberTwo())
                .ticketingDaysBeforeDeparture(whole(spilled.get("before")))
                .lateBooking(within.flatMap(days -> ticket.map(hold -> new LateBooking(days, hold))))
                .build();
    }

    /** A spilled whole number, or none when it was never written or written empty. */
    private static Optional<Integer> whole(String spilled) {
        return spilled == null || spilled.isEmpty() ? Optional.empty() : Optional.of(Numbers.parseInt(spilled));
    }

    private static void instantToRow(InstantPurchase provision, ProvisionRow row) {
        row.setNumberOne(provision.hours());
    }

    private static InstantPurchase instantFromRow(ProvisionRow row) {
        return new InstantPurchase(row.getNumberOne().orElse(0));
    }

    private static void minimumToRow(MinimumStay provision, ProvisionRow row) {
        row.setNumberOne(provision.days().orElse(null));
        row.setNumberTwo(provision.months().orElse(null));
        row.setTextOne(provision.weekday().orElse(""));
    }

    private static MinimumStay minimumFromRow(ProvisionRow row) {
        Optional<String> weekday = Optional.of(row.getTextOne()).filter(text -> !text.isEmpty());
        return new MinimumStay(row.getNumberOne(), weekday, row.getNumberTwo());
    }

    private static void maximumToRow(MaximumStay provision, ProvisionRow row) {
        row.setNumberOne(provision.days().orElse(null));
        row.setNumberTwo(provision.months().orElse(null));
        row.setFlagOne(provision.fromTurnaround());
    }

    private static MaximumStay maximumFromRow(ProvisionRow row) {
        return new MaximumStay(row.getNumberOne(), row.getNumberTwo(), row.isFlagOne());
    }

    private static void salesToRow(SalesRestriction provision, ProvisionRow row) {
        row.setTextOne(String.join("/", provision.countries()));
        row.setTextTwo(String.join("/", provision.excludedCountries()));
        Map<String, Object> spilled = new LinkedHashMap<>();
        spilled.put("payment", provision.payment().value());
        spilled.put("first", provision.firstSale().orElse(null));
        spilled.put("last", provision.lastSale().orElse(null));
        spilled.put("agencies", String.join("|", provision.agencies()));
        row.setDetail(pack(spilled));
    }

    private static SalesRestriction salesFromRow(ProvisionRow row) {
        Map<String, String> spilled = unpack(row.getDetail());
        return new SalesRestriction(
                parts(row.getTextOne(), "/"),
                parts(row.getTextTwo(), "/"),
                parts(spilled.getOrDefault("agencies", ""), "|"),
                FormOfPayment.of(spilled.getOrDefault("payment", FormOfPayment.ANY.value())),
                day(spilled.get("first")),
                day(spilled.get("last")),
                Optional.empty());
    }

    private static void penaltyToRow(Penalty provision, ProvisionRow row) {
        Optional<Money> before = provision.beforeDeparture();
        Optional<Money> after = provision.afterDeparture();
        row.setTextOne(provision.action().value());
        row.setTextTwo(provision.applies().value());
        row.setAmount(before.map(Money::amount).orElse(null));
        row.setCurrency(before.or(() -> after).map(Money::currency).orElse(""));
        row.setFlagOne(provision.permitted());
        row.setFlagTwo(provision.noShowForfeits());
        Map<String, Object> spilled = new LinkedHashMap<>();
        spilled.put("after", after.map(Money::amount).orElse(null));
        spilled.put("percent", provision.percent().orElse(null));
        row.setDetail(pack(spilled));
    }

    private static Penalty penaltyFromRow(ProvisionRow row) {
        Map<String, String> spilled = unpack(row.getDetail());
        return new Penalty(
                Action.of(row.getTextOne()),
                row.getAmount().map(amount -> new Money(amount, row.getCurrency())),
                decimal(spilled.get("after")).map(amount -> new Money(amount, row.getCurrency())),
                Applies.of(row.getTextTwo()),
                row.isFlagOne(),
                row.isFlagTwo(),
                decimal(spilled.get("percent")));
    }

    /** A spilled decimal, or none when it was never written or written empty. */
    private static Optional<BigDecimal> decimal(String spilled) {
        return spilled == null || spilled.isEmpty() ? Optional.empty() : Optional.of(Numbers.parseDecimal(spilled));
    }

    private static void changesToRow(VoluntaryChange provision, ProvisionRow row) {
        row.setTextOne(provision.basis().value());
        row.setTextTwo(provision.residual().value());
        row.setFlagOne(provision.permitted());
        row.setFlagTwo(provision.keepFareBreaks());
        Map<String, Object> spilled = new LinkedHashMap<>();
        spilled.put("same_carrier", provision.sameCarrierOnly() ? 1 : 0);
        spilled.put("once", provision.onceOnly() ? 1 : 0);
        row.setDetail(pack(spilled));
    }

    private static VoluntaryChange changesFromRow(ProvisionRow row) {
        Map<String, String> spilled = unpack(row.getDetail());
        return new VoluntaryChange(
                row.isFlagOne(),
                RepricingBasis.of(row.getTextOne()),
                Residual.of(row.getTextTwo()),
                row.isFlagTwo(),
                "1".equals(spilled.get("same_carrier")),
                "1".equals(spilled.get("once")));
    }

    /** One spilled field, which may be nothing. */
    private static Map<String, Object> spill(String key, Object value) {
        Map<String, Object> one = new LinkedHashMap<>();
        one.put(key, value);
        return one;
    }
}
