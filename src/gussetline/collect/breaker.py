"""The circuit breaker, which stops a dead supplier costing us the whole run.

A supplier that has failed twenty times in a row is not going to answer the
twenty first, and every one of those calls has a timeout on it. Ten markets
against a dead host is ten times the timeout before the run gets to the next
supplier, and a nightly collection that should take four minutes takes forty.

Three states and the middle one is the point.

Closed is normal. Open means stop, and it opens after a run of failures rather
than after a rate, because a rate over a long window keeps a dying host closed
for hours. Half open is the recovery probe: after a cooling period exactly one
call is let through, and what it does decides whether the breaker closes again
or goes straight back to open for another cooling period.

Letting several through at once in half open is the mistake, because a host
that is up but struggling gets a burst at the worst possible moment and dies
again, which reads as a host that never recovers.
"""

from __future__ import annotations

from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum

from gussetline.errors import SupplierUnavailable, ValidationError


class State(str, Enum):
    CLOSED = "closed"
    OPEN = "open"
    HALF_OPEN = "half_open"


@dataclass
class Breaker:
    """One breaker for one supplier."""

    name: str
    threshold: int = 5
    cooldown: float = 60.0
    clock: Callable[[], float] = field(default=lambda: 0.0)

    state: State = State.CLOSED
    failures: int = 0
    opened_at: float = 0.0
    trips: int = 0
    probes: int = 0
    _probe_out: bool = False

    def __post_init__(self) -> None:
        if self.threshold < 1:
            raise ValidationError("a breaker trips after at least one failure")
        if self.cooldown < 0:
            raise ValidationError("a cooldown is not negative")

    @property
    def is_open(self) -> bool:
        return self.state is State.OPEN

    def _cooled(self) -> bool:
        return (self.clock() - self.opened_at) >= self.cooldown

    def allow(self) -> bool:
        """Whether a call may go out, moving the state if the clock says so."""
        if self.state is State.CLOSED:
            return True
        if self.state is State.OPEN:
            if not self._cooled():
                return False
            self.state = State.HALF_OPEN
            self._probe_out = False
        if self.state is State.HALF_OPEN:
            if self._probe_out:
                return False
            self._probe_out = True
            self.probes += 1
            return True
        return False

    def succeeded(self) -> None:
        """A good answer. Closes a half open breaker and clears the run."""
        self.state = State.CLOSED
        self.failures = 0
        self._probe_out = False

    def failed(self) -> None:
        """A bad answer. Trips on the threshold, or reopens a probe."""
        if self.state is State.HALF_OPEN:
            self._open()
            return
        self.failures += 1
        if self.failures >= self.threshold:
            self._open()

    def _open(self) -> None:
        self.state = State.OPEN
        self.opened_at = self.clock()
        self.trips += 1
        self._probe_out = False

    def guard(self) -> None:
        """Raise rather than answer, for a call site that cannot check first."""
        if not self.allow():
            raise SupplierUnavailable("the breaker is open", supplier=self.name, trips=self.trips)

    def __str__(self) -> str:
        return f"{self.name} {self.state.value} after {self.failures} failures"


class BreakerBoard:
    """A breaker per supplier, made on first sight."""

    def __init__(
        self,
        clock: Callable[[], float],
        *,
        threshold: int = 5,
        cooldown: float = 60.0,
    ) -> None:
        self._clock = clock
        self.threshold = threshold
        self.cooldown = cooldown
        self._breakers: dict[str, Breaker] = {}

    def for_supplier(self, name: str) -> Breaker:
        if name not in self._breakers:
            self._breakers[name] = Breaker(
                name=name,
                threshold=self.threshold,
                cooldown=self.cooldown,
                clock=self._clock,
            )
        return self._breakers[name]

    def allow(self, name: str) -> bool:
        return self.for_supplier(name).allow()

    def succeeded(self, name: str) -> None:
        self.for_supplier(name).succeeded()

    def failed(self, name: str) -> None:
        self.for_supplier(name).failed()

    def open_suppliers(self) -> list[str]:
        return sorted(n for n, b in self._breakers.items() if b.is_open)

    def summary(self) -> dict[str, str]:
        return {name: b.state.value for name, b in sorted(self._breakers.items())}

    def __len__(self) -> int:
        return len(self._breakers)
