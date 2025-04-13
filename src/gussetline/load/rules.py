"""Loading rule records, which arrive as a stream and not as a list.

The fare feed is a bag of independent records. The rule feed is not. A rule
header opens a rule and every category record after it belongs to that rule
until the next header, so the order of the lines is part of the data and a
loader that sorts them or reads them in parallel gets the wrong answer
without ever failing.

That has two consequences worth writing down.

A category record before any header is an orphan. It cannot be attached to
anything, and it usually means the file was cut in the middle by whatever
moved it, so it is recorded as a problem rather than dropped. A file that
starts with forty orphans is a truncated file and somebody needs to know that
rather than getting a very quiet partial load.

And a rule is replaced whole, never amended in place. There is no record that
says remove category 6 from rule 100, because the carrier does not think of it
that way: it refiles the rule with the categories it now has. So a header with
no category records after it is not a mistake and not an empty file. It is a
rule with no conditions, which is a real thing an unrestricted fare points at,
and a loader that skips it leaves the previous version of that rule in place
with all its restrictions still attached.

Every category is optional and an absent one means unrestricted. That is the
tariff's own convention and it is the reason nothing here fills in a default:
a category 5 with no days in it says the fare may be bought at any time, and
writing a zero there instead would say it has to be bought on the day.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date
from decimal import Decimal
from typing import Any

from gussetline.collect.fixedwidth import Layout, Problem, RecordSpec, fields
from gussetline.errors import MalformedPayload, ValidationError
from gussetline.money import Money
from gussetline.rules.base import Category, Provision, RuleSet, RuleStore
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Applies, Penalty
from gussetline.rules.purchase import AdvancePurchase
from gussetline.rules.sales import FormOfPayment, SalesRestriction
from gussetline.rules.stay import MaximumStay, MinimumStay

HEADER = "RHD"
ADD = "A"
REPLACE = "R"
DELETE = "D"

RULE_HEADER = RecordSpec(
    tag=HEADER,
    fields=fields(
        ("carrier", 3, 2),
        ("number", 5, 4),
        ("tariff", 9, 3),
        ("action", 12, 1),
    ),
)

ADVANCE = RecordSpec(
    tag="R05",
    fields=fields(
        ("reservation_days", 3, 3, "int", True),
        ("ticketing_after", 6, 3, "int", True),
        ("ticketing_before", 9, 3, "int", True),
    ),
)

MINIMUM = RecordSpec(
    tag="R06",
    fields=fields(
        ("days", 3, 3, "int", True),
        ("weekday", 6, 3, "text", True),
    ),
)

MAXIMUM = RecordSpec(
    tag="R07",
    fields=fields(
        ("days", 3, 3, "int", True),
        ("months", 6, 2, "int", True),
        ("from_turnaround", 8, 1, "text", True),
    ),
)

SALES = RecordSpec(
    tag="R15",
    fields=fields(
        ("countries", 3, 12, "text", True),
        ("excluded", 15, 12, "text", True),
        ("payment", 27, 1, "text", True),
        ("first_sale", 28, 8, "date", True),
        ("last_sale", 36, 8, "date", True),
    ),
)

PENALTY = RecordSpec(
    tag="R16",
    fields=fields(
        ("action", 3, 1),
        ("permitted", 4, 1),
        ("currency", 5, 3, "text", True),
        ("before", 8, 9, "amount", True),
        ("after", 17, 9, "amount", True),
        ("applies", 26, 1, "text", True),
        ("no_show_forfeits", 27, 1, "text", True),
    ),
)

CHANGES = RecordSpec(
    tag="R31",
    fields=fields(
        ("permitted", 3, 1),
        ("basis", 4, 1),
        ("residual", 5, 1),
        ("keep_breaks", 6, 1, "text", True),
        ("same_carrier", 7, 1, "text", True),
        ("once_only", 8, 1, "text", True),
    ),
)

LAYOUT = Layout(
    [RULE_HEADER, ADVANCE, MINIMUM, MAXIMUM, SALES, PENALTY, CHANGES],
    tag_width=3,
)

# One letter codes the feed uses, and what they mean here.
ACTIONS: dict[str, Action] = {
    "C": Action.CHANGE,
    "X": Action.CANCEL,
    "N": Action.NO_SHOW,
}

APPLIES: dict[str, Applies] = {
    "T": Applies.PER_TICKET,
    "C": Applies.PER_COMPONENT,
    "H": Applies.HIGHEST_OF,
}

BASES: dict[str, RepricingBasis] = {
    "C": RepricingBasis.CURRENT,
    "H": RepricingBasis.HISTORICAL,
}

RESIDUALS: dict[str, Residual] = {
    "R": Residual.REFUNDABLE,
    "C": Residual.RETAINED,
    "F": Residual.FORFEITED,
}

PAYMENTS: dict[str, FormOfPayment] = {
    "A": FormOfPayment.ANY,
    "C": FormOfPayment.CARD,
    "H": FormOfPayment.CASH,
}


@dataclass
class RuleLoad:
    """A rule file, read but not applied."""

    rules: list[RuleSet] = field(default_factory=list)
    deleted: list[tuple[str, str, str]] = field(default_factory=list)
    problems: list[Problem] = field(default_factory=list)
    orphans: int = 0

    @property
    def clean(self) -> bool:
        return not self.problems

    def get(self, carrier: str, number: str) -> RuleSet | None:
        for rule in self.rules:
            if rule.carrier == carrier.upper() and rule.number == number:
                return rule
        return None

    def __len__(self) -> int:
        return len(self.rules)

    def __str__(self) -> str:
        return (
            f"{len(self.rules)} rules, {len(self.deleted)} deleted, {len(self.problems)} problems"
        )


@dataclass
class RulesApplied:
    """What a rule load did to the store."""

    loaded: int = 0
    replaced: int = 0
    deleted: int = 0
    unknown_deletes: int = 0
    empty_rules: int = 0

    def __str__(self) -> str:
        return (
            f"{self.loaded} rules loaded, {self.replaced} of them replacing one, "
            f"{self.deleted} deleted, {self.empty_rules} with no conditions"
        )


class _Building:
    """A rule being assembled out of the records following its header."""

    def __init__(self, carrier: str, number: str, tariff: str, action: str) -> None:
        self.carrier = carrier
        self.number = number
        self.tariff = tariff
        self.action = action
        self.provisions: list[Provision] = []

    def finish(self) -> RuleSet:
        return RuleSet(
            carrier=self.carrier,
            number=self.number,
            tariff=self.tariff,
            provisions=tuple(self.provisions),
        )


def read(payload: str | bytes) -> RuleLoad:
    """Walk the records in order, closing each rule when the next opens."""
    reading = LAYOUT.read(payload)
    load = RuleLoad(problems=list(reading.problems))
    building: _Building | None = None

    for record in reading.records:
        if record.tag == HEADER:
            if building is not None:
                _close(load, building)
            building = _open(record.values)
            continue

        if building is None:
            load.orphans += 1
            load.problems.append(
                Problem(record.line_number, str(record), "a category record with no rule above it")
            )
            continue

        try:
            building.provisions.append(_provision(record.tag, record.values))
        except (ValidationError, MalformedPayload) as exc:
            load.problems.append(Problem(record.line_number, str(record), exc.message))

    if building is not None:
        _close(load, building)
    return load


def _open(values: dict[str, Any]) -> _Building:
    return _Building(
        carrier=str(values["carrier"]).strip().upper(),
        number=str(values["number"]).strip(),
        tariff=str(values["tariff"]).strip(),
        action=str(values["action"]).strip().upper() or ADD,
    )


def _close(load: RuleLoad, building: _Building) -> None:
    if building.action == DELETE:
        load.deleted.append((building.carrier, building.number, building.tariff))
        return
    load.rules.append(building.finish())


def _provision(tag: str, values: dict[str, Any]) -> Provision:
    if tag == "R05":
        return AdvancePurchase(
            reservation_days=_int(values.get("reservation_days")),
            ticketing_days_after_reservation=_int(values.get("ticketing_after")),
            ticketing_days_before_departure=_int(values.get("ticketing_before")),
        )
    if tag == "R06":
        return MinimumStay(days=_int(values.get("days")), weekday=_text(values.get("weekday")))
    if tag == "R07":
        return MaximumStay(
            days=_int(values.get("days")),
            months=_int(values.get("months")),
            from_turnaround=_flag(values.get("from_turnaround")),
        )
    if tag == "R15":
        return _sales(values)
    if tag == "R16":
        return _penalty(values)
    if tag == "R31":
        return _changes(values)
    raise MalformedPayload("no reader for that category record", tag=tag)


def _sales(values: dict[str, Any]) -> SalesRestriction:
    return SalesRestriction(
        countries=_codes(values.get("countries")),
        excluded_countries=_codes(values.get("excluded")),
        payment=_looked_up(PAYMENTS, values.get("payment"), FormOfPayment.ANY, "payment"),
        first_sale=_date(values.get("first_sale")),
        last_sale=_date(values.get("last_sale")),
    )


def _penalty(values: dict[str, Any]) -> Penalty:
    currency = _text(values.get("currency")) or "EUR"
    return Penalty(
        action=_looked_up(ACTIONS, values.get("action"), Action.CHANGE, "penalty action"),
        permitted=not _flag(values.get("permitted"), true="N"),
        before_departure=_money(values.get("before"), currency),
        after_departure=_money(values.get("after"), currency),
        applies=_looked_up(APPLIES, values.get("applies"), Applies.PER_TICKET, "penalty scope"),
        no_show_forfeits=_flag(values.get("no_show_forfeits")),
    )


def _changes(values: dict[str, Any]) -> VoluntaryChange:
    return VoluntaryChange(
        permitted=not _flag(values.get("permitted"), true="N"),
        basis=_looked_up(BASES, values.get("basis"), RepricingBasis.CURRENT, "repricing basis"),
        residual=_looked_up(RESIDUALS, values.get("residual"), Residual.FORFEITED, "residual"),
        keep_fare_breaks=_flag(values.get("keep_breaks")),
        same_carrier_only=_flag(values.get("same_carrier")),
        once_only=_flag(values.get("once_only")),
    )


def _looked_up(table: dict[str, Any], raw: Any, fallback: Any, what: str) -> Any:
    """Read a one letter code, where blank means the tariff's own default."""
    code = _text(raw)
    if code is None:
        return fallback
    if code not in table:
        raise MalformedPayload("no such code", what=what, code=code)
    return table[code]


