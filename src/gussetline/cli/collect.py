"""The collect commands, which are what the cron job actually calls.

Three of them and the split is on purpose.

Planning says what would be polled today and stops. It reads the watches, works
out the cadence, and prints the list. It is the command somebody runs when the
question is why a market was not looked at, and it costs nothing, so it can be
run against production at any time.

Running does the same thing and then goes out. It writes the run to the
database, because every reading anybody has about the collectors is built out
of those rows and out of nothing else.

Replaying does the same thing against the archive instead of the suppliers,
and it writes the offers without writing the run. Those two are different
things and the distinction is the whole reason replay exists.

An offer is a reading, and reparsing a stored payload with a fixed parser
produces a better reading of the same moment, which is worth keeping. The
observation table is keyed on content, so writing it again is a stamp move
rather than a second row.

A run is a record that somebody was asked something. Nothing was asked during
a replay, and a reliability report that counted them would say a supplier
answered on a day it was never called. That is the sort of number that gets
believed for a month, so the flag to save one does not exist.
"""

from __future__ import annotations

from datetime import date, datetime

import click

from gussetline.cli.desk import Desk, pass_desk
from gussetline.collect import calendar as calendar_feed
from gussetline.collect import gds as gds_feed
from gussetline.collect import ndc as ndc_feed
from gussetline.collect.harvest import Adapter, Harvest
from gussetline.collect.registry import Capability
from gussetline.collect.rig import Rig, build, live_fetch, replay_fetch
from gussetline.collect.run import RunReport
from gussetline.collect.schedule import Market, RunPlan, Schedule, Series, Task
from gussetline.persistence.unitofwork import UnitOfWork

PARSERS: dict[str, Adapter] = {
    "gds": gds_feed.parse,
    "ndc": ndc_feed.parse,
    "calendar": calendar_feed.parse,
}

# How many task lines to print before saying how many more there were.
SHOWN_TASKS = 20


@click.group("collect")
def collect() -> None:
    """Poll the suppliers, or work out what polling would do."""


@collect.command("plan")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--supplier", default=None, help="Only this one.")
@click.option("--budget", type=int, default=None, help="Cap the tasks per supplier.")
@pass_desk
def plan_only(desk: Desk, today: datetime | None, supplier: str | None, budget: int | None) -> None:
    """Say what would be polled and stop."""
    when = today.date() if today else date.today()
    rig = build(desk.settings, parsers=dict(PARSERS))
    schedule = _schedule_from(desk, when)
    plan = _plan(rig, schedule, when, supplier, budget)

    desk.say(f"{len(schedule)} series followed, {len(schedule.due(when))} due on {when}")
    desk.say(str(plan))
    _show(desk, plan)


@collect.command("run")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--supplier", default=None, help="Only this one.")
@click.option("--budget", type=int, default=None, help="Cap the tasks per supplier.")
@pass_desk
def run_it(desk: Desk, today: datetime | None, supplier: str | None, budget: int | None) -> None:
    """Poll what is due and keep the run."""
    when = today.date() if today else date.today()
    rig = build(desk.settings, parsers=dict(PARSERS))
    schedule = _schedule_from(desk, when)
    plan = _plan(rig, schedule, when, supplier, budget)

    if desk.settings.dry_run:
        desk.say("dry run, nothing went out")
        _show(desk, plan)
        return

    with desk.database.unit() as work:
        kept = _keeper(work)
        report = rig.collector().run(
            plan.tasks, when, live_fetch(rig), rig.parsers, schedule, kept.sink
        )
        row = work.runs.record(report, finished=rig.now())
        work.commit()
        identifier = row.id

    _report(desk, report, rig)
    desk.say(f"kept {kept.written} offers, {kept.moved} already seen")
    desk.say(f"kept as run {identifier}")


