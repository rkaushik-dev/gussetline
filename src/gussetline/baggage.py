"""What a passenger may carry, which is a comparison and not a lookup.

The desk quotes a reissue as a saving, and a passenger who loses a checked bag
in the process has not saved anything. A hundred euro drop onto a fare with one
fewer bag is a loss of about forty, and it arrives as a complaint rather than
as a number, because nothing in the price shows it.

So an allowance is compared and not merely reported, and the comparison is
awkward for three reasons.

Pieces and weight do not convert. A fare filed as two pieces and one filed as
thirty kilos are not orderable: whether the second is worse depends on what the
passenger packed, and a desk that invents a conversion is inventing an answer.
They come back as not comparable, which is a real verdict and not a failure.

The allowance is per component and the journey is not. A ticket with two pieces
across the Atlantic and one on the domestic connection carries the smaller of
them in practice, because the bag has to be checked through, and reporting the
larger is how a passenger arrives at a desk being asked for money.

And the most restrictive wins even when the carriers differ. An interline
journey applies the allowance of the carrier whose sector it is, and the
passenger meets the strictest of them at the first check in desk.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.offers import Baggage


class Verdict(str, Enum):
    """What a change of fare does to what the passenger may carry."""

    SAME = "same"
    BETTER = "better"
    WORSE = "worse"
    NOT_COMPARABLE = "not_comparable"
    UNKNOWN = "unknown"

    @property
    def costs_the_passenger(self) -> bool:
        """Whether the desk has to say something about it before quoting."""
        return self in (Verdict.WORSE, Verdict.NOT_COMPARABLE, Verdict.UNKNOWN)


@dataclass(frozen=True, slots=True)
class Change:
    """One comparison, and what it is worth if it goes the wrong way."""

    was: Baggage
    now: Baggage
    verdict: Verdict
    lost_pieces: int = 0
    lost_kilos: int = 0

    @property
    def worth_saying(self) -> bool:
        return self.verdict.costs_the_passenger

    def why(self) -> str:
        if self.verdict is Verdict.UNKNOWN:
            return "one of the fares does not state an allowance"
        if self.verdict is Verdict.NOT_COMPARABLE:
            return f"{self.was} and {self.now} are not the same kind of allowance"
        if self.verdict is Verdict.WORSE:
            if self.lost_pieces:
                return f"{self.lost_pieces} fewer checked pieces"
            return f"{self.lost_kilos} kilos less"
        if self.verdict is Verdict.BETTER:
            return "a larger allowance"
        return "the same allowance"

    def __str__(self) -> str:
        return f"{self.was} to {self.now}: {self.why()}"


def compare(was: Baggage, now: Baggage) -> Change:
    """What changing from one fare to another does to the allowance."""
    if not was.is_stated or not now.is_stated:
        # Not nothing. A fare with no allowance filed may include a bag or
        # may not, and quoting a saving against it without saying so is the
        # complaint this whole file exists to prevent.
        return Change(was, now, Verdict.UNKNOWN)

    if was.pieces is not None and now.pieces is not None:
        return _numbers(was, now, was.pieces, now.pieces, pieces=True)
    if was.kilos is not None and now.kilos is not None:
        return _numbers(was, now, was.kilos, now.kilos, pieces=False)

    # One of each. Whether thirty kilos beats two pieces depends on what the
    # passenger packed, and a desk that invents a conversion invents an answer.
    return Change(was, now, Verdict.NOT_COMPARABLE)


def _numbers(was: Baggage, now: Baggage, before: int, after: int, pieces: bool) -> Change:
    if after == before:
        return Change(was, now, Verdict.SAME)
    if after > before:
        return Change(was, now, Verdict.BETTER)
    lost = before - after
    return Change(
        was,
        now,
        Verdict.WORSE,
        lost_pieces=lost if pieces else 0,
        lost_kilos=0 if pieces else lost,
    )


def smallest(allowances: list[Baggage]) -> Baggage:
    """What a journey actually carries, which is the least of its sectors.

    The bag is checked through, so the passenger meets the strictest sector's
    allowance at the first desk. Reporting the largest is how somebody arrives
    at check in being asked for money.

    Two kinds together give nothing stated, because the honest answer to what
    a two piece and a thirty kilo journey carries is that somebody has to look.
    """
    stated = [one for one in allowances if one.is_stated]
    if not stated:
        return Baggage()

    pieces = [one.pieces for one in stated if one.pieces is not None]
    kilos = [one.kilos for one in stated if one.kilos is not None]
    if pieces and kilos:
        return Baggage()
    if pieces:
        return Baggage(pieces=min(pieces))
    return Baggage(kilos=min(kilos))


def worth_of(change: Change, per_piece: Money, per_kilo: Money | None = None) -> Money:
    """What a lost allowance costs the passenger, at the desk's own rates.

    Only what is lost. A better allowance is not a saving to be added to a
    recommendation: the passenger was not going to be paid for the bag they
    did not check, and counting it makes every recommendation look larger
    than the money that actually moves.
    """
    if change.verdict is not Verdict.WORSE:
        return Money.zero(per_piece.currency)
    if change.lost_pieces:
        return per_piece * change.lost_pieces
    if per_kilo is None:
        raise ValidationError("a weight allowance needs a rate per kilo", kilos=change.lost_kilos)
    return per_kilo * change.lost_kilos


def net_benefit(
    saving: Money, change: Change, per_piece: Money, per_kilo: Money | None = None
) -> Money:
    """A saving with the lost allowance taken off it.

    This is the number the desk should be quoting. A hundred euro drop onto a
    fare with one fewer bag is a saving of about forty, and quoting the
    hundred is how a recommendation becomes a complaint.
    """
    cost = worth_of(change, per_piece, per_kilo)
    if cost.currency != saving.currency:
        raise ValidationError(
            "a benefit is worked out in one currency",
            saving=saving.currency,
            cost=cost.currency,
        )
    return saving - cost
