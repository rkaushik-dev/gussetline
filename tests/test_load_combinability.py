from __future__ import annotations

import pytest

from gussetline.collect.fixedwidth import write_record
from gussetline.combinability import CombinabilityStore
from gussetline.itinerary import JourneyType
from gussetline.load.combinability import (
    COMBINABILITY,
    apply,
    load_into,
    permissive,
    read,
)
from gussetline.persistence.unitofwork import in_memory


def line(
    carrier="LH",
    basis="KLWAP7RT",
    round_trip="Y",
    circle_trip=None,
    open_jaw=None,
    double_open_jaw=None,
    end_on_end=None,
    side_trip=None,
    any_carrier=None,
    same_rule_only=None,
    action=None,
) -> str:
    return write_record(
        COMBINABILITY,
        {
            "carrier": carrier,
            "fare_basis": basis,
            "round_trip": round_trip,
            "circle_trip": circle_trip,
            "open_jaw": open_jaw,
            "double_open_jaw": double_open_jaw,
            "end_on_end": end_on_end,
            "side_trip": side_trip,
            "any_carrier": any_carrier,
            "same_rule_only": same_rule_only,
            "action": action,
        },
    )


def file_of(*lines: str) -> str:
    return "\n".join(lines)


def test_an_empty_file_reads_as_nothing():
    load = read("")
    assert len(load) == 0
    assert load.clean


def test_a_record_reads_into_the_object_the_engine_uses():
    load = read(line(round_trip="Y", circle_trip="Y"))
    record = load.records[0]
    assert record.carrier == "LH"
    assert record.fare_basis == "KLWAP7RT"
    assert record.round_trip
    assert record.circle_trip


def test_everything_a_record_does_not_mention_is_refused():
    # A half read filing has to produce a fare that combines with nothing.
    # The alternative prices a round trip a hundred euro light in silence.
    record = read(line(round_trip=None)).records[0]
    assert not record.round_trip
    assert not record.circle_trip
    assert not record.open_jaw
    assert not record.end_on_end
    assert not record.side_trip


def test_same_carrier_only_defaults_the_other_way_because_it_is_a_restriction():
    record = read(line()).records[0]
    assert record.same_carrier_only


def test_a_record_that_says_any_carrier_lifts_the_restriction():
    record = read(line(any_carrier="Y")).records[0]
    assert not record.same_carrier_only


def test_every_flag_can_be_filed():
    record = read(
        line(
            circle_trip="Y",
            open_jaw="Y",
            double_open_jaw="Y",
            end_on_end="Y",
            side_trip="Y",
            same_rule_only="Y",
        )
    ).records[0]
    assert record.circle_trip
    assert record.open_jaw
    assert record.double_open_jaw
    assert record.end_on_end
    assert record.side_trip
    assert record.same_rule_only


def test_anything_that_is_not_a_yes_is_a_no():
    record = read(line(round_trip="N", circle_trip="X")).records[0]
    assert not record.round_trip
    assert not record.circle_trip


def test_a_record_with_no_fare_basis_is_one_bad_record():
    load = read(file_of(line(), line(basis="")))
    assert len(load) == 1
    assert len(load.problems) == 1
    assert "names a fare basis" in load.problems[0].reason


def test_the_records_land_in_a_store_the_engine_can_read():
    store = CombinabilityStore()
    load_into(store, file_of(line(), line(basis="MLWAP7RT")))
    assert store.bases("LH") == ["KLWAP7RT", "MLWAP7RT"]
    found = store.get("LH", "KLWAP7RT")
    assert found is not None
    assert found.permits(JourneyType.ROUND_TRIP)


def test_a_refiled_record_replaces_the_old_one_whole():
    store = CombinabilityStore()
    load_into(store, line(round_trip="Y", circle_trip="Y"))
    _, applied = load_into(store, line(round_trip="Y"))
    assert applied.replaced == 1
    found = store.get("LH", "KLWAP7RT")
    assert found is not None
    assert not found.circle_trip


def test_a_deleted_record_leaves_the_store():
    store = CombinabilityStore()
    load_into(store, line())
    _, applied = load_into(store, line(action="D"))
    assert applied.deleted == 1
    assert store.get("LH", "KLWAP7RT") is None


