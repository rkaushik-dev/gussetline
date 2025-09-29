"""The raw archive, which is the only thing that makes an audit arguable.

Every payload is written to disk exactly as it arrived, keyed by the hash of
its own bytes, before anything tries to parse it. Three reasons, in the order
they came up.

A supplier disputes a finding. The desk needs the payload it actually sent,
not our reading of it, and not the payload it sends today.

A parser is wrong. Every payload ever collected can be reparsed without asking
anybody for anything, which turns a parser fix from a fortnight of waiting for
new data into an afternoon.

And the same payload arrives twice, which happens constantly, because most
markets do not move between polls. Content addressing means the second copy
costs nothing but a manifest line, and the manifest line is what says the
market was checked and had not moved. That distinction is invisible if you
only keep what changed.

Content is gzipped, because fare payloads are repetitive text and compress to
about a tenth.
"""

from __future__ import annotations

import gzip
import hashlib
import json
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any

from gussetline.errors import ValidationError
from gussetline.retention import keepable


def digest_of(payload: bytes) -> str:
    """The key a payload is stored under. Sixteen bytes is plenty here."""
    return hashlib.sha256(payload).hexdigest()[:32]


@dataclass(frozen=True, slots=True)
class Sighting:
    """One time a payload was seen, whether or not it was new."""

    digest: str
    supplier: str
    url: str
    seen_at: datetime
    status: int = 200
    fresh: bool = True
    size: int = 0
    context: dict[str, Any] = field(default_factory=dict)

    def as_dict(self) -> dict[str, Any]:
        return {
            "digest": self.digest,
            "supplier": self.supplier,
            "url": self.url,
            "seen_at": self.seen_at.isoformat(),
            "status": self.status,
            "fresh": self.fresh,
            "size": self.size,
            "context": dict(self.context),
        }

    def __str__(self) -> str:
        mark = "new" if self.fresh else "same"
        return f"{self.seen_at:%Y-%m-%d %H:%M} {self.supplier} {mark} {self.digest[:8]}"


class Archive:
    """Content addressed storage plus an append only manifest."""

    def __init__(self, root: Path | str) -> None:
        self.root = Path(root)
        self.blobs = self.root / "blobs"
        self.manifest_path = self.root / "manifest.jsonl"
        self.blobs.mkdir(parents=True, exist_ok=True)

    def _path_for(self, digest: str) -> Path:
        if len(digest) < 4:
            raise ValidationError("not a digest", digest=digest)
        return self.blobs / digest[:2] / digest[2:4] / digest

    def holds(self, digest: str) -> bool:
        return self._path_for(digest).exists()

    def put(
        self,
        payload: bytes,
        supplier: str,
        url: str,
        seen_at: datetime,
        *,
        status: int = 200,
        context: dict[str, Any] | None = None,
    ) -> Sighting:
        """Store a payload and record that it was seen.

        A payload we already hold is not written again and the sighting is
        marked not fresh, which is how the coverage report tells a market
        that did not move from one that was never polled.
        """
        digest = digest_of(payload)
        target = self._path_for(digest)
        fresh = not target.exists()
        if fresh:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(gzip.compress(payload))

        sighting = Sighting(
            digest=digest,
            supplier=supplier,
            url=url,
            seen_at=seen_at,
            status=status,
            fresh=fresh,
            size=len(payload),
            context=dict(context or {}),
        )
        with self.manifest_path.open("a", encoding="utf-8", newline="\n") as handle:
            handle.write(json.dumps(sighting.as_dict(), sort_keys=True) + "\n")
        return sighting

    def get(self, digest: str) -> bytes:
        target = self._path_for(digest)
        if not target.exists():
            raise ValidationError("nothing stored under that digest", digest=digest)
        return gzip.decompress(target.read_bytes())

    def manifest(self) -> list[dict[str, Any]]:
        if not self.manifest_path.exists():
            return []
        rows: list[dict[str, Any]] = []
        for line in self.manifest_path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                rows.append(json.loads(line))
        return rows

    def sightings_of(self, digest: str) -> list[dict[str, Any]]:
        return [row for row in self.manifest() if row["digest"] == digest]

    def replay(self, supplier: str | None = None) -> Iterator[tuple[dict[str, Any], bytes]]:
        """Every stored payload, for reparsing after a parser is fixed."""
        seen: set[str] = set()
        for row in self.manifest():
            if supplier is not None and row["supplier"] != supplier:
                continue
            if row["digest"] in seen:
                continue
            seen.add(row["digest"])
            yield row, self.get(row["digest"])

    def prune(self, before: datetime) -> tuple[int, int, int]:
        """Drop old sightings and any payload nothing still names.

        Returns the rows removed, the blobs removed, and the blobs kept
        because a surviving row still names them. That last number is the one
        worth reading: a payload seen on thirty mornings is one blob and
        thirty rows, and deleting it because one row is old takes the other
        twenty nine with it.

        The manifest is rewritten whole. It is append only everywhere else,
        so this is the one place that may not be interrupted, and it writes
        beside the file and moves it into place rather than truncating.
        """
        rows = self.manifest()
        kept, named = keepable(rows, before)
        dropped_rows = len(rows) - len(kept)

        removed = 0
        for digest in {str(row["digest"]) for row in rows} - named:
            target = self._path_for(digest)
            if target.exists():
                target.unlink()
                removed += 1

        if dropped_rows:
            beside = self.manifest_path.with_suffix(".rewriting")
            with beside.open("w", encoding="utf-8", newline="\n") as handle:
                for row in kept:
                    handle.write(json.dumps(row, sort_keys=True) + "\n")
            beside.replace(self.manifest_path)

        return dropped_rows, removed, len(named)

    def stored_count(self) -> int:
        return sum(1 for _ in self.blobs.rglob("*") if _.is_file())

    def __str__(self) -> str:
        return f"{self.stored_count()} payloads under {self.root}"
