"""Carriers, and the difference between flying together and ticketing together.

Two carriers in the same alliance do not necessarily interline, and two
carriers that interline are frequently in no alliance at all. The desk cares
about interline because a fare on one carrier's ticket stock cannot carry a
sector the validating carrier has no agreement for, and the supplier will
happily quote it anyway.

Codes are the two character designators, which are not all letters. A carrier
code is compared as an opaque string and never uppercased twice, because a
lowercase designator has already been through somebody else's parser.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from enum import Enum

from gussetline.errors import ValidationError

_CODE = re.compile(r"^[0-9A-Z][0-9A-Z]$")


class Alliance(str, Enum):
    STAR = "star"
    ONEWORLD = "oneworld"
    SKYTEAM = "skyteam"
    NONE = "none"


@dataclass(frozen=True, slots=True)
class Carrier:
    code: str
    name: str
    country: str
    alliance: Alliance
    low_cost: bool = False

    @property
    def is_aligned(self) -> bool:
        return self.alliance is not Alliance.NONE


_CARRIER_ROWS: tuple[tuple[str, str, str, Alliance, bool], ...] = (
    ("LH", "Lufthansa", "DE", Alliance.STAR, False),
    ("LX", "Swiss", "CH", Alliance.STAR, False),
    ("OS", "Austrian", "AT", Alliance.STAR, False),
    ("SN", "Brussels Airlines", "BE", Alliance.STAR, False),
    ("SK", "SAS", "SE", Alliance.STAR, False),
    ("TP", "TAP Portugal", "PT", Alliance.STAR, False),
    ("LO", "LOT", "PL", Alliance.STAR, False),
    ("TK", "Turkish Airlines", "TR", Alliance.STAR, False),
    ("AC", "Air Canada", "CA", Alliance.STAR, False),
    ("UA", "United", "US", Alliance.STAR, False),
    ("NH", "All Nippon Airways", "JP", Alliance.STAR, False),
    ("OZ", "Asiana", "KR", Alliance.STAR, False),
    ("SQ", "Singapore Airlines", "SG", Alliance.STAR, False),
    ("TG", "Thai Airways", "TH", Alliance.STAR, False),
    ("AI", "Air India", "IN", Alliance.STAR, False),
    ("ET", "Ethiopian", "KE", Alliance.STAR, False),
    ("SA", "South African", "ZA", Alliance.STAR, False),
    ("NZ", "Air New Zealand", "NZ", Alliance.STAR, False),
    ("BA", "British Airways", "GB", Alliance.ONEWORLD, False),
    ("IB", "Iberia", "ES", Alliance.ONEWORLD, False),
    ("AY", "Finnair", "FI", Alliance.ONEWORLD, False),
    ("EI", "Aer Lingus", "IE", Alliance.NONE, False),
    ("AA", "American Airlines", "US", Alliance.ONEWORLD, False),
    ("QF", "Qantas", "AU", Alliance.ONEWORLD, False),
    ("CX", "Cathay Pacific", "HK", Alliance.ONEWORLD, False),
    ("JL", "Japan Airlines", "JP", Alliance.ONEWORLD, False),
    ("QR", "Qatar Airways", "QA", Alliance.ONEWORLD, False),
    ("RJ", "Royal Jordanian", "IL", Alliance.ONEWORLD, False),
    ("MH", "Malaysia Airlines", "MY", Alliance.ONEWORLD, False),
    ("UL", "SriLankan", "LK", Alliance.ONEWORLD, False),
    ("AF", "Air France", "FR", Alliance.SKYTEAM, False),
    ("KL", "KLM", "NL", Alliance.SKYTEAM, False),
    ("AZ", "ITA Airways", "IT", Alliance.SKYTEAM, False),
    ("DL", "Delta", "US", Alliance.SKYTEAM, False),
    ("KE", "Korean Air", "KR", Alliance.SKYTEAM, False),
    ("MU", "China Eastern", "CN", Alliance.SKYTEAM, False),
    ("CI", "China Airlines", "CN", Alliance.SKYTEAM, False),
    ("VN", "Vietnam Airlines", "VN", Alliance.SKYTEAM, False),
    ("SV", "Saudia", "AE", Alliance.SKYTEAM, False),
    ("KQ", "Kenya Airways", "KE", Alliance.SKYTEAM, False),
    ("AM", "Aeromexico", "MX", Alliance.SKYTEAM, False),
    ("EK", "Emirates", "AE", Alliance.NONE, False),
    ("EY", "Etihad", "AE", Alliance.NONE, False),
    ("VS", "Virgin Atlantic", "GB", Alliance.NONE, False),
    ("LY", "El Al", "IL", Alliance.NONE, False),
    ("GF", "Gulf Air", "BH", Alliance.NONE, False),
    ("6E", "IndiGo", "IN", Alliance.NONE, True),
    ("FR", "Ryanair", "IE", Alliance.NONE, True),
    ("U2", "easyJet", "GB", Alliance.NONE, True),
    ("W6", "Wizz Air", "HU", Alliance.NONE, True),
    ("VY", "Vueling", "ES", Alliance.NONE, True),
    ("DY", "Norwegian", "NO", Alliance.NONE, True),
    ("PC", "Pegasus", "TR", Alliance.NONE, True),
    ("3K", "Jetstar Asia", "SG", Alliance.NONE, True),
    ("AK", "AirAsia", "MY", Alliance.NONE, True),
    ("G3", "Gol", "BR", Alliance.NONE, True),
)

CARRIERS: dict[str, Carrier] = {
    code: Carrier(code, name, country, alliance, low_cost)
    for code, name, country, alliance, low_cost in _CARRIER_ROWS
}

# Interline agreements the desk holds paper on. The pairs are unordered and
# stored one way round only. Anything not listed and not inside one alliance
# has no agreement, which is the safe default because quoting a sector we
# cannot ticket costs an agent an hour on the phone.
_INTERLINE_PAIRS: frozenset[frozenset[str]] = frozenset(
    frozenset(pair)
    for pair in (
        ("LH", "EK"),
        ("LH", "EY"),
        ("BA", "EI"),
        ("BA", "AY"),
        ("AF", "EK"),
        ("KL", "EK"),
        ("EK", "QF"),
        ("EY", "AF"),
        ("VS", "DL"),
        ("VS", "AF"),
        ("LY", "LH"),
        ("GF", "BA"),
        ("6E", "TK"),
        ("6E", "QR"),
        ("UL", "EK"),
    )
)


def carrier(code: str) -> Carrier:
    key = normalise_code(code)
    try:
        return CARRIERS[key]
    except KeyError:
        raise ValidationError("unknown carrier", code=code) from None


def normalise_code(code: str) -> str:
    """Uppercase and check the shape, without inventing a carrier."""
    key = code.strip().upper()
    if not _CODE.match(key):
        raise ValidationError("not a carrier designator", code=code)
    return key


def is_known(code: str) -> bool:
    try:
        return normalise_code(code) in CARRIERS
    except ValidationError:
        return False


def alliance_of(code: str) -> Alliance:
    return carrier(code).alliance


def same_alliance(left: str, right: str) -> bool:
    """Two carriers in the same alliance. Unaligned carriers share nothing."""
    one, two = carrier(left), carrier(right)
    if one.alliance is Alliance.NONE or two.alliance is Alliance.NONE:
        return False
    return one.alliance is two.alliance


def interlines(left: str, right: str) -> bool:
    """Whether one carrier can carry the other's coupon.

    A carrier always interlines with itself. Alliance membership implies an
    agreement. Everything else has to be on the paper list.
    """
    one, two = normalise_code(left), normalise_code(right)
    if one == two:
        return carrier(one) is not None
    if same_alliance(one, two):
        return True
    return frozenset({one, two}) in _INTERLINE_PAIRS


def members(alliance: Alliance) -> tuple[str, ...]:
    return tuple(sorted(c.code for c in CARRIERS.values() if c.alliance is alliance))


def low_cost_carriers() -> tuple[str, ...]:
    return tuple(sorted(c.code for c in CARRIERS.values() if c.low_cost))
