"""The rule categories, and the three answers a provision can give.

Every published fare points at a rule, and a rule is a numbered set of
provisions. Category 5 says how far in advance it has to be bought, category 6
how long the passenger has to stay, category 16 what a change costs. The
numbers are the tariff's and they are stable, so they are the enum and not a
name we invented.

The part that catches people is the third answer. A provision does not pass or
fail, it passes, fails, or does not apply, and the difference between failing
and not applying is the difference between a fare that may not be sold and a
fare with no such condition. A rule set with no category 5 in it has no
advance purchase requirement, and treating that as a failure would refuse
every unrestricted fare in the tariff.

Provisions are evaluated against a context and never against each other.
Where one category genuinely depends on another, the dependency is in the
context, which is why the context carries the itinerary rather than a
summary of it.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date
from enum import Enum
from typing import Any

from gussetline.carriers import normalise_code
from gussetline.components import FareComponent
from gussetline.discounts import Passenger, PassengerType
from gussetline.errors import NoRuleFound, ValidationError
from gussetline.itinerary import Itinerary
from gussetline.trace import Stage, Trace


class Category(int, Enum):
    """The tariff's own numbering. Do not renumber these."""

    ELIGIBILITY = 1
    DAY_TIME = 2
    SEASONALITY = 3
    FLIGHT_APPLICATION = 4
    ADVANCE_RESERVATION = 5
    MINIMUM_STAY = 6
    MAXIMUM_STAY = 7
    STOPOVERS = 8
    TRANSFERS = 9
    COMBINABILITY = 10
    BLACKOUTS = 11
    SURCHARGES = 12
    ACCOMPANIED_TRAVEL = 13
    TRAVEL_RESTRICTIONS = 14
    SALES_RESTRICTIONS = 15
    PENALTIES = 16
    TICKET_ENDORSEMENT = 18
    CHILD_DISCOUNTS = 19
    VOLUNTARY_CHANGES = 31
    VOLUNTARY_REFUNDS = 33

    @property
    def label(self) -> str:
        return f"cat {self.value}"


class Answer(str, Enum):
    PASSES = "passes"
    FAILS = "fails"
    NOT_APPLICABLE = "not_applicable"


@dataclass(frozen=True, slots=True)
class Outcome:
    """What one provision decided, in the words the desk will read."""

    category: Category
    answer: Answer
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    @property
    def failed(self) -> bool:
        return self.answer is Answer.FAILS

    @property
    def applied(self) -> bool:
        return self.answer is not Answer.NOT_APPLICABLE

    def __str__(self) -> str:
        return f"{self.category.label} {self.answer.value}, {self.message}"


def passes(category: Category, message: str, **details: Any) -> Outcome:
    return Outcome(category, Answer.PASSES, message, dict(details))


def fails(category: Category, message: str, **details: Any) -> Outcome:
    return Outcome(category, Answer.FAILS, message, dict(details))


def silent(category: Category, message: str = "no provision filed") -> Outcome:
    return Outcome(category, Answer.NOT_APPLICABLE, message)


@dataclass(frozen=True, slots=True)
class Context:
    """Everything a provision is allowed to look at.

    Deliberately the whole itinerary and not a digest of it. Category 8 needs
    the stopovers, category 6 needs the arrival at the turnaround, and both of
    those are readings of the same journey that have to agree with each other
    and with the tax assessment.
    """

    itinerary: Itinerary
    component: FareComponent
    ticketing: date
    passenger: Passenger = field(default_factory=lambda: Passenger(PassengerType.ADULT))
    today: date | None = None
    reissue: bool = False

    @property
    def departure(self) -> date:
        return self.component.segments[0].departure.date()

    @property
    def journey_departure(self) -> date:
        return self.itinerary.departure.date()

    @property
    def return_departure(self) -> date | None:
        """When the passenger starts home, or None on a one way.

        Read off the first sector after the turnaround rather than off the
        last sector, because a journey with two sectors home starts home on
        the first of them and the minimum stay is measured from there.
        """
        turn = self.itinerary.turnaround_index()
        if turn <= 0 or turn >= len(self.itinerary.elements):
            return None
        return self.itinerary.segments[turn].departure.date()


class Provision:
    """One filed condition inside a rule."""

    category: Category = Category.ELIGIBILITY

    def evaluate(self, context: Context) -> Outcome:  # pragma: no cover - interface
        raise NotImplementedError


@dataclass(frozen=True, slots=True)
class RuleSet:
    """One numbered rule and everything filed inside it."""

    carrier: str
    number: str
    provisions: tuple[Provision, ...] = field(default_factory=tuple)
    tariff: str = "1"

    def __post_init__(self) -> None:
        object.__setattr__(self, "carrier", normalise_code(self.carrier))
        object.__setattr__(self, "number", self.number.strip())
        if not self.number:
            raise ValidationError("a rule has a number")

    def categories(self) -> tuple[Category, ...]:
        return tuple(dict.fromkeys(p.category for p in self.provisions))

    def has(self, category: Category) -> bool:
        return any(p.category is category for p in self.provisions)

    def for_category(self, category: Category) -> tuple[Provision, ...]:
        return tuple(p for p in self.provisions if p.category is category)

    def __len__(self) -> int:
        return len(self.provisions)

    def __str__(self) -> str:
        names = ",".join(str(c.value) for c in self.categories())
        return f"{self.carrier} rule {self.number} [{names or 'empty'}]"


class RuleStore:
    """Rules by carrier, number and tariff, which is how a fare names one."""

    def __init__(self) -> None:
        self._rules: dict[tuple[str, str, str], RuleSet] = {}

    def add(self, rule: RuleSet) -> RuleSet:
        self._rules[(rule.carrier, rule.number, rule.tariff)] = rule
        return rule

    def get(self, carrier: str, number: str, tariff: str = "1") -> RuleSet:
        key = (normalise_code(carrier), number.strip(), tariff.strip())
        try:
            return self._rules[key]
        except KeyError:
            raise NoRuleFound(
                "no rule filed", carrier=key[0], number=key[1], tariff=key[2]
            ) from None

    def find(self, carrier: str, number: str, tariff: str = "1") -> RuleSet | None:
        try:
            return self.get(carrier, number, tariff)
        except NoRuleFound:
            return None

    def __len__(self) -> int:
        return len(self._rules)


def evaluate(
    provisions: Iterable[Provision],
    context: Context,
    trace: Trace | None = None,
) -> tuple[Outcome, ...]:
    """Run every provision and keep all the answers, failures included.

    Nothing short circuits. A fare that fails category 5 and category 6 has
    two things wrong with it, and the desk needs both, because fixing the
    advance purchase and finding the fare still refused is the way an hour
    disappears.
    """
    outcomes: list[Outcome] = []
    for provision in provisions:
        outcome = provision.evaluate(context)
        outcomes.append(outcome)
        if trace is not None and outcome.applied:
            if outcome.failed:
                trace.failed_step(Stage.RULE, str(outcome), **outcome.details)
            else:
                trace.passed(Stage.RULE, str(outcome), **outcome.details)
    return tuple(outcomes)


def refusals(outcomes: Sequence[Outcome]) -> tuple[str, ...]:
    return tuple(str(o) for o in outcomes if o.failed)


def permitted(outcomes: Sequence[Outcome]) -> bool:
    return not any(o.failed for o in outcomes)
