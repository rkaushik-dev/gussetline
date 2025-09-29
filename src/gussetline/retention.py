"""What the desk throws away, and the three things it never does.

A year of collecting is nine million observations and forty gigabytes of raw
payloads, almost none of which anybody will ever read again. Keeping all of it
is a machine that fills up on a Sunday. Throwing away the wrong part of it is
worse, because the parts that matter are not the big ones.

An observation is a reading of a market at a moment and it rolls off. Once the
departure has gone, nobody will ever ask what the fare was, and the row is
several hundred bytes of nothing.

A recommendation never rolls off. It is what the desk told a customer, the
savings report reads it, and a report that changes when old rows are deleted is
a report nobody can put in front of a manager. There is no cutoff here that
touches one.

A run rolls off, but later than the observations do, because the reliability
report is read over a quarter and the coverage report reads runs to work out
what was polled.

And the archive is content addressed, which is the trap. The same payload seen
on thirty mornings is one blob and thirty manifest rows, so deleting the blob
because one of those rows is old takes the other twenty nine with it. Nothing
here deletes a blob that any surviving row still names.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import date, datetime, timedelta

from gussetline.errors import ValidationError

# How long each kind is kept when nobody says otherwise.
OBSERVATION_DAYS = 400
RUN_DAYS = 120
ARCHIVE_DAYS = 90


@dataclass(frozen=True, slots=True)
class Policy:
    """How long each kind of row is worth keeping.

    Recommendations are not here and there is no setting for them. A savings
    report that changes when old rows are deleted cannot be put in front of
    anybody, so the only safe number is no number at all.
    """

    observation_days: int = OBSERVATION_DAYS
    run_days: int = RUN_DAYS
    archive_days: int = ARCHIVE_DAYS

    def __post_init__(self) -> None:
        for name in ("observation_days", "run_days", "archive_days"):
            value = getattr(self, name)
            if value < 1:
                raise ValidationError("a retention is at least a day", setting=name, value=value)
        if self.run_days < self.archive_days:
            # The reliability report reads runs and the archive is what a
            # replay reads. Keeping payloads for a run that has been deleted
            # is disk with nothing to say about it.
            raise ValidationError(
                "runs are kept at least as long as the payloads they explain",
                runs=self.run_days,
                archive=self.archive_days,
            )

    def observations_before(self, today: date) -> date:
        return today - timedelta(days=self.observation_days)

    def runs_before(self, today: date) -> datetime:
        return datetime.combine(today - timedelta(days=self.run_days), datetime.min.time())

    def archive_before(self, today: date) -> datetime:
        return datetime.combine(today - timedelta(days=self.archive_days), datetime.min.time())

    def __str__(self) -> str:
        return (
            f"observations {self.observation_days}d, runs {self.run_days}d, "
            f"payloads {self.archive_days}d"
        )


@dataclass
class Swept:
    """What one sweep removed, per kind."""

    observations: int = 0
    runs: int = 0
    manifest_rows: int = 0
    blobs: int = 0
    blobs_kept: int = 0

    @property
    def anything(self) -> bool:
        return bool(self.observations or self.runs or self.manifest_rows or self.blobs)

    def __str__(self) -> str:
        return (
            f"{self.observations} observations, {self.runs} runs, "
            f"{self.blobs} payloads, {self.blobs_kept} payloads still referenced"
        )


def default_policy() -> Policy:
    """The numbers the desk ships with."""
    return Policy()


def keepable(rows: list[dict], before: datetime) -> tuple[list[dict], set[str]]:
    """Split a manifest into what survives and which blobs are still named.

    The digests come off the surviving rows and not off the deleted ones,
    which is the whole point: a payload seen on thirty mornings is one blob
    and thirty rows, and deleting it because one row is old takes the other
    twenty nine with it.
    """
    kept: list[dict] = []
    for row in rows:
        seen = row.get("seen_at", "")
        if not seen or datetime.fromisoformat(str(seen)) >= before:
            kept.append(row)
    return kept, {str(row["digest"]) for row in kept if row.get("digest")}
