package io.github.rkaushikdev.gussetline.reissue;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.rules.Changes;
import io.github.rkaushikdev.gussetline.rules.Residual;
import io.github.rkaushikdev.gussetline.taxes.AppliedTax;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * What a reissue does to the taxes, which is where the money actually goes: every tax line on the
 * reissue, and the two totals that come out of it.
 *
 * <p>The fare side of a reissue is arithmetic and lives with category 31 ({@link
 * io.github.rkaushikdev.gussetline.rules.Reissue}). The tax side is not arithmetic and it is where
 * every reissue argument this desk has ever had ended up. Four rules decide it, and none of them
 * follows from the others.
 *
 * <p>A tax already paid on a coupon that has been flown is gone. The passenger flew out of
 * Frankfurt, the German departure tax was owed and collected, and changing the return does not unmake
 * the departure. Refunding it is the single most expensive mistake available here, because it looks
 * correct on every screen and the carrier finds it at the year end.
 *
 * <p>A tax on a point the new itinerary no longer touches comes back. Not because the fare came down
 * but because the taxable event did not happen: the passenger is not departing Vienna any more, so
 * nobody is owed a Vienna departure tax.
 *
 * <p>A carrier imposed surcharge is not a tax. YQ and YR are the carrier's own money in a tax shaped
 * box, they move with the fare, and they follow the residual: a fare whose difference is forfeited
 * forfeits its YQ with it. Treating them as taxes refunds money the carrier never agreed to give
 * back.
 *
 * <p>And a tax that went up is collected whatever else happens. There is no netting against a fare
 * that came down: the government is owed what it is owed and the residual question is between the
 * passenger and the carrier.
 *
 * <p>Everything here works per tax code and per point, because that is the grain the rules are
 * written at. Totalling first and comparing the totals gives the right answer on the easy cases and
 * quietly wrong ones on every itinerary that changed a country.
 *
 * @param currency the one currency every line is in
 * @param lines every tax line, ordered by code and then by point
 */
public record TaxReissue(String currency, List<Line> lines) {

    /** Orders the lines by code, then by point. */
    private static final Comparator<Key> SETTLED =
            Comparator.comparing(Key::code).thenComparing(Key::point);

    public TaxReissue {
        Objects.requireNonNull(currency, "currency");
        lines = List.copyOf(lines);
    }

    /** A reissue with no lines yet. */
    public TaxReissue(String currency) {
        this(currency, List.of());
    }

    /** What the passenger pays in tax: the sum of every collected line. */
    public Money collect() {
        Money total = Money.zero(currency);
        for (Line line : lines) {
            total = total.plus(line.collected());
        }
        return total;
    }

    /** What comes back in tax: the sum of every refunded line. */
    public Money refund() {
        Money total = Money.zero(currency);
        for (Line line : lines) {
            total = total.plus(line.refunded());
        }
        return total;
    }

    /** What the carrier keeps, which is a residual and not a refund. */
    public Money kept() {
        Money total = Money.zero(currency);
        for (Line line : lines) {
            if (line.treatment() == Treatment.KEPT) {
                total = total.plus(line.was().minus(line.now()));
            }
        }
        return total;
    }

    /** What is gone because the taxable event already happened. */
    public Money flown() {
        Money total = Money.zero(currency);
        for (Line line : lines) {
            if (line.treatment() == Treatment.FLOWN) {
                total = total.plus(line.was());
            }
        }
        return total;
    }

    /** The lines given one treatment, in order. */
    public List<Line> of(Treatment treatment) {
        return lines.stream().filter(line -> line.treatment() == treatment).toList();
    }

    /** How many lines there are. */
    public int size() {
        return lines.size();
    }

    /** {@code collect 7.50 EUR, refund 0 EUR, 1 lines}. */
    @Override
    public String toString() {
        return "collect " + collect() + ", refund " + refund() + ", " + lines.size() + " lines";
    }

    /** {@link #assess(List, List, Set, Residual)} with nothing flown and a refundable residual. */
    public static TaxReissue assess(List<AppliedTax> was, List<AppliedTax> now) {
        return assess(was, now, Set.of(), Residual.REFUNDABLE);
    }

    /** {@link #assess(List, List, Set, Residual)} with a refundable residual. */
    public static TaxReissue assess(List<AppliedTax> was, List<AppliedTax> now, Set<String> flownPoints) {
        return assess(was, now, flownPoints, Residual.REFUNDABLE);
    }

    /** {@link #assess(List, List, Set, Residual)} with nothing flown. */
    public static TaxReissue assess(List<AppliedTax> was, List<AppliedTax> now, Residual residual) {
        return assess(was, now, Set.of(), residual);
    }

