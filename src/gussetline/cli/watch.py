"""The watch commands, which are how a ticket gets onto the desk at all.

Adding a watch is the only write in this whole command line that a person does
by hand rather than a feed doing it, and that makes it the one place where the
input has to be argued with rather than trusted.

Two arguments in particular.

A watch on a departure that has already gone is not an error worth refusing,
it is a ticket somebody typed the wrong year on, and the difference matters:
refusing it makes the agent retype the whole entry, while accepting it puts a
watch on the schedule that will never be polled and never be looked at again.
So it is refused, and the refusal says what the date was read as.

And the threshold is worked out rather than asked for. What a drop has to beat
is the change fee plus a margin, and an agent who is asked for a number will
type fifty every time, which is right for a short haul economy ticket and
absurd for a business class transatlantic one. The fee comes off the ticket's
own rule when there is one, and the margin off the settings.

Retiring is by hand and it records that. A watch closed by a person and one
closed because the departure went are different readings of the desk, and a
coverage report that cannot tell them apart shows the desk giving up.
"""

from __future__ import annotations

from datetime import date, datetime

import click
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from gussetline.cli.desk import Desk, pass_desk
from gussetline.desk.watch import Retired, default_margin, threshold_for
from gussetline.errors import NotFoundError, ValidationError
from gussetline.flights import Cabin
from gussetline.geography import city_of
from gussetline.money import Money
from gussetline.persistence.models import ObservationRow, RecommendationRow, TicketRow, WatchRow
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.rules.base import Category
from gussetline.rules.penalties import Action, Penalty

# How many rows a listing prints before it stops.
SHOWN = 40


@click.group("watch")
def watch() -> None:
    """Put a ticket on the desk, or take it off."""


@watch.command("add")
@click.argument("ticket_number")
@click.option("--threshold", default=None, help="Override what a drop has to beat.")
@click.option("--cabin", default="Y", help="Which cabin to watch.")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@pass_desk
def add(
    desk: Desk,
    ticket_number: str,
    threshold: str | None,
    cabin: str,
    today: datetime | None,
) -> None:
    """Start watching a ticket's market."""
    when = today.date() if today else date.today()

    with desk.database.unit() as work:
        ticket = _ticket(work, ticket_number)
        if ticket.departure < when:
            raise ValidationError(
                "that departure has gone",
                ticket=ticket_number,
                departure=ticket.departure.isoformat(),
                today=when.isoformat(),
            )

        wanted = Cabin(cabin.strip().upper())
        series = _series_key(ticket, wanted)
        if _already(work, series):
            raise click.ClickException(f"{series} is already watched")

        beat = (
            Money.of(threshold, ticket.base_currency)
            if threshold
            else _threshold(desk, work, ticket)
        )
        row = WatchRow(
            ticket_id=ticket.id,
            series_key=series,
            origin=city_of(ticket.origin),
            destination=city_of(ticket.destination),
            departure=ticket.departure,
            cabin=wanted.value,
            threshold_amount=beat.amount,
            threshold_currency=beat.currency,
        )
        work.session.add(row)
        work.commit()

    desk.say(f"watching {series}, a drop has to beat {beat}")


@watch.command("list")
@click.option("--all", "everything", is_flag=True, help="Retired ones as well.")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@pass_desk
def listing(desk: Desk, everything: bool, today: datetime | None) -> None:
    """What the desk is watching, closest departure first."""
    when = today.date() if today else date.today()

    with desk.database.unit() as work:
        statement = select(WatchRow).options(selectinload(WatchRow.ticket))
        if not everything:
            statement = statement.where(WatchRow.active, WatchRow.departure >= when)
        rows = work.session.scalars(statement.order_by(WatchRow.departure)).all()
        lines = [_line(work, row) for row in rows[:SHOWN]]
        total = len(rows)

    if not total:
        desk.say("nothing watched")
        return
    for line in lines:
        desk.say(line)
    if total > SHOWN:
        desk.say(f"and {total - SHOWN} more")


@watch.command("retire")
@click.argument("series_key")
@pass_desk
def retire(desk: Desk, series_key: str) -> None:
    """Stop watching a series, and record that a person said so."""
    with desk.database.unit() as work:
        row = work.session.scalars(
            select(WatchRow).where(WatchRow.series_key == series_key.strip())
        ).one_or_none()
        if row is None:
            raise NotFoundError("no such watch", series=series_key)
        if not row.active:
            raise click.ClickException(f"{series_key} is already retired, {row.retired_reason}")
        row.active = False
        row.retired_reason = Retired.BY_HAND.value
        work.commit()

    desk.say(f"retired {series_key}")


def _ticket(work: UnitOfWork, number: str) -> TicketRow:
    row = work.session.scalars(
        select(TicketRow)
        .options(selectinload(TicketRow.coupons))
        .where(TicketRow.number == number.strip())
    ).one_or_none()
    if row is None:
        raise NotFoundError("no such ticket", ticket=number)
    return row


def _series_key(ticket: TicketRow, cabin: Cabin) -> str:
    return (
        f"{city_of(ticket.origin)}-{city_of(ticket.destination)}-"
        f"{ticket.departure.isoformat()}-{cabin.value}"
    )


def _already(work: UnitOfWork, series: str) -> bool:
    found = work.session.scalars(
        select(WatchRow).where(WatchRow.series_key == series, WatchRow.active)
    ).first()
    return found is not None


def _threshold(desk: Desk, work: UnitOfWork, ticket: TicketRow) -> Money:
    """What a drop has to beat, worked out from the ticket's own rule.

    The change fee plus a margin. An agent asked for a number types fifty
    every time, which is right for a short haul economy ticket and absurd for
    a business class transatlantic one.
    """
    currency = ticket.base_currency
    margin = default_margin(currency)
    fee = _change_fee(work, ticket, currency)
    if fee is None:
        # No rule loaded for this fare. The settings figure is a stated
        # default rather than a guess at the fee, and the agent can override.
        return Money(desk.settings.watch_threshold, currency)
    return threshold_for(fee, margin)


def _change_fee(work: UnitOfWork, ticket: TicketRow, currency: str) -> Money | None:
    for coupon in ticket.coupons:
        fare = work.tariff.by_basis(ticket.validating_carrier, coupon.fare_basis)
        if fare is None:
            continue
        rule = work.rules.get(ticket.validating_carrier, fare.rule_number)
        if rule is None:
            continue
        for provision in rule.for_category(Category.PENALTIES):
            if not isinstance(provision, Penalty) or provision.action is not Action.CHANGE:
                continue
            filed = provision.before_departure
            if filed is not None and filed.currency == currency:
                return filed
    return None


def _line(work: UnitOfWork, row: WatchRow) -> str:
    """One watch, with the two numbers somebody actually wants next to it."""
    seen = work.session.scalar(
        select(ObservationRow.total_amount)
        .where(ObservationRow.series_key == row.series_key)
        .order_by(ObservationRow.total_amount)
        .limit(1)
    )
    open_ones = work.session.scalars(
        select(RecommendationRow).where(
            RecommendationRow.watch_id == row.id, RecommendationRow.state == "open"
        )
    ).all()
    state = "live" if row.active else f"retired, {row.retired_reason}"
    cheapest = f"{seen} {row.threshold_currency}" if seen is not None else "nothing seen"
    return (
        f"{row.series_key:<28} {state:<34} paid {row.ticket.base_amount} "
        f"cheapest {cheapest}{', open' if open_ones else ''}"
    )
