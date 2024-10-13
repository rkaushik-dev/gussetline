from __future__ import annotations

import json
from datetime import datetime
from decimal import Decimal

import pytest

from gussetline.collect.ndc import (
    amount_of,
    as_list,
    money_of,
    parse,
    pick,
    snake,
    when,
)
from gussetline.errors import MalformedPayload
from gussetline.money import Money

SEEN = datetime(2025, 5, 15, 9, 0)


def a_segment(**over):
    node = {
        "marketingCarrier": "LH",
        "flightNumber": "400",
        "origin": "FRA",
        "destination": "JFK",
        "departureTime": "2025-06-04T10:25:00+02:00",
        "arrivalTime": "2025-06-04T13:05:00-04:00",
        "bookingClass": "K",
        "cabin": "Y",
    }
    node.update(over)
    return node


def an_offer(**over):
    node = {
        "offerId": "abc",
        "priceDetail": {
            "baseAmount": {"amount": "420.00", "currencyCode": "EUR"},
            "taxes": [
                {"code": "DE", "amount": "70.83", "currencyCode": "EUR"},
                {"code": "YQ", "amount": "180.00", "currencyCode": "EUR"},
            ],
            "totalAmount": {"amount": "670.83", "currencyCode": "EUR"},
        },
        "paxJourneys": [{"segments": [a_segment()]}],
        "fareBasis": ["KLWAP7RT"],
        "seatsRemaining": 4,
        "baggageAllowance": {"pieces": 1},
        "refundable": False,
        "validatingCarrier": "LH",
    }
    node.update(over)
    return node


def document(*offers):
    return json.dumps({"offerResponse": {"offers": list(offers) or [an_offer()]}})


def test_snake_case_conversion():
    assert snake("marketingCarrier") == "marketing_carrier"
    assert snake("code") == "code"
    assert snake("HTTPStatus") == "h_t_t_p_status"


def test_a_key_is_found_under_either_spelling():
    assert pick({"marketingCarrier": "LH"}, "marketingCarrier") == "LH"
    assert pick({"marketing_carrier": "LH"}, "marketingCarrier") == "LH"
    assert pick({"carrier": "LH"}, "marketingCarrier", "carrier") == "LH"


def test_a_null_is_absent_and_takes_the_default():
    assert pick({"cabin": None}, "cabin", default="Y") == "Y"
    assert pick({}, "cabin", default="Y") == "Y"
    assert pick("not a dict", "cabin", default="Y") == "Y"


def test_one_of_something_reads_as_a_list_of_one():
    assert as_list({"a": 1}) == [{"a": 1}]
    assert as_list([{"a": 1}, {"b": 2}]) == [{"a": 1}, {"b": 2}]
    assert as_list(None) == []


def test_an_amount_is_read_from_its_text_and_never_through_a_float():
    assert amount_of("420.00") == Decimal("420.00")
    assert amount_of(420) == Decimal(420)
    assert amount_of(Decimal("420.00")) == Decimal("420.00")


def test_a_json_number_survives_the_round_trip_exactly():
    # 0.1 as a float is not a tenth, and reading it through repr keeps the
    # shortest text that round trips rather than the binary expansion.
    assert amount_of(json.loads("420.10")) == Decimal("420.1")


def test_a_boolean_or_a_null_is_not_an_amount():
    with pytest.raises(MalformedPayload):
        amount_of(True)
    with pytest.raises(MalformedPayload):
        amount_of(None)
    with pytest.raises(MalformedPayload):
        amount_of("four hundred")


def test_money_needs_both_an_amount_and_a_code():
    assert money_of({"amount": "420.00", "currencyCode": "EUR"}) == Money.of("420.00", "EUR")
    assert money_of({"amount": "420.00"}) is None
    assert money_of({"currencyCode": "EUR"}) is None
    assert money_of(None) is None
    assert money_of("nonsense") is None


def test_money_takes_a_fallback_currency_when_the_node_has_none():
    assert money_of({"amount": "70.83"}, "EUR") == Money.of("70.83", "EUR")


def test_a_timestamp_drops_its_offset_because_sectors_are_wall_clock():
    assert when("2025-06-04T10:25:00+02:00") == datetime(2025, 6, 4, 10, 25)
    assert when("2025-06-04T10:25:00Z") == datetime(2025, 6, 4, 10, 25)
    assert when("2025-06-04T10:25:00") == datetime(2025, 6, 4, 10, 25)


def test_a_timestamp_we_cannot_read_is_absent_rather_than_wrong():
    assert when("the fourth of June") is None
    assert when("") is None
    assert when(None) is None


def test_a_payload_that_is_not_json_is_refused():
    with pytest.raises(MalformedPayload):
        parse("<html>gateway error</html>", SEEN)


def test_a_payload_with_no_offers_is_refused():
    with pytest.raises(MalformedPayload):
        parse(json.dumps({"offerResponse": {"offers": []}}), SEEN)


def test_a_simple_offer_comes_across_whole():
    reading = parse(document(), SEEN)
    assert reading.clean
    assert len(reading) == 1
    offer = reading.offers[0]
    assert offer.source == "ndc"
    assert offer.breakdown.base == Money.of("420.00", "EUR")
    assert offer.fare_bases == ("KLWAP7RT",)
    assert offer.seats == 4
    assert offer.baggage.pieces == 1
    assert offer.refundable is False


