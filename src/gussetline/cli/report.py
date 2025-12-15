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

from gussetline.cli.desk import Desk, pass_desk
from gussetline.collect.registry import default_registry
from gussetline.collect.schedule import Market, Schedule
from gussetline.desk import queue as worklist
from gussetline.errors import NotFoundError
from gussetline.persistence.book import open_book, raised_between
from gussetline.persistence.models import LoadRow, WatchRow
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.reports import coverage as coverage_report
from gussetline.reports import filing as filing_report
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
        made = raised_between(work, first, last)

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
        stalled = [row.started_at for row in work.runs.unfinished()]

    built = reliability_report.build(history)
    click.echo(reliability_report.render(built))

    # A run that started and never said it was done took its offers with it.
    # Nothing else here can see that: every supplier in the report above is
    # graded on the calls that did come back.
    for started in stalled[:5]:
        desk.warn(f"run of {started:%Y-%m-%d %H:%M} never finished")
    if stalled:
        desk.warn(f"{len(stalled)} runs started and never finished")

    if built.needing_a_human() or stalled:
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


@report.command("filing")
@click.argument("carrier")
@click.option("--against", type=int, default=None, help="Read against this load id.")
@click.option("--show", type=int, default=15, help="How many of each list to print.")
@pass_desk
def filing(desk: Desk, carrier: str, against: int | None, show: int) -> None:
    """What moved between a carrier's last two filings.

    Exits one when something needs doing before the desk opens, which is a
    fare that fell or a fare that stopped being filed. A tariff that only
    grew is not news and should not page anybody.
    """
    with desk.database.unit() as work:
        loads = work.tariff.loads_for(carrier)
        if len(loads) < 2:
            raise NotFoundError(
                "a carrier needs two filings before they can be compared",
                carrier=carrier.upper(),
                filings=len(loads),
            )
        after = loads[0]
        before = _pick(loads, against)
        desk.say(f"{before.filename} to {after.filename}")
        built = filing_report.build(
            work.tariff.fares_in(before),
            work.tariff.fares_in(after),
            desk.settings.selling_currency,
        )

    click.echo(filing_report.render(built, limit=show))
    if built.worth_a_look():
        raise SystemExit(1)


def _pick(loads: list[LoadRow], against: int | None) -> LoadRow:
    """Which filing to read the newest one against.

    The one before it by default, because that is the question somebody has
    on the morning a file lands. A load id when somebody is reading a week of
    filings back to one they trust.
    """
    if against is None:
        return loads[1]
    for row in loads:
        if row.id == against:
            return row
    raise NotFoundError("no such filing for that carrier", load=against)


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


@report.command("queue")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--show", type=int, default=20, help="How many places to print.")
@click.option("--urgent", "only_urgent", is_flag=True, help="Only the ones for today.")
@pass_desk
def queue(desk: Desk, today: datetime | None, show: int, only_urgent: bool) -> None:
    """What an agent should work through, in the order to work it.

    The same list the worklist screen shows, for the mornings when nobody
    wants to open a browser.
    """
    when = today.date() if today else date.today()

    with desk.database.unit() as work:
        book = open_book(work)

    made = book.recommendations
    places = worklist.build(made, when, book.departures)
    if only_urgent:
        places = worklist.urgent(places)

    click.echo(worklist.render(places, limit=show))
    if places:
        currency = made[0].currency
        click.echo(f"worth {worklist.worth_of(places, currency)}")
