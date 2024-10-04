"""Reading fixed width records, which is what the pricing gateway still sends.

The format is from the seventies and it has not moved: two characters of
record tag, then fields at documented offsets, one record per line, and a
whole response is a run of records that only makes sense in order. There is no
library for it because every gateway's layout is different, so what is here is
a way to declare a layout rather than a parser for one.

Two things this refuses to do, both learned the hard way.

It does not strip a field before deciding whether it is empty. A quantity
field of four spaces is missing and a quantity field of four zeroes is zero,
and a parser that strips first cannot tell them apart. So the raw slice is
kept and the emptiness test is on the raw.

And it does not skip a line it cannot read. An unknown tag, a short line, a
number with a letter in it are all recorded as problems against the line
number, and the read continues. A supplier that adds a record type should not
cost us the whole payload, and a supplier that corrupts one should not do it
silently.
"""

from __future__ import annotations

from collections.abc import Callable, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import date, datetime
from decimal import Decimal, InvalidOperation
from typing import Any

from gussetline.errors import MalformedPayload


@dataclass(frozen=True, slots=True)
class Field:
    """One field at a fixed offset, and how to read it."""

    name: str
    start: int
    length: int
    kind: str = "text"
    optional: bool = False

    @property
    def end(self) -> int:
        return self.start + self.length

    def slice_from(self, line: str) -> str:
        return line[self.start : self.end]


def _read_int(raw: str) -> int:
    return int(raw.strip())


def _read_decimal(raw: str, scale: int = 2) -> Decimal:
    """Read an implied decimal, which is how amounts are sent.

    ``000042000`` with a scale of two is four hundred and twenty. The decimal
    point is not in the payload and never has been.
    """
    digits = raw.strip()
    if not digits.lstrip("-").isdigit():
        raise ValueError(f"not a number: {raw!r}")
    sign = -1 if digits.startswith("-") else 1
    digits = digits.lstrip("-")
    if scale == 0:
        return Decimal(digits) * sign
    padded = digits.rjust(scale + 1, "0")
    return Decimal(f"{padded[:-scale]}.{padded[-scale:]}") * sign


def _read_date(raw: str) -> date:
    text = raw.strip()
    if len(text) == 8:
        return datetime.strptime(text, "%Y%m%d").date()
    raise ValueError(f"not a date: {raw!r}")


def _read_daytime(raw: str) -> tuple[int, int, int, int]:
    """``03041025`` is the fourth of March at ten twenty five."""
    text = raw.strip()
    if len(text) != 8 or not text.isdigit():
        raise ValueError(f"not a day and time: {raw!r}")
    return int(text[0:2]), int(text[2:4]), int(text[4:6]), int(text[6:8])


READERS: dict[str, Callable[[str], Any]] = {
    "text": lambda raw: raw.strip(),
    "raw": lambda raw: raw,
    "int": _read_int,
    "amount": _read_decimal,
    "amount0": lambda raw: _read_decimal(raw, 0),
    "amount3": lambda raw: _read_decimal(raw, 3),
    "date": _read_date,
    "daytime": _read_daytime,
}


@dataclass(frozen=True, slots=True)
class RecordSpec:
    """A tag and the fields that follow it."""

    tag: str
    fields: tuple[Field, ...]

    @property
    def width(self) -> int:
        return max((f.end for f in self.fields), default=len(self.tag))


@dataclass
class Problem:
    """Something wrong with one line, kept rather than raised."""

    line_number: int
    line: str
    reason: str

    def __str__(self) -> str:
        return f"line {self.line_number}: {self.reason}"


@dataclass
class Record:
    """One parsed line."""

    tag: str
    values: dict[str, Any] = field(default_factory=dict)
    line_number: int = 0

    def __getitem__(self, name: str) -> Any:
        return self.values[name]

    def get(self, name: str, default: Any = None) -> Any:
        return self.values.get(name, default)

    def __contains__(self, name: str) -> bool:
        return name in self.values

    def __str__(self) -> str:
        return f"{self.tag} {self.values}"


@dataclass
class Reading:
    """Everything a payload gave up, and everything it could not."""

    records: list[Record] = field(default_factory=list)
    problems: list[Problem] = field(default_factory=list)

    @property
    def clean(self) -> bool:
        return not self.problems

    def tagged(self, tag: str) -> list[Record]:
        return [r for r in self.records if r.tag == tag]

    def first(self, tag: str) -> Record | None:
        for record in self.records:
            if record.tag == tag:
                return record
        return None

    def require(self, tag: str) -> Record:
        found = self.first(tag)
        if found is None:
            raise MalformedPayload("the payload has no such record", tag=tag)
        return found

    def __len__(self) -> int:
        return len(self.records)

    def __str__(self) -> str:
        return f"{len(self.records)} records, {len(self.problems)} problems"


class Layout:
    """A set of record specs, read as one payload."""

    def __init__(self, specs: Iterable[RecordSpec], tag_width: int = 2) -> None:
        self.tag_width = tag_width
        self._specs: dict[str, RecordSpec] = {}
        for spec in specs:
            self._specs[spec.tag] = spec

    def spec_for(self, tag: str) -> RecordSpec | None:
        return self._specs.get(tag)

    def tags(self) -> list[str]:
        return sorted(self._specs)

    def read(self, payload: str | bytes) -> Reading:
        """Read a whole payload, keeping problems rather than raising."""
        text = payload.decode("utf-8", errors="replace") if isinstance(payload, bytes) else payload
        reading = Reading()

        for number, line in enumerate(text.splitlines(), start=1):
            if not line.strip():
                continue
            tag = line[: self.tag_width]
            spec = self.spec_for(tag)
            if spec is None:
                reading.problems.append(Problem(number, line, f"unknown record tag {tag!r}"))
                continue
            if len(line) < spec.width:
                reading.problems.append(
                    Problem(number, line, f"line is {len(line)} and the layout wants {spec.width}")
                )
                continue
            record = self._read_line(spec, line, number, reading)
            if record is not None:
                reading.records.append(record)

        return reading

    def _read_line(
        self, spec: RecordSpec, line: str, number: int, reading: Reading
    ) -> Record | None:
        values: dict[str, Any] = {}
        for column in spec.fields:
            raw = column.slice_from(line)
            if not raw.strip():
                if column.optional:
                    continue
                if column.kind in ("text", "raw"):
                    values[column.name] = READERS[column.kind](raw)
                    continue
                reading.problems.append(
                    Problem(number, line, f"{column.name} is empty and is not optional")
                )
                return None
            try:
                values[column.name] = READERS[column.kind](raw)
            except (ValueError, InvalidOperation, KeyError) as exc:
                reading.problems.append(Problem(number, line, f"{column.name} did not read: {exc}"))
                return None
        return Record(tag=spec.tag, values=values, line_number=number)


def fields(*rows: Sequence[Any]) -> tuple[Field, ...]:
    """Declare a record's fields from tuples, which reads like the spec sheet."""
    return tuple(Field(*row) for row in rows)
