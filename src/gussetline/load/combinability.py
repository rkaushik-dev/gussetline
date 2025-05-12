"""Loading category 10, which is filed against a fare and not against a rule.

That is the only structural thing about it and it is the reason it is here
rather than in the rule loader. A rule is a numbered set of provisions that
any number of fares point at. Combinability is filed per fare basis, so two
fares on the same rule can permit entirely different joins, and reading it out
of the rule file would give both of them whichever one was filed last.

The defaults are the whole safety argument. Everything a record does not
mention is refused. A filing that arrives half read produces a fare that
combines with nothing, which shows up as a refusal somebody looks at, and the
alternative is a fare that quietly combines with everything and prices a round
trip a hundred euro light. The one that fails loudly is the one to have.

Same carrier only is the exception and it defaults the other way, to true,
because it is a restriction rather than a permission. A record that does not
mention it is the ordinary case of a carrier that will not combine with
anybody else, and defaulting it to false would let every unread filing build
an interline construction nobody permitted.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from gussetline.collect.fixedwidth import Layout, Problem, RecordSpec, fields
from gussetline.combinability import Combinability, CombinabilityStore
from gussetline.errors import MalformedPayload, ValidationError

ADD = "A"
DELETE = "D"

COMBINABILITY = RecordSpec(
    tag="C10",
    fields=fields(
        ("carrier", 3, 2),
        ("fare_basis", 5, 10),
        ("round_trip", 15, 1, "text", True),
        ("circle_trip", 16, 1, "text", True),
        ("open_jaw", 17, 1, "text", True),
        ("double_open_jaw", 18, 1, "text", True),
        ("end_on_end", 19, 1, "text", True),
        ("side_trip", 20, 1, "text", True),
        ("any_carrier", 21, 1, "text", True),
        ("same_rule_only", 22, 1, "text", True),
        ("action", 23, 1, "text", True),
    ),
)

LAYOUT = Layout([COMBINABILITY], tag_width=3)


@dataclass
class CombinabilityLoad:
    """A category 10 file, read but not applied."""

    records: list[Combinability] = field(default_factory=list)
    deleted: list[tuple[str, str]] = field(default_factory=list)
    problems: list[Problem] = field(default_factory=list)

    @property
    def clean(self) -> bool:
        return not self.problems

    def get(self, carrier: str, fare_basis: str) -> Combinability | None:
        for record in self.records:
            if record.carrier == carrier.upper() and record.fare_basis == fare_basis.upper():
                return record
        return None

    def __len__(self) -> int:
        return len(self.records)

    def __str__(self) -> str:
        return (
            f"{len(self.records)} records, {len(self.deleted)} deleted, "
            f"{len(self.problems)} problems"
        )


@dataclass
class CombinabilityApplied:
    """What a category 10 load did to the store."""

    added: int = 0
    replaced: int = 0
    deleted: int = 0
    unknown_deletes: int = 0
    combine_with_nothing: int = 0

    def __str__(self) -> str:
        return (
            f"{self.added} added, {self.replaced} replaced, {self.deleted} deleted, "
            f"{self.combine_with_nothing} that combine with nothing"
        )


def read(payload: str | bytes) -> CombinabilityLoad:
    """Read a category 10 file without touching a store."""
    reading = LAYOUT.read(payload)
    load = CombinabilityLoad(problems=list(reading.problems))

    for record in reading.tagged("C10"):
        try:
            _one(load, record.values)
        except (ValidationError, MalformedPayload) as exc:
            load.problems.append(Problem(record.line_number, str(record), exc.message))
    return load


def _one(load: CombinabilityLoad, values: dict[str, Any]) -> None:
    carrier = str(values["carrier"]).strip().upper()
    basis = str(values["fare_basis"]).strip().upper()
    if not basis:
        raise MalformedPayload("a combinability record names a fare basis", carrier=carrier)

    if _flag(values.get("action")) == DELETE:
        load.deleted.append((carrier, basis))
        return

    load.records.append(
        Combinability(
            carrier=carrier,
            fare_basis=basis,
            round_trip=_yes(values.get("round_trip")),
            circle_trip=_yes(values.get("circle_trip")),
            open_jaw=_yes(values.get("open_jaw")),
            double_open_jaw=_yes(values.get("double_open_jaw")),
            end_on_end=_yes(values.get("end_on_end")),
            side_trip=_yes(values.get("side_trip")),
            # Filed the other way round. The record says whether any carrier
            # will do, and the domain object says whether only ours will.
            same_carrier_only=not _yes(values.get("any_carrier")),
            same_rule_only=_yes(values.get("same_rule_only")),
        )
    )


def _flag(raw: Any) -> str:
    return "" if raw is None else str(raw).strip().upper()


def _yes(raw: Any) -> bool:
    return _flag(raw) == "Y"


def apply(store: CombinabilityStore, load: CombinabilityLoad) -> CombinabilityApplied:
    """Put a read file into a store, whole records at a time."""
    applied = CombinabilityApplied()
    for record in load.records:
        if store.get(record.carrier, record.fare_basis) is not None:
            applied.replaced += 1
        else:
            applied.added += 1
        store.add(record)
        if not any((record.round_trip, record.circle_trip, record.open_jaw, record.end_on_end)):
            applied.combine_with_nothing += 1

    for carrier, basis in load.deleted:
        if store.remove(carrier, basis):
            applied.deleted += 1
        else:
            applied.unknown_deletes += 1
    return applied


def load_into(
    store: CombinabilityStore, payload: str | bytes
) -> tuple[CombinabilityLoad, CombinabilityApplied]:
    """Read and apply in one call, for the ordinary case."""
    reading = read(payload)
    return reading, apply(store, reading)


def permissive(carrier: str, fare_basis: str) -> Combinability:
    """A record that permits everything, for a laptop and a first run.

    Never used by the loader and never reachable from a file. It exists so a
    test or a demonstration can price a round trip before anybody has filed
    category 10, and it is deliberately awkward to reach from anywhere else.
    """
    return Combinability(
        carrier=carrier,
        fare_basis=fare_basis,
        round_trip=True,
        circle_trip=True,
        open_jaw=True,
        double_open_jaw=True,
        end_on_end=True,
        side_trip=True,
        same_carrier_only=False,
    )
