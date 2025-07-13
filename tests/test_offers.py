from __future__ import annotations

from datetime import datetime
from decimal import Decimal

import pytest

from conftest import at, segment
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import (
    Baggage,
    Offer,
    PriceBreakdown,
    cheapest,
    deduplicate,
    spread,
)
from gussetline.taxes import AppliedTax


def tax(code="DE", amount="70.83", currency="EUR", carrier=False):
    return AppliedTax(code, Money.of(amount, currency), "FRA", carrier_imposed=carrier)


def trip(departure="2025-03-04 10:25"):
    return Itinerary([segment("LH", "400", "FRA", "JFK", departure, "2025-03-04 13:05")])


def offer(**over):
    base = {
        "source": "gds",
        "itinerary": trip(),
        "breakdown": PriceBreakdown(Money.of("420.00", "EUR"), (tax(),)),
        "fare_bases": ("KLWAP7RT",),
        "observed_at": datetime(2025, 2, 1, 9, 0),
    }
    base.update(over)
    return Offer(**base)


def test_a_bag_allowance_is_pieces_or_kilos_and_reads_back():
    assert str(Baggage(pieces=2)) == "2PC"
    assert str(Baggage(kilos=23)) == "23KG"
    assert str(Baggage()) == "unstated"
    assert not Baggage().is_stated
    assert Baggage(pieces=0).is_stated


def test_a_negative_allowance_is_refused():
    with pytest.raises(ValidationError):
        Baggage(pieces=-1)
    with pytest.raises(ValidationError):
        Baggage(kilos=-1)


def test_a_breakdown_is_in_one_currency():
    with pytest.raises(ValidationError):
        PriceBreakdown(Money.of("420", "EUR"), (tax(currency="USD"),))
    with pytest.raises(ValidationError):
        PriceBreakdown(Money.of("420", "EUR"), (), Money.of("500", "USD"))


def test_the_tax_total_leaves_the_carrier_surcharge_out():
    breakdown = PriceBreakdown(
        Money.of("420.00", "EUR"),
        (tax(), tax(code="YQ", amount="180.00", carrier=True)),
    )
    assert breakdown.tax_total == Money.of("70.83", "EUR")
    assert breakdown.surcharge_total == Money.of("180.00", "EUR")
    assert breakdown.computed_total == Money.of("670.83", "EUR")


def test_without_a_quoted_total_the_parts_are_the_total():
    breakdown = PriceBreakdown(Money.of("420.00", "EUR"), (tax(),))
    assert breakdown.stated_total == Money.of("490.83", "EUR")
    assert breakdown.adds_up
    assert breakdown.discrepancy.is_zero


def test_a_quoted_total_that_disagrees_is_kept_and_the_gap_is_reported():
    breakdown = PriceBreakdown(Money.of("420.00", "EUR"), (tax(),), Money.of("505.00", "EUR"))
    assert breakdown.stated_total == Money.of("505.00", "EUR")
    assert breakdown.computed_total == Money.of("490.83", "EUR")
    assert breakdown.discrepancy == Money.of("14.17", "EUR")
    assert not breakdown.adds_up


def test_a_quoted_total_below_the_parts_gives_a_negative_gap():
    breakdown = PriceBreakdown(Money.of("420.00", "EUR"), (tax(),), Money.of("480.00", "EUR"))
    assert breakdown.discrepancy.is_negative


def test_tax_codes_come_back_sorted_and_deduplicated():
    breakdown = PriceBreakdown(
        Money.of("420.00", "EUR"), (tax(code="RA"), tax(code="DE"), tax(code="DE"))
    )
    assert breakdown.tax_codes() == ("DE", "RA")


def test_breakdown_reads_back_as_three_parts():
    assert str(PriceBreakdown(Money.of("420.00", "EUR"), (tax(),))) == (
        "420.00 EUR + 70.83 EUR tax + 0 EUR yq"
    )


def test_an_offer_names_its_source_and_normalises_it():
    assert offer(source=" GDS ").source == "gds"
    with pytest.raises(ValidationError):
        offer(source="   ")


def test_an_offer_carries_at_least_one_fare_basis():
    with pytest.raises(ValidationError):
        offer(fare_bases=())


def test_a_negative_seat_count_is_refused():
    with pytest.raises(ValidationError):
        offer(seats=-1)
    assert offer(seats=0).seats == 0


