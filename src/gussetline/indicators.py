"""Global indicators, which are part of the fare key and not a description.

Frankfurt to Tokyo has one published fare eastbound over Siberia and another
one westbound over the pole and North America, and they are different fares
with different rules and different prices. The tariff distinguishes them with
a two letter global indicator, and a pricing engine that ignores it will
cheerfully apply the wrong one and then argue about rounding.

The indicator is decided by the areas the journey touches and by the points it
routes through, so it cannot be computed from origin and destination alone.
A journey with no via points gets the default for the area pair, which is what
the tariff calls the shortest sector indicator.
"""

from __future__ import annotations

from collections.abc import Sequence
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.geography import Area, SubArea, area_of, same_country, sub_area_of


class GlobalIndicator(str, Enum):
    """The indicators the desk sees. There are more, and we do not sell them."""

    AT = "AT"  # via the Atlantic
    PA = "PA"  # via the Pacific
    AP = "AP"  # via both, which is a round the world construction
    EH = "EH"  # within the eastern hemisphere
    WH = "WH"  # within the western hemisphere
    TS = "TS"  # via Trans Siberia
    RU = "RU"  # via Russia east of the Urals but not the Siberian routing
    FE = "FE"  # within the Far East
    SA = "SA"  # via the South Atlantic
    DO = "DO"  # wholly within one country


# The sub areas a Trans Siberian routing has to cross to earn TS. A journey
# that touches Moscow on the way from Europe to Japan is TS, and the same
# journey through Dubai is EH, and the fares differ by several hundred.
_SIBERIAN_POINTS = frozenset({"MOW", "SVO", "DME", "VKO", "LED"})

_SOUTH_ATLANTIC_LEFT = frozenset({SubArea.SOUTH_AMERICA})
_SOUTH_ATLANTIC_RIGHT = frozenset({SubArea.AFRICA})

_FAR_EAST = frozenset({SubArea.JAPAN_KOREA, SubArea.SOUTH_EAST_ASIA, SubArea.SOUTH_WEST_PACIFIC})


def _legs(points: Sequence[str]) -> list[tuple[str, str]]:
    return [(points[i], points[i + 1]) for i in range(len(points) - 1)]


def _ocean(leg: tuple[str, str]) -> str | None:
    """Which ocean one leg crosses, if it crosses one at all.

    A leg between Area 1 and Area 3 goes over the Pacific unless the Area 3
    end is in South Asia, which is reached the other way round over the pole
    and prices as an Atlantic fare. That single exception is why this is a
    function on a leg and not a lookup on the area pair.
    """
    left, right = area_of(leg[0]), area_of(leg[1])
    pair = {left, right}
    if pair == {Area.ONE, Area.TWO}:
        return "AT"
    if pair == {Area.ONE, Area.THREE}:
        far = leg[1] if left is Area.ONE else leg[0]
        if sub_area_of(far) is SubArea.SOUTH_ASIA:
            return "AT"
        return "PA"
    return None


def _is_south_atlantic(origin: str, destination: str) -> bool:
    left, right = sub_area_of(origin), sub_area_of(destination)
    return (left in _SOUTH_ATLANTIC_LEFT and right in _SOUTH_ATLANTIC_RIGHT) or (
        right in _SOUTH_ATLANTIC_LEFT and left in _SOUTH_ATLANTIC_RIGHT
    )


def global_indicator(
    origin: str,
    destination: str,
    via: Sequence[str] = (),
) -> GlobalIndicator:
    """Work out which published fare family a journey belongs to.

    The journey is walked leg by leg rather than read off the set of areas it
    touches, because a set cannot tell a journey that crosses one ocean from
    one that crosses two. New York to Delhi over Frankfurt touches all three
    areas and crosses only the Atlantic.
    """
    if not origin or not destination:
        raise ValidationError("a journey needs two ends", origin=origin, destination=destination)

    via = tuple(via)
    points = (origin, *via, destination)
    touched = {area_of(p) for p in points}
    oceans = {crossed for crossed in (_ocean(leg) for leg in _legs(points)) if crossed}

    if same_country(origin, destination) and all(same_country(origin, p) for p in via):
        return GlobalIndicator.DO

    if oceans == {"AT", "PA"}:
        return GlobalIndicator.AP
    if oceans == {"PA"}:
        return GlobalIndicator.PA
    if oceans == {"AT"}:
        if _is_south_atlantic(origin, destination):
            return GlobalIndicator.SA
        return GlobalIndicator.AT

    if touched == {Area.ONE}:
        return GlobalIndicator.WH

    if any(point.upper() in _SIBERIAN_POINTS for point in points):
        return GlobalIndicator.TS
    if touched == {Area.THREE} and _both_far_east(origin, destination):
        return GlobalIndicator.FE
    return GlobalIndicator.EH


def _both_far_east(origin: str, destination: str) -> bool:
    return sub_area_of(origin) in _FAR_EAST and sub_area_of(destination) in _FAR_EAST


def is_international(origin: str, destination: str) -> bool:
    return not same_country(origin, destination)


def indicator_matches(published: GlobalIndicator, journey: GlobalIndicator) -> bool:
    """Whether a fare published under one indicator may price a journey.

    A fare filed EH prices an EH journey and also a TS or FE one, because both
    of those are eastern hemisphere journeys with a narrower name. The reverse
    is not true and that asymmetry is the whole reason this function exists
    rather than an equality check at the call site.
    """
    if published is journey:
        return True
    if published is GlobalIndicator.EH:
        return journey in (GlobalIndicator.TS, GlobalIndicator.RU, GlobalIndicator.FE)
    if published is GlobalIndicator.AT:
        return journey is GlobalIndicator.SA
    return False
