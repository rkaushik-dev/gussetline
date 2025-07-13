"""The night command, which is the run the desk is actually paid for.

Everything before this collects and prices. This is where a price becomes
something a person will phone a customer about, and it is the only command
that both reads and writes the desk's own tables in one transaction.

One transaction on purpose. The session expires stale recommendations, retires
watches, withdraws superseded recommendations and raises new ones, and those
four have to land together. A run that expired forty and then failed before
raising anything leaves the desk with an empty queue and no way to tell that
is what happened.

Where the rules come from is the part worth stating. A ticket carries fare
bases and not rule numbers, so the fares have to be read to get from one to the
other, and a ticket whose fare basis has rolled out of the tariff gets no rule
at all. That is not a failure and it is not a permission either: the watch is
left alone, counted, and reported, because guessing that a change is permitted
is how a desk quotes a reissue the carrier refuses at the counter.

The dry run reads everything and writes nothing. It is what runs before the
first real night against a new tariff, and the number it prints is what the
desk would have been told.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

import click
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from gussetline.cli.desk import Desk, pass_desk
from gussetline.components import FareComponent
from gussetline.desk.recommend import Recommendation
from gussetline.desk.session import Night, run
from gussetline.desk.ticket import Coupon
from gussetline.desk.watch import Observation, Watch
from gussetline.flights import Segment
from gussetline.itinerary import Itinerary
from gussetline.persistence.deskmap import (
    apply_recommendation,
    observation_row_to_reading,
    recommendation_to_row,
    row_to_recommendation,
    row_to_watch,
)
from gussetline.persistence.models import (
    ObservationRow,
    RecommendationRow,
    TicketRow,
    WatchRow,
)
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.rules.base import Category, Context, RuleSet, RuleStore
from gussetline.rules.changes import VoluntaryChange
from gussetline.rules.penalties import Penalty

# How many readings a watch is given. The hysteresis is two or three, so a
# handful is plenty, and reading a year of them for nine thousand watches is
# how the first version of this took eleven minutes.
READINGS = 8

# A made up block time for a coupon, which carries a departure and no arrival.
# Nothing in the reprice reads it; it exists so the segment will build.
NOMINAL_BLOCK_HOURS = 2

# How many lines of each kind the run prints before it stops.
SHOWN = 20


@click.command("night")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--dry-run", is_flag=True, help="Work it all out and write nothing.")
@pass_desk
def night(desk: Desk, today: datetime | None, dry_run: bool) -> None:
    """Reprice every live watch and raise what is worth raising."""
    when = today.date() if today else date.today()

    with desk.database.unit() as work:
        rows = _live(work, when)
        if not rows:
            desk.say("no live watches")
            return

        watches = [_watch_from(work, row) for row in rows]
        book = _open_book(work)
        rules = _rules_for(work, watches, when)
        report = run(watches, book, rules, when, desk.settings.selling_currency)

        _say(desk, report, len(rules.missing))
        if dry_run:
            desk.say("dry run, nothing written")
            return

        _write(work, rows, watches, report)
        work.commit()

    desk.say(f"wrote {len(report.raised)} recommendations")


def _live(work: UnitOfWork, when: date) -> list[WatchRow]:
    """Every watch still worth checking, with its ticket and coupons already in."""
    return list(
        work.session.scalars(
            select(WatchRow)
            .options(selectinload(WatchRow.ticket).selectinload(TicketRow.coupons))
            .where(WatchRow.active, WatchRow.departure >= when)
            .order_by(WatchRow.departure)
        ).all()
    )


def _watch_from(work: UnitOfWork, row: WatchRow) -> Watch:
    """One watch with the last few readings on it, oldest first.

    Oldest first because the hysteresis reads the tail of the list, and a
    history in the other order would report the dearest of the oldest few.

    A stored observation is a price and not a poll. The table is keyed on
    content, so a market that sat at the same number for three mornings is one
    row with a sighting count of three, and reading one reading off it tells
    the hysteresis that the price has been seen once. That is the difference
    between a drop that has held for three days and one seen this morning, so
    each row is expanded by the number of times it was actually seen.
    """
    rows = work.session.scalars(
        select(ObservationRow)
        .where(ObservationRow.series_key == row.series_key)
        .order_by(ObservationRow.last_seen.desc())
        .limit(READINGS)
    ).all()

    history: list[Observation] = []
    for stored in reversed(rows):
        reading = observation_row_to_reading(stored)
        history.extend([reading] * max(stored.sightings, 1))
    return row_to_watch(row, history[-READINGS:])


def _open_book(work: UnitOfWork) -> list[Recommendation]:
    """Every recommendation still open, as the objects the session moves."""
    rows = work.session.scalars(
        select(RecommendationRow)
        .options(selectinload(RecommendationRow.watch).selectinload(WatchRow.ticket))
        .where(RecommendationRow.state == "open")
    ).all()
    return [
        row_to_recommendation(row, row.watch.ticket.number, row.watch.series_key) for row in rows
    ]


class Rules:
    """What the session asks for, answered out of the rule store.

    Holds the watches it could not answer for rather than raising, because one
    ticket whose fare basis has left the tariff must not stop the other eight
    thousand from being priced.
    """

    def __init__(self, store: RuleStore, numbers: dict[tuple[str, str], tuple[str, str]]) -> None:
        self.store = store
        self.numbers = numbers
        self.missing: list[str] = []

    def __call__(self, watch: Watch) -> tuple[VoluntaryChange, list[Penalty], Context]:
        rule = self.rule_for(watch)
        context = _context(watch)
        if rule is None:
            self.missing.append(watch.series_key)
            # Not permitted rather than permitted. Guessing the other way
            # quotes a reissue the carrier refuses at the counter.
            return VoluntaryChange(permitted=False), [], context

        filed = rule.for_category(Category.VOLUNTARY_CHANGES)
        changes = [p for p in filed if isinstance(p, VoluntaryChange)]
        penalties = [p for p in rule.for_category(Category.PENALTIES) if isinstance(p, Penalty)]
        change = changes[0] if changes else VoluntaryChange(permitted=False)
        return change, penalties, context

    def rule_for(self, watch: Watch) -> RuleSet | None:
        """The rule this ticket's fare points at, in that fare's own tariff.

        The tariff number comes off the fare and is not defaulted. A carrier
        files rule 100 in tariff 1 and a different rule 100 in tariff 3, and
        looking the first one up under the default finds nothing, which reads
        as a fare with no rule and retires the watch.
        """
        carrier = watch.ticket.validating_carrier
        for basis in watch.ticket.fare_bases():
            filed = self.numbers.get((carrier, basis))
            if filed is None:
                continue
            number, tariff = filed
            found = self.store.find(carrier, number, tariff)
            if found is not None:
                return found
        return None


def _rules_for(work: UnitOfWork, watches: list[Watch], when: date) -> Rules:
    """Build the fare basis to rule number lookup once for the whole run."""
    carriers = list(dict.fromkeys(w.ticket.validating_carrier for w in watches))
    numbers: dict[tuple[str, str], tuple[str, str]] = {}
    for watch in watches:
        carrier = watch.ticket.validating_carrier
        for basis in watch.ticket.fare_bases():
            if (carrier, basis) in numbers:
                continue
            fare = work.tariff.by_basis(carrier, basis, when)
            if fare is not None:
                numbers[(carrier, basis)] = (fare.rule_number, fare.tariff_number)
    return Rules(work.rules.store_for(carriers), numbers)


def _context(watch: Watch) -> Context:
    """The context a category 16 fee is priced against."""
    segments = tuple(_segment(c) for c in watch.ticket.coupons)
    return Context(
        itinerary=Itinerary(segments),
        component=FareComponent(segments),
        ticketing=watch.ticket.issued_on,
        today=date.today(),
        reissue=True,
    )


def _segment(coupon: Coupon) -> Segment:
    """A coupon as the segment the rules read.

    A coupon carries a departure and no arrival, because a ticket does not
    record one. The arrival here is made up and marked as such: nothing in a
    reprice reads it, and a segment will not build without one.
    """
    return Segment(
        marketing_carrier=coupon.carrier,
        flight_number=coupon.flight_number,
        origin=coupon.origin,
        destination=coupon.destination,
        departure=coupon.departure,
        arrival=coupon.departure + timedelta(hours=NOMINAL_BLOCK_HOURS),
        booking_class=coupon.booking_class,
        cabin=coupon.cabin,
        estimated=True,
    )


def _write(work: UnitOfWork, rows: list[WatchRow], watches: list[Watch], report: Night) -> None:
    """Put the night back, matching each watch to the row it came from."""
    by_key = {row.series_key: row for row in rows}
    for watch in watches:
        row = by_key.get(watch.series_key)
        if row is None:
            continue
        row.active = watch.active
        row.retired_reason = watch.retired_reason.value if watch.retired_reason else ""

    settled = {made.fingerprint: made for made in report.withdrawn}
    if settled:
        for open_row in work.session.scalars(
            select(RecommendationRow).where(RecommendationRow.state == "open")
        ).all():
            moved = settled.get(open_row.observation_fingerprint)
            if moved is not None:
                apply_recommendation(open_row, moved)

    for made in report.raised:
        row = by_key.get(made.series_key)
        if row is None:
            continue
        work.session.add(recommendation_to_row(made, row.id))


def _say(desk: Desk, report: Night, missing: int) -> None:
    desk.say(str(report))
    for made in report.raised[:SHOWN]:
        desk.say(f"  {made}")
    for series, reason in report.retired[:SHOWN]:
        desk.say(f"  retired {series}, {reason.value}")
    for verdict, count in sorted(report.why_not().items()):
        desk.say(f"  {verdict:<22} {count}")
    if report.held_back:
        desk.say(f"  {report.held_back} held back as too small an improvement")
    if missing:
        desk.warn(f"{missing} watches have no rule filed and were left alone")
