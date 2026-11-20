package io.github.rkaushikdev.gussetline.carriers;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Carriers, and the difference between flying together and ticketing together.
 *
 * <p>Two carriers in the same alliance do not necessarily interline, and two carriers that
 * interline are frequently in no alliance at all. The desk cares about interline because a fare on
 * one carrier's ticket stock cannot carry a sector the validating carrier has no agreement for, and
 * the supplier will happily quote it anyway.
 *
 * <p>Codes are the two character designators, which are not all letters. A carrier code is
 * compared as an opaque string and never uppercased twice, because a lowercase designator has
 * already been through somebody else's parser.
 */
public final class Carriers {

    private static final Pattern CODE = Pattern.compile("[0-9A-Z][0-9A-Z]");

    /** The carriers the desk knows, by designator, in a settled order. */
    public static final Map<String, Carrier> CARRIERS = index(List.of(
            carrier("LH", "Lufthansa", "DE", Alliance.STAR, false),
            carrier("LX", "Swiss", "CH", Alliance.STAR, false),
            carrier("OS", "Austrian", "AT", Alliance.STAR, false),
            carrier("SN", "Brussels Airlines", "BE", Alliance.STAR, false),
            carrier("SK", "SAS", "SE", Alliance.STAR, false),
            carrier("TP", "TAP Portugal", "PT", Alliance.STAR, false),
            carrier("LO", "LOT", "PL", Alliance.STAR, false),
            carrier("TK", "Turkish Airlines", "TR", Alliance.STAR, false),
            carrier("AC", "Air Canada", "CA", Alliance.STAR, false),
            carrier("UA", "United", "US", Alliance.STAR, false),
            carrier("NH", "All Nippon Airways", "JP", Alliance.STAR, false),
            carrier("OZ", "Asiana", "KR", Alliance.STAR, false),
            carrier("SQ", "Singapore Airlines", "SG", Alliance.STAR, false),
            carrier("TG", "Thai Airways", "TH", Alliance.STAR, false),
            carrier("AI", "Air India", "IN", Alliance.STAR, false),
            carrier("ET", "Ethiopian", "KE", Alliance.STAR, false),
            carrier("SA", "South African", "ZA", Alliance.STAR, false),
            carrier("NZ", "Air New Zealand", "NZ", Alliance.STAR, false),
            carrier("BA", "British Airways", "GB", Alliance.ONEWORLD, false),
            carrier("IB", "Iberia", "ES", Alliance.ONEWORLD, false),
            carrier("AY", "Finnair", "FI", Alliance.ONEWORLD, false),
            carrier("EI", "Aer Lingus", "IE", Alliance.NONE, false),
            carrier("AA", "American Airlines", "US", Alliance.ONEWORLD, false),
            carrier("QF", "Qantas", "AU", Alliance.ONEWORLD, false),
            carrier("CX", "Cathay Pacific", "HK", Alliance.ONEWORLD, false),
            carrier("JL", "Japan Airlines", "JP", Alliance.ONEWORLD, false),
            carrier("QR", "Qatar Airways", "QA", Alliance.ONEWORLD, false),
            carrier("RJ", "Royal Jordanian", "IL", Alliance.ONEWORLD, false),
            carrier("MH", "Malaysia Airlines", "MY", Alliance.ONEWORLD, false),
            carrier("UL", "SriLankan", "LK", Alliance.ONEWORLD, false),
            carrier("AF", "Air France", "FR", Alliance.SKYTEAM, false),
            carrier("KL", "KLM", "NL", Alliance.SKYTEAM, false),
            carrier("AZ", "ITA Airways", "IT", Alliance.SKYTEAM, false),
            carrier("DL", "Delta", "US", Alliance.SKYTEAM, false),
            carrier("KE", "Korean Air", "KR", Alliance.SKYTEAM, false),
            carrier("MU", "China Eastern", "CN", Alliance.SKYTEAM, false),
            carrier("CI", "China Airlines", "CN", Alliance.SKYTEAM, false),
            carrier("VN", "Vietnam Airlines", "VN", Alliance.SKYTEAM, false),
            carrier("SV", "Saudia", "AE", Alliance.SKYTEAM, false),
            carrier("KQ", "Kenya Airways", "KE", Alliance.SKYTEAM, false),
            carrier("AM", "Aeromexico", "MX", Alliance.SKYTEAM, false),
            carrier("EK", "Emirates", "AE", Alliance.NONE, false),
            carrier("EY", "Etihad", "AE", Alliance.NONE, false),
            carrier("VS", "Virgin Atlantic", "GB", Alliance.NONE, false),
            carrier("LY", "El Al", "IL", Alliance.NONE, false),
            carrier("GF", "Gulf Air", "BH", Alliance.NONE, false),
            carrier("6E", "IndiGo", "IN", Alliance.NONE, true),
            carrier("FR", "Ryanair", "IE", Alliance.NONE, true),
            carrier("U2", "easyJet", "GB", Alliance.NONE, true),
            carrier("W6", "Wizz Air", "HU", Alliance.NONE, true),
            carrier("VY", "Vueling", "ES", Alliance.NONE, true),
            carrier("DY", "Norwegian", "NO", Alliance.NONE, true),
            carrier("PC", "Pegasus", "TR", Alliance.NONE, true),
            carrier("3K", "Jetstar Asia", "SG", Alliance.NONE, true),
            carrier("AK", "AirAsia", "MY", Alliance.NONE, true),
            carrier("G3", "Gol", "BR", Alliance.NONE, true)));

