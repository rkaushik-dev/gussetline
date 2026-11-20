package io.github.rkaushikdev.gussetline.geography;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Where places are, in the terms the tariff uses.
 *
 * <p>The tariff does not know about continents. It knows about three IATA areas and the sub areas
 * inside them, and almost every rule that says "not applicable for travel between" is written
 * against those and not against countries. A fare between Frankfurt and Tokyo is an Area 2 to Area
 * 3 fare and the direction it takes around the planet decides which published fare applies at all,
 * so the global indicator is not a label, it is part of the key.
 *
 * <p>Metro codes matter for the same reason. LON is not an airport, and a fare published LON to NYC
 * prices out of Gatwick into Newark without anybody mentioning either.
 */
public final class Geography {

    /** The countries the desk knows, by code, in a settled order. */
    public static final Map<String, Country> COUNTRIES = index(
            List.of(
                    country("DE", "Germany", SubArea.EUROPE, "EUR"),
                    country("FR", "France", SubArea.EUROPE, "EUR"),
                    country("GB", "United Kingdom", SubArea.EUROPE, "GBP"),
                    country("IE", "Ireland", SubArea.EUROPE, "EUR"),
                    country("NL", "Netherlands", SubArea.EUROPE, "EUR"),
                    country("BE", "Belgium", SubArea.EUROPE, "EUR"),
                    country("CH", "Switzerland", SubArea.EUROPE, "CHF"),
                    country("AT", "Austria", SubArea.EUROPE, "EUR"),
                    country("IT", "Italy", SubArea.EUROPE, "EUR"),
                    country("ES", "Spain", SubArea.EUROPE, "EUR"),
                    country("PT", "Portugal", SubArea.EUROPE, "EUR"),
                    country("SE", "Sweden", SubArea.EUROPE, "SEK"),
                    country("NO", "Norway", SubArea.EUROPE, "NOK"),
                    country("DK", "Denmark", SubArea.EUROPE, "DKK"),
                    country("FI", "Finland", SubArea.EUROPE, "EUR"),
                    country("PL", "Poland", SubArea.EUROPE, "PLN"),
                    country("CZ", "Czechia", SubArea.EUROPE, "CZK"),
                    country("HU", "Hungary", SubArea.EUROPE, "HUF"),
                    country("TR", "Turkiye", SubArea.EUROPE, "TRY"),
                    country("RU", "Russia", SubArea.EUROPE, "EUR"),
                    country("US", "United States", SubArea.NORTH_AMERICA, "USD"),
                    country("CA", "Canada", SubArea.NORTH_AMERICA, "CAD"),
                    country("MX", "Mexico", SubArea.CENTRAL_AMERICA, "MXN"),
                    country("BR", "Brazil", SubArea.SOUTH_AMERICA, "BRL"),
                    country("AR", "Argentina", SubArea.SOUTH_AMERICA, "USD"),
                    country("CL", "Chile", SubArea.SOUTH_AMERICA, "USD"),
                    country("PE", "Peru", SubArea.SOUTH_AMERICA, "USD"),
                    country("CO", "Colombia", SubArea.SOUTH_AMERICA, "USD"),
                    country("BB", "Barbados", SubArea.CARIBBEAN, "USD"),
                    country("ZA", "South Africa", SubArea.AFRICA, "ZAR"),
                    country("KE", "Kenya", SubArea.AFRICA, "USD"),
                    country("EG", "Egypt", SubArea.AFRICA, "USD"),
                    country("MA", "Morocco", SubArea.AFRICA, "EUR"),
                    country("NG", "Nigeria", SubArea.AFRICA, "USD"),
                    country("AE", "United Arab Emirates", SubArea.MIDDLE_EAST, "AED"),
                    country("QA", "Qatar", SubArea.MIDDLE_EAST, "USD"),
                    country("KW", "Kuwait", SubArea.MIDDLE_EAST, "KWD"),
                    country("BH", "Bahrain", SubArea.MIDDLE_EAST, "BHD"),
                    country("IL", "Israel", SubArea.MIDDLE_EAST, "USD"),
                    country("IN", "India", SubArea.SOUTH_ASIA, "INR"),
                    country("PK", "Pakistan", SubArea.SOUTH_ASIA, "USD"),
                    country("LK", "Sri Lanka", SubArea.SOUTH_ASIA, "USD"),
                    country("SG", "Singapore", SubArea.SOUTH_EAST_ASIA, "SGD"),
                    country("TH", "Thailand", SubArea.SOUTH_EAST_ASIA, "THB"),
                    country("MY", "Malaysia", SubArea.SOUTH_EAST_ASIA, "USD"),
                    country("ID", "Indonesia", SubArea.SOUTH_EAST_ASIA, "USD"),
                    country("VN", "Vietnam", SubArea.SOUTH_EAST_ASIA, "USD"),
                    country("PH", "Philippines", SubArea.SOUTH_EAST_ASIA, "USD"),
                    country("HK", "Hong Kong", SubArea.SOUTH_EAST_ASIA, "HKD"),
                    country("CN", "China", SubArea.SOUTH_EAST_ASIA, "CNY"),
                    country("JP", "Japan", SubArea.JAPAN_KOREA, "JPY"),
                    country("KR", "Korea", SubArea.JAPAN_KOREA, "KRW"),
                    country("AU", "Australia", SubArea.SOUTH_WEST_PACIFIC, "AUD"),
                    country("NZ", "New Zealand", SubArea.SOUTH_WEST_PACIFIC, "NZD")),
            Country::code);

