package io.github.rkaushikdev.gussetline.negotiated;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.Market;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One privately filed fare, and the public fare it takes its rule from.
 *
 * <p>Negotiated fares are the ones that are not in the tariff at all. A published fare is filed for
 * everybody. A negotiated fare is filed for one agency or one corporate account, it is not visible
 * to anybody else, and it is the reason two agencies quote different numbers for the same flight on
 * the same morning. Three things follow and all of them bite.
 *
 * <p>A negotiated fare is only visible to a caller who presents the code it was filed against
 * ({@link #visibleTo}, {@link NegotiatedStore#forAccount}). Not preferred, not cheaper: invisible. A
 * desk that shows one to a caller without the code has published somebody else's commercial terms,
 * which is a contract breach and not a pricing bug.
 *
 * <p>A negotiated fare beats a published one at the same price ({@link #bestFor}), which is the
 * opposite of the add-on rule and for the opposite reason. An arbitrary is a construction the
 * carrier did not publish, so the published fare wins. A negotiated fare is what the carrier agreed
 * with this customer specifically, so it wins.
 *
 * <p>And a negotiated fare inherits its conditions from a published one it names ({@link
 * #withInheritedRule}). The carrier does not refile twenty categories to change a number, it files
 * an amount and points at the rule the public fare uses. Reading its own blank rule number as
 * unrestricted gives the customer a fully flexible ticket at a discount fare price, which is the
 * most expensive way to be wrong here.
 *
 * @param account who the fare is filed for
 * @param fare the fare itself, filed as {@link FareType#NEGOTIATED} or {@link FareType#PRIVATE}
 * @param inheritsFrom the fare basis of the published fare whose rule it takes, or empty when it
 *     files a rule of its own
 * @param notes remarks filed with it, in order
 */
public record Negotiated(Account account, Fare fare, String inheritsFrom, List<String> notes) {

    private static final Comparator<Fare> BY_AMOUNT =
            Comparator.comparing(fare -> fare.amount().amount());

    /**
     * Checks the fare is filed as a private one.
     *
     * @throws ValidationError naming the {@code fare_type} when the fare is filed as anything else
     */
    public Negotiated {
        Objects.requireNonNull(account, "account");
        Objects.requireNonNull(fare, "fare");
        Objects.requireNonNull(inheritsFrom, "inheritsFrom");
        notes = List.copyOf(notes);
        if (fare.fareType() != FareType.NEGOTIATED && fare.fareType() != FareType.PRIVATE) {
            throw new ValidationError(
                    "a negotiated fare is filed as one",
                    Details.of("fare_type", fare.fareType().value()));
        }
    }

    /** A private fare that files a rule of its own, with no notes. */
    public Negotiated(Account account, Fare fare) {
        this(account, fare, "", List.of());
    }

    /** A private fare that takes its rule from the published fare named, with no notes. */
    public Negotiated(Account account, Fare fare, String inheritsFrom) {
        this(account, fare, inheritsFrom, List.of());
    }

    /** The carrier that filed it. */
    public String carrier() {
        return fare.carrier();
    }

    /** The direction it is filed in, between two cities. */
    public Market market() {
        return fare.market();
    }

    /**
     * The private fare, carrying the rule of the public fare it names.
     *
     * <p>It keeps its own carrier, market, basis, amount, cabin, type and windows, and takes the
     * public fare's rule, tariff and footnote, and its routing too when it files none of its own. A
     * private fare that names no public fare stands on its own rule, and must have one: reading its
     * blank rule number as unrestricted hands the customer a fully flexible ticket at a discount
     * price.
     *
     * @param published the public fares loaded, searched by fare basis for the one it names
     * @throws ValidationError naming the {@code fare_basis} when it names no fare and files no rule,
     *     or naming it and the {@code inherits_from} when the fare it names is not among those given
     */
    public static Fare withInheritedRule(Negotiated filed, List<Fare> published) {
        Fare own = filed.fare();
        if (filed.inheritsFrom().isEmpty()) {
            if (!own.ruleNumber().isEmpty()) {
                return own;
            }
            throw new ValidationError(
                    "a negotiated fare needs a rule of its own or one to inherit",
                    Details.of("fare_basis", own.fareBasis()));
        }

        Fare parent = published.stream()
                .filter(fare -> fare.fareBasis().equals(filed.inheritsFrom()))
                .findFirst()
                .orElseThrow(() -> new ValidationError(
                        "the fare it inherits from is not loaded",
                        Details.of("fare_basis", own.fareBasis(), "inherits_from", filed.inheritsFrom())));

        return own.toBuilder()
                .ruleNumber(parent.ruleNumber())
                .routingNumber(own.routingNumber().isEmpty() ? parent.routingNumber() : own.routingNumber())
                .tariffNumber(parent.tariffNumber())
                .footnote(parent.footnote())
                .build();
    }

    /**
     * What this caller may actually buy, out of both kinds: the cheaper of the cheapest published and
     * the cheapest private fare, or empty when there is neither.
     *
     * <p>A negotiated fare wins a tie, which is the opposite of the add-on rule and for the opposite
     * reason: an arbitrary is a construction the carrier did not publish, and a negotiated fare is what
     * the carrier agreed with this customer by name. Among fares at one amount, the first given wins.
     */
    public static Optional<Fare> bestFor(List<Fare> published, List<Negotiated> privateFares) {
        Optional<Fare> cheapestPublic = published.stream().min(BY_AMOUNT);
        Optional<Fare> cheapestPrivate =
                privateFares.stream().map(Negotiated::fare).min(BY_AMOUNT);

        if (cheapestPrivate.isEmpty()) {
            return cheapestPublic;
        }
        if (cheapestPublic.isEmpty()) {
            return cheapestPrivate;
        }
        if (BY_AMOUNT.compare(cheapestPrivate.get(), cheapestPublic.get()) <= 0) {
            return cheapestPrivate;
        }
        return cheapestPublic;
    }

    /**
     * What the private fare is worth over the best public one. Never negative: a private fare dearer
     * than the public one, or nothing published to compare with, is worth nothing.
     *
     * @throws ValidationError when the cheapest public fare is in another currency
     */
    public static Money savingAgainst(List<Fare> published, Negotiated filed) {
        Money nothing = Money.zero(filed.fare().amount().currency());
        Optional<Fare> cheapest = published.stream().min(BY_AMOUNT);
        if (cheapest.isEmpty()) {
            return nothing;
        }
        Money gap = cheapest.get().amount().minus(filed.fare().amount());
        return gap.isNegative() ? nothing : gap;
    }

    /** Whether a caller presenting these codes, in any case, may see this fare at all. */
    public static boolean visibleTo(Negotiated filed, List<String> codes) {
        return presented(codes).contains(filed.account().code());
    }

    /** The codes a caller presented, normalised, each once, in the order given, blanks left out. */
    static Set<String> presented(List<String> codes) {
        return codes.stream()
                .filter(code -> code != null && !Text.isBlank(code))
                .map(Text::code)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** The fare and who it is for: {@code LH FRANYC KCORP 320.00 EUR for ACME}. */
    @Override
    public String toString() {
        return fare + " for " + account.code();
    }
}
