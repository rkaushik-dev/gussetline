"""Reading an itinerary the way an agent types one.

Nobody at the desk is going to fill in a form. They have been typing sell
entries at a green screen for twenty years and the shape of one is fixed:

    FRA LH400 K 04AUG JFK LH401 K 18AUG FRA

A point, then a flight and a class and a date, then the point it lands at, and
so on. A surface sector, where the passenger gets themselves between two
points, is two slashes in place of the flight:

    FRA LH400 K 04AUG MUC // VIE LH500 K 18AUG FRA

Two things are worth explaining.

Dates have no year on them, because the tariff's own date format has none, and
the year cannot be read off one date in isolation. It comes from the order:
the first date is the next occurrence on or after the day the entry is being
priced for, and every later date rolls forward the moment the month goes
backwards. An outbound on 28DEC and an inbound on 04JAN is a nine day trip
over a new year, not a journey that finishes eleven months before it starts.
Getting that wrong produces a negative maximum stay and a fare that fails a
category 7 check nobody filed.

Times are optional, and when they are missing they are made up rather than
left out. A segment with no times cannot be tested for a stopover, cannot have
its day change worked out, and cannot be priced against a category 2 rule, so
a missing time is filled with a nominal block off the great circle distance
and the departure is put at midday. Midday rather than midnight because a
midnight departure with a nominal block turns every eastbound sector into a
day change that did not happen.

A made up time is marked, so nothing downstream mistakes it for something a
supplier said.

Made up times also have to agree with each other. Two sectors on one date both
put at midday means the second leaves an hour before the first lands, and the
itinerary refuses itself. So a made up departure is pushed past the previous
arrival by a nominal connection when the two share a date. That is not a claim
about the real timetable. It is the least the entry has to say for the rest of
the ladder to be able to read it at all.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from datetime import date, datetime, timedelta

from gussetline.distance import great_circle
from gussetline.errors import ValidationError
from gussetline.flights import Cabin, Segment, YearRoll, infer_day_change
from gussetline.geography import airport
from gussetline.itinerary import Element, Itinerary, SurfaceSector

SURFACE = "//"

# What a made up sector assumes, which is a jet and half an hour of taxiing.
CRUISE_MILES_PER_HOUR = 500
GROUND_MINUTES = 30
NOMINAL_DEPARTURE_HOUR = 12

# The least time a made up connection is given, so a same day connection
# does not read as a sector that leaves before the one before it lands.
NOMINAL_CONNECTION_MINUTES = 60

MONTHS: dict[str, int] = {
    "JAN": 1,
    "FEB": 2,
    "MAR": 3,
    "APR": 4,
    "MAY": 5,
    "JUN": 6,
    "JUL": 7,
    "AUG": 8,
    "SEP": 9,
    "OCT": 10,
    "NOV": 11,
    "DEC": 12,
}

_POINT = re.compile(r"^[A-Z]{3}$")
_FLIGHT = re.compile(r"^([A-Z0-9]{2})(\d{1,4})$")
_DATE = re.compile(r"^(\d{1,2})([A-Z]{3})(?:/(\d{4}))?$")


@dataclass(frozen=True, slots=True)
class Leg:
    """One flown sector as the entry described it, before it becomes a segment."""

    carrier: str
    number: str
    booking_class: str
    origin: str
    destination: str
    departure: datetime
    arrival: datetime
    timed: bool

    def __str__(self) -> str:
        return f"{self.origin}{self.destination} {self.carrier}{self.number} {self.booking_class}"


def month_of(name: str) -> int:
    """The tariff's three letter month. Anything else is a refusal."""
    code = name.strip().upper()
    if code not in MONTHS:
        raise ValidationError("that is not a month", month=name)
    return MONTHS[code]


def nominal_block(origin: str, destination: str) -> int:
    """Minutes a sector would take if nobody told us. Never less than an hour."""
    miles = great_circle(origin, destination)
    flying = (miles * 60) // CRUISE_MILES_PER_HOUR
    return max(int(flying) + GROUND_MINUTES, 60)


def parse(text: str, on: date) -> Itinerary:
    """Read a sell entry into an itinerary, priced from a given day."""
    tokens = text.replace(",", " ").split()
    if not tokens:
        raise ValidationError("nothing to price")

    roll = YearRoll.from_date(on)
    elements: list[Element] = []
    point = _point(tokens[0])
    index = 1

    while index < len(tokens):
        token = tokens[index].strip().upper()
        if token == SURFACE:
            index, point = _surface(tokens, index, point, elements)
            continue
        if token == point:
            # An agent who breaks the entry across sectors types the board
            # point again on the second one. Reading it as a flight number
            # refuses an entry that is perfectly ordinary.
            index += 1
            continue
        index, point = _flown(tokens, index, point, elements, roll)

    if not elements:
        raise ValidationError("an entry needs at least one sector", entry=text)
    return Itinerary(elements)


