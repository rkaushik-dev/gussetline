"""The fare calculation line, which is the price written where a person reads it.

Every ticket carries one. It says which cities were priced against each other,
which carrier flew each hop, where the passenger merely connected rather than
stopped, what each component cost in neutral units, and what the whole came to
before conversion. An agent who can read one can check a price without a
system, which is why the format has outlived every system that produced it.

    FRA LH X/LON LH NYC 441.64 LH FRA 441.64 NUC883.28END ROE0.951000

The rules that matter when writing one. A connection is marked with X before
the city and a stopover is not, so the line records the clock reading the
itinerary made. A Q surcharge is written separately in front of the amount,
while a mileage surcharge is folded into the amount and only its band is
shown. And the rate of exchange is written to six decimals whatever the
currency does, because the line is checked by hand against the published rate.
"""

from __future__ import annotations

from collections.abc import Iterable
from decimal import Decimal

from gussetline.components import FareComponent
from gussetline.construction import Construction
from gussetline.errors import ConstructionError
from gussetline.flights import Segment
from gussetline.geography import city_of

# Charge reasons written in front of the amount rather than folded into it.
SEPARATE = ("Q",)

# Charge reasons that are surcharge bands, shown as a token and folded in.
BANDS = ("M", "5M", "10M", "15M", "20M", "25M")


def _amount(value: Decimal) -> str:
    return f"{value.quantize(Decimal('0.01')):.2f}"


def render_component(
    component: FareComponent,
    connections: Iterable[str] = (),
) -> str:
    """One component's worth of the line, without the totals."""
    if component.nuc is None:
        raise ConstructionError("an unpriced component has no fare calculation")

    stopping = {city_of(point) for point in connections}
    tokens: list[str] = [component.board_point]

    for index, element in enumerate(component.elements):
        if isinstance(element, Segment):
            tokens.append(element.marketing_carrier)
        else:
            tokens.append("//")
        point = city_of(element.destination)
        last = index == len(component.elements) - 1
        if not last and point in stopping:
            tokens.append(f"X/{point}")
        else:
            tokens.append(point)

    separate = sum((component.charge_for(reason) for reason in SEPARATE), Decimal(0))
    if separate > 0:
        tokens.append(f"Q{_amount(separate)}")

    for band in component.notes:
        if band in BANDS:
            tokens.append(band)
            break

    tokens.append(_amount(component.total_nuc - separate))
    return " ".join(tokens)


def render(
    construction: Construction,
    roe: Decimal,
    connections: Iterable[str] = (),
) -> str:
    """The whole line, components then the neutral total and the rate."""
    if roe <= 0:
        raise ConstructionError("a rate of exchange is positive", roe=str(roe))

    parts: list[str] = []
    for unit in construction.units:
        for component in unit.components:
            rendered = render_component(component, connections)
            # Every component after the first drops its board point, because
            # the previous component already ended there.
            parts.append(rendered if not parts else rendered.split(" ", 1)[1])

    body = " ".join(parts)
    total = _amount(construction.total_nuc)
    return f"{body} NUC{total}END ROE{roe:.6f}"


def components_in(line: str) -> int:
    """How many amounts the line carries, which is how many components it has."""
    return sum(1 for token in line.split() if _looks_like_amount(token))


def _looks_like_amount(token: str) -> bool:
    if token.startswith("Q"):
        return False
    if "." not in token:
        return False
    head, _, tail = token.partition(".")
    return head.isdigit() and len(tail) == 2 and tail.isdigit()


def total_in(line: str) -> Decimal:
    """The neutral total the line claims, read back off it."""
    for token in line.split():
        if token.startswith("NUC") and token.endswith("END"):
            return Decimal(token[3:-3])
    raise ConstructionError("no neutral total on the line", line=line)


def roe_in(line: str) -> Decimal:
    for token in line.split():
        if token.startswith("ROE"):
            return Decimal(token[3:])
    raise ConstructionError("no rate of exchange on the line", line=line)


def adds_up(line: str) -> bool:
    """Whether the amounts on the line add to the total it claims.

    This is the cheapest audit there is and it catches a surprising amount.
    A supplier that rebuilds a line after a manual fare override frequently
    forgets to rebuild the total.
    """
    claimed = total_in(line)
    running = Decimal(0)
    for token in line.split():
        if _looks_like_amount(token):
            running += Decimal(token)
        elif token.startswith("Q") and len(token) > 1:
            try:
                running += Decimal(token[1:])
            except Exception:
                continue
    return running == claimed
