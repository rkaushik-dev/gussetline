"""Loading a fare filing feed, which is an amendment file and not a snapshot.

This is the distinction the first version of this got wrong and it cost a
month of quietly stale prices. A filing feed does not describe the tariff. It
describes the changes to it since the last one, and every record carries an
action saying which: add, change, or delete. Loading one as though it were a
full file leaves every deleted fare live in the store forever, and a deleted
fare is exactly the one a carrier withdrew because it was too cheap, so the
desk goes on quoting a price nobody will sell.

Three consequences, and all three are here rather than in the caller.

A delete for a fare we do not hold is not an error. Feeds are replayed, files
overlap at the edges, and we do not load every market. It is counted so a
number that suddenly jumps says something, and it is otherwise ignored.

A change for a fare we do not hold is an add. The alternative is to drop it
and wait for a full refresh that may be a week away, which means the desk
prices off a fare the carrier has already amended.

And a record the layout can read but the tariff refuses, a sale window that
closes before it opens or an amount of nothing, is one bad record and not a
bad file. It goes in the problems list against its line number, the rest of
the file loads, and somebody reads the list in the morning.

The other thing worth stating is that a load is worked out in full before any
of it is applied. A file that fails halfway through would otherwise leave the
store holding the first half of an amendment, which is worse than holding
none of it, because nothing afterwards can tell that is what happened.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from enum import Enum

from gussetline.collect.fixedwidth import Layout, Problem, Reading, RecordSpec, fields
from gussetline.errors import MalformedPayload, ValidationError
from gussetline.flights import Cabin
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.tariff import Fare, FareType, OwRt, TariffStore

# The one letter each filing carries saying what to do with it.
ADD = "A"
CHANGE = "C"
DELETE = "D"

# Fare types as the feed spells them, which is not how we spell them.
FARE_TYPES: dict[str, FareType] = {
    "P": FareType.PUBLISHED,
    "V": FareType.PRIVATE,
    "N": FareType.NEGOTIATED,
    "T": FareType.INCLUSIVE_TOUR,
    "C": FareType.CORPORATE,
}

FARE_RECORD = RecordSpec(
    tag="FR",
    fields=fields(
        ("carrier", 2, 2),
        ("origin", 4, 3),
        ("destination", 7, 3),
        ("fare_basis", 10, 8),
        ("currency", 18, 3),
        ("amount", 21, 9, "amount"),
        ("owrt", 30, 1),
        ("global_indicator", 31, 2),
        ("rule_number", 33, 4),
        ("routing_number", 37, 4),
        ("tariff_number", 41, 3),
        ("footnote", 44, 2, "text", True),
        ("cabin", 46, 1),
        ("fare_type", 47, 1),
        ("effective", 48, 8, "date"),
        ("discontinue", 56, 8, "date"),
        ("first_travel", 64, 8, "date"),
        ("last_travel", 72, 8, "date"),
        ("action", 80, 1),
    ),
)

# The header says which carrier and which day the file is for. It is checked
# rather than trusted, because a file loaded against the wrong carrier looks
# like a very successful load.
HEADER_RECORD = RecordSpec(
    tag="HD",
    fields=fields(
        ("carrier", 2, 2),
        ("filed_on", 4, 8, "date"),
        ("sequence", 12, 6, "int"),
    ),
)

TRAILER_RECORD = RecordSpec(
    tag="TR",
    fields=fields(
        ("records", 2, 6, "int"),
    ),
)

LAYOUT = Layout([HEADER_RECORD, FARE_RECORD, TRAILER_RECORD])


class Action(str, Enum):
    """What a filing says to do with the fare it carries."""

    ADDED = "added"
    CHANGED = "changed"
    DELETED = "deleted"


@dataclass(frozen=True, slots=True)
class Filing:
    """One record, read and turned into something the store can take."""

    action: Action
    fare: Fare
    line_number: int = 0

    def __str__(self) -> str:
        return f"{self.action.value} {self.fare}"


@dataclass
class Load:
    """A whole file, read but not yet applied."""

    carrier: str = ""
    filed_on: date | None = None
    sequence: int = 0
    filings: list[Filing] = field(default_factory=list)
    problems: list[Problem] = field(default_factory=list)
    declared: int | None = None

    @property
    def clean(self) -> bool:
        return not self.problems

    @property
    def counted(self) -> bool:
        """Whether the trailer's count agrees with what was read."""
        if self.declared is None:
            return True
        return self.declared == len(self.filings) + len(self.problems)

    def of(self, action: Action) -> list[Filing]:
        return [f for f in self.filings if f.action is action]

    def __len__(self) -> int:
        return len(self.filings)

    def __str__(self) -> str:
        when = self.filed_on.isoformat() if self.filed_on else "no date"
        return f"{self.carrier} {when}: {len(self.filings)} filings, {len(self.problems)} problems"


