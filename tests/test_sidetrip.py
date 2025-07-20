from __future__ import annotations

from datetime import timedelta

import pytest

from conftest import segment, surface
from gussetline.combinability import Combinability, CombinabilityStore
from gussetline.errors import ValidationError
from gussetline.itinerary import Itinerary, JourneyType
from gussetline.sidetrip import SideTrip, find, miles_off, permitted, without


def leg(origin, destination, day, hour=10, carrier="LH", number="400"):
    return segment(
        carrier,
        number,
        origin,
        destination,
        f"2025-08-{day:02d} {hour:02d}:00",
        f"2025-08-{day:02d} {hour + 2:02d}:00",
    )


def one_way_with_a_side_trip() -> Itinerary:
    """Frankfurt to New York, by way of a week in Vienna."""
    return Itinerary(
        [
            leg("FRA", "VIE", 4),
            leg("VIE", "FRA", 11),
            leg("FRA", "JFK", 12),
        ]
    )


def test_a_plain_one_way_has_no_side_trip():
    assert find(Itinerary([leg("FRA", "JFK", 4)])) == ()


def test_a_round_trip_is_not_a_side_trip():
    # It comes home, which is what a round trip does, and it prices as one.
    made = Itinerary([leg("FRA", "JFK", 4), leg("JFK", "FRA", 18)])
    assert made.journey_type() is JourneyType.ROUND_TRIP
    assert find(made) == ()


def test_an_excursion_in_the_middle_is_a_side_trip():
    trips = find(one_way_with_a_side_trip())
    assert len(trips) == 1
    assert trips[0].at == "FRA"
    assert trips[0].turnaround == "VIE"
    assert len(trips[0]) == 2


def test_a_side_trip_names_only_the_points_it_visits():
    trips = find(one_way_with_a_side_trip())
    assert trips[0].points == ("VIE",)


def test_a_side_trip_reads_back_in_one_line():
    assert str(find(one_way_with_a_side_trip())[0]) == "side trip to VIE off FRA"


def test_a_connection_through_a_point_is_not_a_side_trip():
    # Frankfurt twice in an afternoon is a connection, whatever the shape of
    # the point list says.
    made = Itinerary(
        [
            leg("MUC", "FRA", 4, hour=6),
            leg("FRA", "VIE", 4, hour=9),
            leg("VIE", "FRA", 4, hour=13),
            leg("FRA", "JFK", 4, hour=17),
        ]
    )
    assert find(made) == ()


def test_the_time_away_is_what_decides_it():
    quick = Itinerary(
        [leg("FRA", "VIE", 4, hour=6), leg("VIE", "FRA", 4, hour=14), leg("FRA", "JFK", 5)]
    )
    assert find(quick) == ()
    assert len(find(quick, least=timedelta(hours=4))) == 1


def test_taking_the_side_trip_out_leaves_a_journey_that_joins_up():
    made = one_way_with_a_side_trip()
    left = without(made, find(made))
    assert [e.origin for e in left.elements] == ["FRA"]
    assert left.destination == "JFK"


def test_what_is_left_is_a_plain_one_way():
    # Not a circle trip and not two round trips, whatever the point list looks
    # like before the excursion comes out.
    made = one_way_with_a_side_trip()
    assert without(made, find(made)).journey_type() is JourneyType.ONE_WAY


def test_taking_nothing_out_gives_back_the_same_journey():
    made = one_way_with_a_side_trip()
    assert without(made, ()) is made


def test_a_journey_that_is_all_side_trip_is_refused():
    # Not reachable through find, which never calls the whole journey an
    # excursion. Reachable from a caller that worked the indices out itself.
    made = Itinerary([leg("FRA", "VIE", 4), leg("VIE", "FRA", 11)])
    everything = SideTrip(at="FRA", first=0, last=1, elements=tuple(made.elements))
    with pytest.raises(ValidationError, match="all side trip"):
        without(made, (everything,))


