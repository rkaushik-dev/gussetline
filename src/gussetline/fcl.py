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
from dataclasses import dataclass
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


@dataclass(frozen=True, slots=True)
class ParsedComponent:
    """One component read back off a supplier's line."""

    points: tuple[str, ...]
    carriers: tuple[str, ...]
    amount: Decimal
    q_surcharge: Decimal = Decimal(0)
    band: str = ""
    connections: tuple[str, ...] = ()

    @property
    def board(self) -> str:
        return self.points[0]

    @property
    def off(self) -> str:
        return self.points[-1]

    def __str__(self) -> str:
        return f"{self.board}{self.off} {self.amount}"


@dataclass(frozen=True, slots=True)
class ParsedLine:
    """A whole line read back, with everything it claimed."""

    components: tuple[ParsedComponent, ...]
    total: Decimal
    roe: Decimal
    raw: str

    @property
    def adds_up(self) -> bool:
        running = sum((c.amount + c.q_surcharge for c in self.components), Decimal(0))
        return running == self.total

    @property
    def unexplained(self) -> Decimal:
        """Total minus the parts. A minimum check shows up here and should."""
        return self.total - sum((c.amount + c.q_surcharge for c in self.components), Decimal(0))


def parse(line: str) -> ParsedLine:
    """Read a fare calculation line into its parts.

    Written for lines other systems produced, so it is forgiving about
    spacing and refuses only on the two things it cannot guess at, which are
    a missing neutral total and a missing rate of exchange. A component whose
    shape it does not recognise is still returned, because a line the desk
    cannot fully read is exactly the line worth looking at by hand.
    """
    tokens = line.replace("//", " // ").split()
    if not tokens:
        raise ConstructionError("nothing to parse")

    total: Decimal | None = None
    roe: Decimal | None = None
    body: list[str] = []
    for token in tokens:
        if token.startswith("NUC") and token.endswith("END"):
            total = Decimal(token[3:-3])
            continue
        if token.startswith("ROE"):
            roe = Decimal(token[3:])
            continue
        body.append(token)

    if total is None:
        raise ConstructionError("no neutral total on the line", line=line)
    if roe is None:
        raise ConstructionError("no rate of exchange on the line", line=line)

    components: list[ParsedComponent] = []
    points: list[str] = []
    carriers: list[str] = []
    connections: list[str] = []
    q_amount = Decimal(0)
    band = ""

    for token in body:
        if _looks_like_amount(token):
            if points:
                components.append(
                    ParsedComponent(
                        points=tuple(points),
                        carriers=tuple(carriers),
                        amount=Decimal(token),
                        q_surcharge=q_amount,
                        band=band,
                        connections=tuple(connections),
                    )
                )
            carry = points[-1] if points else ""
            points = [carry] if carry else []
            carriers, connections = [], []
            q_amount, band = Decimal(0), ""
            continue
        if token.startswith("Q") and len(token) > 1 and token[1].isdigit():
            q_amount = Decimal(token[1:])
            continue
        if token in BANDS:
            band = token
            continue
        if token == "//":
            carriers.append("//")
            continue
        if token.startswith("X/"):
            points.append(token[2:])
            connections.append(token[2:])
            continue
        if len(token) == 3 and token.isalnum() and token.isupper() and not token.isdigit():
            points.append(token)
            continue
        carriers.append(token)

    return ParsedLine(tuple(components), total, roe, line)


def compare(ours: str, theirs: str) -> list[str]:
    """Every way two lines disagree, said in words.

    Returns an empty list when they agree, which is the only shape a caller
    should treat as a pass. Anything else is a finding, and the sentences go
    straight into the discrepancy the desk sends the supplier.
    """
    mine, yours = parse(ours), parse(theirs)
    found: list[str] = []

    if mine.total != yours.total:
        found.append(f"neutral total {mine.total} against {yours.total}")
    if mine.roe != yours.roe:
        found.append(f"rate of exchange {mine.roe} against {yours.roe}")
    if len(mine.components) != len(yours.components):
        found.append(f"{len(mine.components)} components against {len(yours.components)}")
        return found

    for index, (left, right) in enumerate(
        zip(mine.components, yours.components, strict=True), start=1
    ):
        where = f"component {index}"
        if left.points != right.points:
            found.append(f"{where} routes {'-'.join(left.points)} against {'-'.join(right.points)}")
        if left.amount != right.amount:
            found.append(f"{where} is {left.amount} against {right.amount}")
        if left.q_surcharge != right.q_surcharge:
            found.append(f"{where} q surcharge {left.q_surcharge} against {right.q_surcharge}")
        if left.band != right.band:
            found.append(f"{where} band {left.band or 'none'} against {right.band or 'none'}")
    return found
