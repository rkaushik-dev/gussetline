"""What the desk writes down about itself, in the shape a person greps.

One line per event, key equals value, sorted. Not JSON: the two people who
read these read them over ssh at seven in the morning with grep and cut, and a
line they have to pipe through a tool to read is a line they do not read.

The correlation is one identifier per command or per request, put on every line
that run writes. Without it a night run and a collection that overlap produce
one interleaved file and no way to pull either of them out, which is exactly
the file somebody needs on the morning both of them went wrong.

Three things never reach a line here.

A ticket number is a payment instrument. Anybody holding one can call a carrier
and change a booking, and a log file is copied to a laptop, mailed to a
supplier, and pasted into a ticket tracker. It is masked to its last four.

A passenger name is somebody's name. It is not needed to work out what went
wrong, and a log that carries it becomes a personal data holding with
everything that follows from that.

An API key never appears at all, not even masked. A masked key is still a key
narrowed down, and there is no question a log answers that needs one.
"""

from __future__ import annotations

import logging
import sys
import uuid
from collections.abc import Iterator
from contextlib import contextmanager
from contextvars import ContextVar
from datetime import date, datetime
from decimal import Decimal
from typing import Any

LOGGER = logging.getLogger("gussetline")

# The one identifier every line in a run shares.
_RUN: ContextVar[str] = ContextVar("run_id", default="")

# What a masked ticket number keeps.
KEPT_DIGITS = 4

# Field names that never reach a line whatever is in them.
NEVER: frozenset[str] = frozenset({"key", "api_key", "secret", "token", "password"})

# Field names that are masked rather than dropped, because the shape of the
# value is what makes a line readable and the value itself is not needed.
MASKED: frozenset[str] = frozenset({"ticket", "ticket_number", "passenger", "passenger_name"})


def new_run_id() -> str:
    """A short identifier for one command or one request."""
    return uuid.uuid4().hex[:12]


@contextmanager
def run(run_id: str = "") -> Iterator[str]:
    """Put every line written inside this block under one identifier."""
    made = run_id or new_run_id()
    token = _RUN.set(made)
    try:
        yield made
    finally:
        _RUN.reset(token)


def current_run() -> str:
    return _RUN.get()


def mask(value: str) -> str:
    """A ticket number reduced to what makes it recognisable and no more."""
    text = str(value)
    digits = [character for character in text if character.isdigit()]
    if len(digits) <= KEPT_DIGITS:
        return "*" * len(text)
    return "*" * (len(text) - KEPT_DIGITS) + text[-KEPT_DIGITS:]


def render(value: Any) -> str:
    """One value, in the form that greps and sorts the way somebody expects."""
    if isinstance(value, bool):
        return "yes" if value else "no"
    if isinstance(value, datetime):
        return value.isoformat(timespec="seconds")
    if isinstance(value, date):
        return value.isoformat()
    if isinstance(value, Decimal):
        return str(value)
    text = str(value)
    if text == "" or any(character in text for character in " \t=\"'"):
        return '"' + text.replace('"', "'") + '"'
    return text


def line(event: str, **fields: Any) -> str:
    """One event as the line that goes in the file.

    The fields are sorted so two lines for the same event line up under each
    other in a terminal, which is the whole reason anybody reads these.
    """
    parts = [f"event={render(event)}"]
    run_id = current_run()
    if run_id:
        parts.append(f"run={run_id}")

    for name in sorted(fields):
        if name in NEVER:
            continue
        value = fields[name]
        if value is None:
            continue
        if name in MASKED:
            value = mask(str(value))
        parts.append(f"{name}={render(value)}")
    return " ".join(parts)


def event(name: str, **fields: Any) -> None:
    """Write one line at the ordinary level."""
    LOGGER.info(line(name, **fields))


def problem(name: str, **fields: Any) -> None:
    """Write one line for something somebody has to look at."""
    LOGGER.warning(line(name, **fields))


def setup(level: str = "INFO", stream: Any = None) -> None:
    """Point the logger at somewhere, once, without duplicating handlers.

    Called by the commands and by the service. Safe to call twice, because a
    process that sets up twice otherwise writes every line twice and the
    second copy is the one that makes a file unreadable.
    """
    LOGGER.setLevel(level.upper())
    LOGGER.propagate = False
    wanted = stream if stream is not None else sys.stderr

    for existing in list(LOGGER.handlers):
        if isinstance(existing, logging.StreamHandler) and existing.stream is wanted:
            return
        LOGGER.removeHandler(existing)

    handler = logging.StreamHandler(wanted)
    handler.setFormatter(logging.Formatter("%(asctime)s %(levelname)s %(message)s"))
    LOGGER.addHandler(handler)
