package io.github.rkaushikdev.gussetline.addon;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.tariff.Fare;
import io.github.rkaushikdev.gussetline.tariff.FareType;
import io.github.rkaushikdev.gussetline.tariff.OwRt;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Add-on construction, which is how a fare exists for a market nobody filed.
 *
 * <p>A carrier files Frankfurt to New York and files Frankfurt to Nuremberg. It does not file
 * Nuremberg to New York, because there are ten thousand such pairs and nobody is going to file them.
 * Instead it files an add-on, an arbitrary amount that may be added to a gateway fare to reach an
 * interior point, and the desk builds the fare it needs out of the two.
 *
 * <p>The construction itself is addition. Everything difficult is in what may be added to what. Two
 * arbitraries at one end is not a construction, it is a mistake: the second would be measured from
 * a point the first one already left. Nor is an add-on added to a constructed fare, since a fare
 * built out of a fare out of a fare is one no carrier prices and no audit department will accept.
 *
 * <p>The rule and the footnote come from the gateway fare and never from the add-on, because an
 * add-on has no conditions of its own and reading its blank rule number as unrestricted is how a
 * constructed fare ends up with no conditions at all. And a constructed fare loses a tie to a
 * published one: if a carrier has filed the market, that fare is what it sells, whatever an
 * arbitrary adds up to.
 */
public final class AddOns {

    private static final Comparator<Fare> BY_AMOUNT =
            Comparator.comparing(fare -> fare.amount().amount());

    private AddOns() {}

    /**
     * {@link #construct(Fare, Optional, Optional)} with one arbitrary at the origin end.
     *
     * @throws ConstructionError for any construction a carrier does not price
     */
    public static Constructed constructAtOrigin(Fare gatewayFare, AddOn originAddOn) {
        return construct(gatewayFare, Optional.of(originAddOn), Optional.empty());
    }

    /**
     * {@link #construct(Fare, Optional, Optional)} with one arbitrary at the destination end.
     *
     * @throws ConstructionError for any construction a carrier does not price
     */
    public static Constructed constructAtDestination(Fare gatewayFare, AddOn destinationAddOn) {
        return construct(gatewayFare, Optional.empty(), Optional.of(destinationAddOn));
    }

    /**
     * {@link #construct(Fare, Optional, Optional)} with an arbitrary at each end.
     *
     * @throws ConstructionError for any construction a carrier does not price
     */
    public static Constructed construct(Fare gatewayFare, AddOn originAddOn, AddOn destinationAddOn) {
        return construct(gatewayFare, Optional.of(originAddOn), Optional.of(destinationAddOn));
    }

    /**
     * Build the fare a market has no filing for.
     *
     * <p>The fare runs from the origin arbitrary's point, or the gateway fare's origin when there is
     * none, to the destination arbitrary's point, or the gateway fare's destination. Its amount is
     * the gateway fare's plus each arbitrary's. Every other field comes off the gateway fare: the
     * rule, the routing, the tariff, the footnote, the cabin, the fare type, both windows and the
     * global indicator.
     *
     * <p>Refuses more than it accepts, and every refusal is a construction a carrier does not price
     * rather than a rule this desk invented.
     *
     * @throws ConstructionError when there is no arbitrary at either end; when the gateway fare is a
     *     negotiated one (naming its {@code fare_basis}); or, checking the origin end and then the
     *     destination end, when an arbitrary is filed for the other end (naming the end it was
     *     {@code filed} for and the end it was {@code used} at), belongs to another carrier (naming
     *     both, as {@code addon} and {@code fare}), does not meet the fare (naming its {@code
     *     gateway} and the {@code fare_point}), or is in another currency (naming both, as {@code
     *     addon} and {@code fare})
     * @throws ValidationError when the fare the construction gives is not a fare, such as one that
     *     starts and ends in the same city
     */
    public static Constructed construct(
            Fare gatewayFare, Optional<AddOn> originAddOn, Optional<AddOn> destinationAddOn) {
        Objects.requireNonNull(gatewayFare, "gatewayFare");
        Objects.requireNonNull(originAddOn, "originAddOn");
        Objects.requireNonNull(destinationAddOn, "destinationAddOn");
        if (originAddOn.isEmpty() && destinationAddOn.isEmpty()) {
            throw new ConstructionError("a construction needs an arbitrary");
        }
        if (gatewayFare.fareType() == FareType.NEGOTIATED) {
            throw new ConstructionError(
                    "a negotiated fare is not a gateway fare", Details.of("fare_basis", gatewayFare.fareBasis()));
        }
        originAddOn.ifPresent(addOn -> check(gatewayFare, addOn, End.ORIGIN));
        destinationAddOn.ifPresent(addOn -> check(gatewayFare, addOn, End.DESTINATION));

        List<AddOn> attached = Stream.of(originAddOn, destinationAddOn)
                .flatMap(Optional::stream)
                .toList();
        Money amount = gatewayFare.amount();
        for (AddOn addOn : attached) {
            amount = amount.plus(addOn.amount());
        }

        // The rule and the footnote stay those of the gateway fare. An add-on is an amount and
        // carries no conditions of its own, so reading its blank rule number would leave the fare
        // unrestricted.
        Fare fare = gatewayFare.toBuilder()
                .origin(originAddOn.map(AddOn::point).orElse(gatewayFare.origin()))
                .destination(destinationAddOn.map(AddOn::point).orElse(gatewayFare.destination()))
                .amount(amount)
                .globalIndicator(indicator(gatewayFare))
                .build();
        return new Constructed(
                fare,
                gatewayFare,
                originAddOn,
                destinationAddOn,
                attached.stream().map(AddOn::toString).toList());
    }

