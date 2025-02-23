"""API keys and what each one is allowed to do.

Three roles and they are not a hierarchy by accident. A reader can look at
observations and prices. An agent can additionally settle a recommendation,
which is the only thing that moves money. An operator can additionally see the
supplier screen, which carries the quota and the cost per call and is
commercially sensitive in a way the fares are not.

Keys are compared with a constant time comparison. That is not theatre: the
keys are short, the service is on the desk's own network, and an early return
on the first wrong character is a measurable difference over enough requests.

A key is stored as a hash and never as itself, so a leaked configuration file
does not hand anybody a working key. The desk generates one, writes down the
plaintext once, and configures the hash.
"""

from __future__ import annotations

import hashlib
import hmac
from collections.abc import Callable
from dataclasses import dataclass, field
from enum import Enum

from fastapi import Depends, Request

from gussetline.errors import GussetlineError

HEADER = "X-Api-Key"


class Forbidden(GussetlineError):
    """The key is good and does not cover this."""

    code = "auth.forbidden"


class Unauthorised(GussetlineError):
    """No key, or one we do not hold."""

    code = "auth.unauthorised"


class Role(str, Enum):
    """What a key may do. Ordered, and the order is meaningful."""

    READER = "reader"
    AGENT = "agent"
    OPERATOR = "operator"

    @property
    def rank(self) -> int:
        return {"reader": 1, "agent": 2, "operator": 3}[self.value]

    def covers(self, wanted: Role) -> bool:
        """Whether this role is at least the one being asked for."""
        return self.rank >= wanted.rank


def fingerprint(key: str) -> str:
    """The stored form of a key. Never the key itself."""
    return hashlib.sha256(key.strip().encode("utf-8")).hexdigest()


@dataclass(frozen=True, slots=True)
class ApiKey:
    """One issued key, by its hash."""

    name: str
    digest: str
    role: Role = Role.READER

    def matches(self, presented: str) -> bool:
        return hmac.compare_digest(self.digest, fingerprint(presented))

    def __str__(self) -> str:
        return f"{self.name} as {self.role.value}"


@dataclass
class KeyRing:
    """Every key the service will accept."""

    keys: list[ApiKey] = field(default_factory=list)
    enabled: bool = True

    def issue(self, name: str, key: str, role: Role = Role.READER) -> ApiKey:
        made = ApiKey(name=name, digest=fingerprint(key), role=role)
        self.keys.append(made)
        return made

    def find(self, presented: str) -> ApiKey | None:
        """The key that matches, checking every one of them.

        Every key is compared even after a match, so the time taken does not
        depend on where in the ring the key sits.
        """
        found: ApiKey | None = None
        for key in self.keys:
            if key.matches(presented):
                found = key
        return found

    def __len__(self) -> int:
        return len(self.keys)


def caller(request: Request) -> ApiKey:
    """The key behind this request, or a refusal."""
    ring: KeyRing | None = getattr(request.app.state, "keyring", None)
    if ring is None or not ring.enabled:
        return ApiKey(name="anonymous", digest="", role=Role.OPERATOR)

    presented = request.headers.get(HEADER, "")
    if not presented:
        raise Unauthorised("no api key was presented", header=HEADER)

    key = ring.find(presented)
    if key is None:
        raise Unauthorised("that api key is not one of ours")
    return key


def requires(role: Role) -> Callable[[ApiKey], ApiKey]:
    """A dependency that refuses anything below the role given."""

    def guard(key: ApiKey = Depends(caller)) -> ApiKey:
        if not key.role.covers(role):
            raise Forbidden(
                "this key does not cover that",
                key=key.name,
                has=key.role.value,
                needs=role.value,
            )
        return key

    return guard


reader = requires(Role.READER)
agent = requires(Role.AGENT)
operator = requires(Role.OPERATOR)
