"""The desk command line, and the three exit codes it uses.

Nought is success. One is something the operator can fix: a market with no
fares filed, a ticket number that does not exist, a setting that is not a
number. Two is something they cannot: a supplier is down, the database is
unreachable, a payload did not parse.

The split matters because these commands run under cron. A one means the run
found nothing to do and the night shift can ignore it; a two means somebody
should look. A command line that returns one code for both produces either a
pager that goes off every night or one that never goes off.

Every command takes the same connection and settings, built once here and
handed down, so a subcommand cannot quietly open a second database.
"""

from __future__ import annotations

import sys
from dataclasses import dataclass

import click

from gussetline import __version__
from gussetline.config import Settings, from_env
from gussetline.errors import (
    ConfigError,
    GussetlineError,
    NotFoundError,
    SupplierError,
    TariffError,
    ValidationError,
)
from gussetline.persistence.unitofwork import Database

OK = 0
OPERATOR_CAN_FIX = 1
SOMEBODY_SHOULD_LOOK = 2

# Which failures the operator can do something about tonight.
FIXABLE: tuple[type[GussetlineError], ...] = (
    NotFoundError,
    ValidationError,
    TariffError,
    ConfigError,
)


def exit_code_for(exc: BaseException) -> int:
    """Turn a failure into the code cron will read."""
    if isinstance(exc, FIXABLE):
        return OPERATOR_CAN_FIX
    if isinstance(exc, SupplierError):
        return SOMEBODY_SHOULD_LOOK
    return SOMEBODY_SHOULD_LOOK


@dataclass
class Desk:
    """What every command is handed. Built once, at the top."""

    settings: Settings
    database: Database
    quiet: bool = False

    def say(self, message: str) -> None:
        if not self.quiet:
            click.echo(message)

    def warn(self, message: str) -> None:
        click.echo(message, err=True)


pass_desk = click.make_pass_decorator(Desk)


@click.group(context_settings={"help_option_names": ["-h", "--help"]})
@click.option("--database", "database_url", default=None, help="Override the database url.")
@click.option("--quiet", is_flag=True, help="Say nothing on success.")
# The version comes off the package itself and not off the installed
# distribution, because the desk runs this straight out of a checkout
# and asking the metadata there raises rather than answering.
@click.version_option(version=__version__, prog_name="gussetline")
@click.pass_context
def cli(ctx: click.Context, database_url: str | None, quiet: bool) -> None:
    """Fare audit and reprice desk."""
    settings = from_env()
    if database_url:
        settings = settings.with_database(database_url)
    ctx.obj = Desk(settings=settings, database=Database(settings), quiet=quiet)


@cli.command()
@pass_desk
def settings(desk: Desk) -> None:
    """Print what this run would use, which is the first thing to check."""
    click.echo(str(desk.settings))
    click.echo(f"quota      {desk.settings.daily_quota}")
    click.echo(f"rate       {desk.settings.rate_per_second}/s burst {desk.settings.burst}")
    click.echo(f"archive    {desk.settings.archive_root}")
    click.echo(f"threshold  {desk.settings.watch_threshold} {desk.settings.selling_currency}")
    click.echo(f"dry run    {'yes' if desk.settings.dry_run else 'no'}")


@cli.command("create-schema")
@pass_desk
def create_schema(desk: Desk) -> None:
    """Build the schema. For a laptop and a first run, not for a deploy."""
    desk.database.create_schema()
    desk.say("schema built")


@cli.command()
@pass_desk
def check(desk: Desk) -> None:
    """Say whether the database is reachable and what is in it."""
    with desk.database.unit() as work:
        airports = work.airports.count()
        observations = work.observations.count()
        fares = work.tariff.count_fares()
    click.echo(f"airports      {airports}")
    click.echo(f"fares         {fares}")
    click.echo(f"observations  {observations}")


def _register() -> None:
    """Hang the command groups off the root.

    Done here rather than at the top of the file because every group imports
    Desk and pass_desk from this module, and importing them at the top would
    be a cycle. The call is at the bottom, after both exist.
    """
    from gussetline.cli.collect import collect
    from gussetline.cli.load import load

    cli.add_command(load)
    cli.add_command(collect)


def main() -> int:
    """The entry point, which turns a failure into the right code."""
    try:
        cli.main(standalone_mode=False)
    except click.ClickException as exc:
        exc.show()
        return OPERATOR_CAN_FIX
    except click.Abort:
        click.echo("stopped", err=True)
        return OPERATOR_CAN_FIX
    except GussetlineError as exc:
        click.echo(str(exc), err=True)
        return exit_code_for(exc)
    return OK


_register()


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
