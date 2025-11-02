"""Settings, read from the environment once and then frozen.

Everything the desk can change without a deploy lives here. The rule is that a
setting is read at startup and never again, because a value that can change
under a running collector produces a run whose first half used one budget and
whose second half used another, and nothing in the report says so.

Every setting has a default that works. A desk that has just cloned the repo
can run the whole thing against a file backed database with no environment at
all, which is what makes the end to end test in the suite a real test rather
than a mock.
"""

from __future__ import annotations

import os
from collections.abc import Mapping
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from pathlib import Path

from gussetline.errors import ConfigError

PREFIX = "GUSSETLINE_"


def _read(env: Mapping[str, str], name: str, fallback: str) -> str:
    return env.get(PREFIX + name, fallback)


def _read_int(env: Mapping[str, str], name: str, fallback: int) -> int:
    raw = _read(env, name, str(fallback))
    try:
        return int(raw)
    except ValueError:
        raise ConfigError("not a whole number", setting=PREFIX + name, value=raw) from None


def _read_float(env: Mapping[str, str], name: str, fallback: float) -> float:
    raw = _read(env, name, str(fallback))
    try:
        return float(raw)
    except ValueError:
        raise ConfigError("not a number", setting=PREFIX + name, value=raw) from None


def _read_decimal(env: Mapping[str, str], name: str, fallback: str) -> Decimal:
    raw = _read(env, name, fallback)
    try:
        return Decimal(raw)
    except InvalidOperation:
        raise ConfigError("not an amount", setting=PREFIX + name, value=raw) from None


def _read_bool(env: Mapping[str, str], name: str, fallback: bool) -> bool:
    raw = _read(env, name, "yes" if fallback else "no").strip().lower()
    if raw in ("1", "yes", "true", "on"):
        return True
    if raw in ("0", "no", "false", "off"):
        return False
    raise ConfigError("not a yes or no", setting=PREFIX + name, value=raw)


# Not slotted, unlike everything else here. The reader below uses
# ``Settings.daily_quota`` as its fallback, and under slots that attribute is
# a descriptor rather than the default value, which silently turns every
# fallback into an object nobody can parse.
@dataclass(frozen=True)
class Settings:
    """Every knob, with the values this run will use."""

    database_url: str = "sqlite+pysqlite:///var/gussetline.sqlite3"
    archive_root: Path = Path("var/raw")
    selling_currency: str = "EUR"
    country_of_sale: str = "DE"

    daily_quota: int = 1000
    rate_per_second: float = 1.0
    burst: int = 5
    request_timeout: float = 10.0
    retry_attempts: int = 3

    breaker_threshold: int = 5
    breaker_cooldown: float = 60.0

    material_difference: Decimal = Decimal("1.00")
    watch_threshold: Decimal = Decimal("50.00")
    credit_discount: Decimal = Decimal("0.80")

    user_agent: str = "gussetline/1.0"
    honour_robots: bool = True
    dry_run: bool = False

    def __post_init__(self) -> None:
        if self.daily_quota < 0:
            raise ConfigError("a quota is not negative", value=self.daily_quota)
        if self.rate_per_second <= 0:
            raise ConfigError("a rate is positive", value=self.rate_per_second)
        if self.burst < 1:
            raise ConfigError("a burst holds at least one", value=self.burst)
        if self.retry_attempts < 1:
            raise ConfigError("at least one attempt", value=self.retry_attempts)
        if not 0 <= self.credit_discount <= 1:
            raise ConfigError("a credit is worth between nothing and cash")
        if len(self.selling_currency) != 3:
            raise ConfigError("a currency code is three letters", value=self.selling_currency)

    @property
    def is_sqlite(self) -> bool:
        return self.database_url.startswith("sqlite")

    @property
    def in_memory(self) -> bool:
        return ":memory:" in self.database_url

    def with_database(self, url: str) -> Settings:
        """A copy pointed at another database, for a test or a dry run."""
        return Settings(
            database_url=url,
            archive_root=self.archive_root,
            selling_currency=self.selling_currency,
            country_of_sale=self.country_of_sale,
            daily_quota=self.daily_quota,
            rate_per_second=self.rate_per_second,
            burst=self.burst,
            request_timeout=self.request_timeout,
            retry_attempts=self.retry_attempts,
            breaker_threshold=self.breaker_threshold,
            breaker_cooldown=self.breaker_cooldown,
            material_difference=self.material_difference,
            watch_threshold=self.watch_threshold,
            credit_discount=self.credit_discount,
            user_agent=self.user_agent,
            honour_robots=self.honour_robots,
            dry_run=self.dry_run,
        )

    def __str__(self) -> str:
        where = "memory" if self.in_memory else self.database_url.rsplit("/", 1)[-1]
        return f"gussetline against {where}, selling in {self.selling_currency}"