def test_a_delete_for_a_record_we_never_held_is_counted_and_ignored():
    _, applied = load_into(CombinabilityStore(), line(action="D"))
    assert applied.unknown_deletes == 1
    assert applied.deleted == 0


def test_a_delete_carries_no_flags_with_it():
    load = read(line(round_trip="Y", action="D"))
    assert load.records == []
    assert load.deleted == [("LH", "KLWAP7RT")]


def test_the_ones_that_combine_with_nothing_are_counted_for_the_operator():
    store = CombinabilityStore()
    _, applied = load_into(store, file_of(line(), line(basis="MLWAP7RT", round_trip=None)))
    assert applied.combine_with_nothing == 1
    assert applied.added == 2


def test_two_carriers_are_two_records_at_the_same_fare_basis():
    store = CombinabilityStore()
    load_into(store, file_of(line(carrier="LH"), line(carrier="UA")))
    assert store.get("LH", "KLWAP7RT") is not None
    assert store.get("UA", "KLWAP7RT") is not None


def test_reading_never_touches_the_store():
    store = CombinabilityStore()
    load = read(line())
    assert store.get("LH", "KLWAP7RT") is None
    assert apply(store, load).added == 1


def test_a_load_reads_back_in_one_line():
    load = read(file_of(line(), line(basis="MLWAP7RT", action="D")))
    assert str(load) == "1 records, 1 deleted, 0 problems"


def test_what_was_applied_reads_back_in_one_line():
    _, applied = load_into(CombinabilityStore(), line())
    assert str(applied) == "1 added, 0 replaced, 0 deleted, 0 that combine with nothing"


def test_the_permissive_record_is_for_a_laptop_and_permits_everything():
    record = permissive("LH", "KLWAP7RT")
    assert record.permits(JourneyType.ROUND_TRIP)
    assert record.permits(JourneyType.CIRCLE_TRIP)
    assert record.permits(JourneyType.OPEN_JAW)
    assert record.end_on_end
    assert not record.same_carrier_only


@pytest.fixture
def database():
    return in_memory()


def test_a_record_survives_the_round_trip_through_the_database(database):
    record = read(line(circle_trip="Y", end_on_end="Y", any_carrier="Y")).records[0]
    with database.unit() as work:
        work.combinability.save(record)
        work.commit()

    with database.unit() as work:
        back = work.combinability.get("LH", "KLWAP7RT")
    assert back == record


def test_saving_the_same_pair_twice_updates_rather_than_duplicates(database):
    with database.unit() as work:
        work.combinability.save(read(line(circle_trip="Y")).records[0])
        work.commit()
    with database.unit() as work:
        work.combinability.save(read(line()).records[0])
        work.commit()

    with database.unit() as work:
        assert work.combinability.count() == 1
        back = work.combinability.get("LH", "KLWAP7RT")
    assert back is not None
    assert not back.circle_trip


def test_the_store_for_a_carrier_is_read_in_one_go(database):
    with database.unit() as work:
        work.combinability.save_all(read(file_of(line(), line(basis="MLWAP7RT"))).records)
        work.combinability.save(read(line(carrier="UA")).records[0])
        work.commit()

    with database.unit() as work:
        store = work.combinability.store_for(["LH"])
    assert store.bases("LH") == ["KLWAP7RT", "MLWAP7RT"]
    assert store.get("UA", "KLWAP7RT") is None


def test_the_store_for_no_carriers_is_empty_without_a_query(database):
    with database.unit() as work:
        assert len(work.combinability.store_for([])) == 0


def test_a_withdrawn_record_is_deleted_and_not_blanked(database):
    with database.unit() as work:
        work.combinability.save(read(line()).records[0])
        work.commit()

    with database.unit() as work:
        assert work.combinability.delete("LH", "KLWAP7RT")
        work.commit()

    with database.unit() as work:
        assert work.combinability.count() == 0
        assert work.combinability.get("LH", "KLWAP7RT") is None


def test_deleting_something_that_is_not_there_says_so(database):
    with database.unit() as work:
        assert not work.combinability.delete("LH", "NOTHING")