def _surface(tokens: list[str], index: int, point: str, elements: list[Element]) -> tuple[int, str]:
    if index + 1 >= len(tokens):
        raise ValidationError("a surface sector needs somewhere to arrive", at=point)
    landing = _point(tokens[index + 1])
    elements.append(SurfaceSector(point, landing))
    return index + 2, landing


def _flown(
    tokens: list[str],
    index: int,
    point: str,
    elements: list[Element],
    roll: YearRoll,
) -> tuple[int, str]:
    if index + 3 >= len(tokens):
        raise ValidationError(
            "a sector is a flight, a class, a date and a point",
            after=point,
            saw=" ".join(tokens[index:]),
        )
    carrier, number = _flight(tokens[index])
    booking = _booking_class(tokens[index + 1])
    when, timed = _when(tokens[index + 2], roll)
    landing = _point(tokens[index + 3])

    if not timed:
        when = _after(when, elements)
    elements.append(_segment(carrier, number, booking, point, landing, when, timed))
    return index + 4, landing


def _after(when: datetime, elements: list[Element]) -> datetime:
    """Push a made up departure past the sector before it, on the same date.

    Only for made up times, and only within a date. A real time is what the
    supplier said and is never moved, and a sector on a later date has a whole
    night in front of it.
    """
    flown = [e for e in elements if isinstance(e, Segment)]
    if not flown:
        return when
    landed = flown[-1].arrival
    if landed.date() != when.date():
        return when
    earliest = landed + timedelta(minutes=NOMINAL_CONNECTION_MINUTES)
    return max(when, earliest)


def _segment(
    carrier: str,
    number: str,
    booking: str,
    origin: str,
    destination: str,
    departure: datetime,
    timed: bool,
) -> Segment:
    block = nominal_block(origin, destination)
    arrival = departure + timedelta(minutes=block)
    change = infer_day_change(origin, destination, departure, arrival, block)
    return Segment(
        marketing_carrier=carrier,
        flight_number=number,
        origin=origin,
        destination=destination,
        departure=departure,
        arrival=arrival,
        booking_class=booking,
        cabin=Cabin.ECONOMY,
        day_change=change,
        elapsed_minutes=block,
        estimated=not timed,
    )


def _point(token: str) -> str:
    code = token.strip().upper()
    if not _POINT.match(code):
        raise ValidationError("that is not a point", token=token)
    airport(code)
    return code


def _flight(token: str) -> tuple[str, str]:
    found = _FLIGHT.match(token.strip().upper())
    if not found:
        raise ValidationError("that is not a flight", token=token)
    return found.group(1), found.group(2).lstrip("0") or "0"


def _booking_class(token: str) -> str:
    code = token.strip().upper()
    if len(code) != 1 or not code.isalpha():
        raise ValidationError("a booking class is one letter", token=token)
    return code


def _when(token: str, roll: YearRoll) -> tuple[datetime, bool]:
    found = _DATE.match(token.strip().upper())
    if not found:
        raise ValidationError("that is not a date", token=token)
    day = int(found.group(1))
    month = month_of(found.group(2))
    clock = found.group(3)

    try:
        when = roll.resolve(month, day)
    except ValueError as exc:
        raise ValidationError("no such day", token=token) from exc

    if clock is None:
        return datetime.combine(when, datetime.min.time()).replace(
            hour=NOMINAL_DEPARTURE_HOUR
        ), False
    hour, minute = int(clock[:2]), int(clock[2:])
    if hour > 23 or minute > 59:
        raise ValidationError("that is not a time", token=token)
    return datetime.combine(when, datetime.min.time()).replace(hour=hour, minute=minute), True


def render(itinerary: Itinerary) -> str:
    """Write an itinerary back out in the form it was typed in.

    A round trip through this and back is the only check that the reader and
    the writer agree, and it is worth having because the writer is what a
    stored itinerary is shown as.
    """
    if not itinerary.elements:
        return ""
    parts = [itinerary.elements[0].origin]
    for element in itinerary.elements:
        if isinstance(element, SurfaceSector):
            parts.extend([SURFACE, element.destination])
            continue
        month = next(name for name, number in MONTHS.items() if number == element.departure.month)
        parts.extend(
            [
                f"{element.marketing_carrier}{element.flight_number}",
                element.booking_class,
                f"{element.departure.day:02d}{month}",
                element.destination,
            ]
        )
    return " ".join(parts)
