package io.github.rkaushikdev.gussetline.reports;

import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.persistence.models.FareRow;
import io.github.rkaushikdev.gussetline.reports.Layout.Figure;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;

/**
 * What moved between two filings of a carrier's tariff.
 *
 * <p>A carrier refiles constantly. Most of a weekly file is the same fares it held last week, some
 * amounts move, a handful of fare bases appear, and a handful stop being filed. The desk needs to know
 * which is which before it opens, because each one means something different and only one of them is
 * news.
 *
 * <p>An amount that moved is the interesting case. Every watch in that market is now measuring against
 * a fare the carrier no longer files at that price, and the ones that moved down are the ones worth
 * repricing this morning rather than tonight. That is the only reason this report exists.
 *
 * <p>A fare that stopped being filed is the dangerous case, because nothing about it looks broken. The
 * fare is still in the database under the old load, it still prices, and the answer it gives is one
 * the carrier will refuse at the ticket desk. The desk finds out when a passenger is on the phone.
 *
 * <p>A new fare is usually nothing. Carriers file promotional fares constantly and most of them are for
 * markets nobody is watching. They are counted rather than listed, so the two lists that matter stay
 * on the page.
 *
 * <p>What counts as the same fare, and this is the whole design: carrier, market, fare basis, cabin and
 * the direction the fare is filed in ({@link Identity}). Not the amount, obviously, and not the rule
 * number either, because a carrier that repoints a fare at a new rule has changed what the fare permits
 * and not which fare it is. That change is reported as an amendment beside the price ones, because a
 * rule change on an unchanged price is exactly the amendment that gets missed.
 *
 * <p>Sale dates are deliberately not part of the identity. A refile that extends the discontinue date
 * of an unchanged fare is the most common single edit in a tariff, and treating it as a withdrawal plus
 * an addition would bury the two lists that matter under a hundred rows of nothing happening.
 *
 * @param carrier whose filing this is, carriers joined by {@code +} when it is more than one's, or
 *     empty text when neither side holds a fare
 * @param currency the desk's selling currency
 * @param before how many fares the older filing held
 * @param after how many fares the newer filing holds
 * @param moves the fares in both whose price changed, in the newer filing's order
 * @param amendments the terms that changed on fares whose price held, in the newer filing's order
 * @param withdrawn the fares the newer filing no longer holds, in order of their identity
 * @param added how many fares the newer filing holds that the older one did not
 */
