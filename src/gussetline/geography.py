"""Where places are, in the terms the tariff uses.

The tariff does not know about continents. It knows about three IATA areas and
the sub areas inside them, and almost every rule that says "not applicable for
travel between" is written against those and not against countries. A fare
between Frankfurt and Tokyo is an Area 2 to Area 3 fare and the direction it
takes around the planet decides which published fare applies at all, so the
global indicator is not a label, it is part of the key.

Metro codes matter for the same reason. LON is not an airport, and a fare
published LON to NYC prices out of Gatwick into Newark without anybody
mentioning either.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from gussetline.errors import ValidationError


class Area(str, Enum):
    """The three IATA traffic conference areas."""

    ONE = "1"
    TWO = "2"
    THREE = "3"


class SubArea(str, Enum):
    """The sub areas rules actually name."""

    NORTH_AMERICA = "NA"
    CENTRAL_AMERICA = "CA"
    SOUTH_AMERICA = "SA"
    CARIBBEAN = "CB"
    EUROPE = "EU"
    AFRICA = "AF"
    MIDDLE_EAST = "ME"
    SOUTH_ASIA = "SS"
    SOUTH_EAST_ASIA = "SE"
    JAPAN_KOREA = "JK"
    SOUTH_WEST_PACIFIC = "SW"


AREA_OF_SUBAREA: dict[SubArea, Area] = {
    SubArea.NORTH_AMERICA: Area.ONE,
    SubArea.CENTRAL_AMERICA: Area.ONE,
    SubArea.SOUTH_AMERICA: Area.ONE,
    SubArea.CARIBBEAN: Area.ONE,
    SubArea.EUROPE: Area.TWO,
    SubArea.AFRICA: Area.TWO,
    SubArea.MIDDLE_EAST: Area.TWO,
    SubArea.SOUTH_ASIA: Area.THREE,
    SubArea.SOUTH_EAST_ASIA: Area.THREE,
    SubArea.JAPAN_KOREA: Area.THREE,
    SubArea.SOUTH_WEST_PACIFIC: Area.THREE,
}


@dataclass(frozen=True, slots=True)
class Country:
    code: str
    name: str
    sub_area: SubArea
    currency: str

    @property
    def area(self) -> Area:
        return AREA_OF_SUBAREA[self.sub_area]


@dataclass(frozen=True, slots=True)
class Airport:
    """One airport, and the city it prices as."""

    code: str
    name: str
    city: str
    country: str
    latitude: float
    longitude: float
    utc_offset_minutes: int

    @property
    def is_metro(self) -> bool:
        return self.code == self.city


_COUNTRY_ROWS: tuple[tuple[str, str, SubArea, str], ...] = (
    ("DE", "Germany", SubArea.EUROPE, "EUR"),
    ("FR", "France", SubArea.EUROPE, "EUR"),
    ("GB", "United Kingdom", SubArea.EUROPE, "GBP"),
    ("IE", "Ireland", SubArea.EUROPE, "EUR"),
    ("NL", "Netherlands", SubArea.EUROPE, "EUR"),
    ("BE", "Belgium", SubArea.EUROPE, "EUR"),
    ("CH", "Switzerland", SubArea.EUROPE, "CHF"),
    ("AT", "Austria", SubArea.EUROPE, "EUR"),
    ("IT", "Italy", SubArea.EUROPE, "EUR"),
    ("ES", "Spain", SubArea.EUROPE, "EUR"),
    ("PT", "Portugal", SubArea.EUROPE, "EUR"),
    ("SE", "Sweden", SubArea.EUROPE, "SEK"),
    ("NO", "Norway", SubArea.EUROPE, "NOK"),
    ("DK", "Denmark", SubArea.EUROPE, "DKK"),
    ("FI", "Finland", SubArea.EUROPE, "EUR"),
    ("PL", "Poland", SubArea.EUROPE, "PLN"),
    ("CZ", "Czechia", SubArea.EUROPE, "CZK"),
    ("HU", "Hungary", SubArea.EUROPE, "HUF"),
    ("TR", "Turkiye", SubArea.EUROPE, "TRY"),
    ("RU", "Russia", SubArea.EUROPE, "EUR"),
    ("US", "United States", SubArea.NORTH_AMERICA, "USD"),
    ("CA", "Canada", SubArea.NORTH_AMERICA, "CAD"),
    ("MX", "Mexico", SubArea.CENTRAL_AMERICA, "MXN"),
    ("BR", "Brazil", SubArea.SOUTH_AMERICA, "BRL"),
    ("AR", "Argentina", SubArea.SOUTH_AMERICA, "USD"),
    ("CL", "Chile", SubArea.SOUTH_AMERICA, "USD"),
    ("PE", "Peru", SubArea.SOUTH_AMERICA, "USD"),
    ("CO", "Colombia", SubArea.SOUTH_AMERICA, "USD"),
    ("BB", "Barbados", SubArea.CARIBBEAN, "USD"),
    ("ZA", "South Africa", SubArea.AFRICA, "ZAR"),
    ("KE", "Kenya", SubArea.AFRICA, "USD"),
    ("EG", "Egypt", SubArea.AFRICA, "USD"),
    ("MA", "Morocco", SubArea.AFRICA, "EUR"),
    ("NG", "Nigeria", SubArea.AFRICA, "USD"),
    ("AE", "United Arab Emirates", SubArea.MIDDLE_EAST, "AED"),
    ("QA", "Qatar", SubArea.MIDDLE_EAST, "USD"),
    ("KW", "Kuwait", SubArea.MIDDLE_EAST, "KWD"),
    ("BH", "Bahrain", SubArea.MIDDLE_EAST, "BHD"),
    ("IL", "Israel", SubArea.MIDDLE_EAST, "USD"),
    ("IN", "India", SubArea.SOUTH_ASIA, "INR"),
    ("PK", "Pakistan", SubArea.SOUTH_ASIA, "USD"),
    ("LK", "Sri Lanka", SubArea.SOUTH_ASIA, "USD"),
    ("SG", "Singapore", SubArea.SOUTH_EAST_ASIA, "SGD"),
    ("TH", "Thailand", SubArea.SOUTH_EAST_ASIA, "THB"),
    ("MY", "Malaysia", SubArea.SOUTH_EAST_ASIA, "USD"),
    ("ID", "Indonesia", SubArea.SOUTH_EAST_ASIA, "USD"),
    ("VN", "Vietnam", SubArea.SOUTH_EAST_ASIA, "USD"),
    ("PH", "Philippines", SubArea.SOUTH_EAST_ASIA, "USD"),
    ("HK", "Hong Kong", SubArea.SOUTH_EAST_ASIA, "HKD"),
    ("CN", "China", SubArea.SOUTH_EAST_ASIA, "CNY"),
    ("JP", "Japan", SubArea.JAPAN_KOREA, "JPY"),
    ("KR", "Korea", SubArea.JAPAN_KOREA, "KRW"),
    ("AU", "Australia", SubArea.SOUTH_WEST_PACIFIC, "AUD"),
    ("NZ", "New Zealand", SubArea.SOUTH_WEST_PACIFIC, "NZD"),
)

COUNTRIES: dict[str, Country] = {
    code: Country(code, name, sub, cur) for code, name, sub, cur in _COUNTRY_ROWS
}

_AIRPORT_ROWS: tuple[tuple[str, str, str, str, float, float, int], ...] = (
    ("FRA", "Frankfurt am Main", "FRA", "DE", 50.033, 8.570, 60),
    ("MUC", "Munich", "MUC", "DE", 48.354, 11.786, 60),
    ("TXL", "Berlin Tegel", "BER", "DE", 52.559, 13.288, 60),
    ("BER", "Berlin Brandenburg", "BER", "DE", 52.362, 13.501, 60),
    ("DUS", "Dusseldorf", "DUS", "DE", 51.289, 6.767, 60),
    ("HAM", "Hamburg", "HAM", "DE", 53.630, 9.988, 60),
    ("CDG", "Paris Charles de Gaulle", "PAR", "FR", 49.010, 2.548, 60),
    ("ORY", "Paris Orly", "PAR", "FR", 48.726, 2.365, 60),
    ("NCE", "Nice", "NCE", "FR", 43.658, 7.216, 60),
    ("LHR", "London Heathrow", "LON", "GB", 51.470, -0.454, 0),
    ("LGW", "London Gatwick", "LON", "GB", 51.148, -0.190, 0),
    ("LCY", "London City", "LON", "GB", 51.505, 0.055, 0),
    ("STN", "London Stansted", "LON", "GB", 51.885, 0.235, 0),
    ("MAN", "Manchester", "MAN", "GB", 53.365, -2.273, 0),
    ("EDI", "Edinburgh", "EDI", "GB", 55.950, -3.372, 0),
    ("DUB", "Dublin", "DUB", "IE", 53.421, -6.270, 0),
    ("AMS", "Amsterdam Schiphol", "AMS", "NL", 52.309, 4.764, 60),
    ("BRU", "Brussels", "BRU", "BE", 50.901, 4.484, 60),
    ("ZRH", "Zurich", "ZRH", "CH", 47.464, 8.549, 60),
    ("GVA", "Geneva", "GVA", "CH", 46.238, 6.109, 60),
    ("VIE", "Vienna", "VIE", "AT", 48.110, 16.570, 60),
    ("FCO", "Rome Fiumicino", "ROM", "IT", 41.800, 12.239, 60),
    ("MXP", "Milan Malpensa", "MIL", "IT", 45.630, 8.723, 60),
    ("LIN", "Milan Linate", "MIL", "IT", 45.445, 9.277, 60),
    ("MAD", "Madrid Barajas", "MAD", "ES", 40.472, -3.561, 60),
    ("BCN", "Barcelona", "BCN", "ES", 41.297, 2.078, 60),
    ("LIS", "Lisbon", "LIS", "PT", 38.774, -9.134, 0),
    ("ARN", "Stockholm Arlanda", "STO", "SE", 59.652, 17.919, 60),
    ("OSL", "Oslo Gardermoen", "OSL", "NO", 60.194, 11.100, 60),
    ("CPH", "Copenhagen", "CPH", "DK", 55.618, 12.656, 60),
    ("HEL", "Helsinki", "HEL", "FI", 60.317, 24.963, 120),
    ("WAW", "Warsaw Chopin", "WAW", "PL", 52.166, 20.967, 60),
    ("PRG", "Prague", "PRG", "CZ", 50.101, 14.260, 60),
    ("BUD", "Budapest", "BUD", "HU", 47.437, 19.256, 60),
    ("IST", "Istanbul", "IST", "TR", 41.262, 28.742, 180),
    ("SVO", "Moscow Sheremetyevo", "MOW", "RU", 55.973, 37.415, 180),
    ("JFK", "New York Kennedy", "NYC", "US", 40.640, -73.779, -300),
    ("EWR", "Newark", "NYC", "US", 40.690, -74.177, -300),
    ("LGA", "New York LaGuardia", "NYC", "US", 40.777, -73.872, -300),
    ("BOS", "Boston Logan", "BOS", "US", 42.363, -71.006, -300),
    ("IAD", "Washington Dulles", "WAS", "US", 38.947, -77.460, -300),
    ("ORD", "Chicago O Hare", "CHI", "US", 41.978, -87.905, -360),
    ("DFW", "Dallas Fort Worth", "DFW", "US", 32.897, -97.038, -360),
    ("IAH", "Houston Intercontinental", "HOU", "US", 29.984, -95.341, -360),
    ("MIA", "Miami", "MIA", "US", 25.795, -80.287, -300),
    ("ATL", "Atlanta", "ATL", "US", 33.640, -84.427, -300),
    ("DEN", "Denver", "DEN", "US", 39.856, -104.674, -420),
    ("LAX", "Los Angeles", "LAX", "US", 33.942, -118.408, -480),
    ("SFO", "San Francisco", "SFO", "US", 37.619, -122.375, -480),
    ("SEA", "Seattle Tacoma", "SEA", "US", 47.449, -122.309, -480),
    ("YYZ", "Toronto Pearson", "YTO", "CA", 43.677, -79.630, -300),
    ("YVR", "Vancouver", "YVR", "CA", 49.194, -123.184, -480),
    ("MEX", "Mexico City", "MEX", "MX", 19.436, -99.072, -360),
    ("GRU", "Sao Paulo Guarulhos", "SAO", "BR", -23.435, -46.473, -180),
    ("GIG", "Rio de Janeiro", "RIO", "BR", -22.809, -43.250, -180),
    ("EZE", "Buenos Aires Ezeiza", "BUE", "AR", -34.822, -58.536, -180),
    ("SCL", "Santiago", "SCL", "CL", -33.393, -70.786, -240),
    ("LIM", "Lima", "LIM", "PE", -12.022, -77.114, -300),
    ("BOG", "Bogota", "BOG", "CO", 4.702, -74.147, -300),
    ("BGI", "Bridgetown", "BGI", "BB", 13.075, -59.492, -240),
    ("JNB", "Johannesburg", "JNB", "ZA", -26.139, 28.246, 120),
    ("CPT", "Cape Town", "CPT", "ZA", -33.969, 18.602, 120),
    ("NBO", "Nairobi", "NBO", "KE", -1.319, 36.928, 180),
    ("CAI", "Cairo", "CAI", "EG", 30.112, 31.406, 120),
    ("CMN", "Casablanca", "CMN", "MA", 33.367, -7.590, 60),
    ("LOS", "Lagos", "LOS", "NG", 6.577, 3.321, 60),
    ("DXB", "Dubai", "DXB", "AE", 25.253, 55.365, 240),
    ("AUH", "Abu Dhabi", "AUH", "AE", 24.433, 54.651, 240),
    ("DOH", "Doha", "DOH", "QA", 25.261, 51.565, 180),
    ("KWI", "Kuwait", "KWI", "KW", 29.227, 47.969, 180),
    ("BAH", "Bahrain", "BAH", "BH", 26.271, 50.634, 180),
    ("TLV", "Tel Aviv", "TLV", "IL", 32.011, 34.887, 120),
    ("DEL", "Delhi", "DEL", "IN", 28.556, 77.100, 330),
    ("BOM", "Mumbai", "BOM", "IN", 19.089, 72.868, 330),
    ("BLR", "Bengaluru", "BLR", "IN", 13.199, 77.710, 330),
    ("MAA", "Chennai", "MAA", "IN", 12.990, 80.169, 330),
    ("KHI", "Karachi", "KHI", "PK", 24.907, 67.161, 300),
    ("CMB", "Colombo", "CMB", "LK", 7.181, 79.884, 330),
    ("SIN", "Singapore Changi", "SIN", "SG", 1.364, 103.991, 480),
    ("BKK", "Bangkok Suvarnabhumi", "BKK", "TH", 13.690, 100.750, 420),
    ("KUL", "Kuala Lumpur", "KUL", "MY", 2.746, 101.710, 480),
    ("CGK", "Jakarta", "JKT", "ID", -6.126, 106.656, 420),
    ("SGN", "Ho Chi Minh City", "SGN", "VN", 10.819, 106.652, 420),
    ("MNL", "Manila", "MNL", "PH", 14.509, 121.020, 480),
    ("HKG", "Hong Kong", "HKG", "HK", 22.309, 113.915, 480),
    ("PVG", "Shanghai Pudong", "SHA", "CN", 31.143, 121.805, 480),
    ("PEK", "Beijing Capital", "BJS", "CN", 40.080, 116.585, 480),
    ("CAN", "Guangzhou", "CAN", "CN", 23.392, 113.299, 480),
    ("NRT", "Tokyo Narita", "TYO", "JP", 35.765, 140.386, 540),
    ("HND", "Tokyo Haneda", "TYO", "JP", 35.553, 139.781, 540),
    ("KIX", "Osaka Kansai", "OSA", "JP", 34.427, 135.244, 540),
    ("ICN", "Seoul Incheon", "SEL", "KR", 37.469, 126.451, 540),
    ("SYD", "Sydney", "SYD", "AU", -33.946, 151.177, 600),
    ("MEL", "Melbourne", "MEL", "AU", -37.669, 144.841, 600),
    ("BNE", "Brisbane", "BNE", "AU", -27.384, 153.117, 600),
    ("PER", "Perth", "PER", "AU", -31.940, 115.967, 480),
    ("AKL", "Auckland", "AKL", "NZ", -37.008, 174.792, 720),
)

AIRPORTS: dict[str, Airport] = {
    code: Airport(code, name, city, country, lat, lon, offset)
    for code, name, city, country, lat, lon, offset in _AIRPORT_ROWS
}


def _build_metros() -> dict[str, tuple[str, ...]]:
    grouped: dict[str, list[str]] = {}
    for airport in AIRPORTS.values():
        grouped.setdefault(airport.city, []).append(airport.code)
    return {city: tuple(sorted(codes)) for city, codes in grouped.items()}


METROS: dict[str, tuple[str, ...]] = _build_metros()


def airport(code: str) -> Airport:
    """Look one airport up. Metro codes are not airports and are refused."""
    key = code.strip().upper()
    try:
        return AIRPORTS[key]
    except KeyError:
        raise ValidationError("unknown airport", code=code) from None


def country(code: str) -> Country:
    key = code.strip().upper()
    try:
        return COUNTRIES[key]
    except KeyError:
        raise ValidationError("unknown country", code=code) from None


def city_of(code: str) -> str:
    """The city a point prices as. A metro code answers for itself."""
    key = code.strip().upper()
    if key in METROS:
        return key
    return airport(key).city


def airports_in(city: str) -> tuple[str, ...]:
    key = city.strip().upper()
    if key in METROS:
        return METROS[key]
    if key in AIRPORTS:
        return (key,)
    raise ValidationError("unknown city or airport", code=city)


def sub_area_of(code: str) -> SubArea:
    return country(airport_or_city_country(code)).sub_area


def area_of(code: str) -> Area:
    return country(airport_or_city_country(code)).area


def airport_or_city_country(code: str) -> str:
    """The country of a point, whether it was given as an airport or a city."""
    key = code.strip().upper()
    if key in AIRPORTS:
        return AIRPORTS[key].country
    members = airports_in(key)
    return AIRPORTS[members[0]].country


def same_country(left: str, right: str) -> bool:
    return airport_or_city_country(left) == airport_or_city_country(right)
