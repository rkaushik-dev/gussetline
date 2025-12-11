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

Writing is the part that needed care, and it is worth saying why. A content
addressed store answers "do we hold this" by asking whether a file exists at
the path the hash names. Write that file in place and a process killed
partway through leaves a short one sitting at exactly that path. Every later
poll then says yes, we hold it, and never fetches the payload again. The
audit that needed the supplier's own bytes gets a gzip error, or worse, a
truncated document that parses. The one property this whole module exists for
is quietly gone and nothing reported anything.

So a blob is written beside itself and moved into place, which is atomic on
both the platforms this runs on, and a reader checks that the bytes it got
back still hash to the name it asked for. That check is cheap next to the
network call that would otherwise be needed to replace them, and it turns an
undetectable wrong answer into a loud one.
"""

from __future__ import annotations

import gzip
import hashlib
import json
import os
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import Any
from uuid import uuid4

from gussetline.errors import ValidationError
from gussetline.retention import keepable


def digest_of(payload: bytes) -> str:
    """The key a payload is stored under. Sixteen bytes is plenty here."""
    return hashlib.sha256(payload).hexdigest()[:32]


# What a half written blob is called while it is being written. Named so that
# a sweep can recognise one and so that it can never be mistaken for a digest.
PARTIAL = ".partial"


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
        """Whether this payload is here. Existence only, because it is hot.

        Every poll asks this once per payload, so it stays a stat call. What
        it cannot tell you is whether the bytes are still the bytes, which is
        what verify is for.
        """
        return self._path_for(digest).exists()

    def _write(self, target: Path, payload: bytes) -> None:
        """Put a blob at its path, or leave nothing at that path at all.

        Beside and then moved, because a file written in place and
        interrupted leaves a short one under the name of the full payload,
        and this store answers "do we hold it" by looking for that name.

        The temporary file goes in the same directory rather than a system
        temp dir, since a move across filesystems is a copy and a copy can be
        interrupted, which is the thing being avoided.

        Its name carries a random tail because the pool stores payloads from
        several threads at once and two markets that came back identical are
        two writers heading for one path. Content addressing is what makes
        that harmless: whoever gets there first wrote the same bytes, so a
        writer that finds the target already taken drops its own copy rather
        than fighting for it. Windows will not move a file onto one another
        process holds open, and there is nothing to gain by winning.
        """
        target.parent.mkdir(parents=True, exist_ok=True)
        beside = target.with_name(f"{target.name}.{uuid4().hex[:8]}{PARTIAL}")
        with beside.open("wb") as handle:
            handle.write(gzip.compress(payload))
            handle.flush()
            os.fsync(handle.fileno())

        if target.exists():
            beside.unlink(missing_ok=True)
            return
        try:
            os.replace(beside, target)
        except OSError:
            beside.unlink(missing_ok=True)
            if not target.exists():
                raise

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
            self._write(target, payload)

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
        """The payload as it arrived, checked against the name it is under.

        A blob that does not hash to its own name is not returned. Handing
        back bytes a supplier did not send is worse than handing back
        nothing, because the whole reason anybody reads this store is to
        settle an argument about what was sent.
        """
        target = self._path_for(digest)
        if not target.exists():
            raise ValidationError("nothing stored under that digest", digest=digest)

        try:
            payload = gzip.decompress(target.read_bytes())
        except (OSError, EOFError, gzip.BadGzipFile) as exc:
            raise ValidationError("stored payload will not decompress", digest=digest) from exc

        if digest_of(payload) != digest:
            raise ValidationError("stored payload is not what it is filed as", digest=digest)
        return payload

    def verify(self) -> list[str]:
        """Every stored payload read back and checked. Returns the bad ones.

        Slow on purpose and not on any hot path. Run it after a machine goes
        down mid poll, or on a schedule if the disk is old.
        """
        bad = []
        for path in sorted(self.blobs.rglob("*")):
            if not path.is_file() or path.name.endswith(PARTIAL):
                continue
            try:
                self.get(path.name)
            except ValidationError:
                bad.append(path.name)
        return bad

    def scrub(self) -> tuple[int, int]:
        """Take out what cannot be read and what was never finished.

        Returns the corrupt blobs removed and the abandoned part files
        removed. Removing a corrupt blob is the repair: the digest goes back
        to being one we do not hold, so the next poll fetches it again.
        """
        corrupt = self.verify()
        for digest in corrupt:
            self._path_for(digest).unlink(missing_ok=True)

        leftovers = [path for path in self.blobs.rglob("*" + PARTIAL) if path.is_file()]
        for path in leftovers:
            path.unlink(missing_ok=True)
        return len(corrupt), len(leftovers)

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
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(beside, self.manifest_path)

        return dropped_rows, removed, len(named)

    def stored_count(self) -> int:
        """Finished payloads. A part file is not one and is not counted."""
        return sum(
            1
            for path in self.blobs.rglob("*")
            if path.is_file() and not path.name.endswith(PARTIAL)
        )

    def __str__(self) -> str:
        return f"{self.stored_count()} payloads under {self.root}"
