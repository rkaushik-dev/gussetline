"""Assembling a collector out of the parts, which nothing else should do.

Every piece below this is independent and testable on its own, and that is
worth keeping. The cost is that something has to know how they fit together,
and when that something is the command line it gets copied into the second
command and then the two drift. So the assembly is here, once.

The part worth explaining is the replay fetch.

Every payload a collector has ever taken is in the archive, keyed by content,
with the market and the departure written next to it in the manifest. That
makes a second fetch possible that never touches the network: given a task, it
looks up the newest stored payload for that market and departure and hands it
back as though a supplier had just answered. A whole day of collection then
runs against a new parser in a second.

That is not a test fixture. It is how a parser bug gets fixed: the payload
that broke it is already stored, so the fix can be proved against the real
bytes rather than against a reduction of them somebody typed out afterwards.
It is also the only way to reparse a month of history when a supplier's date
format turns out to have changed three weeks ago.

A replayed task with nothing stored for it is not an error either. It means
that market was never collected, and the run records it the same way it would
record a supplier that had nothing to say.
"""

from __future__ import annotations

import time
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any

from gussetline.collect.archive import Archive
from gussetline.collect.breaker import BreakerBoard
from gussetline.collect.harvest import Adapter
from gussetline.collect.http import HttpClient, Response, RetryPolicy
from gussetline.collect.limits import Limiter
from gussetline.collect.registry import Registry, Supplier, default_registry
from gussetline.collect.run import Collector, Fetch
from gussetline.collect.schedule import Task
from gussetline.config import Settings
from gussetline.errors import SupplierUnavailable

# What a replayed task gets when the archive has nothing for that market.
NOTHING_STORED = b""


@dataclass
class Rig:
    """Everything a collection run needs, built once from the settings."""

    settings: Settings
    registry: Registry
    limiter: Limiter
    breakers: BreakerBoard
    archive: Archive
    parsers: dict[str, Adapter] = field(default_factory=dict)
    clock: Callable[[], float] = time.monotonic
    now: Callable[[], datetime] = datetime.now

    def collector(self) -> Collector:
        return Collector(
            self.registry,
            self.limiter,
            self.breakers,
            self.archive,
            now=self.now,
        )

    def register(self, supplier: str, parser: Adapter) -> None:
        self.parsers[supplier] = parser

    def __str__(self) -> str:
        return (
            f"{len(self.registry)} suppliers, {len(self.parsers)} parsers, "
            f"quota {self.settings.daily_quota} a day"
        )


def build(
    settings: Settings,
    *,
    registry: Registry | None = None,
    parsers: dict[str, Adapter] | None = None,
    clock: Callable[[], float] | None = None,
    now: Callable[[], datetime] | None = None,
) -> Rig:
    """Wire the parts together the one way they go together.

    Every supplier's host is configured on the limiter here rather than on
    first use, so a run against a supplier that is completely down still
    respects its budget, and so the settings command can print what a run
    would allow before anything runs.
    """
    tick = clock if clock is not None else time.monotonic
    made = Rig(
        settings=settings,
        registry=registry if registry is not None else default_registry(),
        limiter=Limiter(
            tick,
            default_rate=settings.rate_per_second,
            default_burst=settings.burst,
            default_quota=settings.daily_quota,
        ),
        breakers=BreakerBoard(
            tick,
            threshold=settings.breaker_threshold,
            cooldown=settings.breaker_cooldown,
        ),
        archive=Archive(settings.archive_root),
        parsers=dict(parsers or {}),
        clock=tick,
        now=now if now is not None else datetime.now,
    )
    for name in made.registry.names():
        made.limiter.configure(made.registry.get(name).host)
    return made


def live_fetch(rig: Rig, client_for: Callable[[Supplier], HttpClient] | None = None) -> Fetch:
    """A fetch that actually goes out, one client per supplier."""
    clients: dict[str, HttpClient] = {}

    def client(supplier: Supplier) -> HttpClient:
        if supplier.name not in clients:
            clients[supplier.name] = (
                client_for(supplier)
                if client_for is not None
                else HttpClient(
                    base_url=f"https://{supplier.host}",
                    timeout=rig.settings.request_timeout,
                    policy=RetryPolicy(attempts=rig.settings.retry_attempts),
                    headers={"User-Agent": supplier.user_agent},
                )
            )
        return clients[supplier.name]

    def fetch(supplier: Supplier, task: Task) -> Response:
        return client(supplier).get(
            supplier.path,
            params={
                "origin": task.market.origin,
                "destination": task.market.destination,
                "departure": task.departure.isoformat(),
            },
        )

    return fetch


def replay_fetch(rig: Rig, supplier_only: str | None = None) -> Fetch:
    """A fetch that answers out of the archive and never leaves the machine."""
    stored = _index(rig.archive, supplier_only)

    def fetch(supplier: Supplier, task: Task) -> Response:
        key = (supplier.name, task.market.key, task.departure.isoformat())
        found = stored.get(key)
        if found is None:
            raise SupplierUnavailable(
                "nothing stored for that market",
                supplier=supplier.name,
                market=task.market.key,
            )
        return Response(
            status=200,
            body=found,
            url=f"replay://{supplier.name}{supplier.path}",
            attempts=1,
        )

    return fetch


def _index(archive: Archive, supplier_only: str | None) -> dict[tuple[str, str, str], bytes]:
    """The newest stored payload for every market and departure.

    Newest rather than first, because a market polled every day has thirty
    payloads and the only one worth replaying is the one that broke.
    """
    newest: dict[tuple[str, str, str], tuple[str, str]] = {}
    for row in archive.manifest():
        if supplier_only is not None and row["supplier"] != supplier_only:
            continue
        context: dict[str, Any] = row.get("context") or {}
        market = context.get("market")
        departure = context.get("departure")
        if not market or not departure:
            continue
        key = (row["supplier"], str(market), str(departure))
        seen = str(row["seen_at"])
        if key not in newest or seen > newest[key][0]:
            newest[key] = (seen, row["digest"])

    return {key: archive.get(digest) for key, (_, digest) in newest.items()}