def test_the_taxes_split_into_government_and_carrier_money():
    offer = parse(document(), SEEN).offers[0]
    assert offer.breakdown.tax_total == Money.of("70.83", "EUR")
    assert offer.breakdown.surcharge_total == Money.of("180.00", "EUR")


def test_the_segment_times_are_wall_clock_at_each_end():
    segment = parse(document(), SEEN).offers[0].itinerary.segments[0]
    assert segment.departure == datetime(2025, 6, 4, 10, 25)
    assert segment.arrival == datetime(2025, 6, 4, 13, 5)
    assert segment.day_change == 0


def test_a_journey_sent_as_one_object_rather_than_a_list_still_reads():
    offer = an_offer(paxJourneys={"segments": a_segment()})
    reading = parse(document(offer), SEEN)
    assert len(reading) == 1
    assert len(reading.offers[0].itinerary.segments) == 1


def test_the_snake_case_spelling_of_the_whole_document_reads_too():
    node = {
        "offer_response": {
            "offers": [
                {
                    "price_detail": {"base_amount": {"amount": "420.00", "currency_code": "EUR"}},
                    "pax_journeys": [
                        {
                            "segments": [
                                {
                                    "marketing_carrier": "LH",
                                    "flight_number": "400",
                                    "origin": "FRA",
                                    "destination": "JFK",
                                    "departure_time": "2025-06-04T10:25:00",
                                    "arrival_time": "2025-06-04T13:05:00",
                                    "booking_class": "K",
                                }
                            ]
                        }
                    ],
                    "fare_basis": ["KLWAP7RT"],
                }
            ]
        }
    }
    reading = parse(json.dumps(node), SEEN)
    assert len(reading) == 1


def test_an_offer_with_no_base_amount_is_a_problem_and_the_others_survive():
    broken = an_offer(priceDetail={"taxes": []})
    reading = parse(document(broken, an_offer()), SEEN)
    assert len(reading) == 1
    assert "no base amount" in reading.problems[0]


def test_a_segment_missing_a_field_is_a_problem_and_names_the_offer():
    broken = an_offer(paxJourneys=[{"segments": [a_segment(bookingClass=None)]}])
    reading = parse(document(broken), SEEN)
    assert len(reading) == 0
    assert reading.problems == ["offer 0: a segment was missing a field"]


def test_segments_that_do_not_join_up_are_a_problem():
    broken = an_offer(
        paxJourneys=[{"segments": [a_segment(), a_segment(origin="BOS", destination="FRA")]}]
    )
    reading = parse(document(broken), SEEN)
    assert len(reading) == 0
    assert "do not join up" in reading.problems[0]


def test_an_offer_with_no_segments_at_all_is_a_problem():
    reading = parse(document(an_offer(paxJourneys=[])), SEEN)
    assert len(reading) == 0
    assert "could not be assembled" in reading.problems[0]


def test_a_tax_missing_its_code_is_dropped_rather_than_failing_the_offer():
    offer = an_offer(
        priceDetail={
            "baseAmount": {"amount": "420.00", "currencyCode": "EUR"},
            "taxes": [{"amount": "70.83", "currencyCode": "EUR"}],
        }
    )
    reading = parse(document(offer), SEEN)
    assert len(reading) == 1
    assert reading.offers[0].breakdown.taxes == ()


def test_a_reading_reads_back_with_both_counts():
    reading = parse(document(an_offer(priceDetail={}), an_offer()), SEEN)
    assert str(reading) == "1 offers, 1 problems"


def test_a_bag_allowance_sent_as_a_bare_number_still_reads():
    offer = an_offer(baggageAllowance=2)
    assert parse(document(offer), SEEN).offers[0].baggage.pieces == 2


def test_a_document_with_no_envelope_is_read_as_the_envelope():
    reading = parse(json.dumps({"offers": [an_offer()]}), SEEN)
    assert len(reading) == 1


def test_a_western_offset_is_cut_as_well_as_an_eastern_one():
    # A minus offset has the same shape as the hyphens in the date, so a
    # careless cut takes the string apart at the year instead.
    assert when("2025-06-04T13:05:00-04:00") == datetime(2025, 6, 4, 13, 5)
    assert when("2025-06-04T13:05:00+05:30") == datetime(2025, 6, 4, 13, 5)


def test_an_arrival_on_the_next_day_gives_a_day_change():
    node = a_segment(
        origin="FRA",
        destination="NRT",
        departureTime="2025-06-04T17:20:00+02:00",
        arrivalTime="2025-06-05T11:55:00+09:00",
    )
    offer = an_offer(paxJourneys=[{"segments": [node]}])
    segment = parse(document(offer), SEEN).offers[0].itinerary.segments[0]
    assert segment.day_change == 1


def test_an_arrival_before_the_departure_does_not_give_a_negative_day_change():
    node = a_segment(
        departureTime="2025-06-04T17:00:00+02:00",
        arrivalTime="2025-06-04T10:30:00-04:00",
    )
    offer = an_offer(paxJourneys=[{"segments": [node]}])
    segment = parse(document(offer), SEEN).offers[0].itinerary.segments[0]
    assert segment.day_change == 0