@dataclass
class Applied:
    """What a load did to the store."""

    added: int = 0
    changed: int = 0
    deleted: int = 0
    unknown_deletes: int = 0
    changed_as_adds: int = 0

    @property
    def touched(self) -> int:
        return self.added + self.changed + self.deleted

    def __str__(self) -> str:
        return (
            f"{self.added} added, {self.changed} changed, {self.deleted} deleted, "
            f"{self.unknown_deletes} deletes for fares we do not hold"
        )


def read(payload: str | bytes) -> Load:
    """Read a filing file without touching the store."""
    reading = LAYOUT.read(payload)
    load = Load(problems=list(reading.problems))
    _read_header(reading, load)
    _read_trailer(reading, load)

    for record in reading.tagged("FR"):
        try:
            load.filings.append(_filing(record.values, record.line_number))
        except (ValidationError, MalformedPayload) as exc:
            load.problems.append(Problem(record.line_number, str(record), exc.message))
    return load


def _read_header(reading: Reading, load: Load) -> None:
    header = reading.first("HD")
    if header is None:
        return
    load.carrier = str(header["carrier"]).strip().upper()
    load.filed_on = header["filed_on"]
    load.sequence = int(header["sequence"])


def _read_trailer(reading: Reading, load: Load) -> None:
    trailer = reading.first("TR")
    if trailer is not None:
        load.declared = int(trailer["records"])


def _filing(values: dict[str, object], line_number: int) -> Filing:
    """Turn one read record into a filing, or refuse it by itself."""
    action = _action(str(values["action"]))
    fare = Fare(
        carrier=str(values["carrier"]),
        origin=str(values["origin"]),
        destination=str(values["destination"]),
        fare_basis=str(values["fare_basis"]),
        amount=Money(Decimal(str(values["amount"])), str(values["currency"]).strip().upper()),
        owrt=OwRt(str(values["owrt"]).strip()),
        global_indicator=_indicator(str(values["global_indicator"])),
        rule_number=str(values["rule_number"]).strip(),
        routing_number=str(values["routing_number"]).strip(),
        tariff_number=str(values["tariff_number"]).strip(),
        footnote=str(values.get("footnote") or "").strip(),
        cabin=_cabin(str(values["cabin"])),
        fare_type=_fare_type(str(values["fare_type"])),
        effective=_as_date(values["effective"]),
        discontinue=_as_date(values["discontinue"]),
        first_travel=_as_date(values["first_travel"]),
        last_travel=_as_date(values["last_travel"]),
    )
    return Filing(action=action, fare=fare, line_number=line_number)


def _action(raw: str) -> Action:
    code = raw.strip().upper()
    if code == ADD:
        return Action.ADDED
    if code == CHANGE:
        return Action.CHANGED
    if code == DELETE:
        return Action.DELETED
    raise MalformedPayload("that is not a filing action", action=raw)


def _indicator(raw: str) -> GlobalIndicator:
    try:
        return GlobalIndicator(raw.strip().upper())
    except ValueError as exc:
        raise MalformedPayload("no such global indicator", indicator=raw) from exc


def _cabin(raw: str) -> Cabin:
    try:
        return Cabin(raw.strip().upper())
    except ValueError as exc:
        raise MalformedPayload("no such cabin", cabin=raw) from exc


def _fare_type(raw: str) -> FareType:
    code = raw.strip().upper()
    if code not in FARE_TYPES:
        raise MalformedPayload("no such fare type", fare_type=raw)
    return FARE_TYPES[code]


def _as_date(value: object) -> date:
    if not isinstance(value, date):
        raise MalformedPayload("that field is not a date", value=str(value))
    return value


def apply(store: TariffStore, load: Load) -> Applied:
    """Put a read file into the store, all of it or none of it.

    Nothing here can fail partway: the load was validated when it was read,
    and every operation below is a dictionary write the store already knows
    how to do. That is the whole reason reading and applying are two calls.
    """
    applied = Applied()
    for filing in load.filings:
        if filing.action is Action.DELETED:
            if store.remove(filing.fare):
                applied.deleted += 1
            else:
                applied.unknown_deletes += 1
            continue

        replaced = store.replace(filing.fare)
        if filing.action is Action.CHANGED:
            if replaced:
                applied.changed += 1
            else:
                applied.changed_as_adds += 1
                applied.added += 1
        else:
            applied.added += 1
    return applied


def load_into(store: TariffStore, payload: str | bytes) -> tuple[Load, Applied]:
    """Read and apply in one call, for the ordinary case."""
    reading = read(payload)
    return reading, apply(store, reading)