    /** The airports the desk knows, by code, in a settled order. */
    public static final Map<String, Airport> AIRPORTS = index(
            List.of(
                    airport("FRA", "Frankfurt am Main", "FRA", "DE", 50.033, 8.57, 60),
                    airport("MUC", "Munich", "MUC", "DE", 48.354, 11.786, 60),
                    airport("TXL", "Berlin Tegel", "BER", "DE", 52.559, 13.288, 60),
                    airport("BER", "Berlin Brandenburg", "BER", "DE", 52.362, 13.501, 60),
                    airport("DUS", "Dusseldorf", "DUS", "DE", 51.289, 6.767, 60),
                    airport("HAM", "Hamburg", "HAM", "DE", 53.63, 9.988, 60),
                    airport("CDG", "Paris Charles de Gaulle", "PAR", "FR", 49.01, 2.548, 60),
                    airport("ORY", "Paris Orly", "PAR", "FR", 48.726, 2.365, 60),
                    airport("NCE", "Nice", "NCE", "FR", 43.658, 7.216, 60),
                    airport("LHR", "London Heathrow", "LON", "GB", 51.47, -0.454, 0),
                    airport("LGW", "London Gatwick", "LON", "GB", 51.148, -0.19, 0),
                    airport("LCY", "London City", "LON", "GB", 51.505, 0.055, 0),
                    airport("STN", "London Stansted", "LON", "GB", 51.885, 0.235, 0),
                    airport("MAN", "Manchester", "MAN", "GB", 53.365, -2.273, 0),
                    airport("EDI", "Edinburgh", "EDI", "GB", 55.95, -3.372, 0),
                    airport("DUB", "Dublin", "DUB", "IE", 53.421, -6.27, 0),
                    airport("AMS", "Amsterdam Schiphol", "AMS", "NL", 52.309, 4.764, 60),
                    airport("BRU", "Brussels", "BRU", "BE", 50.901, 4.484, 60),
                    airport("ZRH", "Zurich", "ZRH", "CH", 47.464, 8.549, 60),
                    airport("GVA", "Geneva", "GVA", "CH", 46.238, 6.109, 60),
                    airport("VIE", "Vienna", "VIE", "AT", 48.11, 16.57, 60),
                    airport("FCO", "Rome Fiumicino", "ROM", "IT", 41.8, 12.239, 60),
                    airport("MXP", "Milan Malpensa", "MIL", "IT", 45.63, 8.723, 60),
                    airport("LIN", "Milan Linate", "MIL", "IT", 45.445, 9.277, 60),
                    airport("MAD", "Madrid Barajas", "MAD", "ES", 40.472, -3.561, 60),
                    airport("BCN", "Barcelona", "BCN", "ES", 41.297, 2.078, 60),
                    airport("LIS", "Lisbon", "LIS", "PT", 38.774, -9.134, 0),
                    airport("ARN", "Stockholm Arlanda", "STO", "SE", 59.652, 17.919, 60),
                    airport("OSL", "Oslo Gardermoen", "OSL", "NO", 60.194, 11.1, 60),
                    airport("CPH", "Copenhagen", "CPH", "DK", 55.618, 12.656, 60),
                    airport("HEL", "Helsinki", "HEL", "FI", 60.317, 24.963, 120),
                    airport("WAW", "Warsaw Chopin", "WAW", "PL", 52.166, 20.967, 60),
                    airport("PRG", "Prague", "PRG", "CZ", 50.101, 14.26, 60),
                    airport("BUD", "Budapest", "BUD", "HU", 47.437, 19.256, 60),
                    airport("IST", "Istanbul", "IST", "TR", 41.262, 28.742, 180),
                    airport("SVO", "Moscow Sheremetyevo", "MOW", "RU", 55.973, 37.415, 180),
                    airport("JFK", "New York Kennedy", "NYC", "US", 40.64, -73.779, -300),
                    airport("EWR", "Newark", "NYC", "US", 40.69, -74.177, -300),
                    airport("LGA", "New York LaGuardia", "NYC", "US", 40.777, -73.872, -300),
                    airport("BOS", "Boston Logan", "BOS", "US", 42.363, -71.006, -300),
                    airport("IAD", "Washington Dulles", "WAS", "US", 38.947, -77.46, -300),
                    airport("ORD", "Chicago O Hare", "CHI", "US", 41.978, -87.905, -360),
                    airport("DFW", "Dallas Fort Worth", "DFW", "US", 32.897, -97.038, -360),
                    airport("IAH", "Houston Intercontinental", "HOU", "US", 29.984, -95.341, -360),
                    airport("MIA", "Miami", "MIA", "US", 25.795, -80.287, -300),
                    airport("ATL", "Atlanta", "ATL", "US", 33.64, -84.427, -300),
                    airport("DEN", "Denver", "DEN", "US", 39.856, -104.674, -420),
                    airport("LAX", "Los Angeles", "LAX", "US", 33.942, -118.408, -480),
                    airport("SFO", "San Francisco", "SFO", "US", 37.619, -122.375, -480),
                    airport("SEA", "Seattle Tacoma", "SEA", "US", 47.449, -122.309, -480),
                    airport("YYZ", "Toronto Pearson", "YTO", "CA", 43.677, -79.63, -300),
                    airport("YVR", "Vancouver", "YVR", "CA", 49.194, -123.184, -480),
                    airport("MEX", "Mexico City", "MEX", "MX", 19.436, -99.072, -360),
                    airport("GRU", "Sao Paulo Guarulhos", "SAO", "BR", -23.435, -46.473, -180),
                    airport("GIG", "Rio de Janeiro", "RIO", "BR", -22.809, -43.25, -180),
                    airport("EZE", "Buenos Aires Ezeiza", "BUE", "AR", -34.822, -58.536, -180),
                    airport("SCL", "Santiago", "SCL", "CL", -33.393, -70.786, -240),
                    airport("LIM", "Lima", "LIM", "PE", -12.022, -77.114, -300),
                    airport("BOG", "Bogota", "BOG", "CO", 4.702, -74.147, -300),
                    airport("BGI", "Bridgetown", "BGI", "BB", 13.075, -59.492, -240),
                    airport("JNB", "Johannesburg", "JNB", "ZA", -26.139, 28.246, 120),
                    airport("CPT", "Cape Town", "CPT", "ZA", -33.969, 18.602, 120),
                    airport("NBO", "Nairobi", "NBO", "KE", -1.319, 36.928, 180),
                    airport("CAI", "Cairo", "CAI", "EG", 30.112, 31.406, 120),
                    airport("CMN", "Casablanca", "CMN", "MA", 33.367, -7.59, 60),
                    airport("LOS", "Lagos", "LOS", "NG", 6.577, 3.321, 60),
                    airport("DXB", "Dubai", "DXB", "AE", 25.253, 55.365, 240),
                    airport("AUH", "Abu Dhabi", "AUH", "AE", 24.433, 54.651, 240),
                    airport("DOH", "Doha", "DOH", "QA", 25.261, 51.565, 180),
                    airport("KWI", "Kuwait", "KWI", "KW", 29.227, 47.969, 180),
                    airport("BAH", "Bahrain", "BAH", "BH", 26.271, 50.634, 180),
                    airport("TLV", "Tel Aviv", "TLV", "IL", 32.011, 34.887, 120),
                    airport("DEL", "Delhi", "DEL", "IN", 28.556, 77.1, 330),
                    airport("BOM", "Mumbai", "BOM", "IN", 19.089, 72.868, 330),
                    airport("BLR", "Bengaluru", "BLR", "IN", 13.199, 77.71, 330),
                    airport("MAA", "Chennai", "MAA", "IN", 12.99, 80.169, 330),
                    airport("KHI", "Karachi", "KHI", "PK", 24.907, 67.161, 300),
                    airport("CMB", "Colombo", "CMB", "LK", 7.181, 79.884, 330),
                    airport("SIN", "Singapore Changi", "SIN", "SG", 1.364, 103.991, 480),
                    airport("BKK", "Bangkok Suvarnabhumi", "BKK", "TH", 13.69, 100.75, 420),
                    airport("KUL", "Kuala Lumpur", "KUL", "MY", 2.746, 101.71, 480),
                    airport("CGK", "Jakarta", "JKT", "ID", -6.126, 106.656, 420),
                    airport("SGN", "Ho Chi Minh City", "SGN", "VN", 10.819, 106.652, 420),
                    airport("MNL", "Manila", "MNL", "PH", 14.509, 121.02, 480),
                    airport("HKG", "Hong Kong", "HKG", "HK", 22.309, 113.915, 480),
                    airport("PVG", "Shanghai Pudong", "SHA", "CN", 31.143, 121.805, 480),
                    airport("PEK", "Beijing Capital", "BJS", "CN", 40.08, 116.585, 480),
                    airport("CAN", "Guangzhou", "CAN", "CN", 23.392, 113.299, 480),
                    airport("NRT", "Tokyo Narita", "TYO", "JP", 35.765, 140.386, 540),
                    airport("HND", "Tokyo Haneda", "TYO", "JP", 35.553, 139.781, 540),
                    airport("KIX", "Osaka Kansai", "OSA", "JP", 34.427, 135.244, 540),
                    airport("ICN", "Seoul Incheon", "SEL", "KR", 37.469, 126.451, 540),
                    airport("SYD", "Sydney", "SYD", "AU", -33.946, 151.177, 600),
                    airport("MEL", "Melbourne", "MEL", "AU", -37.669, 144.841, 600),
                    airport("BNE", "Brisbane", "BNE", "AU", -27.384, 153.117, 600),
                    airport("PER", "Perth", "PER", "AU", -31.94, 115.967, 480),
                    airport("AKL", "Auckland", "AKL", "NZ", -37.008, 174.792, 720)),
            Airport::code);

