"""Throwing away what nobody will read again, and nothing else.

A year of collecting fills a disk. The rows that fill it are the ones nobody
will ever ask about, and the rows that matter are small, so this is a job with
a lot of upside and one very expensive way to be wrong.

It never touches a recommendation. There is no flag for it and no cutoff that
reaches one, because the savings report reads them and a report that changes
when old rows are deleted cannot be put in front of anybody.

It is dry by default. A retention job that deletes on its first run is one
somebody points at production to see what it would do, and then it has done it.
Passing the flag is the whole ceremony and it is enough.
"""

from __future__ import annotations

from datetime import date, datetime

import click

from gussetline import logs
from gussetline.cli.desk import Desk, pass_desk
from gussetline.collect.archive import Archive
from gussetline.retention import Policy, Swept, default_policy


@click.command("prune")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--observation-days", type=int, default=None, help="Keep readings this long.")
@click.option("--run-days", type=int, default=None, help="Keep runs this long.")
@click.option("--archive-days", type=int, default=None, help="Keep payloads this long.")
@click.option("--apply", "applying", is_flag=True, help="Actually delete. Off by default.")
@click.option("--scrub", "scrubbing", is_flag=True, help="Also check every stored payload.")
@pass_desk
def prune(
    desk: Desk,
    today: datetime | None,
    observation_days: int | None,
    run_days: int | None,
    archive_days: int | None,
    applying: bool,
    scrubbing: bool,
) -> None:
    """Drop what has aged out. Says what it would do unless told to do it."""
    when = today.date() if today else date.today()
    policy = _policy(observation_days, run_days, archive_days)
    desk.say(f"keeping {policy}")

    swept = _sweep(desk, policy, when, applying)
    desk.say(str(swept))
    logs.event(
        "prune.swept",
        on=when,
        observations=swept.observations,
        runs=swept.runs,
        payloads=swept.blobs,
        applied=applying,
    )

    if scrubbing:
        _scrub(desk, applying)

    if not applying:
        desk.say("nothing was deleted, pass --apply to do it")


def _scrub(desk: Desk, applying: bool) -> None:
    """Read every payload back and say which ones are not what they claim.

    Not part of the ordinary sweep. It reads and decompresses the whole
    store, which on a year of collecting is minutes rather than seconds, and
    the answer is almost always none.

    A corrupt blob is repaired by deleting it. That sounds backwards and is
    not: the digest goes back to being one the desk does not hold, and the
    next poll of that market fetches the payload again.
    """
    archive = Archive(desk.settings.archive_root)
    if not applying:
        bad = archive.verify()
        desk.say(f"{len(bad)} payloads do not read back")
        for digest in bad[:10]:
            desk.warn(f"  {digest}")
        return

    corrupt, leftovers = archive.scrub()
    desk.say(f"removed {corrupt} unreadable payloads and {leftovers} unfinished writes")
    logs.event("prune.scrubbed", corrupt=corrupt, leftovers=leftovers)


def _policy(observation_days: int | None, run_days: int | None, archive_days: int | None) -> Policy:
    base = default_policy()
    return Policy(
        observation_days=observation_days or base.observation_days,
        run_days=run_days or base.run_days,
        archive_days=archive_days or base.archive_days,
    )


def _sweep(desk: Desk, policy: Policy, when: date, applying: bool) -> Swept:
    """Count what would go, and delete it only when told to.

    The counting and the deleting are the same code path with one rollback
    between them. Two paths would mean the dry run reports on something the
    real one does not do, which is the one thing a dry run must never do.
    """
    swept = Swept()

    with desk.database.unit() as work:
        swept.observations = work.observations.prune(policy.observations_before(when))
        swept.runs = work.runs.prune(policy.runs_before(when))
        if applying:
            work.commit()

    archive = Archive(desk.settings.archive_root)
    if applying:
        rows, blobs, kept = archive.prune(policy.archive_before(when))
    else:
        from gussetline.retention import keepable

        manifest = archive.manifest()
        surviving, named = keepable(manifest, policy.archive_before(when))
        rows = len(manifest) - len(surviving)
        blobs = len({str(row["digest"]) for row in manifest} - named)
        kept = len(named)

    swept.manifest_rows = rows
    swept.blobs = blobs
    swept.blobs_kept = kept
    return swept