def test_an_offer_forwards_the_ends_and_the_total():
    o = offer()
    assert o.origin == "FRA"
    assert o.destination == "JFK"
    assert o.total == Money.of("490.83", "EUR")
    assert o.currency == "EUR"
    assert o.carriers == ("LH",)
    assert o.is_direct


def test_the_fingerprint_ignores_when_it_was_seen():
    early = offer(observed_at=datetime(2025, 2, 1, 9, 0))
    late = offer(observed_at=datetime(2025, 2, 3, 17, 30))
    assert early.fingerprint() == late.fingerprint()


def test_the_fingerprint_ignores_which_supplier_sent_it():
    assert offer(source="gds").fingerprint() == offer(source="ndc").fingerprint()


def test_the_fingerprint_moves_when_the_price_moves():
    dearer = offer(breakdown=PriceBreakdown(Money.of("520.00", "EUR"), (tax(),)))
    assert offer().fingerprint() != dearer.fingerprint()


def test_the_fingerprint_moves_when_the_flights_move():
    later = offer(itinerary=trip("2025-03-04 16:25"))
    assert offer().fingerprint() != later.fingerprint()


def test_the_fingerprint_moves_when_the_fare_basis_moves():
    assert offer().fingerprint() != offer(fare_bases=("QLWAP14RT",)).fingerprint()


def test_the_series_key_groups_a_market_and_a_date():
    # Cities, because a watch is on a market. A series keyed on Newark never
    # meets the watch keyed on New York and nothing raises to say so.
    assert offer().series_key() == ("FRA", "NYC", "2025-03-04", "Y")


def test_two_offers_compare_and_subtract_in_one_currency():
    dearer = offer(breakdown=PriceBreakdown(Money.of("520.00", "EUR"), (tax(),)))
    assert offer().cheaper_than(dearer)
    assert dearer.difference_from(offer()) == Money.of("100.00", "EUR")


def test_two_offers_in_different_currencies_refuse_to_compare():
    other = offer(breakdown=PriceBreakdown(Money.of("520.00", "USD"), (tax(currency="USD"),)))
    with pytest.raises(ValidationError):
        offer().cheaper_than(other)
    with pytest.raises(ValidationError):
        offer().difference_from(other)


def test_a_note_appends_and_leaves_the_original_alone():
    plain = offer()
    noted = plain.with_note("quoted total disagrees").with_note("fallback mileage")
    assert plain.notes == ()
    assert noted.notes == ("quoted total disagrees", "fallback mileage")


def test_string_form_walks_the_route():
    assert str(offer()) == "gds FRAJFK KLWAP7RT 490.83 EUR"


def test_cheapest_takes_the_lowest_total():
    dearer = offer(breakdown=PriceBreakdown(Money.of("520.00", "EUR"), (tax(),)))
    assert cheapest([dearer, offer()]).total == Money.of("490.83", "EUR")


def test_cheapest_breaks_a_tie_on_the_earlier_sighting():
    early = offer(source="ndc", observed_at=datetime(2025, 2, 1, 8, 0))
    late = offer(source="gds", observed_at=datetime(2025, 2, 1, 9, 0))
    assert cheapest([late, early]).source == "ndc"


def test_cheapest_refuses_an_empty_list_and_a_mixed_currency_one():
    with pytest.raises(ValidationError):
        cheapest([])
    other = offer(breakdown=PriceBreakdown(Money.of("520.00", "USD"), ()))
    with pytest.raises(ValidationError):
        cheapest([offer(), other])


def test_deduplicate_keeps_the_first_sighting():
    early = offer(observed_at=datetime(2025, 2, 1, 8, 0))
    late = offer(observed_at=datetime(2025, 2, 1, 9, 0))
    kept = deduplicate([late, early])
    assert len(kept) == 1
    assert kept[0].observed_at == datetime(2025, 2, 1, 8, 0)


def test_deduplicate_keeps_two_genuinely_different_offers():
    dearer = offer(breakdown=PriceBreakdown(Money.of("520.00", "EUR"), (tax(),)))
    assert len(deduplicate([offer(), dearer])) == 2


def test_spread_is_zero_on_fewer_than_two():
    assert spread([]) == Decimal(0)
    assert spread([offer()]) == Decimal(0)


def test_spread_is_the_gap_between_the_ends():
    dearer = offer(breakdown=PriceBreakdown(Money.of("620.00", "EUR"), (tax(),)))
    assert spread([offer(), dearer]) == Decimal("200.00")


def test_the_conftest_clock_helper_reads_a_wall_clock():
    assert at("2025-03-04 10:25") == datetime(2025, 3, 4, 10, 25)
