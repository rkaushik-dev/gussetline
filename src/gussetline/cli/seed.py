"""Enough of a desk to run every other command against, on a laptop.

Somebody new clones this, builds a schema, and every command they try answers
with nothing at all, because a desk with no tariff and no tickets is a correct
empty desk. That is a bad first ten minutes and it is entirely avoidable.

So this writes one carrier's fares, the rules they point at, the combinability
that lets a round trip be sold, two tickets and the watches on them, and a week
of readings that end in a drop worth telling somebody about. The night run then
raises a recommendation and every report has something in it.

Everything it writes is obviously fake. The carrier is a real code because the
tariff refuses one that is not, but the amounts are round numbers, the passenger
is a placeholder, and the ticket numbers are sequential from one. Nobody is
going to mistake this for production data, which is the point: seed data that
looks real ends up quoted in a meeting.

It refuses to run against a desk that already holds anything. A seed is for an
empty database and merging it into a real one puts invented fares next to filed
ones with nothing to tell them apart afterwards.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal

import click

from gussetline import logs
from gussetline.cli.desk import Desk, pass_desk
from gussetline.combinability import Combinability
from gussetline.desk.ticket import Coupon, Ticket
from gussetline.geography import city_of
from gussetline.indicators import GlobalIndicator
from gussetline.money import Money
from gussetline.persistence.deskmap import ticket_to_row
from gussetline.persistence.models import ObservationRow, WatchRow
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.rules.base import RuleSet
from gussetline.rules.changes import RepricingBasis, Residual, VoluntaryChange
from gussetline.rules.penalties import Action, Penalty
from gussetline.rules.stay import MinimumStay
from gussetline.tariff import Fare, FareType, OwRt

# What the invented market did over the week. Steady, then a drop that holds,
# which is the one shape every command downstream has something to say about.
PRICES = ("840.00", "840.00", "690.00", "690.00", "690.00")

CARRIER = "LH"
MARKETS = (("FRA", "JFK"), ("FRA", "ORD"))

# What a seeded reading claims to have come from. Its own name, so forcing a
# second seed can find the first one's readings without guessing at series.
SOURCE = "seed"

# A restricted half, a flexible half, and a one way, so a seeded desk can
# price all three shapes an agent actually types. Without the one way, the
# first entry anybody tries comes back saying a round trip half cannot stand
# on its own, which reads as a broken desk rather than a fare it has not got.
FILED = (
    ("KLWAP7RT", "420.00", "0100", OwRt.ROUND_TRIP_HALF),
    ("KFLEXRT", "620.00", "0200", OwRt.ROUND_TRIP_HALF),
    ("KOW", "560.00", "0100", OwRt.ONE_WAY_ONLY),
)


@click.command("seed")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--force", is_flag=True, help="Write it even though the desk holds something.")
@pass_desk
def seed(desk: Desk, today: datetime | None, force: bool) -> None:
    """Fill an empty desk with enough to run everything else against."""
    when = today.date() if today else date.today()
    departure = when + timedelta(days=45)

    with desk.database.unit() as work:
        held = _what_is_here(work)
        if held and not force:
            raise click.ClickException(f"this desk already holds {held}, so nothing was written")

        if force:
            _clear_previous(work)

        fares = _fares(work, when)
        _rules(work)
        _tickets_and_watches(work, when, departure)
        readings = _readings(work, when, departure)
        work.commit()

    desk.say(f"wrote {fares} fares, {len(MARKETS)} tickets, {readings} readings")
    desk.say("")
    desk.say("try:")
    desk.say(f"  gussetline price 'FRA LH400 K {departure:%d%b} JFK' --on {when}")
    desk.say(f"  gussetline night --on {when} --dry-run")
    desk.say(f"  gussetline report queue --on {when}")
    logs.event("seed.written", on=when, fares=fares, readings=readings)


def _clear_previous(work: UnitOfWork) -> None:
    """Take out what an earlier seed wrote, so forcing it can write again.

    Only what a seed writes, found by the numbers a seed invents. Anything
    else on the desk is somebody's real data and forcing a seed is not a
    licence to take it out.
    """
    from sqlalchemy import select

    from gussetline.persistence.models import TicketRow

    numbers = [f"220-000000000{index}" for index in range(1, len(MARKETS) + 1)]
    for row in work.session.scalars(select(TicketRow).where(TicketRow.number.in_(numbers))).all():
        work.session.delete(row)

    # The readings go too, found by their source rather than by their series.
    # A seeded reading is the only thing on the desk that claims to have come
    # from a supplier without anybody having asked one, so the source is the
    # honest way to find them again.
    for reading in work.session.scalars(
        select(ObservationRow).where(ObservationRow.source == SOURCE)
    ).all():
        work.session.delete(reading)
    work.session.flush()


def _what_is_here(work: UnitOfWork) -> str:
    """What the desk already holds, in the words the refusal uses."""
    counts = {
        "fares": work.tariff.count_fares(),
        "observations": work.observations.count(),
        "runs": work.runs.count(),
        "rules": work.rules.count(),
    }
    return ", ".join(f"{count} {name}" for name, count in counts.items() if count)


def _fares(work: UnitOfWork, when: date) -> int:
    """One published fare per market, plus a dearer flexible one to choose from."""
    row = work.tariff.open_load(
        carrier=CARRIER,
        filename="seed.dat",
        digest="0" * 32,
        loaded_at=datetime.combine(when, datetime.min.time()),
        effective=when,
    )
    made: list[Fare] = []
    for origin, destination in MARKETS:
        for basis, amount, rule, owrt in FILED:
            for one, two in ((origin, destination), (destination, origin)):
                made.append(_fare(one, two, basis, amount, rule, owrt, when))
    work.tariff.add_fares(row, made)

    for filed in made:
        work.combinability.save(
            Combinability(
                carrier=CARRIER,
                fare_basis=filed.fare_basis,
                round_trip=True,
                circle_trip=True,
                open_jaw=True,
            )
        )
    return len(made)


def _fare(
    origin: str,
    destination: str,
    basis: str,
    amount: str,
    rule: str,
    owrt: OwRt,
    when: date,
) -> Fare:
    return Fare(
        carrier=CARRIER,
        origin=origin,
        destination=destination,
        fare_basis=basis,
        amount=Money.of(amount, "EUR"),
        owrt=owrt,
        global_indicator=GlobalIndicator.AT,
        rule_number=rule,
        routing_number="0000",
        tariff_number="1",
        fare_type=FareType.PUBLISHED,
        effective=when - timedelta(days=30),
        discontinue=when + timedelta(days=300),
        first_travel=when - timedelta(days=30),
        last_travel=when + timedelta(days=300),
    )


def _rules(work: UnitOfWork) -> None:
    """One restricted rule and one flexible one, so a reprice has a choice."""
    work.rules.save(
        RuleSet(
            carrier=CARRIER,
            number="0100",
            tariff="1",
            provisions=(
                MinimumStay(weekday="SUN"),
                Penalty(action=Action.CHANGE, before_departure=Money.of("50.00", "EUR")),
                VoluntaryChange(
                    permitted=True,
                    basis=RepricingBasis.CURRENT,
                    residual=Residual.REFUNDABLE,
                ),
            ),
        )
    )
    work.rules.save(
        RuleSet(
            carrier=CARRIER,
            number="0200",
            tariff="1",
            provisions=(
                Penalty(action=Action.CHANGE, before_departure=Money.zero("EUR")),
                VoluntaryChange(
                    permitted=True,
                    basis=RepricingBasis.CURRENT,
                    residual=Residual.REFUNDABLE,
                ),
            ),
        )
    )


def _tickets_and_watches(work: UnitOfWork, when: date, departure: date) -> None:
    for index, (origin, destination) in enumerate(MARKETS, start=1):
        ticket = Ticket(
            number=f"220-000000000{index}",
            record_locator=f"SEED0{index}",
            passenger_name="TEST/PASSENGER",
            coupons=(
                Coupon(
                    position=0,
                    carrier=CARRIER,
                    flight_number="400",
                    origin=origin,
                    destination=destination,
                    departure=datetime.combine(departure, datetime.min.time()).replace(hour=10),
                    booking_class="K",
                    fare_basis="KLWAP7RT",
                ),
            ),
            issued_on=when - timedelta(days=60),
            validating_carrier=CARRIER,
            base_fare=Money.of("840.00", "EUR"),
            total_paid=Money.of("1090.83", "EUR"),
        )
        row = ticket_to_row(ticket)
        work.session.add(row)
        work.session.flush()
        work.session.add(
            WatchRow(
                ticket_id=row.id,
                series_key=_series(origin, destination, departure),
                origin=city_of(origin),
                destination=city_of(destination),
                departure=departure,
                threshold_amount=Decimal("75.00"),
                threshold_currency="EUR",
            )
        )


def _readings(work: UnitOfWork, when: date, departure: date) -> int:
    """A week of what the collectors would have seen, ending in a drop.

    One row per distinct price and not one per morning, because that is what
    a real run produces: the observation table is keyed on content, so a
    market that sat at the same number for two mornings is one row with a
    sighting count of two. Seeding it the other way writes rows no run could
    write, and the first thing anybody does with a seeded desk is compare it
    to a real one.
    """
    written = 0
    for origin, destination in MARKETS:
        series = _series(origin, destination, departure)
        for amount, first, last, times in _mornings(when):
            work.session.add(
                ObservationRow(
                    series_key=series,
                    source=SOURCE,
                    fingerprint=f"{series}-{amount}".ljust(32, "0")[:32],
                    origin=origin,
                    destination=destination,
                    departure=departure,
                    cabin="Y",
                    fare_bases="KLWAP7RT",
                    base_amount=Decimal(amount),
                    base_currency="EUR",
                    tax_amount=Decimal("0.00"),
                    surcharge_amount=Decimal("0.00"),
                    total_amount=Decimal(amount),
                    first_seen=first,
                    last_seen=last,
                    sightings=times,
                )
            )
            written += 1
    return written


def _mornings(when: date) -> list[tuple[str, datetime, datetime, int]]:
    """The week's prices, collapsed the way a run of collections would."""
    seen: dict[str, list[datetime]] = {}
    for offset, amount in enumerate(PRICES):
        morning = datetime.combine(when - timedelta(days=len(PRICES) - offset), datetime.min.time())
        seen.setdefault(amount, []).append(morning)
    return [(amount, min(days), max(days), len(days)) for amount, days in seen.items()]


def _series(origin: str, destination: str, departure: date) -> str:
    return f"{city_of(origin)}-{city_of(destination)}-{departure.isoformat()}-Y"
