package io.github.rkaushikdev.gussetline.fcl;

import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.components.FareComponent;
import io.github.rkaushikdev.gussetline.components.PricingUnit;
import io.github.rkaushikdev.gussetline.construction.Construction;
import io.github.rkaushikdev.gussetline.flights.Segment;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.itinerary.Element;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The fare calculation line, which is the price written where a person reads it.
 *
 * <p>Every ticket carries one. It says which cities were priced against each other, which carrier
 * flew each hop, where the passenger merely connected rather than stopped, what each component cost
 * in neutral units, and what the whole came to before conversion. An agent who can read one can
 * check a price without a system, which is why the format has outlived every system that produced
 * it.
 *
 * <pre>FRA LH X/LON LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000</pre>
 *
 * <p>The rules that matter when writing one ({@link #render}). A connection is marked with {@code X/}
 * before the city and a stopover is not, so the line records the clock reading the itinerary made.
 * A Q surcharge is written separately in front of the amount, while a mileage surcharge is folded
 * into the amount and only its band is shown. And the rate of exchange is written to six decimals
 * whatever the currency does, because the line is checked by hand against the published rate.
 *
 * <p>The line is also read back ({@link #parse}, {@link #compare}), because suppliers send theirs
 * and the cheapest audit there is compares two lines token by token. Amounts on the line are written
 * to two decimals, ties going to the even neighbour, and every figure read back is exact.
 */
public final class Fcl {

    /** Charge reasons written in front of the amount rather than folded into it. */
    public static final List<String> SEPARATE = List.of("Q");

    /** Charge reasons that are surcharge bands, shown as a token and folded in. */
    public static final List<String> BANDS = List.of("M", "5M", "10M", "15M", "20M", "25M");

    private Fcl() {}

    /** {@link #renderComponent(FareComponent, Collection)} with no connection to mark. */
    public static String renderComponent(FareComponent component) {
        return renderComponent(component, List.of());
    }

    /**
     * One component's worth of the line, without the totals: {@code FRA LH X/LON LH NYC 441.64}.
     *
     * <p>The board point, then for each sector its marketing carrier (or {@code //} for a surface
     * sector) and the city it arrives at, marked {@code X/} when it is one of the connections given
     * and is not the component's last point. Then the Q surcharge as {@code Q30.00} when there is
     * one, the first mileage band charged, and the amount with every charge but the Q surcharge folded
     * in.
     *
     * @param connections the points the passenger only connected at, as airports or cities
     * @throws ConstructionError when the component has not been priced
     * @throws io.github.rkaushikdev.gussetline.ValidationError when a point is neither a city nor an
     *     airport
     */
    public static String renderComponent(FareComponent component, Collection<String> connections) {
        if (component.nuc().isEmpty()) {
            throw new ConstructionError("an unpriced component has no fare calculation");
        }

        Set<String> stopping = new HashSet<>();
        connections.forEach(point -> stopping.add(Geography.cityOf(point)));
        List<String> tokens = new ArrayList<>();
        tokens.add(component.boardPoint());

        List<Element> elements = component.elements();
        for (int index = 0; index < elements.size(); index++) {
            Element element = elements.get(index);
            tokens.add(element instanceof Segment segment ? segment.marketingCarrier() : "//");
            String point = Geography.cityOf(element.destination());
            boolean last = index == elements.size() - 1;
            tokens.add(!last && stopping.contains(point) ? "X/" + point : point);
        }

        BigDecimal separate =
                Numbers.sum(SEPARATE.stream().map(component::chargeFor).toList());
        if (separate.signum() > 0) {
            tokens.add("Q" + amount(separate));
        }

        component.notes().stream().filter(BANDS::contains).findFirst().ifPresent(tokens::add);

        tokens.add(amount(component.totalNuc().subtract(separate, Numbers.CONTEXT)));
        return String.join(" ", tokens);
    }

    /** {@link #render(Construction, BigDecimal, Collection)} with no connection to mark. */
    public static String render(Construction construction, BigDecimal roe) {
        return render(construction, roe, List.of());
    }

    /**
     * The whole line, components then the neutral total and the rate: {@code ... NUC883.28END
     * ROE0.951000}.
     *
     * <p>Every component after the first drops its board point, because the previous component
     * already ended there.
     *
     * @param roe the rate of exchange into the selling currency, written to six decimals
     * @param connections the points the passenger only connected at, as airports or cities
     * @throws ConstructionError naming the {@code roe} when it is zero or less
     */
    public static String render(Construction construction, BigDecimal roe, Collection<String> connections) {
        if (roe.signum() <= 0) {
            throw new ConstructionError("a rate of exchange is positive", Details.of("roe", roe.toString()));
        }

        List<String> parts = new ArrayList<>();
        for (PricingUnit unit : construction.units()) {
            for (FareComponent component : unit.components()) {
                String rendered = renderComponent(component, connections);
                parts.add(parts.isEmpty() ? rendered : rendered.substring(rendered.indexOf(' ') + 1));
            }
        }

        String body = String.join(" ", parts);
        return body + " NUC" + amount(construction.totalNuc()) + "END ROE" + Numbers.fixed(roe, 6);
    }

    /** How many amounts the line carries, which is how many components it has. */
    public static int componentsIn(String line) {
        return (int) Text.words(line).stream().filter(Fcl::looksLikeAmount).count();
    }

    /**
     * The neutral total the line claims, read back off it.
     *
     * @throws ConstructionError naming the {@code line} when it carries no {@code NUC...END} total
     * @throws NumberFormatException when the total is not a decimal
     */
    public static BigDecimal totalIn(String line) {
        for (String token : Text.words(line)) {
            if (isTotal(token)) {
                return total(token);
            }
        }
        throw new ConstructionError("no neutral total on the line", Details.of("line", line));
    }

    /**
     * The rate of exchange the line claims, read back off it.
     *
     * @throws ConstructionError naming the {@code line} when it carries no {@code ROE} token
     * @throws NumberFormatException when the rate is not a decimal
     */
    public static BigDecimal roeIn(String line) {
        for (String token : Text.words(line)) {
            if (token.startsWith("ROE")) {
                return Numbers.parseDecimal(token.substring(3));
            }
        }
        throw new ConstructionError("no rate of exchange on the line", Details.of("line", line));
    }

    /**
     * Whether the amounts on the line, Q surcharges included, add to the total it claims.
     *
     * <p>This is the cheapest audit there is and it catches a surprising amount. A supplier that
     * rebuilds a line after a manual fare override frequently forgets to rebuild the total. A token
     * that starts with {@code Q} and is not followed by a decimal is a carrier or a city, not a
     * surcharge, and is passed over.
     *
     * @throws ConstructionError as {@link #totalIn} does
     */
    public static boolean addsUp(String line) {
        BigDecimal claimed = totalIn(line);
        BigDecimal running = BigDecimal.ZERO;
        for (String token : Text.words(line)) {
            if (looksLikeAmount(token)) {
                running = running.add(Numbers.parseDecimal(token), Numbers.CONTEXT);
            } else if (token.startsWith("Q") && Text.length(token) > 1) {
                Optional<BigDecimal> surcharge = decimal(token.substring(1));
                if (surcharge.isPresent()) {
                    running = running.add(surcharge.get(), Numbers.CONTEXT);
                }
            }
        }
        return running.compareTo(claimed) == 0;
    }

    /**
     * Read a fare calculation line into its parts.
     *
     * <p>Written for lines other systems produced, so it is forgiving about spacing and refuses only
     * on the two things it cannot guess at, which are a missing neutral total and a missing rate of
     * exchange. A component whose shape it does not recognise is still returned, because a line the
     * desk cannot fully read is exactly the line worth looking at by hand.
     *
     * <p>Each amount closes a component, which starts where the previous one ended. A token of three
     * upper case letters or digits that is not all digits is a city, {@code X/} marks a connection,
     * {@code //} a surface sector, {@code Q} and a digit a Q surcharge, and a band such as {@code 5M}
     * the band; anything else is taken for a carrier.
     *
     * @throws ConstructionError when the line is empty, or carries no neutral total or no rate of
     *     exchange (naming the {@code line})
     * @throws NumberFormatException when the total, the rate or a Q surcharge is not a decimal
     */
    public static ParsedLine parse(String line) {
        List<String> tokens = Text.words(line.replace("//", " // "));
        if (tokens.isEmpty()) {
            throw new ConstructionError("nothing to parse");
        }

        BigDecimal total = null;
        BigDecimal roe = null;
        List<String> body = new ArrayList<>();
        for (String token : tokens) {
            if (isTotal(token)) {
                total = total(token);
            } else if (token.startsWith("ROE")) {
                roe = Numbers.parseDecimal(token.substring(3));
            } else {
                body.add(token);
            }
        }

        if (total == null) {
            throw new ConstructionError("no neutral total on the line", Details.of("line", line));
        }
        if (roe == null) {
            throw new ConstructionError("no rate of exchange on the line", Details.of("line", line));
        }

        List<ParsedComponent> components = new ArrayList<>();
        List<String> points = new ArrayList<>();
        List<String> carriers = new ArrayList<>();
        List<String> connections = new ArrayList<>();
        BigDecimal qAmount = BigDecimal.ZERO;
        String band = "";

        for (String token : body) {
            if (looksLikeAmount(token)) {
                if (!points.isEmpty()) {
                    components.add(new ParsedComponent(
                            points, carriers, Numbers.parseDecimal(token), qAmount, band, connections));
                }
                String carry = points.isEmpty() ? "" : points.get(points.size() - 1);
                points = new ArrayList<>();
                if (!carry.isEmpty()) {
                    points.add(carry);
                }
                carriers = new ArrayList<>();
                connections = new ArrayList<>();
                qAmount = BigDecimal.ZERO;
                band = "";
            } else if (token.startsWith("Q") && Text.length(token) > 1 && Text.isDigits(Text.slice(token, 1, 2))) {
                qAmount = Numbers.parseDecimal(token.substring(1));
            } else if (BANDS.contains(token)) {
                band = token;
            } else if (token.equals("//")) {
                carriers.add("//");
            } else if (token.startsWith("X/")) {
                points.add(token.substring(2));
                connections.add(token.substring(2));
            } else if (isCity(token)) {
                points.add(token);
            } else {
                carriers.add(token);
            }
        }

        return new ParsedLine(components, total, roe, line);
    }

    /**
     * Every way two lines disagree, said in words.
     *
     * <p>Returns an empty list when they agree, which is the only shape a caller should treat as a
     * pass. Anything else is a finding, and the sentences go straight into the discrepancy the desk
     * sends the supplier: the neutral total, the rate, then, when both lines have as many components,
     * each component's route, amount, Q surcharge and band. A different number of components is
     * reported and the comparison stops there.
     *
     * @throws ConstructionError as {@link #parse} does, for either line
     */
    public static List<String> compare(String ours, String theirs) {
        ParsedLine mine = parse(ours);
        ParsedLine yours = parse(theirs);
        List<String> found = new ArrayList<>();

        if (mine.total().compareTo(yours.total()) != 0) {
            found.add("neutral total " + mine.total() + " against " + yours.total());
        }
        if (mine.roe().compareTo(yours.roe()) != 0) {
            found.add("rate of exchange " + mine.roe() + " against " + yours.roe());
        }
        if (mine.components().size() != yours.components().size()) {
            found.add(mine.components().size() + " components against "
                    + yours.components().size());
            return List.copyOf(found);
        }

        for (int index = 0; index < mine.components().size(); index++) {
            ParsedComponent left = mine.components().get(index);
            ParsedComponent right = yours.components().get(index);
            String where = "component " + (index + 1);
            if (!left.points().equals(right.points())) {
                found.add(where + " routes " + String.join("-", left.points()) + " against "
                        + String.join("-", right.points()));
            }
            if (left.amount().compareTo(right.amount()) != 0) {
                found.add(where + " is " + left.amount() + " against " + right.amount());
            }
            if (left.qSurcharge().compareTo(right.qSurcharge()) != 0) {
                found.add(where + " q surcharge " + left.qSurcharge() + " against " + right.qSurcharge());
            }
            if (!left.band().equals(right.band())) {
                found.add(where + " band " + orNone(left.band()) + " against " + orNone(right.band()));
            }
        }
        return List.copyOf(found);
    }

    /** An amount the way the line writes it: two decimals, ties to the even neighbour. */
    private static String amount(BigDecimal value) {
        return Numbers.fixed(value, 2);
    }

    /** Digits, a point, then exactly two digits, and not a Q surcharge. */
    private static boolean looksLikeAmount(String token) {
        if (token.startsWith("Q")) {
            return false;
        }
        int dot = token.indexOf('.');
        if (dot < 0) {
            return false;
        }
        String head = token.substring(0, dot);
        String tail = token.substring(dot + 1);
        return Text.isDigits(head) && Text.length(tail) == 2 && Text.isDigits(tail);
    }

    private static boolean isTotal(String token) {
        return token.startsWith("NUC") && token.endsWith("END");
    }

    private static BigDecimal total(String token) {
        return Numbers.parseDecimal(token.substring(3, token.length() - 3));
    }

    /** Three upper case letters or digits, not all of them digits: a city code. */
    private static boolean isCity(String token) {
        return Text.length(token) == 3 && Text.isLettersOrDigits(token) && Text.isUpper(token) && !Text.isDigits(token);
    }

    private static Optional<BigDecimal> decimal(String text) {
        try {
            return Optional.of(Numbers.parseDecimal(text));
        } catch (NumberFormatException notADecimal) {
            return Optional.empty();
        }
    }

    private static String orNone(String band) {
        return band.isEmpty() ? "none" : band;
    }
}