    /**
     * Interline agreements the desk holds paper on. The pairs are unordered. Anything not listed
     * and not inside one alliance has no agreement, which is the safe default because quoting a
     * sector we cannot ticket costs an agent an hour on the phone.
     */
    private static final Set<Set<String>> INTERLINE_PAIRS = Set.of(
            Set.of("LH", "EK"),
            Set.of("LH", "EY"),
            Set.of("BA", "EI"),
            Set.of("BA", "AY"),
            Set.of("AF", "EK"),
            Set.of("KL", "EK"),
            Set.of("EK", "QF"),
            Set.of("EY", "AF"),
            Set.of("VS", "DL"),
            Set.of("VS", "AF"),
            Set.of("LY", "LH"),
            Set.of("GF", "BA"),
            Set.of("6E", "TK"),
            Set.of("6E", "QR"),
            Set.of("UL", "EK"));

    private Carriers() {}

    /**
     * Look a carrier up by designator.
     *
     * @throws ValidationError when the designator is malformed or names no carrier we hold
     */
    public static Carrier carrier(String code) {
        Carrier found = CARRIERS.get(normaliseCode(code));
        if (found == null) {
            throw new ValidationError("unknown carrier", Details.of("code", code));
        }
        return found;
    }

    /**
     * Uppercase and check the shape, without inventing a carrier.
     *
     * @throws ValidationError when the code is not two letters or digits
     */
    public static String normaliseCode(String code) {
        String key = Text.code(code);
        if (!CODE.matcher(key).matches()) {
            throw new ValidationError("not a carrier designator", Details.of("code", code));
        }
        return key;
    }

    /** Whether the code is a well formed designator of a carrier we hold. */
    public static boolean isKnown(String code) {
        try {
            return CARRIERS.containsKey(normaliseCode(code));
        } catch (ValidationError malformed) {
            return false;
        }
    }

    /** The alliance a carrier flies in. */
    public static Alliance allianceOf(String code) {
        return carrier(code).alliance();
    }

    /** Two carriers in the same alliance. Unaligned carriers share nothing. */
    public static boolean sameAlliance(String left, String right) {
        Carrier one = carrier(left);
        Carrier two = carrier(right);
        if (one.alliance() == Alliance.NONE || two.alliance() == Alliance.NONE) {
            return false;
        }
        return one.alliance() == two.alliance();
    }

    /**
     * Whether one carrier can carry the other's coupon.
     *
     * <p>A carrier always interlines with itself. Alliance membership implies an agreement.
     * Everything else has to be on the paper list.
     *
     * @throws ValidationError when either code is malformed or names no carrier we hold
     */
    public static boolean interlines(String left, String right) {
        String one = normaliseCode(left);
        String two = normaliseCode(right);
        if (one.equals(two)) {
            carrier(one);
            return true;
        }
        if (sameAlliance(one, two)) {
            return true;
        }
        return INTERLINE_PAIRS.contains(Set.of(one, two));
    }

    /** The designators in an alliance, sorted. */
    public static List<String> members(Alliance alliance) {
        return CARRIERS.values().stream()
                .filter(c -> c.alliance() == alliance)
                .map(Carrier::code)
                .sorted()
                .toList();
    }

    /** The low cost carriers, sorted. */
    public static List<String> lowCostCarriers() {
        return CARRIERS.values().stream()
                .filter(Carrier::lowCost)
                .map(Carrier::code)
                .sorted()
                .toList();
    }

    private static Carrier carrier(String code, String name, String country, Alliance alliance, boolean lowCost) {
        return new Carrier(code, name, country, alliance, lowCost);
    }

    private static Map<String, Carrier> index(List<Carrier> rows) {
        Map<String, Carrier> table = new LinkedHashMap<>();
        for (Carrier row : rows) {
            table.put(row.code(), row);
        }
        return Collections.unmodifiableMap(table);
    }
}