    /** Each city code and its airports, sorted. Derived from {@link #AIRPORTS}, never typed twice. */
    public static final Map<String, List<String>> METROS = metros();

    private Geography() {}

    /**
     * Look one airport up, ignoring case and surrounding white space. Metro codes are not airports
     * and are refused.
     *
     * @throws ValidationError when the code is not an airport we hold
     */
    public static Airport airport(String code) {
        Airport found = AIRPORTS.get(Text.code(code));
        if (found == null) {
            throw new ValidationError("unknown airport", Details.of("code", code));
        }
        return found;
    }

    /**
     * Look one country up, ignoring case and surrounding white space.
     *
     * @throws ValidationError when the code is not a country we hold
     */
    public static Country country(String code) {
        Country found = COUNTRIES.get(Text.code(code));
        if (found == null) {
            throw new ValidationError("unknown country", Details.of("code", code));
        }
        return found;
    }

    /**
     * The city a point prices as. A metro code answers for itself.
     *
     * @throws ValidationError when the code is neither a city nor an airport
     */
    public static String cityOf(String code) {
        String key = Text.code(code);
        if (METROS.containsKey(key)) {
            return key;
        }
        return airport(key).city();
    }

    /**
     * The airports of a city, sorted, or the one airport a standalone airport code names.
     *
     * @throws ValidationError when the code is neither a city nor an airport
     */
    public static List<String> airportsIn(String city) {
        String key = Text.code(city);
        List<String> members = METROS.get(key);
        if (members != null) {
            return members;
        }
        if (AIRPORTS.containsKey(key)) {
            return List.of(key);
        }
        throw new ValidationError("unknown city or airport", Details.of("code", city));
    }

