"""What every command is handed, and the three codes it can exit with.

Split out from the root command on purpose. Every command module needs Desk
and pass_desk, and the root needs every command module in order to register
them, so putting both in one file is a cycle: importing a command module
directly, which is exactly what a test does, imports the root, which imports
the command module again while it is still half built.

Nought is success. One is something the operator can fix: a market with no
fares filed, a ticket number that does not exist, a setting that is not a
number. Two is something they cannot: a supplier is down, the database is
unreachable, a payload did not parse.

The split matters because these commands run under cron. A one means the run
found nothing to do and the night shift can ignore it; a two means somebody
should look. A command line that returns one code for both produces either a
pager that goes off every night or one that never goes off.
"""

from __future__ import annotations

from dataclasses import dataclass

import click

from gussetline.config import Settings
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
