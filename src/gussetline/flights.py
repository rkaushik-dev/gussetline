"""Flight segments, and time arithmetic that survives crossing a date line.

Every supplier sends departure and arrival as local wall clock times with no
offset, because that is what a timetable is. Two of them also send a day
change digit, one sends none and expects the reader to work it out, and one
sends an arrival that is genuinely before the departure and means the next
day. So a segment stores wall clock times plus the airport, and converts on
demand using the offsets in the geography table.

There is no tz database here on purpose. The offsets are fixed per airport and
summer time is ignored, which is wrong by an hour twice a year for elapsed
times and is right for everything the tariff cares about, since a connection
minimum is checked in local minutes and a fare rule in local dates.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import date, datetime, timedelta
from enum import Enum

from gussetline.carriers import normalise_code
from gussetline.errors import ValidationError
from gussetline.geography import airport


class Cabin(str, Enum):
    """The four cabins the tariff prices. Anything else is marketing."""

    FIRST = "F"
    BUSINESS = "C"
    PREMIUM = "W"
    ECONOMY = "Y"

    @property
    def rank(self) -> int:
        return {"F": 4, "C": 3, "W": 2, "Y": 1}[self.value]

    def outranks(self, other: Cabin) -> bool:
        return self.rank > other.rank


@dataclass(frozen=True, slots=True)
class Segment:
    """One flown sector as the timetable describes it.

    ``departure`` and ``arrival`` are naive local wall clock. ``day_change``
    is how many calendar days the arrival is after the departure date, and it
    is derived when a supplier does not send one.
    """

    marketing_carrier: str
    flight_number: str
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    booking_class: str
    cabin: Cabin
    operating_carrier: str | None = None
    equipment: str | None = None
    day_change: int = 0
    stops: int = 0

    def __post_init__(self) -> None:
        object.__setattr__(self, "marketing_carrier", normalise_code(self.marketing_carrier))
        if self.operating_carrier is not None:
            object.__setattr__(self, "operating_carrier", normalise_code(self.operating_carrier))
        object.__setattr__(self, "origin", airport(self.origin).code)
        object.__setattr__(self, "destination", airport(self.destination).code)
        if self.origin == self.destination:
            raise ValidationError("a sector cannot end where it started", point=self.origin)
        if len(self.booking_class) != 1 or not self.booking_class.isalpha():
            raise ValidationError("a booking class is one letter", value=self.booking_class)
        object.__setattr__(self, "booking_class", self.booking_class.upper())
        if not self.flight_number.strip():
            raise ValidationError("a segment needs a flight number")
        if self.day_change < 0:
            raise ValidationError("a flight does not arrive the day before", value=self.day_change)
        if self.stops < 0:
            raise ValidationError("stops cannot be negative", value=self.stops)

    @property
    def designator(self) -> str:
        return f"{self.marketing_carrier}{self.flight_number.strip()}"

    @property
    def operated_by(self) -> str:
        """Who actually flies it. Absent means the marketing carrier does."""
        return self.operating_carrier or self.marketing_carrier

    @property
    def is_codeshare(self) -> bool:
        return self.operated_by != self.marketing_carrier

    @property
    def departure_date(self) -> date:
        return self.departure.date()

    @property
    def arrival_date(self) -> date:
        return self.departure.date() + timedelta(days=self.day_change)

    @property
    def departure_utc(self) -> datetime:
        offset = airport(self.origin).utc_offset_minutes
        return self.departure - timedelta(minutes=offset)

    @property
    def arrival_utc(self) -> datetime:
        offset = airport(self.destination).utc_offset_minutes
        landed = datetime.combine(self.arrival_date, self.arrival.time())
        return landed - timedelta(minutes=offset)

    @property
    def elapsed(self) -> timedelta:
        """Block time, which is the only duration the desk quotes."""
        return self.arrival_utc - self.departure_utc

    @property
    def is_nonstop(self) -> bool:
        return self.stops == 0

    def key(self) -> tuple[str, str, str, str]:
        return (self.designator, self.origin, self.destination, self.departure.isoformat())

    def __str__(self) -> str:
        return f"{self.designator} {self.origin}{self.destination} {self.departure:%d%b%y %H%M}"


def infer_day_change(
    origin: str,
    destination: str,
    departure: datetime,
    arrival: datetime,
    elapsed_minutes: int | None = None,
) -> int:
    """Work out how many days a sector crosses when the supplier will not say.

    With a block time the answer is arithmetic. The departure is pushed to UTC
    with the origin's offset, the block time is added, and the result is read
    back in the destination's own day. Without one, the day change is the
    smallest number of days that puts the arrival after the departure, which
    is right for every nonstop sector and wrong only for the multi day
    positioning legs the desk does not sell.
    """
    if elapsed_minutes is not None and elapsed_minutes < 0:
        raise ValidationError("block time cannot be negative", minutes=elapsed_minutes)

    origin_offset = airport(origin).utc_offset_minutes
    destination_offset = airport(destination).utc_offset_minutes
    departure_utc = departure - timedelta(minutes=origin_offset)

    if elapsed_minutes is not None:
        landed_utc = departure_utc + timedelta(minutes=elapsed_minutes)
        landed_local = landed_utc + timedelta(minutes=destination_offset)
        return max(0, (landed_local.date() - departure.date()).days)

    for candidate in range(0, 4):
        landed = datetime.combine(departure.date() + timedelta(days=candidate), arrival.time())
        if landed - timedelta(minutes=destination_offset) > departure_utc:
            return candidate
    return 0


@dataclass(slots=True)
class SegmentBuilder:
    """Collects the pieces a parser finds and refuses an incomplete segment.

    Suppliers send the fields in different orders and some of them arrive in
    a later block of the same payload, so the parsers fill one of these in and
    call build once. It exists so that a half read segment fails at the parser
    with the field named, rather than three layers up as a missing attribute.
    """

    marketing_carrier: str | None = None
    flight_number: str | None = None
    origin: str | None = None
    destination: str | None = None
    departure: datetime | None = None
    arrival: datetime | None = None
    booking_class: str | None = None
    cabin: Cabin | None = None
    operating_carrier: str | None = None
    equipment: str | None = None
    day_change: int | None = None
    stops: int = 0
    elapsed_minutes: int | None = None
    notes: list[str] = field(default_factory=list)

    _REQUIRED = (
        "marketing_carrier",
        "flight_number",
        "origin",
        "destination",
        "departure",
        "arrival",
        "booking_class",
        "cabin",
    )

    def missing(self) -> list[str]:
        return [name for name in self._REQUIRED if getattr(self, name) is None]

    def build(self) -> Segment:
        gaps = self.missing()
        if gaps:
            raise ValidationError("segment is incomplete", missing=gaps)
        assert self.departure is not None and self.arrival is not None
        day_change = self.day_change
        if day_change is None:
            day_change = infer_day_change(
                str(self.origin),
                str(self.destination),
                self.departure,
                self.arrival,
                self.elapsed_minutes,
            )
        return Segment(
            marketing_carrier=str(self.marketing_carrier),
            flight_number=str(self.flight_number),
            origin=str(self.origin),
            destination=str(self.destination),
            departure=self.departure,
            arrival=self.arrival,
            booking_class=str(self.booking_class),
            cabin=self.cabin or Cabin.ECONOMY,
            operating_carrier=self.operating_carrier,
            equipment=self.equipment,
            day_change=day_change,
            stops=self.stops,
        )
