from __future__ import annotations

from datetime import date, datetime

from conftest import segment
from gussetline.collect.harvest import (
    Harvest,
    Quote,
    combine,
    nothing,
    offers_only,
    quotes_only,
)
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown

SEEN = datetime(2025, 6, 25, 9, 0)


def an_offer(amount="840.00") -> Offer:
    return Offer(
        source="gds",
        itinerary=Itinerary(
            [segment("LH", "400", "FRA", "JFK", "2025-07-04 10:25", "2025-07-04 13:05")]
        ),
        breakdown=PriceBreakdown(Money.of(amount, "EUR")),
        fare_bases=("KLWAP7RT",),
        observed_at=SEEN,
    )


def a_quote(amount="420.00", day=4, sold_out=False) -> Quote:
    return Quote(
        origin="FRA",
        destination="NYC",
        departure=date(2025, 7, day),
        amount=Money.of(amount, "EUR"),
        observed_at=SEEN,
        sold_out=sold_out,
    )


def test_an_empty_harvest_is_clean_and_empty():
    harvest = Harvest()
    assert harvest.clean
    assert harvest.empty
    assert len(harvest) == 0
    assert str(harvest) == "nothing"


def test_a_harvest_counts_both_kinds_together():
    harvest = Harvest(offers=[an_offer()], quotes=[a_quote(), a_quote(day=5)])
    assert len(harvest) == 3
    assert not harvest.empty
    assert str(harvest) == "1 offers, 2 quotes"


def test_problems_show_in_the_summary_and_stop_it_being_clean():
    harvest = Harvest(offers=[an_offer()], problems=["offer 2 had no base amount"])
    assert not harvest.clean
    assert str(harvest) == "1 offers, 1 problems"


def test_a_harvest_with_only_problems_reads_as_nothing():
    assert str(nothing("could not parse")) == "nothing, 1 problems"


def test_the_cheapest_offer_is_found():
    harvest = Harvest(offers=[an_offer("840.00"), an_offer("790.00")])
    cheapest = harvest.cheapest_offer()
    assert cheapest is not None
    assert cheapest.total == Money.of("790.00", "EUR")


def test_a_harvest_with_no_offers_has_no_cheapest_offer():
    assert Harvest(quotes=[a_quote()]).cheapest_offer() is None


def test_the_cheapest_quote_skips_the_sold_out_days():
    harvest = Harvest(quotes=[a_quote("420.00"), a_quote("0", day=5, sold_out=True)])
    cheapest = harvest.cheapest_quote()
    assert cheapest is not None
    assert cheapest.amount == Money.of("420.00", "EUR")


def test_a_harvest_where_everything_is_sold_out_has_no_cheapest_quote():
    assert Harvest(quotes=[a_quote("0", sold_out=True)]).cheapest_quote() is None


def test_the_cheapest_amount_prefers_an_offer_over_a_quote():
    harvest = Harvest(offers=[an_offer("840.00")], quotes=[a_quote("420.00")])
    assert harvest.cheapest_amount() == "840.00 EUR"


def test_the_cheapest_amount_falls_to_a_quote_when_there_are_no_offers():
    assert Harvest(quotes=[a_quote("420.00")]).cheapest_amount() == "420.00 EUR"


def test_an_empty_harvest_has_no_cheapest_amount():
    assert Harvest().cheapest_amount() == ""


def test_a_quote_can_be_looked_up_by_its_day():
    harvest = Harvest(quotes=[a_quote(day=4), a_quote("380.00", day=5)])
    found = harvest.quote_on(date(2025, 7, 5))
    assert found is not None
    assert found.amount == Money.of("380.00", "EUR")
    assert harvest.quote_on(date(2025, 8, 1)) is None


def test_extending_takes_everything_including_the_problems():
    left = Harvest(offers=[an_offer()], problems=["one"])
    right = Harvest(quotes=[a_quote()], problems=["two"])
    left.extend(right)
    assert len(left) == 2
    assert left.problems == ["one", "two"]


def test_combining_a_run_keeps_every_problem():
    joined = combine([Harvest(offers=[an_offer()]), nothing("bad payload"), Harvest()])
    assert len(joined.offers) == 1
    assert joined.problems == ["bad payload"]


def test_combining_nothing_gives_an_empty_harvest():
    assert combine([]).empty


def test_the_two_convenience_builders_do_what_they_say():
    assert offers_only([an_offer()], "gds").source == "gds"
    assert offers_only([an_offer()]).quotes == []
    assert quotes_only([a_quote()], "calendar").offers == []
    assert quotes_only([a_quote()]).quotes


def test_a_quote_compares_and_reads_back():
    cheap = a_quote("380.00")
    dear = a_quote("420.00", day=5)
    assert cheap.cheaper_than(dear)
    assert cheap.market == ("FRA", "NYC")
    assert str(cheap) == "FRANYC 2025-07-04 380.00 EUR"
    assert str(a_quote("0", sold_out=True)).endswith("sold out")
