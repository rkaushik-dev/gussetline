"""The trail a price leaves behind it.

The product is not the number. The desk can get a number from any supplier for
free. The product is being able to say, in one screen, that the fare selected
was this one, that the routing passed, that the mileage came in at a hundred
and four percent of the maximum so a five percent surcharge applied, that the
higher intermediate point check raised the component to the Chicago fare, and
that the supplier's total is therefore eleven euro light.

So every decision writes a step. This costs a little on every price and it is
the reason a discrepancy takes two minutes to explain rather than an afternoon
of rerunning things by hand.

A trace never raises. Recording a failure is not the same as failing, because
some checks are allowed to fail and be recovered from, and a trace that threw
on the first refusal could not describe the alternative that was taken.
"""

from __future__ import annotations

from collections.abc import Iterable, Iterator
from dataclasses import dataclass, field
from enum import Enum
from typing import Any


class Stage(str, Enum):
    """The parts of a price that can each pass or refuse on their own."""

    SELECTION = "selection"
    ROUTING = "routing"
    MILEAGE = "mileage"
    MINIMUM = "minimum"
    RULE = "rule"
    COMBINABILITY = "combinability"
    SURCHARGE = "surcharge"
    CONVERSION = "conversion"
    TAX = "tax"
    AUDIT = "audit"


class Verdict(str, Enum):
    PASS = "pass"
    FAIL = "fail"
    INFO = "info"


@dataclass(frozen=True, slots=True)
class Step:
    """One recorded decision."""

    stage: Stage
    verdict: Verdict
    message: str
    details: dict[str, Any] = field(default_factory=dict)

    @property
    def failed(self) -> bool:
        return self.verdict is Verdict.FAIL

    def as_dict(self) -> dict[str, Any]:
        return {
            "stage": self.stage.value,
            "verdict": self.verdict.value,
            "message": self.message,
            "details": dict(self.details),
        }

    def __str__(self) -> str:
        mark = {"pass": "ok", "fail": "no", "info": "  "}[self.verdict.value]
        rendered = ""
        if self.details:
            rendered = " " + " ".join(f"{k}={v}" for k, v in sorted(self.details.items()))
        return f"{mark} {self.stage.value:<13} {self.message}{rendered}"


class Trace:
    """An ordered record of what a price did and why."""

    def __init__(self, subject: str = "") -> None:
        self.subject = subject
        self._steps: list[Step] = []

    def record(
        self,
        stage: Stage,
        verdict: Verdict,
        message: str,
        **details: Any,
    ) -> Step:
        step = Step(stage, verdict, message, dict(details))
        self._steps.append(step)
        return step

    def passed(self, stage: Stage, message: str, **details: Any) -> Step:
        return self.record(stage, Verdict.PASS, message, **details)

    def failed_step(self, stage: Stage, message: str, **details: Any) -> Step:
        return self.record(stage, Verdict.FAIL, message, **details)

    def note(self, stage: Stage, message: str, **details: Any) -> Step:
        return self.record(stage, Verdict.INFO, message, **details)

    @property
    def steps(self) -> tuple[Step, ...]:
        return tuple(self._steps)

    @property
    def failed(self) -> bool:
        return any(step.failed for step in self._steps)

    def failures(self) -> tuple[Step, ...]:
        return tuple(step for step in self._steps if step.failed)

    def reasons(self) -> tuple[str, ...]:
        """Just the sentences, which is what a report prints."""
        return tuple(step.message for step in self._steps if step.failed)

    def for_stage(self, stage: Stage) -> tuple[Step, ...]:
        return tuple(step for step in self._steps if step.stage is stage)

    def stage_failed(self, stage: Stage) -> bool:
        return any(step.failed for step in self.for_stage(stage))

    def extend(self, other: Trace, prefix: str = "") -> None:
        """Fold another trace in, which is how a component joins a journey.

        The prefix names which component the steps came from, because a
        journey with three components produces three mileage checks and a
        trail that cannot tell them apart is not a trail.
        """
        for step in other.steps:
            details = dict(step.details)
            if prefix:
                details["component"] = prefix
            self._steps.append(Step(step.stage, step.verdict, step.message, details))

    def as_list(self) -> list[dict[str, Any]]:
        return [step.as_dict() for step in self._steps]

    def render(self) -> str:
        head = f"trace {self.subject}" if self.subject else "trace"
        return "\n".join([head, *(str(step) for step in self._steps)])

    def __len__(self) -> int:
        return len(self._steps)

    def __iter__(self) -> Iterator[Step]:
        return iter(self._steps)

    def __bool__(self) -> bool:
        """A trace is truthy when it holds steps, not when it passed.

        Spelled out because ``if trace:`` reads either way and the wrong
        reading turns an empty trace into a silent pass.
        """
        return bool(self._steps)


def combine(traces: Iterable[Trace], subject: str = "") -> Trace:
    """Fold several traces into one, keeping each one's name on its steps."""
    combined = Trace(subject)
    for index, trace in enumerate(traces, start=1):
        combined.extend(trace, trace.subject or f"fc{index}")
    return combined
