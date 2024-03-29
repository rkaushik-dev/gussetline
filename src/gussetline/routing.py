"""Routing maps, which are the other way a fare says where it may go.

Every published fare carries a routing number. If it is the mileage routing,
the fare may go anywhere the mileage principle permits and the surcharge bands
decide the price. If it is a real number, the fare names the paths it is good
for and nothing else will do, however short the detour.

A routing map is a directed graph of city pairs with the carriers permitted on
each hop, and a component matches when its cities trace a path from the board
point to the off point using only permitted hops. Two things make that less
trivial than it sounds. A map can be filed both directions and read either way
round, and a hop can permit any carrier, which is how a carrier files its own
online routings and then adds an interline hop for the last leg.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass

from gussetline.carriers import normalise_code
from gussetline.errors import ValidationError
from gussetline.geography import city_of

# The routing number that means there is no map, price it on mileage.
MILEAGE_ROUTING = "0000"


@dataclass(frozen=True, slots=True)
class Hop:
    """One permitted move, and who may fly it."""

    origin: str
    destination: str
    carriers: frozenset[str] = frozenset()

    def __post_init__(self) -> None:
        object.__setattr__(self, "origin", city_of(self.origin))
        object.__setattr__(self, "destination", city_of(self.destination))
        if self.origin == self.destination:
            raise ValidationError("a hop goes somewhere", point=self.origin)
        object.__setattr__(self, "carriers", frozenset(normalise_code(c) for c in self.carriers))

    @property
    def any_carrier(self) -> bool:
        return not self.carriers

    def permits(self, carrier: str | None) -> bool:
        if self.any_carrier:
            return True
        if carrier is None:
            return False
        return normalise_code(carrier) in self.carriers

    def reversed(self) -> Hop:
        return Hop(self.destination, self.origin, self.carriers)

    def __str__(self) -> str:
        who = "/".join(sorted(self.carriers)) if self.carriers else "any"
        return f"{self.origin}-{self.destination}[{who}]"


class RoutingMap:
    """The paths one routing number permits."""

    def __init__(self, number: str, *, bidirectional: bool = True) -> None:
        self.number = number.strip()
        if not self.number:
            raise ValidationError("a routing map needs a number")
        self.bidirectional = bidirectional
        self._hops: list[Hop] = []
        self._out: dict[str, list[Hop]] = {}

    def add(self, origin: str, destination: str, carriers: Iterable[str] = ()) -> Hop:
        hop = Hop(origin, destination, frozenset(carriers))
        self._register(hop)
        if self.bidirectional:
            self._register(hop.reversed())
        return hop

    def _register(self, hop: Hop) -> None:
        self._hops.append(hop)
        self._out.setdefault(hop.origin, []).append(hop)

    def add_string(self, points: Sequence[str], carriers: Iterable[str] = ()) -> None:
        """Add a whole path at once, which is how maps are actually filed."""
        if len(points) < 2:
            raise ValidationError("a routing string needs two points")
        held = list(carriers)
        for index in range(len(points) - 1):
            self.add(points[index], points[index + 1], held)

    @property
    def is_mileage(self) -> bool:
        return self.number == MILEAGE_ROUTING

    def hops(self) -> tuple[Hop, ...]:
        return tuple(self._hops)

    def points(self) -> tuple[str, ...]:
        found: set[str] = set()
        for hop in self._hops:
            found.add(hop.origin)
            found.add(hop.destination)
        return tuple(sorted(found))

    def permits(self, cities: Sequence[str], carriers: Sequence[str | None] = ()) -> bool:
        """Whether a city sequence traces a permitted path through the map.

        Carriers are positional against the hops and may be short, in which
        case the remaining hops are checked with no carrier and therefore need
        an any carrier hop to pass.
        """
        if self.is_mileage:
            return True
        if len(cities) < 2:
            raise ValidationError("a routing needs two points to check")
        folded = [city_of(c) for c in cities]
        for index in range(len(folded) - 1):
            carrier = carriers[index] if index < len(carriers) else None
            if not self._hop_permits(folded[index], folded[index + 1], carrier):
                return False
        return True

    def _hop_permits(self, origin: str, destination: str, carrier: str | None) -> bool:
        for hop in self._out.get(origin, ()):
            if hop.destination == destination and hop.permits(carrier):
                return True
        return False

    def refusal(self, cities: Sequence[str], carriers: Sequence[str | None] = ()) -> str | None:
        """The first hop the map refuses, said in words, or None if it passes.

        The desk sends these to suppliers, so the sentence matters more than
        the boolean does. A refusal that says the routing failed and nothing
        else costs an analyst twenty minutes with a printed map.
        """
        if self.is_mileage:
            return None
        folded = [city_of(c) for c in cities]
        for index in range(len(folded) - 1):
            carrier = carriers[index] if index < len(carriers) else None
            origin, destination = folded[index], folded[index + 1]
            if self._hop_permits(origin, destination, carrier):
                continue
            if any(hop.destination == destination for hop in self._out.get(origin, ())):
                return f"routing {self.number} does not permit {carrier} on {origin}-{destination}"
            return f"routing {self.number} does not permit {origin}-{destination} at all"
        return None

    def __len__(self) -> int:
        return len(self._hops)

    def __str__(self) -> str:
        return f"routing {self.number} over {len(self.points())} points"


class RoutingStore:
    """Routing maps by carrier and number, which is how a fare names one."""

    def __init__(self) -> None:
        self._maps: dict[tuple[str, str], RoutingMap] = {}

    def add(self, carrier: str, routing: RoutingMap) -> RoutingMap:
        self._maps[(normalise_code(carrier), routing.number)] = routing
        return routing

    def get(self, carrier: str, number: str) -> RoutingMap | None:
        return self._maps.get((normalise_code(carrier), number.strip()))

    def permits(
        self,
        carrier: str,
        number: str,
        cities: Sequence[str],
        carriers: Sequence[str | None] = (),
    ) -> bool:
        """A fare whose map we do not hold prices on mileage rather than failing.

        This is deliberate and it is the conservative choice. Refusing would
        drop every fare on a carrier whose maps have not loaded yet, and the
        mileage check that runs instead is stricter than most maps are.
        """
        if number.strip() == MILEAGE_ROUTING:
            return True
        found = self.get(carrier, number)
        if found is None:
            return True
        return found.permits(cities, carriers)

    def __len__(self) -> int:
        return len(self._maps)
