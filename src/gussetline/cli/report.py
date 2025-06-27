"""The report commands, which are the three numbers the desk gets asked for.

Savings is what the desk is measured on. Reliability is what the operator gets
paged about. Coverage is the one nobody asks for and everybody needs, because
it is the only report that catches a market quietly falling off the schedule.

All three read history and write nothing, so they are safe to run against
production in the middle of a night run, and none of them takes a window
longer than it needs: the whole point of a report over a month is that it is
comparable to the one from last month, and a default window that grows with
the age of the database is not.

Savings reads recommendations rather than runs, which is the distinction that
took two arguments to settle. A run says the desk found a hundred euro drop.
A recommendation says the desk told somebody about it. The first is a reading
of the collectors and the second is a reading of the desk, and reporting the
first as savings makes a quiet month look like a good one.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

import click
from sqlalchemy import select
from sqlalchemy.orm import selectinload

from gussetline.cli.desk import Desk, pass_desk
from gussetline.collect.registry import default_registry
from gussetline.collect.schedule import Market, Schedule
from gussetline.desk.recommend import Recommendation
from gussetline.persistence.deskmap import row_to_recommendation
from gussetline.persistence.models import RecommendationRow, WatchRow
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.reports import coverage as coverage_report
from gussetline.reports import reliability as reliability_report
from gussetline.reports import savings as savings_report

# How far back each report looks when nobody says. Different on purpose: a
# month of savings is a management number, a fortnight of runs is enough to
# see a supplier going bad, and coverage is a reading of today.
SAVINGS_DAYS = 30
RELIABILITY_DAYS = 14


@click.group("report")
def report() -> None:
    """Read history. None of these writes anything."""


@report.command("savings")
@click.option("--since", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--until", type=click.DateTime(["%Y-%m-%d"]), default=None)
@pass_desk
def savings(desk: Desk, since: datetime | None, until: datetime | None) -> None:
    """What the desk found, took and missed."""
    last = until.date() if until else date.today()
    first = since.date() if since else last - timedelta(days=SAVINGS_DAYS)

    with desk.database.unit() as work:
        made = _recommendations(work, first, last)

    built = savings_report.build(made, desk.settings.selling_currency, first=first, last=last)
    click.echo(savings_report.render(built))


@report.command("reliability")
@click.option("--since", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--runs", type=int, default=None, help="Read this many runs instead.")
@pass_desk
def reliability(desk: Desk, since: datetime | None, runs: int | None) -> None:
    """Which suppliers are answering."""
    with desk.database.unit() as work:
        if runs is not None:
            history = work.runs.recent(limit=runs)
        else:
            first = since if since else datetime.now() - timedelta(days=RELIABILITY_DAYS)
            history = work.runs.since(first)

    built = reliability_report.build(history)
    click.echo(reliability_report.render(built))
    if built.needing_a_human():
        raise SystemExit(1)


@report.command("coverage")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--runs", type=int, default=60, help="How many runs to read.")
@click.option("--show", type=int, default=20, help="How many gaps to list.")
@pass_desk
def coverage(desk: Desk, today: datetime | None, runs: int, show: int) -> None:
    """What the desk is actually watching, against what it meant to."""
    when = today.date() if today else date.today()

    with desk.database.unit() as work:
        schedule = _schedule(work, when)
        history = work.runs.recent(limit=runs)

    built = coverage_report.build(schedule, history, default_registry(), when)
    click.echo(coverage_report.render(built, limit=show))
    if built.gaps():
        raise SystemExit(1)


def _recommendations(work: UnitOfWork, first: date, last: date) -> list[Recommendation]:
    """Every recommendation raised in a window, with its watch already in.

    The window is on the day it was raised and not on the day it was settled,
    because a recommendation belongs to the month the desk found it in. A
    report keyed on the settlement moves rows between months every time an
    agent works through a backlog.
    """
    rows = work.session.scalars(
        select(RecommendationRow)
        .options(selectinload(RecommendationRow.watch).selectinload(WatchRow.ticket))
        .where(RecommendationRow.raised_on >= first, RecommendationRow.raised_on <= last)
        .order_by(RecommendationRow.raised_on)
    ).all()
    return [
        row_to_recommendation(row, row.watch.ticket.number, row.watch.series_key) for row in rows
    ]


def _schedule(work: UnitOfWork, when: date) -> Schedule:
    """The schedule as the live watches describe it.

    Retired watches are left out. A retired watch is not a gap, and counting
    one makes the coverage figure fall every time the desk correctly stops
    watching something.
    """
    schedule = Schedule()
    for row in work.session.scalars(select(WatchRow).where(WatchRow.active)).all():
        series = schedule.follow(Market(row.origin, row.destination), row.departure)
        if row.last_checked is not None:
            series.last_polled = row.last_checked.date()
    return schedule