def test_a_journey_that_touches_its_own_origin_and_carries_on_has_left_again():
    # The whole distinction in one shape. Coming back to Frankfurt in the
    # middle is an excursion; coming back at the end is a round trip.
    made = Itinerary([leg("FRA", "VIE", 4), leg("VIE", "FRA", 11), leg("FRA", "JFK", 12)])
    assert len(find(made)) == 1
    home = Itinerary([leg("FRA", "VIE", 4), leg("VIE", "FRA", 11)])
    assert find(home) == ()


def test_two_excursions_are_two_side_trips():
    made = Itinerary(
        [
            leg("FRA", "VIE", 4),
            leg("VIE", "FRA", 11),
            leg("FRA", "JFK", 12),
            leg("JFK", "BOS", 20),
            leg("BOS", "JFK", 26),
            leg("JFK", "FRA", 28),
        ]
    )
    trips = find(made)
    assert [trip.turnaround for trip in trips] == ["VIE", "BOS"]


def test_taking_two_out_leaves_the_round_trip_underneath():
    made = Itinerary(
        [
            leg("FRA", "VIE", 4),
            leg("VIE", "FRA", 11),
            leg("FRA", "JFK", 12),
            leg("JFK", "BOS", 20),
            leg("BOS", "JFK", 26),
            leg("JFK", "FRA", 28),
        ]
    )
    left = without(made, find(made))
    assert left.journey_type() is JourneyType.ROUND_TRIP
    assert len(left.elements) == 2


def test_the_furthest_point_of_a_longer_excursion_is_its_turnaround():
    made = Itinerary(
        [
            leg("FRA", "VIE", 4),
            leg("VIE", "PRG", 6),
            leg("PRG", "FRA", 11),
            leg("FRA", "JFK", 12),
        ]
    )
    trips = find(made)
    assert len(trips) == 1
    assert trips[0].points == ("VIE", "PRG")
    assert trips[0].turnaround == "PRG"


def test_an_excursion_driven_one_way_is_still_an_excursion():
    made = Itinerary(
        [leg("FRA", "VIE", 4), surface("VIE", "MUC"), leg("MUC", "FRA", 11), leg("FRA", "JFK", 12)]
    )
    trips = find(made)
    assert len(trips) == 1
    assert trips[0].turnaround == "MUC"


def test_the_miles_an_excursion_accounts_for_are_counted_on_their_own():
    # The difference between the mileage a naive check would use and the one
    # the transatlantic component is entitled to.
    made = one_way_with_a_side_trip()
    trips = find(made)
    assert miles_off(made, trips) > 700
    assert miles_off(made, ()) == 0
    assert without(made, trips).total_miles() < made.total_miles()


def test_a_fare_that_permits_a_side_trip_permits_it():
    store = CombinabilityStore()
    store.add(Combinability(carrier="LH", fare_basis="KLWAP7RT", side_trip=True))
    assert permitted(find(one_way_with_a_side_trip()), "LH", "KLWAP7RT", store) == []


def test_a_fare_that_does_not_permit_one_refuses_it():
    store = CombinabilityStore()
    store.add(Combinability(carrier="LH", fare_basis="KLWAP7RT"))
    refusals = permitted(find(one_way_with_a_side_trip()), "LH", "KLWAP7RT", store)
    assert refusals == ["LH KLWAP7RT does not permit a side trip"]


def test_a_fare_with_no_category_10_filed_refuses_it():
    refusals = permitted(find(one_way_with_a_side_trip()), "LH", "KLWAP7RT", CombinabilityStore())
    assert "has no combinability filed" in refusals[0]


def test_a_journey_with_no_excursion_asks_nothing_of_category_10():
    assert permitted((), "LH", "KLWAP7RT", CombinabilityStore()) == []


def test_a_side_trip_that_runs_backwards_is_refused():
    with pytest.raises(ValidationError, match="runs forwards"):
        SideTrip(at="FRA", first=3, last=1, elements=(leg("FRA", "VIE", 4),))


def test_a_side_trip_that_goes_nowhere_is_refused():
    with pytest.raises(ValidationError, match="goes somewhere"):
        SideTrip(at="FRA", first=0, last=0, elements=())
