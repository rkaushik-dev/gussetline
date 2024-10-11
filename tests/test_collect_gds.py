from __future__ import annotations

from datetime import date, datetime
from decimal import Decimal

import pytest

from gussetline.collect.fixedwidth import write_record
from gussetline.collect.gds import (
    COMPONENT,
    END,
    HEADER,
    OFFER,
    SECTOR,
    TAX,
    YearRoll,
    parse,
    problems,
)
from gussetline.errors import MalformedPayload
from gussetline.flights import Cabin
from gussetline.money import Money

SEEN = datetime(2025, 2, 14, 9, 0)


def header(origin="FRA", destination="JFK", stamp=date(2025, 2, 14), currency="EUR"):
    return write_record(
        HEADER,
        {
            "source": "GDSFARE",
            "stamp": stamp,
            "origin": origin,
            "destination": destination,
            "currency": currency,
        },
    )


def offer_line(reference="000001", seats=4, bags=1):
    return write_record(OFFER, {"reference": reference, "seats": seats, "bags": bags})


def sector(
    carrier="LH",
    flight="0400",
    origin="FRA",
    destination="JFK",
    departure=(3, 4, 10, 25),
    arrival=(3, 4, 13, 5),
    day_change=0,
    booking_class="K",
    cabin="Y",
    operating=None,
):
    return write_record(
        SECTOR,
        {
            "carrier": carrier,
            "flight": flight,
            "origin": origin,
            "destination": destination,
            "departure": departure,
            "arrival": arrival,
            "day_change": day_change,
            "booking_class": booking_class,
            "cabin": cabin,
            "operating": operating,
        },
    )


def component(basis="KLWAP7RT", amount="420.00", currency="EUR", indicator="AT"):
    return write_record(
        COMPONENT,
        {
            "basis": basis,
            "currency": currency,
            "amount": Decimal(amount),
            "indicator": indicator,
        },
    )


def tax(code="DE", amount="70.83", currency="EUR"):
    return write_record(TAX, {"code": code, "currency": currency, "amount": Decimal(amount)})


def end(amount="490.83", currency="EUR"):
    return write_record(END, {"currency": currency, "amount": Decimal(amount)})


def payload(*lines: str) -> str:
    return "\n".join(lines) + "\n"


SIMPLE = payload(
    header(),
    offer_line(),
    sector(),
    sector(
        carrier="LH",
        flight="0401",
        origin="JFK",
        destination="FRA",
        departure=(3, 18, 18, 30),
        arrival=(3, 19, 8, 5),
        day_change=1,
    ),
    component(),
    component(basis="KLWAP7RT", amount="420.00"),
    tax(),
    tax(code="YQ", amount="180.00"),
    end(amount="1090.83"),
)


def test_a_year_roll_starts_where_it_is_told():
    roll = YearRoll(2025)
    assert roll.resolve(3, 4) == date(2025, 3, 4)
    assert roll.resolve(3, 18) == date(2025, 3, 18)


def test_a_year_rolls_when_the_month_goes_backwards():
    roll = YearRoll(2025)
    assert roll.resolve(12, 28) == date(2025, 12, 28)
    assert roll.resolve(1, 4) == date(2026, 1, 4)


def test_a_year_never_rolls_back():
    roll = YearRoll(2025)
    roll.resolve(1, 4)
    assert roll.resolve(3, 4) == date(2025, 3, 4)


def test_a_payload_from_another_system_is_refused():
    other = payload(
        write_record(
            HEADER,
            {
                "source": "NDCFARE",
                "stamp": date(2025, 2, 14),
                "origin": "FRA",
                "destination": "JFK",
                "currency": "EUR",
            },
        )
    )
    with pytest.raises(MalformedPayload) as caught:
        parse(other, SEEN)
    assert caught.value.details["source"] == "NDCFARE"


def test_a_payload_with_no_header_is_refused():
    with pytest.raises(MalformedPayload):
        parse(payload(offer_line(), sector()), SEEN)


def test_a_simple_response_gives_one_offer():
    offers = parse(SIMPLE, SEEN)
    assert len(offers) == 1
    assert offers[0].source == "gds"
    assert offers[0].origin == "FRA"
    assert offers[0].destination == "FRA"


def test_the_sectors_come_back_as_a_joined_itinerary():
    offer = parse(SIMPLE, SEEN)[0]
    assert len(offer.itinerary.segments) == 2
    assert offer.itinerary.segments[0].designator == "LH400"
    assert offer.itinerary.segments[0].departure == datetime(2025, 3, 4, 10, 25)
    assert offer.itinerary.segments[1].day_change == 1


