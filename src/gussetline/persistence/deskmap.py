"""Mapping the desk's own objects, which are the ones people have edited.

Split from the tariff mappers because the direction of trust is different.
A fare row is written once by a loader and read a thousand times; a ticket, a
watch and a recommendation are written by a loader, edited by an agent through
the API, edited again by the night run, and read by a report that has to give
the same answer next year. Everything here has to survive a round trip through
somebody else's hands.

Three things about the shapes that are not obvious from the columns.

The ticket row carries the market and the departure even though the coupons
already say it. That is denormalised for one query, the one that finds the
tickets in a market, and it is derived on the way in rather than taken from a
caller, because a row where the market disagrees with its own coupons is
unfindable afterwards.

The watch row has no history on it. Observations live in their own table and a
watch reads them back by series key, so a Watch built from a row starts with
an empty history and the caller fills it. That is deliberate: the night run
wants the last few readings and the coverage report wants all of them, and a
mapper that guessed which would be wrong for one of them.

And the recommendation row carries no link to the observation it came from,
only its fingerprint. The observation is a reading of a market at a moment and
it rolls off; the recommendation is what the desk told a customer and it does
not. A foreign key would take the second down with the first.
"""

from __future__ import annotations

from datetime import date

from gussetline.desk.recommend import Recommendation, State
from gussetline.desk.ticket import Coupon, CouponStatus, Ticket
from gussetline.desk.watch import Observation, Retired, Watch
from gussetline.errors import ValidationError
from gussetline.flights import Cabin
from gussetline.money import Money
from gussetline.persistence.models import (
    CouponRow,
    ObservationRow,
    RecommendationRow,
    TicketRow,
    WatchRow,
)
from gussetline.rules.changes import Residual

# What separates the fare bases stored on a recommendation.
BASES = "/"


def coupon_to_row(coupon: Coupon) -> CouponRow:
    return CouponRow(
        position=coupon.position,
        carrier=coupon.carrier,
        flight_number=coupon.flight_number,
        origin=coupon.origin,
        destination=coupon.destination,
        departure=coupon.departure,
        booking_class=coupon.booking_class,
        fare_basis=coupon.fare_basis,
        status=coupon.status.value,
        flown=coupon.status is CouponStatus.USED,
    )


def row_to_coupon(row: CouponRow) -> Coupon:
    return Coupon(
        position=row.position,
        carrier=row.carrier,
        flight_number=row.flight_number,
        origin=row.origin,
        destination=row.destination,
        departure=row.departure,
        booking_class=row.booking_class,
        fare_basis=row.fare_basis,
        status=CouponStatus(row.status),
    )


def ticket_to_row(ticket: Ticket) -> TicketRow:
    """A ticket and its coupons as one row and its children.

    The market is taken off the coupons rather than from anywhere else. A row
    whose origin disagrees with its own first coupon cannot be found by either
    of the two queries that look for it.
    """
    row = TicketRow(
        number=ticket.number,
        record_locator=ticket.record_locator,
        passenger_name=ticket.passenger_name,
        origin=ticket.origin,
        destination=ticket.destination,
        departure=ticket.departure,
        issued_on=ticket.issued_on,
        validating_carrier=ticket.validating_carrier,
        fare_calculation=ticket.fare_calculation[:512],
        base_amount=ticket.base_fare.amount,
        base_currency=ticket.base_fare.currency,
        total_amount=ticket.total_paid.amount,
        country_of_sale=ticket.country_of_sale,
        reissued_from=ticket.reissued_from,
    )
    row.coupons = [coupon_to_row(c) for c in ticket.coupons]
    return row


def row_to_ticket(row: TicketRow) -> Ticket:
    """The ticket back out.

    Both money fields read the base currency, because the row keeps only one:
    a ticket priced in one currency and paid in another is a thing, and it is
    not a thing this desk sells.
    """
    return Ticket(
        number=row.number,
        record_locator=row.record_locator,
        passenger_name=row.passenger_name,
        coupons=tuple(row_to_coupon(c) for c in row.coupons),
        issued_on=row.issued_on,
        validating_carrier=row.validating_carrier,
        base_fare=Money(row.base_amount, row.base_currency),
        total_paid=Money(row.total_amount, row.base_currency),
        fare_calculation=row.fare_calculation,
        country_of_sale=row.country_of_sale,
        reissued_from=row.reissued_from,
    )


def watch_to_row(watch: Watch, ticket_id: int | None = None) -> WatchRow:
    row = WatchRow(
        series_key=watch.series_key,
        origin=watch.origin,
        destination=watch.destination,
        departure=watch.departure,
        cabin=watch.cabin.value,
        threshold_amount=watch.threshold.amount,
        threshold_currency=watch.threshold.currency,
        active=watch.active,
        retired_reason=watch.retired_reason.value if watch.retired_reason else "",
        last_checked=watch.last_checked,
        checks=watch.checks,
    )
    if ticket_id is not None:
        row.ticket_id = ticket_id
    return row


