"""The root command, which assembles the others and owns the exit code.

Nothing here does any work. It reads the settings once, opens one connection,
hands both to whichever command was asked for, and turns whatever comes back
out of that command into the number cron will read.

Every command takes the same connection and settings, built once here and
handed down, so a subcommand cannot quietly open a second database.

Registration is a function rather than a run of imports at the top, and Desk
lives next door rather than here, for the same reason: every command module
needs Desk, and this file needs every command module. With both in one file,
importing a command directly, which is exactly what a test does, imports this
file, which imports the command again while it is still half built.
"""

from __future__ import annotations

import sys

import click

from gussetline import __version__
from gussetline.cli.desk import (
    FIXABLE,
    OK,
    OPERATOR_CAN_FIX,
    SOMEBODY_SHOULD_LOOK,
    Desk,
    exit_code_for,
    pass_desk,
)
from gussetline.config import from_env
from gussetline.errors import GussetlineError
from gussetline.persistence.unitofwork import Database

__all__ = [
    "FIXABLE",
    "OK",
    "OPERATOR_CAN_FIX",
    "SOMEBODY_SHOULD_LOOK",
    "Desk",
    "cli",
    "exit_code_for",
    "main",
    "pass_desk",
]


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
        joins = work.combinability.count()
        runs = work.runs.count()
    click.echo(f"airports      {airports}")
    click.echo(f"fares         {fares}")
    click.echo(f"combinability {joins}")
    click.echo(f"observations  {observations}")
    click.echo(f"runs          {runs}")


def register() -> None:
    """Hang the command groups off the root.

    Written to be safe to call twice, because a module that imports a command
    directly reaches this on the way through and then again from the root.
    """
    from gussetline.cli.audit import audit_them
    from gussetline.cli.collect import collect
    from gussetline.cli.load import load
    from gussetline.cli.night import night
    from gussetline.cli.price import price_it
    from gussetline.cli.report import report

    for command in (load, collect, price_it, audit_them, night, report):
        if command.name and command.name not in cli.commands:
            cli.add_command(command)


def main() -> int:
    """The entry point, which turns a failure into the right code."""
    register()
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


register()


if __name__ == "__main__":  # pragma: no cover
    sys.exit(main())
