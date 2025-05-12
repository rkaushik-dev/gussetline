"""The load commands, which are the ones somebody runs at four in the morning.

Two things this does that a plain read and write would not.

It refuses a file it has already taken in. Tariff files arrive by transfer and
the transfer retries, so the same bytes turn up twice more often than not, and
loading them twice doubles a market's fares and makes the cheapest one win a
selection it should not. The digest of the payload is the check and it is a
column on the load, not a file on disk, because the check has to survive a
machine being rebuilt.

And it can read without applying. The loaders were built as two calls for
exactly this: a dry run reads the whole file, counts everything, lists every
bad record, and touches nothing. That is what runs against a new carrier's
first file, and it is the difference between finding out the offsets are wrong
now and finding out when the desk quotes a fare that does not exist.

Exit codes follow the rest of the command line. A file that will not parse at
all is a two, because somebody upstream has to fix it. A file that parses with
bad records in it is a one, because the operator can decide whether to take
the rest. A clean load is a nought.
"""

from __future__ import annotations

from datetime import datetime
from pathlib import Path

import click
from sqlalchemy import select

from gussetline.cli.main import OPERATOR_CAN_FIX, Desk, pass_desk
from gussetline.collect.archive import digest_of
from gussetline.combinability import CombinabilityStore
from gussetline.load import combinability as join_feed
from gussetline.load import fares as fare_feed
from gussetline.load import rules as rule_feed
from gussetline.persistence.models import LoadRow
from gussetline.rules.base import RuleStore
from gussetline.tariff import TariffStore

# How many bad records to print before saying how many more there were.
SHOWN_PROBLEMS = 10


@click.group("load")
def load() -> None:
    """Take a filing file in."""


@load.command("fares")
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--dry-run", is_flag=True, help="Read it and say what would happen.")
@click.option("--again", is_flag=True, help="Load it even though we have these bytes.")
@pass_desk
def load_fares(desk: Desk, path: Path, dry_run: bool, again: bool) -> None:
    """Read a fare filing file into the tariff."""
    payload = path.read_bytes()
    digest = digest_of(payload)
    reading = fare_feed.read(payload)

    desk.say(str(reading))
    _problems(desk, reading.problems)
    if not reading.counted:
        desk.warn(f"the trailer says {reading.declared} records and we read {len(reading)}")

    if dry_run:
        store = TariffStore()
        applied = fare_feed.apply(store, reading)
        desk.say(f"would be {applied}")
        _finish(reading.problems, dry_run=True)
        return

    if not again and _already_loaded(desk, digest):
        raise click.ClickException(f"these bytes are already loaded, digest {digest[:12]}")

    with desk.database.unit() as work:
        row = work.tariff.open_load(
            carrier=reading.carrier or "??",
            filename=path.name,
            digest=digest,
            loaded_at=datetime.now(),
            effective=reading.filed_on or datetime.now().date(),
        )
        written = work.tariff.add_fares(row, [f.fare for f in reading.filings])
        work.commit()

    desk.say(f"loaded {written} fares as load {digest[:12]}")
    _finish(reading.problems)


@load.command("rules")
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--dry-run", is_flag=True, help="Read it and say what would happen.")
@pass_desk
def load_rules(desk: Desk, path: Path, dry_run: bool) -> None:
    """Read a rule file, which lives in memory and not in the database."""
    payload = path.read_bytes()
    reading = rule_feed.read(payload)

    desk.say(str(reading))
    if reading.orphans:
        desk.warn(f"{reading.orphans} category records had no rule above them")
    _problems(desk, reading.problems)

    counts = rule_feed.categories_of(reading)
    for category in sorted(counts):
        desk.say(f"  category {category:>2}  {counts[category]}")

    store = RuleStore()
    applied = rule_feed.apply(store, reading)
    desk.say(f"{'would be' if dry_run else 'applied'}: {applied}")
    _finish(reading.problems, dry_run=dry_run)


def _already_loaded(desk: Desk, digest: str) -> bool:
    with desk.database.unit() as work:
        found = work.session.scalars(select(LoadRow).where(LoadRow.digest == digest)).first()
        return found is not None


def _problems(desk: Desk, problems: list) -> None:
    """Print the bad records, capped, and say how many were not shown."""
    for problem in problems[:SHOWN_PROBLEMS]:
        desk.warn(str(problem))
    if len(problems) > SHOWN_PROBLEMS:
        desk.warn(f"and {len(problems) - SHOWN_PROBLEMS} more")


def _finish(problems: list, dry_run: bool = False) -> None:
    """A file with bad records in it is something the operator decides about."""
    if problems and not dry_run:
        raise SystemExit(OPERATOR_CAN_FIX)


@load.command("combinability")
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--dry-run", is_flag=True, help="Read it and say what would happen.")
@pass_desk
def load_combinability(desk: Desk, path: Path, dry_run: bool) -> None:
    """Read a category 10 file into the tariff."""
    reading = join_feed.read(path.read_bytes())

    desk.say(str(reading))
    _problems(desk, reading.problems)

    store = CombinabilityStore()
    applied = join_feed.apply(store, reading)
    desk.say(f"{'would be' if dry_run else 'applied'}: {applied}")
    if applied.combine_with_nothing:
        desk.warn(
            f"{applied.combine_with_nothing} fares combine with nothing, "
            "which refuses every round trip they are in"
        )

    if not dry_run:
        with desk.database.unit() as work:
            written = work.combinability.save_all(reading.records)
            dropped = sum(
                1 for carrier, basis in reading.deleted if work.combinability.delete(carrier, basis)
            )
            work.commit()
        desk.say(f"kept {written} records, dropped {dropped}")

    _finish(reading.problems, dry_run=dry_run)
