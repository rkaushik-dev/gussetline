"""The http client, with the retry policy the suppliers actually need.

Three rules, learned in this order.

Retry only what can succeed on a second try. A timeout, a connection reset and
a 503 are all worth repeating. A 400 is not, and neither is a 404, and a
client that retries those spends its budget arguing with a supplier that has
already given its final answer.

Back off with jitter and not on a fixed schedule. Two collectors that both
retry after exactly two seconds arrive together the second time as well, and
the supplier sees a burst rather than a queue.

And count the attempts on the response. A market that took four tries is not
the same reading as one that answered immediately, even when the payload is
identical, and the reliability report is built entirely out of that number.

The clock and the sleep are injected so the tests do not wait. That is not a
testing convenience bolted on, it is the same seam the scheduler uses to run
a whole day of collection in a second.
"""

from __future__ import annotations

import random
import time
from collections.abc import Callable, Mapping
from dataclasses import dataclass, field
from typing import Any

import httpx

from gussetline.errors import (
    MalformedPayload,
    SupplierBlocked,
    SupplierThrottled,
    SupplierUnavailable,
)

# Status codes worth trying again. Everything else is the supplier's answer.
RETRYABLE_STATUS = frozenset({408, 425, 429, 500, 502, 503, 504})

# Status codes that mean stop asking rather than slow down.
BLOCKING_STATUS = frozenset({401, 403, 451})


@dataclass(frozen=True, slots=True)
class Response:
    """One answer from a supplier, with what it took to get it."""

    status: int
    body: bytes
    headers: Mapping[str, str] = field(default_factory=dict)
    attempts: int = 1
    elapsed_ms: int = 0
    url: str = ""

    @property
    def ok(self) -> bool:
        return 200 <= self.status < 300

    @property
    def text(self) -> str:
        return self.body.decode("utf-8", errors="replace")

    def json(self) -> Any:
        import json

        try:
            return json.loads(self.body)
        except ValueError as exc:
            raise MalformedPayload("response was not json", url=self.url) from exc

    def header(self, name: str, default: str = "") -> str:
        wanted = name.lower()
        for key, value in self.headers.items():
            if key.lower() == wanted:
                return value
        return default

    def retry_after_seconds(self) -> float | None:
        """The supplier's own instruction, when it sends one."""
        raw = self.header("retry-after")
        if not raw:
            return None
        try:
            return float(raw)
        except ValueError:
            return None

    def __str__(self) -> str:
        return f"{self.status} {self.url} in {self.attempts} attempts"


@dataclass(frozen=True, slots=True)
class RetryPolicy:
    """How hard to try, and how politely."""

    attempts: int = 3
    base_delay: float = 0.5
    max_delay: float = 30.0
    jitter: float = 0.25

    def __post_init__(self) -> None:
        if self.attempts < 1:
            raise ValueError("a retry policy tries at least once")

    def delay_for(self, attempt: int, rng: random.Random) -> float:
        """Exponential, capped, then jittered by a fraction either way."""
        raw = min(self.base_delay * (2 ** (attempt - 1)), self.max_delay)
        spread = raw * self.jitter
        return max(0.0, raw + rng.uniform(-spread, spread))


class HttpClient:
    """A thin wrapper that owns the retry decision and nothing else."""

    def __init__(
        self,
        base_url: str = "",
        *,
        timeout: float = 10.0,
        policy: RetryPolicy | None = None,
        transport: httpx.BaseTransport | None = None,
        headers: Mapping[str, str] | None = None,
        sleep: Callable[[float], None] | None = None,
        clock: Callable[[], float] | None = None,
        seed: int | None = None,
    ) -> None:
        self.base_url = base_url.rstrip("/")
        self.policy = policy if policy is not None else RetryPolicy()
        self._sleep = sleep if sleep is not None else time.sleep
        self._clock = clock if clock is not None else time.monotonic
        self._rng = random.Random(seed)
        self._client = httpx.Client(
            base_url=self.base_url,
            timeout=timeout,
            transport=transport,
            headers=dict(headers or {}),
        )
        self.slept: list[float] = []

    def close(self) -> None:
        self._client.close()

    def __enter__(self) -> HttpClient:
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()

    def get(
        self,
        path: str,
        params: Mapping[str, str] | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> Response:
        return self.request("GET", path, params=params, headers=headers)

    def post(
        self,
        path: str,
        content: bytes | str | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> Response:
        return self.request("POST", path, content=content, headers=headers)

    def request(
        self,
        method: str,
        path: str,
        *,
        params: Mapping[str, str] | None = None,
        content: bytes | str | None = None,
        headers: Mapping[str, str] | None = None,
    ) -> Response:
        """Try until it works, the budget runs out, or the answer is final."""
        started = self._clock()
        last: Exception | None = None

        for attempt in range(1, self.policy.attempts + 1):
            try:
                raw = self._client.request(
                    method,
                    path,
                    params=dict(params or {}),
                    content=content,
                    headers=dict(headers or {}),
                )
            except httpx.HTTPError as exc:
                last = exc
                if attempt == self.policy.attempts:
                    raise SupplierUnavailable(
                        "transport failed", url=path, attempts=attempt
                    ) from exc
                self._wait(attempt, None)
                continue

            response = Response(
                status=raw.status_code,
                body=raw.content,
                headers=dict(raw.headers),
                attempts=attempt,
                elapsed_ms=int((self._clock() - started) * 1000),
                url=str(raw.url),
            )

            if raw.status_code in BLOCKING_STATUS:
                raise SupplierBlocked(
                    "the supplier refused us", status=raw.status_code, url=str(raw.url)
                )
            if raw.status_code in RETRYABLE_STATUS:
                if attempt == self.policy.attempts:
                    if raw.status_code == 429:
                        raise SupplierThrottled(
                            "throttled and out of attempts",
                            status=raw.status_code,
                            attempts=attempt,
                        )
                    raise SupplierUnavailable(
                        "the supplier did not answer",
                        status=raw.status_code,
                        attempts=attempt,
                    )
                self._wait(attempt, response.retry_after_seconds())
                continue

            return response

        raise SupplierUnavailable("out of attempts", url=path) from last

    def _wait(self, attempt: int, asked_for: float | None) -> None:
        """Honour the supplier's own delay when it sends one, else back off."""
        delay = asked_for if asked_for is not None else self.policy.delay_for(attempt, self._rng)
        delay = min(delay, self.policy.max_delay)
        self.slept.append(delay)
        self._sleep(delay)