    /**
     * Work out every tax line, per code and per point, comparing the taxes the old ticket carried with
     * the ones the new ticket carries. Codes and points are compared ignoring case; of two taxes on one
     * ticket at the same code and point, the later one stands.
     *
     * <p>Each line is decided in the order the rules stack. A line that went up is collected. A line
     * that did not move is unchanged. A government tax that went down at a point already departed is
     * flown. A carrier surcharge that went down under a residual that is not refundable is kept.
     * Anything else that went down is refunded.
     *
     * @param flownPoints the places the passenger has already departed, in any case; a tax owed at one
     *     of those was owed when it was collected, and changing what happens afterwards does not unmake
     *     it
     * @param residual what the fare does with a difference, which a carrier surcharge follows
     * @throws ValidationError when there is no tax on either side, or the taxes are in more than one
     *     currency, naming the {@code currencies}, sorted
     */
    public static TaxReissue assess(
            List<AppliedTax> was, List<AppliedTax> now, Set<String> flownPoints, Residual residual) {
        String currency = currencyOf(was, now);
        Map<Key, AppliedTax> before = byKey(was);
        Map<Key, AppliedTax> after = byKey(now);
        Set<String> gone = new LinkedHashSet<>();
        for (String point : flownPoints) {
            gone.add(point.toUpperCase(Locale.ROOT));
        }

        TreeSet<Key> keys = new TreeSet<>(SETTLED);
        keys.addAll(before.keySet());
        keys.addAll(after.keySet());
        List<Line> lines = new ArrayList<>();
        for (Key key : keys) {
            lines.add(line(
                    key,
                    Optional.ofNullable(before.get(key)),
                    Optional.ofNullable(after.get(key)),
                    currency,
                    gone,
                    residual));
        }
        return new TaxReissue(currency, lines);
    }

    /**
     * What a refundable tax total is worth when it comes back as a voucher: nothing unless the residual
     * is retained, and otherwise the refund at {@link Changes#CREDIT_DISCOUNT} of its face, to the
     * refund's own number of decimals, half to even.
     *
     * <p>A voucher is not cash and the desk has never valued it as cash. The same discount the fare
     * side uses, so a recommendation cannot be worth more by moving money between the fare and the
     * taxes.
     */
    public static Money creditFor(TaxReissue report, Residual residual) {
        if (residual != Residual.RETAINED) {
            return Money.zero(report.currency());
        }
        Money refund = report.refund();
        return new Money(
                refund.amount()
                        .multiply(Changes.CREDIT_DISCOUNT, Numbers.CONTEXT)
                        .setScale(refund.amount().scale(), RoundingMode.HALF_EVEN),
                report.currency());
    }

    /**
     * Collect minus refund, which is what the reissue costs in tax alone.
     *
     * <p>Negative means the taxes came back. Reported rather than clamped, because a reissue whose
     * taxes come back further than its fare goes up is a real shape and the desk needs to see the
     * sign.
     */
    public static Money net(TaxReissue report) {
        return report.collect().minus(report.refund());
    }

    /**
     * The departure points behind us, upper case, as the set {@link #assess(List, List, Set,
     * Residual)} reads. Blank entries are skipped.
     *
     * <p>Takes the origins and not the destinations. A tax is owed on departure from a point, so
     * arriving somewhere and not leaving again has not made anything owed there.
     *
     * @param couponsFlown the origin of each flown coupon
     */
    public static Set<String> pointsFlown(Collection<String> couponsFlown) {
        Set<String> points = new LinkedHashSet<>();
        for (String point : couponsFlown) {
            if (point != null && !point.isEmpty()) {
                points.add(point.toUpperCase(Locale.ROOT));
            }
        }
        return Collections.unmodifiableSet(points);
    }

    /** One tax code at one point, decided in the order the rules stack. */
    private static Line line(
            Key key,
            Optional<AppliedTax> old,
            Optional<AppliedTax> fresh,
            String currency,
            Set<String> flown,
            Residual residual) {
        boolean imposed = old.or(() -> fresh).map(AppliedTax::carrierImposed).orElse(false);
        Money was = old.map(AppliedTax::amount).orElse(Money.zero(currency));
        Money became = fresh.map(AppliedTax::amount).orElse(Money.zero(currency));

        if (became.isGreaterThan(was)) {
            // A government is owed what it is owed. There is no netting against a fare that came down.
            return new Line(key.code(), key.point(), was, became, Treatment.COLLECTED, imposed);
        }
        if (became.equals(was)) {
            return new Line(key.code(), key.point(), was, became, Treatment.UNCHANGED, imposed);
        }
        if (flown.contains(key.point()) && !imposed) {
            // The taxable event already happened. Refunding this is the most expensive mistake
            // available here and it looks right on every screen.
            return new Line(key.code(), key.point(), was, became, Treatment.FLOWN, imposed);
        }
        if (imposed && residual != Residual.REFUNDABLE) {
            // The carrier's own money in a tax shaped box, following the fare it belongs to rather
            // than the rules a government writes.
            return new Line(key.code(), key.point(), was, became, Treatment.KEPT, imposed);
        }
        return new Line(key.code(), key.point(), was, became, Treatment.REFUNDED, imposed);
    }

    /** The taxes by code and point, the later of two at one key standing. */
    private static Map<Key, AppliedTax> byKey(List<AppliedTax> taxes) {
        Map<Key, AppliedTax> found = new LinkedHashMap<>();
        for (AppliedTax tax : taxes) {
            found.put(new Key(tax.code().toUpperCase(Locale.ROOT), tax.point().toUpperCase(Locale.ROOT)), tax);
        }
        return found;
    }

    private static String currencyOf(List<AppliedTax> was, List<AppliedTax> now) {
        TreeSet<String> found = new TreeSet<>();
        for (AppliedTax tax : was) {
            found.add(tax.amount().currency());
        }
        for (AppliedTax tax : now) {
            found.add(tax.amount().currency());
        }
        if (found.isEmpty()) {
            throw new ValidationError("a reissue needs a tax to compare");
        }
        if (found.size() > 1) {
            throw new ValidationError("a reissue compares one currency", Details.of("currencies", List.copyOf(found)));
        }
        return found.first();
    }

    /** One tax code at one point, both upper case. */
    private record Key(String code, String point) {}
}