def _int(raw: Any) -> int | None:
    return None if raw is None else int(raw)


def _text(raw: Any) -> str | None:
    if raw is None:
        return None
    value = str(raw).strip().upper()
    return value or None


def _flag(raw: Any, true: str = "Y") -> bool:
    return _text(raw) == true


def _codes(raw: Any) -> tuple[str, ...]:
    value = _text(raw)
    if value is None:
        return ()
    return tuple(part for part in value.split("/") if part)


def _date(raw: Any) -> date | None:
    return raw if isinstance(raw, date) else None


def _money(raw: Any, currency: str) -> Money | None:
    if raw is None:
        return None
    return Money(Decimal(str(raw)), currency)


def apply(store: RuleStore, load: RuleLoad) -> RulesApplied:
    """Swap whole rules into the store, in the order they were filed."""
    applied = RulesApplied()
    for rule in load.rules:
        if store.find(rule.carrier, rule.number, rule.tariff) is not None:
            applied.replaced += 1
        store.add(rule)
        applied.loaded += 1
        if not rule.provisions:
            applied.empty_rules += 1
    for carrier, number, tariff in load.deleted:
        if store.remove(carrier, number, tariff):
            applied.deleted += 1
        else:
            applied.unknown_deletes += 1
    return applied


def load_into(store: RuleStore, payload: str | bytes) -> tuple[RuleLoad, RulesApplied]:
    """Read and apply in one call, for the ordinary case."""
    reading = read(payload)
    return reading, apply(store, reading)


def categories_of(load: RuleLoad) -> dict[int, int]:
    """How many of each category the file carried, for the load report."""
    counts: dict[int, int] = {}
    for rule in load.rules:
        for provision in rule.provisions:
            key = int(provision.category)
            counts[key] = counts.get(key, 0) + 1
    return counts


def missing_categories(rule: RuleSet, wanted: tuple[Category, ...]) -> tuple[Category, ...]:
    """Which of a set of categories a rule does not file. Absent is not zero."""
    return tuple(category for category in wanted if not rule.has(category))