def from_env(env: Mapping[str, str] | None = None) -> Settings:
    """Read the settings once. Every value has a default that works."""
    values = env if env is not None else os.environ
    return Settings(
        database_url=_read(values, "DATABASE_URL", Settings.database_url),
        archive_root=Path(_read(values, "ARCHIVE_ROOT", str(Settings.archive_root))),
        selling_currency=_read(values, "SELLING_CURRENCY", Settings.selling_currency).upper(),
        country_of_sale=_read(values, "COUNTRY_OF_SALE", Settings.country_of_sale).upper(),
        daily_quota=_read_int(values, "DAILY_QUOTA", Settings.daily_quota),
        rate_per_second=_read_float(values, "RATE_PER_SECOND", Settings.rate_per_second),
        burst=_read_int(values, "BURST", Settings.burst),
        request_timeout=_read_float(values, "REQUEST_TIMEOUT", Settings.request_timeout),
        retry_attempts=_read_int(values, "RETRY_ATTEMPTS", Settings.retry_attempts),
        breaker_threshold=_read_int(values, "BREAKER_THRESHOLD", Settings.breaker_threshold),
        breaker_cooldown=_read_float(values, "BREAKER_COOLDOWN", Settings.breaker_cooldown),
        material_difference=_read_decimal(values, "MATERIAL_DIFFERENCE", "1.00"),
        watch_threshold=_read_decimal(values, "WATCH_THRESHOLD", "50.00"),
        credit_discount=_read_decimal(values, "CREDIT_DISCOUNT", "0.80"),
        user_agent=_read(values, "USER_AGENT", Settings.user_agent),
        honour_robots=_read_bool(values, "HONOUR_ROBOTS", Settings.honour_robots),
        dry_run=_read_bool(values, "DRY_RUN", Settings.dry_run),
    )


# Every setting the environment can carry, in the order the settings command
# prints them. Written out rather than derived from the dataclass, because the
# README lists these by name and a test walks both lists: a setting renamed in
# one place and not the other is then a named failure rather than a knob that
# quietly stops working.
ENVIRONMENT_NAMES: tuple[str, ...] = (
    "DATABASE_URL",
    "ARCHIVE_ROOT",
    "SELLING_CURRENCY",
    "COUNTRY_OF_SALE",
    "DAILY_QUOTA",
    "RATE_PER_SECOND",
    "BURST",
    "REQUEST_TIMEOUT",
    "RETRY_ATTEMPTS",
    "BREAKER_THRESHOLD",
    "BREAKER_COOLDOWN",
    "MATERIAL_DIFFERENCE",
    "WATCH_THRESHOLD",
    "CREDIT_DISCOUNT",
    "USER_AGENT",
    "HONOUR_ROBOTS",
    "DRY_RUN",
)


def environment_names() -> tuple[str, ...]:
    """What a caller may set, fully prefixed, in a settled order."""
    return tuple(f"{PREFIX}{name}" for name in ENVIRONMENT_NAMES)


def what_is_set(env: Mapping[str, str] | None = None) -> dict[str, str]:
    """Which of them this process actually has, and to what.

    For the settings command, which is the first thing anybody checks when a
    run does something surprising. A value that is not set does not appear:
    the point is to see what was chosen, and a screen of defaults buries it.
    """
    values = env if env is not None else os.environ
    return {name: values[name] for name in environment_names() if name in values}