public record Filing(
        String carrier,
        String currency,
        int before,
        int after,
        List<Move> moves,
        List<Amendment> amendments,
        List<Gone> withdrawn,
        int added) {

    /** The currency a filing is read in when nobody says. */
    public static final String DEFAULT_CURRENCY = "EUR";

    /** How many rows of each list a rendered report shows unless told otherwise. */
    private static final int SHOWN = 15;

    /**
     * The terms a fare can be amended on without its price moving, and how each is read off a row.
     * The order is the order they are reported in, which is roughly how much trouble each causes.
     */
    private static final List<Term> TERMS = List.of(
            new Term("rule number", FareRow::getRuleNumber),
            new Term("routing number", FareRow::getRoutingNumber),
            new Term("tariff number", FareRow::getTariffNumber),
            new Term("footnote", FareRow::getFootnote),
            new Term("global indicator", FareRow::getGlobalIndicator));

    /** The terms a fare can be amended on without its price moving, as reported, in reporting order. */
    public static final List<String> AMENDABLE = TERMS.stream().map(Term::name).toList();

    public Filing {
        Objects.requireNonNull(carrier, "carrier");
        Objects.requireNonNull(currency, "currency");
        moves = List.copyOf(moves);
        amendments = List.copyOf(amendments);
        withdrawn = List.copyOf(withdrawn);
    }

    /**
     * What makes two filed fares the same fare: carrier, market, basis, cabin and direction. Not the
     * price. Identities order part by part, in that order, each by code point.
     *
     * @param carrier the filing carrier, {@code LH}
     * @param origin where the fare starts
     * @param destination where the fare ends
     * @param fareBasis the fare basis, {@code KLWAP7RT}
     * @param cabin the cabin, {@code Y}
     * @param owrt the direction it is filed in, {@code 1} one way or {@code 2} round trip
     */
    public record Identity(
            String carrier, String origin, String destination, String fareBasis, String cabin, String owrt)
            implements Comparable<Identity> {

        private static final Comparator<Identity> ORDER = Comparator.comparing(Identity::carrier, Text.CODE_POINT_ORDER)
                .thenComparing(Identity::origin, Text.CODE_POINT_ORDER)
                .thenComparing(Identity::destination, Text.CODE_POINT_ORDER)
                .thenComparing(Identity::fareBasis, Text.CODE_POINT_ORDER)
                .thenComparing(Identity::cabin, Text.CODE_POINT_ORDER)
                .thenComparing(Identity::owrt, Text.CODE_POINT_ORDER);

        public Identity {
            Objects.requireNonNull(carrier, "carrier");
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(destination, "destination");
            Objects.requireNonNull(fareBasis, "fareBasis");
            Objects.requireNonNull(cabin, "cabin");
            Objects.requireNonNull(owrt, "owrt");
        }

        @Override
        public int compareTo(Identity other) {
            return ORDER.compare(this, other);
        }

        /** {@code LH FRA-NYC KLWAP7RT}: the carrier, the market and the basis. */
        private String named() {
            return carrier + " " + origin + "-" + destination + " " + fareBasis;
        }
    }

    /**
     * One fare whose price changed, and by how much.
     *
     * @param fare which fare
     * @param was what the older filing priced it at
     * @param now what the newer filing prices it at
     */
    public record Move(Identity fare, Money was, Money now) {

        public Move {
            Objects.requireNonNull(fare, "fare");
            Objects.requireNonNull(was, "was");
            Objects.requireNonNull(now, "now");
        }

        /** The new price less the old one. Refuses a move across currencies. */
        public Money difference() {
            return now.minus(was);
        }

        /** Whether the price came down. Refuses a move across currencies. */
        public boolean fell() {
            return now.isLessThan(was);
        }

        /**
         * How far it moved, as a percentage of the old price to the hundredth, half to even, for
         * ordering. Zero rather than a division error when the old price was nothing.
         */
        public BigDecimal percent() {
            if (was.isZero()) {
                return BigDecimal.ZERO;
            }
            return Numbers.divide(difference().amount(), was.amount())
                    .multiply(BigDecimal.valueOf(100), Numbers.CONTEXT)
                    .setScale(2, RoundingMode.HALF_EVEN);
        }

        /** {@code LH FRA-NYC KLWAP7RT Y 420.00 EUR to 380.00 EUR, down 9.52%}. */
        @Override
        public String toString() {
            String way = fell() ? "down" : "up";
            return fare.named() + " " + fare.cabin() + " " + was + " to " + now + ", " + way + " "
                    + percent().abs() + "%";
        }
    }

    /**
     * One fare whose price held and whose terms did not.
     *
     * @param fare which fare
     * @param field the term that changed, as {@link #AMENDABLE} names it
     * @param was the term in the older filing
     * @param now the term in the newer filing
     */
    public record Amendment(Identity fare, String field, String was, String now) {

        public Amendment {
            Objects.requireNonNull(fare, "fare");
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(was, "was");
            Objects.requireNonNull(now, "now");
        }

        /** {@code LH FRA-NYC KLWAP7RT rule number 0100 to 0200}. */
        @Override
        public String toString() {
            return fare.named() + " " + field + " " + was + " to " + now;
        }
    }

    /**
     * One fare the carrier has stopped filing.
     *
     * @param fare which fare
     * @param was what the older filing priced it at
     */
    public record Gone(Identity fare, Money was) {

        public Gone {
            Objects.requireNonNull(fare, "fare");
            Objects.requireNonNull(was, "was");
        }

        /** {@code LH FRA-NYC MLWAP7RT Y was 420.00 EUR}. */
        @Override
        public String toString() {
            return fare.named() + " " + fare.cabin() + " was " + was;
        }
    }

    /** Fares in both filings, whatever happened to them. */
    public int held() {
        return after - added;
    }

    /** Fares in both filings that neither moved nor were amended, each amended term counting once. */
    public int unchanged() {
        return held() - moves.size() - amendments.size();
    }

    /** The ones worth repricing this morning, steepest first. */
    public List<Move> falls() {
        return moves.stream()
                .filter(Move::fell)
                .sorted(Comparator.comparing(Move::percent))
                .toList();
    }

    /** The ones that went up, steepest first. */
    public List<Move> rises() {
        return moves.stream()
                .filter(move -> !move.fell())
                .sorted(Comparator.comparing(Move::percent).reversed())
                .toList();
    }

    /** Whether anybody has to do something before the desk opens: a fare fell, or one was withdrawn. */
    public boolean worthALook() {
        return !falls().isEmpty() || !withdrawn.isEmpty();
    }

    /** {@code 1 moved, 1 withdrawn, 1 added over 2 fares}. */
    @Override
    public String toString() {
        return moves.size() + " moved, " + withdrawn.size() + " withdrawn, " + added + " added over " + after
                + " fares";
    }

    /** Carrier, market, basis, cabin and direction. Not the price. */
    public static Identity identity(FareRow row) {
        return new Identity(
                row.getCarrier(),
                row.getOrigin(),
                row.getDestination(),
                row.getFareBasis(),
                row.getCabin(),
                row.getOwrt());
    }

    /** {@link #build(List, List, String)} in {@link #DEFAULT_CURRENCY}. */
    public static Filing build(List<FareRow> before, List<FareRow> after) {
        return build(before, after, DEFAULT_CURRENCY);
    }

    /**
     * Read one filing against the one it replaces.
     *
     * <p>Both sides come in as rows rather than as a load id, so this can be run against a file that has
     * not been taken in yet. Reading a filing before loading it is the whole point on the morning a
     * carrier files something unexpected.
     *
     * <p>A fare filed twice on one side counts twice in that side's total and is read at its last
     * filing. A fare on both sides whose amount or currency differs is a move and nothing else; one whose
     * price held is an amendment for every term in {@link #AMENDABLE} that differs.
     */
    public static Filing build(List<FareRow> before, List<FareRow> after, String currency) {
        Map<Identity, FareRow> was = byIdentity(before);
        Map<Identity, FareRow> now = byIdentity(after);

        List<Move> moves = new ArrayList<>();
        List<Amendment> amendments = new ArrayList<>();
        int added = 0;
        for (Map.Entry<Identity, FareRow> entry : now.entrySet()) {
            Identity key = entry.getKey();
            FareRow row = entry.getValue();
            FareRow old = was.get(key);
            if (old == null) {
                added += 1;
                continue;
            }
            if (old.getAmount().compareTo(row.getAmount()) != 0
                    || !Objects.equals(old.getCurrency(), row.getCurrency())) {
                moves.add(new Move(key, price(old), price(row)));
                continue;
            }
            amendments.addAll(amendments(key, old, row));
        }

        List<Gone> withdrawn = new ArrayList<>();
        was.forEach((key, row) -> {
            if (!now.containsKey(key)) {
                withdrawn.add(new Gone(key, price(row)));
            }
        });
        withdrawn.sort(Comparator.comparing(Gone::fare));

        return new Filing(
                carrier(before, after), currency, before.size(), after.size(), moves, amendments, withdrawn, added);
    }

    /** {@link #render(Filing, int)} showing fifteen of each list. */
    public static String render(Filing report) {
        return render(report, SHOWN);
    }

    /**
     * The report as somebody reads it before the desk opens: the heading, the counts, then the falls,
     * the withdrawn fares, the rises and the amendments, in that order, each cut at {@code limit} rows
     * and left out when it has nothing in it. New fares are counted and never listed.
     */
    public static String render(Filing report, int limit) {
        return Layout.page(
                Layout.heading("filing for " + (report.carrier().isEmpty() ? "nobody" : report.carrier())),
                Layout.figures(
                        new Figure("was", report.before()),
                        new Figure("is", report.after()),
                        new Figure("held", report.held()),
                        new Figure("unchanged", report.unchanged()),
                        new Figure("moved", report.moves().size()),
                        new Figure("amended", report.amendments().size()),
                        new Figure("withdrawn", report.withdrawn().size()),
                        new Figure("added", report.added())),
                Layout.section("fell, steepest first, reprice these", report.falls(), limit),
                Layout.section("no longer filed, these still price and should not", report.withdrawn(), limit),
                Layout.section("rose", report.rises(), limit),
                Layout.section("same price, different terms", report.amendments(), limit));
    }

    /** The rows under their identities, in the order first filed, a later filing of one replacing it. */
    private static Map<Identity, FareRow> byIdentity(List<FareRow> rows) {
        Map<Identity, FareRow> found = new LinkedHashMap<>();
        for (FareRow row : rows) {
            found.put(identity(row), row);
        }
        return found;
    }

    private static Money price(FareRow row) {
        return Money.of(row.getAmount(), row.getCurrency());
    }

    /** Every term that moved on a fare whose price did not. */
    private static List<Amendment> amendments(Identity key, FareRow old, FareRow fresh) {
        List<Amendment> found = new ArrayList<>();
        for (Term term : TERMS) {
            String before = term.read().apply(old);
            String after = term.read().apply(fresh);
            if (!Objects.equals(before, after)) {
                found.add(new Amendment(key, term.name(), Text.plain(before), Text.plain(after)));
            }
        }
        return found;
    }

    /** Whose filing this is, or a note that it is more than one carrier's. */
    private static String carrier(List<FareRow> before, List<FareRow> after) {
        Set<String> carriers = new TreeSet<>(Text.CODE_POINT_ORDER);
        before.forEach(row -> carriers.add(row.getCarrier()));
        after.forEach(row -> carriers.add(row.getCarrier()));
        return String.join("+", carriers);
    }

    /** A term a fare can be amended on, by the name the report gives it, and how it is read off a row. */
    private record Term(String name, Function<FareRow, String> read) {}
}