def row_to_watch(row: WatchRow, history: list[Observation] | None = None) -> Watch:
    """A watch, with whatever history the caller decided it wanted.

    The history is a parameter and not a lookup. The night run wants the last
    few readings and the coverage report wants all of them, and a mapper that
    picked one would be wrong for the other every time.
    """
    return Watch(
        ticket=row_to_ticket(row.ticket),
        origin=row.origin,
        destination=row.destination,
        departure=row.departure,
        threshold=Money(row.threshold_amount, row.threshold_currency),
        cabin=Cabin(row.cabin),
        active=row.active,
        retired_reason=_retired(row.retired_reason),
        checks=row.checks,
        last_checked=row.last_checked,
        history=list(history or []),
    )


def _retired(raw: str) -> Retired | None:
    if not raw:
        return None
    try:
        return Retired(raw)
    except ValueError:
        # A reason written by an older version, or typed at a console. The
        # watch stays retired: a reason we cannot read is not a reason to
        # start spending shopping requests on it again.
        return Retired.BY_HAND


def observation_row_to_reading(row: ObservationRow) -> Observation:
    """One stored offer as the reading a watch keeps.

    The total and not the base fare, because what a watch compares is what the
    customer would pay, and a drop in the fare that a rise in tax swallows is
    not a drop the desk can quote.
    """
    return Observation(
        seen_on=row.last_seen.date(),
        amount=Money(row.total_amount, row.base_currency),
        fingerprint=row.fingerprint,
        source=row.source,
    )


def recommendation_to_row(made: Recommendation, watch_id: int | None = None) -> RecommendationRow:
    row = RecommendationRow(
        raised_on=made.raised_on,
        state=made.state.value,
        paid_amount=made.paid.amount,
        new_fare_amount=made.new_fare.amount,
        penalty_amount=made.penalty.amount,
        benefit_amount=made.benefit.amount,
        currency=made.currency,
        residual=made.residual.value,
        fare_bases=BASES.join(made.fare_bases)[:128],
        observation_fingerprint=made.fingerprint,
        decided_on=made.decided_on,
        decided_by=made.decided_by[:32],
        note=made.note[:256],
    )
    if watch_id is not None:
        row.watch_id = watch_id
    return row


def row_to_recommendation(
    row: RecommendationRow, ticket_number: str, series_key: str
) -> Recommendation:
    """The recommendation back out.

    The ticket number and the series key come from the caller because the row
    reaches them only through its watch, and loading that relationship for
    every row turns a savings report over a year into forty thousand queries.
    """
    currency = row.currency
    return Recommendation(
        ticket_number=ticket_number,
        series_key=series_key,
        raised_on=row.raised_on,
        paid=Money(row.paid_amount, currency),
        new_fare=Money(row.new_fare_amount, currency),
        penalty=Money(row.penalty_amount, currency),
        benefit=Money(row.benefit_amount, currency),
        residual=Residual(row.residual),
        fare_bases=tuple(part for part in row.fare_bases.split(BASES) if part),
        fingerprint=row.observation_fingerprint,
        state=State(row.state),
        decided_on=row.decided_on,
        decided_by=row.decided_by,
        note=row.note,
    )


def apply_recommendation(row: RecommendationRow, made: Recommendation) -> RecommendationRow:
    """Move a stored recommendation to the state the domain object is in.

    Only the fields that can move. Everything else on a recommendation is a
    copy of what the desk said and must read the same in a year, so writing it
    again here would be the one way it could change.
    """
    row.state = made.state.value
    row.decided_on = made.decided_on
    row.decided_by = made.decided_by[:32]
    row.note = made.note[:256]
    return row


def series_key_for_row(row: WatchRow) -> str:
    """What a watch row's series key should be, worked out from its own fields."""
    return f"{row.origin}-{row.destination}-{row.departure.isoformat()}-{row.cabin}"


def check_series_key(row: WatchRow) -> None:
    """Refuse a row whose key disagrees with the fields it was built from.

    Nothing edits the key on purpose, but the API takes one from a caller and
    a key that does not match its own market makes the watch invisible to
    every observation the collectors store for it.
    """
    wanted = series_key_for_row(row)
    if row.series_key != wanted:
        raise ValidationError(
            "the series key does not match the watch",
            stored=row.series_key,
            wanted=wanted,
        )


def latest_check(row: WatchRow) -> date | None:
    return row.last_checked.date() if row.last_checked is not None else None