def test_the_fare_components_add_into_the_base():
    offer = parse(SIMPLE, SEEN)[0]
    assert offer.breakdown.base == Money.of("840.00", "EUR")
    assert offer.fare_bases == ("KLWAP7RT", "KLWAP7RT")


def test_the_taxes_come_across_and_yq_is_marked_as_carrier_money():
    offer = parse(SIMPLE, SEEN)[0]
    assert offer.breakdown.tax_total == Money.of("70.83", "EUR")
    assert offer.breakdown.surcharge_total == Money.of("180.00", "EUR")


def test_the_gateways_own_total_is_kept_beside_ours():
    offer = parse(SIMPLE, SEEN)[0]
    assert offer.breakdown.quoted_total == Money.of("1090.83", "EUR")
    assert offer.breakdown.adds_up


def test_a_total_that_disagrees_is_kept_and_not_corrected():
    other = SIMPLE.replace(end(amount="1090.83"), end(amount="1200.00"))
    offer = parse(other, SEEN)[0]
    assert offer.breakdown.quoted_total == Money.of("1200.00", "EUR")
    assert not offer.breakdown.adds_up


def test_the_seat_and_bag_counts_come_off_the_offer_record():
    offer = parse(SIMPLE, SEEN)[0]
    assert offer.seats == 4
    assert offer.baggage.pieces == 1


def test_an_offer_record_with_no_counts_leaves_them_unstated():
    thin = payload(
        header(),
        write_record(OFFER, {"reference": "000001"}),
        sector(),
        component(),
        end(amount="490.83"),
    )
    offer = parse(thin, SEEN)[0]
    assert offer.seats is None
    assert not offer.baggage.is_stated


def test_two_offer_records_give_two_offers():
    two = payload(
        header(),
        offer_line("000001"),
        sector(),
        component(),
        end(amount="490.83"),
        offer_line("000002"),
        sector(carrier="BA", flight="0175", booking_class="Q"),
        component(basis="QLWAP14RT", amount="380.00"),
        end(amount="450.83"),
    )
    offers = parse(two, SEEN)
    assert len(offers) == 2
    assert offers[1].carriers == ("BA",)
    assert offers[1].fare_bases == ("QLWAP14RT",)


def test_a_codeshare_keeps_both_carriers():
    shared = payload(
        header(), offer_line(), sector(operating="UA"), component(), end(amount="490.83")
    )
    segment = parse(shared, SEEN)[0].itinerary.segments[0]
    assert segment.marketing_carrier == "LH"
    assert segment.operated_by == "UA"
    assert segment.is_codeshare


def test_the_cabin_is_read_off_the_sector():
    business = payload(
        header(),
        offer_line(),
        sector(cabin="C", booking_class="J"),
        component(basis="JRT", amount="2900.00"),
        end(amount="2970.83"),
    )
    assert parse(business, SEEN)[0].cabin is Cabin.BUSINESS


def test_a_cabin_the_tariff_does_not_price_falls_back_to_economy():
    odd = payload(header(), offer_line(), sector(cabin="Z"), component(), end(amount="490.83"))
    assert parse(odd, SEEN)[0].cabin is Cabin.ECONOMY


def test_a_broken_offer_is_dropped_and_the_others_survive():
    mixed = payload(
        header(),
        offer_line("000001"),
        sector(origin="FRA", destination="JFK"),
        sector(origin="BOS", destination="FRA"),
        component(),
        end(amount="490.83"),
        offer_line("000002"),
        sector(),
        component(),
        end(amount="490.83"),
    )
    offers = parse(mixed, SEEN)
    assert len(offers) == 1


def test_an_offer_with_no_fare_component_is_dropped():
    thin = payload(header(), offer_line(), sector(), end(amount="0.00"))
    assert parse(thin, SEEN) == []


def test_records_before_the_first_offer_record_are_ignored():
    stray = payload(header(), sector(), offer_line(), sector(), component(), end())
    offers = parse(stray, SEEN)
    assert len(offers) == 1
    assert len(offers[0].itinerary.segments) == 1


def test_an_unknown_record_is_reported_and_does_not_stop_the_read():
    with_junk = payload(header(), "Z9nonsense", offer_line(), sector(), component(), end())
    assert len(parse(with_junk, SEEN)) == 1
    assert any("unknown record tag" in p for p in problems(with_junk))


def test_a_clean_payload_reports_no_problems():
    assert problems(SIMPLE) == []


def test_a_flight_number_loses_its_padding():
    assert parse(SIMPLE, SEEN)[0].itinerary.segments[0].flight_number == "400"


def test_the_observation_time_is_ours_and_not_the_payloads():
    assert parse(SIMPLE, SEEN)[0].observed_at == SEEN
