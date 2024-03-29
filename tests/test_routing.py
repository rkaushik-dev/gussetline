from __future__ import annotations

import pytest

from gussetline.errors import ValidationError
from gussetline.routing import MILEAGE_ROUTING, Hop, RoutingMap, RoutingStore


def test_a_hop_folds_to_cities():
    hop = Hop("LHR", "EWR")
    assert hop.origin == "LON"
    assert hop.destination == "NYC"


def test_a_hop_goes_somewhere():
    with pytest.raises(ValidationError):
        Hop("LHR", "LGW")


def test_a_hop_with_no_carriers_permits_anybody():
    hop = Hop("FRA", "JFK")
    assert hop.any_carrier
    assert hop.permits("LH")
    assert hop.permits(None)


def test_a_hop_with_carriers_permits_only_those():
    hop = Hop("FRA", "JFK", frozenset({"LH", "UA"}))
    assert hop.permits("LH")
    assert hop.permits("ua")
    assert not hop.permits("BA")
    assert not hop.permits(None)


def test_a_hop_reverses_and_keeps_its_carriers():
    hop = Hop("FRA", "JFK", frozenset({"LH"})).reversed()
    assert (hop.origin, hop.destination) == ("NYC", "FRA")
    assert hop.permits("LH")


def test_a_hop_reads_back_with_its_carriers():
    assert str(Hop("FRA", "JFK", frozenset({"UA", "LH"}))) == "FRA-NYC[LH/UA]"
    assert str(Hop("FRA", "JFK")) == "FRA-NYC[any]"


def test_a_map_needs_a_number():
    with pytest.raises(ValidationError):
        RoutingMap("  ")


def test_a_map_is_bidirectional_by_default():
    m = RoutingMap("0101")
    m.add("FRA", "JFK", ["LH"])
    assert m.permits(["FRA", "JFK"], ["LH"])
    assert m.permits(["JFK", "FRA"], ["LH"])
    assert len(m) == 2


def test_a_one_way_map_only_reads_forwards():
    m = RoutingMap("0102", bidirectional=False)
    m.add("FRA", "JFK", ["LH"])
    assert m.permits(["FRA", "JFK"], ["LH"])
    assert not m.permits(["JFK", "FRA"], ["LH"])


def test_a_string_adds_every_hop_along_it():
    m = RoutingMap("0103")
    m.add_string(["FRA", "LHR", "JFK"], ["LH", "BA"])
    assert m.permits(["FRA", "LON", "NYC"], ["LH", "BA"])
    assert m.points() == ("FRA", "LON", "NYC")


def test_a_string_of_one_point_is_refused():
    with pytest.raises(ValidationError):
        RoutingMap("0104").add_string(["FRA"])


def test_a_path_the_map_does_not_hold_is_refused():
    m = RoutingMap("0105")
    m.add_string(["FRA", "LHR", "JFK"], ["LH"])
    assert not m.permits(["FRA", "AMS", "NYC"], ["LH", "LH"])


def test_the_right_path_on_the_wrong_carrier_is_refused():
    m = RoutingMap("0106")
    m.add("FRA", "JFK", ["LH"])
    assert not m.permits(["FRA", "NYC"], ["BA"])


def test_a_missing_carrier_needs_an_any_carrier_hop():
    strict = RoutingMap("0107")
    strict.add("FRA", "JFK", ["LH"])
    assert not strict.permits(["FRA", "NYC"])

    open_map = RoutingMap("0108")
    open_map.add("FRA", "JFK")
    assert open_map.permits(["FRA", "NYC"])


def test_carriers_shorter_than_the_hops_only_checks_what_it_was_given():
    m = RoutingMap("0109")
    m.add("FRA", "LHR", ["LH"])
    m.add("LHR", "JFK")
    assert m.permits(["FRA", "LON", "NYC"], ["LH"])


def test_the_mileage_routing_permits_everything():
    m = RoutingMap(MILEAGE_ROUTING)
    assert m.is_mileage
    assert m.permits(["FRA", "SIN", "SYD", "NYC"])
    assert m.refusal(["FRA", "SIN"]) is None


def test_a_check_needs_two_points():
    with pytest.raises(ValidationError):
        RoutingMap("0110").permits(["FRA"])


def test_a_refusal_names_the_hop_that_is_not_filed():
    m = RoutingMap("0111")
    m.add_string(["FRA", "LHR", "JFK"], ["LH"])
    said = m.refusal(["FRA", "AMS", "NYC"], ["LH", "LH"])
    assert said == "routing 0111 does not permit FRA-AMS at all"


def test_a_refusal_separates_a_bad_carrier_from_a_bad_hop():
    m = RoutingMap("0112")
    m.add("FRA", "JFK", ["LH"])
    said = m.refusal(["FRA", "NYC"], ["BA"])
    assert said == "routing 0112 does not permit BA on FRA-NYC"


def test_a_path_that_passes_has_no_refusal():
    m = RoutingMap("0113")
    m.add("FRA", "JFK", ["LH"])
    assert m.refusal(["FRA", "NYC"], ["LH"]) is None


def test_a_map_reads_back_with_its_size():
    m = RoutingMap("0114")
    m.add_string(["FRA", "LHR", "JFK"])
    assert str(m) == "routing 0114 over 3 points"


def test_the_store_finds_a_map_by_carrier_and_number():
    store = RoutingStore()
    m = RoutingMap("0201")
    m.add("FRA", "JFK", ["LH"])
    store.add("LH", m)
    assert store.get("lh", "0201") is m
    assert store.get("BA", "0201") is None
    assert len(store) == 1


def test_the_store_applies_the_map_when_it_has_one():
    store = RoutingStore()
    m = RoutingMap("0202")
    m.add("FRA", "JFK", ["LH"])
    store.add("LH", m)
    assert store.permits("LH", "0202", ["FRA", "NYC"], ["LH"])
    assert not store.permits("LH", "0202", ["FRA", "AMS"], ["LH"])


def test_the_store_lets_a_mileage_routing_through_without_a_map():
    assert RoutingStore().permits("LH", MILEAGE_ROUTING, ["FRA", "SIN"])


def test_a_map_we_do_not_hold_falls_through_to_mileage():
    # Conservative on purpose. Refusing here would drop every fare on a
    # carrier whose maps have not loaded, which is worse than checking the
    # mileage instead.
    assert RoutingStore().permits("LH", "0999", ["FRA", "AMS", "NYC"], ["LH", "LH"])
