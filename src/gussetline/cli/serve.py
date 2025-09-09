"""Running the service, and issuing the keys that get into it.

Two commands and they are together because they are the same conversation. A
service with no keys issued lets everything through, which is right on a laptop
and wrong everywhere else, and the command that starts it is where somebody
finds that out.

The keys are held as digests and the plain text is shown once. That is not a
policy borrowed from somewhere, it is the only arrangement where a key leaking
out of the database is not a key: what is stored cannot be presented, so a
dump of the table is a list of names and not a way in.

Reload is refused when a key ring is loaded. The reloader runs the app in a
child process that re-imports everything, the ring is built in memory at start
up, and the child gets an empty one. That is a service that looks like it is
protected and is not, which is worse than one that plainly is not.
"""

from __future__ import annotations

import os
import secrets

import click

from gussetline.api.app import create_app
from gussetline.api.security import ApiKey, KeyRing, Role, fingerprint
from gussetline.cli.desk import Desk, pass_desk

# How many bytes of randomness a key carries. Thirty two is more than anybody
# needs and it costs nothing, and a key nobody types by hand may as well be
# long.
KEY_BYTES = 32

# Where a ring is read from when one is not built by hand.
KEYS_ENVIRONMENT = "GUSSETLINE_API_KEYS"


@click.command("serve")
@click.option("--host", default="127.0.0.1", help="What to bind to.")
@click.option("--port", default=8000, type=int, help="What to listen on.")
@click.option("--reload", "reloading", is_flag=True, help="Restart on a code change.")
@pass_desk
def serve(desk: Desk, host: str, port: int, reloading: bool) -> None:
    """Run the service."""
    import uvicorn

    ring = ring_from_environment()
    if reloading and ring is not None:
        # The reloader re-imports in a child process and the ring is built in
        # memory at start up, so the child would come up with an empty one.
        raise click.ClickException("reload cannot carry the keys, so it is refused with them set")

    if ring is None:
        desk.warn("no keys are set, so everything is allowed through")
    else:
        desk.say(f"{len(ring)} keys loaded")

    desk.say(f"listening on {host}:{port}")
    uvicorn.run(
        create_app(desk.settings, desk.database, ring),
        host=host,
        port=port,
        log_level="info",
    )


@click.command("issue-key")
@click.argument("name")
@click.option(
    "--role",
    type=click.Choice([role.value for role in Role]),
    default=Role.READER.value,
    help="What the key may do.",
)
@pass_desk
def issue_key(desk: Desk, name: str, role: str) -> None:
    """Make a key. The plain text is shown once and never stored."""
    secret = secrets.token_urlsafe(KEY_BYTES)
    made = ApiKey(name=name.strip(), digest=fingerprint(secret), role=Role(role))

    desk.say(f"{made}")
    desk.say("")
    desk.say(f"  {secret}")
    desk.say("")
    # Said out loud rather than left to be discovered. What is stored cannot
    # be presented, so there is no way to show this again.
    desk.say("that is the only time it will be shown")
    desk.say("")
    desk.say("add it to the environment as:")
    desk.say(f"  {KEYS_ENVIRONMENT}={made.name}:{made.role.value}:{secret}")


def ring_from_environment() -> KeyRing | None:
    """The keys, read as name:role:secret triples separated by commas.

    Nothing at all means no ring, which is a service that lets everything
    through. A ring with nothing in it would be a service that refuses
    everything, and the difference matters on a laptop.
    """
    raw = os.environ.get(KEYS_ENVIRONMENT, "").strip()
    if not raw:
        return None

    ring = KeyRing()
    for entry in raw.split(","):
        parts = [part.strip() for part in entry.split(":")]
        if len(parts) != 3 or not all(parts):
            raise click.ClickException(f"a key reads as name:role:secret, not {entry!r}")
        name, role, secret = parts
        try:
            ring.issue(name, secret, Role(role))
        except ValueError as exc:
            raise click.ClickException(f"{role!r} is not a role") from exc
    return ring
