"""The key that groups observations into one watchable price series.

A series is a market, a departure and a cabin: every price anybody has seen
for New York on the fourth of August in economy, whatever supplier said it
and whatever airport they said it out of. The watch reads its history by this
key and the collectors write theirs under it, so the two only ever meet if
both spell it the same way.

They did not. This format was written out by hand in six places across five
modules, and four of them folded airports to cities while two did not. A
ticket on Newark then wrote its watch under EWR while every observation for
the same flight landed under NYC, and nothing at all went wrong: no error, no
empty result anybody looked at, two sets of rows sitting in one table each
looking perfectly correct. The night run simply found no history for the
watch and raised nothing, every night, for as long as nobody checked.

That is the failure this module exists to make impossible. One function
builds the key, everything else calls it, and the city fold happens once
where it cannot be forgotten. The offer module's own comment had already
noticed it was writing the third copy.

The format is positional and readable on purpose, because it turns up in log
lines and in the database where somebody is going to have to eyeball it:

    FRA-NYC-2025-08-04-Y

Origin city, destination city, departure, cabin. The departure carries its
own dashes, which is why reading one back counts from both ends rather than
splitting on the separator and hoping.
"""

from __future__ import annotations

from datetime import date, datetime

from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.geography import city_of

SEPARATOR = "-"

# Origin, destination, cabin, and the three the date contributes.
FIELDS = 6


def key(
    origin: str,
    destination: str,
    departure: date | datetime,
    cabin: Cabin | str,
) -> str:
    """The key for one market, one departure and one cabin.

    Takes airports or cities and returns cities either way, so a caller that
    happens to hold a flight's own airport code cannot open a second series
    for a market that already has one.
    """
    return SEPARATOR.join(parts(origin, destination, departure, cabin))


def parts(
    origin: str,
    destination: str,
    departure: date | datetime,
    cabin: Cabin | str,
) -> tuple[str, str, str, str]:
    """The four fields, folded and normalised, before they are joined.

    Separate from the join because the offer module wants them as a tuple to
    group on, and grouping on a string it then has to take apart again is how
    the format leaks back out into the callers.
    """
    when = departure.date() if isinstance(departure, datetime) else departure
    return (
        city_of(origin),
        city_of(destination),
        when.isoformat(),
        cabin.value if isinstance(cabin, Cabin) else str(cabin).upper(),
    )


def read(made: str) -> tuple[str, str, date, str]:
    """A key taken back apart, for the places that hold one and want a date.

    Counted from both ends. The departure contributes three fields of its own,
    so splitting on the separator and taking the third piece gets the year.
    """
    pieces = made.split(SEPARATOR)
    if len(pieces) != FIELDS:
        raise ValidationError("not a series key", key=made)

    origin, destination = pieces[0], pieces[1]
    cabin = pieces[-1]
    try:
        departure = date.fromisoformat(SEPARATOR.join(pieces[2:5]))
    except ValueError as exc:
        raise ValidationError("not a series key", key=made) from exc
    return (origin, destination, departure, cabin)