@collect.command("replay")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--supplier", default=None, help="Only this one.")
@pass_desk
def replay_it(desk: Desk, today: datetime | None, supplier: str | None) -> None:
    """Reparse what is already in the archive. Never saved, never a run."""
    when = today.date() if today else date.today()
    rig = build(desk.settings, parsers=dict(PARSERS))
    schedule = _schedule_from(desk, when)
    plan = _plan(rig, schedule, when, supplier, None, everything=True)

    with desk.database.unit() as work:
        kept = _keeper(work)
        report = rig.collector().run(
            plan.tasks, when, replay_fetch(rig, supplier), rig.parsers, None, kept.sink
        )
        work.commit()

    _report(desk, report, rig)
    desk.say(f"kept {kept.written} offers, {kept.moved} already seen")
    desk.say("no run kept, because nothing was asked of anybody")


def _schedule_from(desk: Desk, today: date) -> Schedule:
    """Build the schedule out of the live watches.

    The last check is carried over so the cadence picks up where it left off.
    Without it every run after a restart thinks nothing has ever been polled
    and asks for the whole book on the first morning.
    """
    schedule = Schedule()
    with desk.database.unit() as work:
        for row in work.watches.live(today):
            series = schedule.follow(Market(row.origin, row.destination), row.departure)
            if row.last_checked is not None:
                series.last_polled = row.last_checked.date()
    return schedule


def _plan(
    rig: Rig,
    schedule: Schedule,
    today: date,
    supplier: str | None,
    budget: int | None,
    everything: bool = False,
) -> RunPlan:
    """One plan across every supplier that can shop, cheapest first.

    A market both the feed and the gateway cover is planned once, against
    whichever is cheaper, rather than twice. Two suppliers answering the same
    question is how the gateway bill doubled the month the feed was added.
    """
    plan = RunPlan()
    wanted = schedule.series() if everything else schedule.due(today)
    cap = budget if budget is not None else len(wanted)

    for series in sorted(wanted, key=lambda s: (s.days_out(today), s.market.key)):
        if len(plan.tasks) >= cap:
            break
        chosen = _supplier_for(rig, series.market.key, supplier)
        if chosen is None:
            continue
        plan.tasks.append(_task(chosen, series, today, len(plan.tasks)))
    return plan


def _supplier_for(rig: Rig, market: str, only: str | None) -> str | None:
    if only is not None:
        found = rig.registry.get(only)
        return found.name if found.covers(market) else None
    cheapest = rig.registry.cheapest_for(market, Capability.SHOP)
    return cheapest.name if cheapest is not None else None


def _task(supplier: str, series: Series, today: date, priority: int) -> Task:
    return Task(
        supplier=supplier,
        market=series.market,
        departure=series.departure,
        days_out=series.days_out(today),
        priority=priority,
    )


def _show(desk: Desk, plan: RunPlan) -> None:
    for task in plan.tasks[:SHOWN_TASKS]:
        desk.say(f"  {task}")
    if len(plan.tasks) > SHOWN_TASKS:
        desk.say(f"  and {len(plan.tasks) - SHOWN_TASKS} more")


def _report(desk: Desk, report: RunReport, rig: Rig) -> None:
    desk.say(str(report))
    for outcome, count in sorted(report.by_outcome().items()):
        desk.say(f"  {outcome:<10} {count}")
    if report.problems:
        desk.warn(f"{report.problems} offers inside the payloads did not parse")
    for failure in report.failures()[:SHOWN_TASKS]:
        desk.warn(f"  {failure}")
    desk.say(f"cost {report.cost(rig.registry)}")


class _Keeper:
    """Writes the offers a run parsed, and counts what it did.

    One instance per run, so the counts belong to that run and not to the
    process. The sink is a bound method rather than a closure because the
    collector holds it across every task and a closure over a mutable count
    reads worse than an object that owns one.
    """

    def __init__(self, work: UnitOfWork) -> None:
        self.work = work
        self.written = 0
        self.moved = 0

    def sink(self, task: Task, harvest: Harvest) -> None:
        if not harvest.offers:
            return
        added, seen = self.work.observations.save_all(list(harvest.offers))
        self.written += added
        self.moved += seen


def _keeper(work: UnitOfWork) -> _Keeper:
    return _Keeper(work)