    /** The sub area of a point, given as an airport or a city. */
    public static SubArea subAreaOf(String code) {
        return country(airportOrCityCountry(code)).subArea();
    }

    /** The area of a point, given as an airport or a city. */
    public static Area areaOf(String code) {
        return country(airportOrCityCountry(code)).area();
    }

    /** The country of a point, whether it was given as an airport or a city. */
    public static String airportOrCityCountry(String code) {
        String key = Text.code(code);
        Airport found = AIRPORTS.get(key);
        if (found != null) {
            return found.country();
        }
        return AIRPORTS.get(airportsIn(key).get(0)).country();
    }

    /** Whether two points, airports or cities, are in one country. */
    public static boolean sameCountry(String left, String right) {
        return airportOrCityCountry(left).equals(airportOrCityCountry(right));
    }

    private static Country country(String code, String name, SubArea subArea, String currency) {
        return new Country(code, name, subArea, currency);
    }

    private static Airport airport(
            String code, String name, String city, String country, double latitude, double longitude, int offset) {
        return new Airport(code, name, city, country, latitude, longitude, offset);
    }

    private static <T> Map<String, T> index(List<T> rows, Function<T, String> code) {
        Map<String, T> table = new LinkedHashMap<>();
        for (T row : rows) {
            table.put(code.apply(row), row);
        }
        return Collections.unmodifiableMap(table);
    }

    private static Map<String, List<String>> metros() {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (Airport row : AIRPORTS.values()) {
            grouped.computeIfAbsent(row.city(), city -> new ArrayList<>()).add(row.code());
        }
        Map<String, List<String>> sorted = new LinkedHashMap<>();
        grouped.forEach(
                (city, codes) -> sorted.put(city, codes.stream().sorted().toList()));
        return Collections.unmodifiableMap(sorted);
    }
}