    /**
     * The cheapest construction for a market, or empty if there is none.
     *
     * <p>Every gateway fare, in the order given, is tried against every arbitrary that reaches each
     * end, cheapest first, and against nothing at one end. A pairing a carrier does not price is
     * passed over. On a tie the first construction found is kept. It is a small search:
     * a carrier files a handful of arbitraries per point and the fares are already narrowed to one
     * market.
     *
     * @throws ValidationError when the carrier is malformed, a point is unknown, or a pairing gives
     *     something that is not a fare
     */
    public static Optional<Constructed> cheapestFor(
            String carrier, String origin, String destination, List<Fare> gatewayFares, AddOnStore store) {
        Constructed best = null;
        for (Fare fare : gatewayFares) {
            // Nothing at an end is a real option: a market may need an arbitrary at one end only,
            // and the gateway fare already reaches the other.
            List<Optional<AddOn>> origins = orNothing(store.forPoint(carrier, origin, fare.origin(), End.ORIGIN));
            List<Optional<AddOn>> endings =
                    orNothing(store.forPoint(carrier, destination, fare.destination(), End.DESTINATION));
            for (Optional<AddOn> left : origins) {
                for (Optional<AddOn> right : endings) {
                    if (left.isEmpty() && right.isEmpty()) {
                        continue;
                    }
                    Optional<Constructed> made = priced(fare, left, right);
                    if (made.isPresent() && (best == null || isCheaper(made.get(), best))) {
                        best = made.get();
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * What a market sells, when both a filing and a construction exist.
     *
     * <p>The cheapest filing, unless the construction is cheaper than it. If a carrier has bothered
     * to file the market then that fare is what it sells, so the filing wins a tie: a construction
     * that only adds up to the same amount is not a cheaper fare, it is a fare the carrier did not
     * publish. Filings are compared on their amounts alone and the first of the cheapest is taken.
     * Empty only when there is neither.
     */
    public static Optional<Fare> preferPublished(List<Fare> published, Optional<Constructed> built) {
        Optional<Fare> cheapest = published.stream().min(BY_AMOUNT);
        if (cheapest.isEmpty()) {
            return built.map(Constructed::fare);
        }
        if (built.isEmpty()) {
            return cheapest;
        }
        Fare filed = cheapest.get();
        Fare made = built.get().fare();
        return Optional.of(filed.amount().amount().compareTo(made.amount().amount()) <= 0 ? filed : made);
    }

    /**
     * Whether a constructed fare may not be doubled.
     *
     * <p>A carrier files round trip halves and files arbitraries as one way amounts, so a
     * construction that doubles is adding the arbitrary twice for a passenger who only drove to the
     * gateway once.
     */
    public static boolean oneWayOnly(Fare fare) {
        return fare.owrt() == OwRt.ONE_WAY_ONLY;
    }

    private static void check(Fare gatewayFare, AddOn addOn, End end) {
        if (addOn.end() != end) {
            throw new ConstructionError(
                    "that arbitrary is filed for the other end",
                    Details.of("filed", addOn.end().value(), "used", end.value()));
        }
        if (!addOn.carrier().equals(gatewayFare.carrier())) {
            throw new ConstructionError(
                    "an arbitrary belongs to its own carrier",
                    Details.of("addon", addOn.carrier(), "fare", gatewayFare.carrier()));
        }
        String wanted = end == End.ORIGIN ? gatewayFare.origin() : gatewayFare.destination();
        if (!addOn.gateway().equals(wanted)) {
            throw new ConstructionError(
                    "the arbitrary does not meet the fare",
                    Details.of("gateway", addOn.gateway(), "fare_point", wanted));
        }
        if (!addOn.amount().currency().equals(gatewayFare.amount().currency())) {
            throw new ConstructionError(
                    "an arbitrary is added in the fare's own currency",
                    Details.of(
                            "addon", addOn.amount().currency(),
                            "fare", gatewayFare.amount().currency()));
        }
    }

    /**
     * The constructed fare keeps the gateway's indicator.
     *
     * <p>An arbitrary is a short domestic hop at one end and does not change which ocean the journey
     * crossed. This is the one field where the obvious alternative, working it out again from the
     * new endpoints, gives the wrong answer: Nuremberg to New York over the Atlantic is still AT, and
     * re-deriving it from a domestic first sector is how a constructed fare ends up filed as EH.
     */
    private static GlobalIndicator indicator(Fare gatewayFare) {
        return gatewayFare.globalIndicator();
    }

    /** The construction, or empty when it is one a carrier does not price. */
    private static Optional<Constructed> priced(Fare fare, Optional<AddOn> left, Optional<AddOn> right) {
        try {
            return Optional.of(construct(fare, left, right));
        } catch (ConstructionError refused) {
            return Optional.empty();
        }
    }

    private static boolean isCheaper(Constructed made, Constructed best) {
        return made.fare().amount().amount().compareTo(best.fare().amount().amount()) < 0;
    }

    /** Each arbitrary in turn, then nothing at all. */
    private static List<Optional<AddOn>> orNothing(List<AddOn> found) {
        List<Optional<AddOn>> options = new ArrayList<>(found.size() + 1);
        found.forEach(addOn -> options.add(Optional.of(addOn)));
        options.add(Optional.empty());
        return options;
    }
}
