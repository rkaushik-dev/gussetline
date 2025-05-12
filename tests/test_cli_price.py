from __future__ import annotations

from datetime import date, datetime

import pytest
from click.testing import CliRunner

from gussetline.cli.main import OPERATOR_CAN_FIX, cli, exit_code_for
from gussetline.cli.price import _pairs
from gussetline.collect.fixedwidth import write_record
from gussetline.combinability import Combinability
from gussetline.config import Settings
from gussetline.errors import ValidationError
from gussetline.indicators import GlobalIndicator
from gussetline.load.combinability import COMBINABILITY
from gussetline.money import Money
from gussetline.persistence.unitofwork import Database
from gussetline.spec import parse
from gussetline.tariff import Fare, FareType, OwRt

TODAY = "2025-07-22"
OUT = "FRA LH400 K 04AUG JFK"
RETURN = "FRA LH400 K 04AUG JFK LH401 K 18AUG FRA"


@pytest.fixture
def runner() -> CliRunner:
    return CliRunner()


@pytest.fixture
def url(tmp_path):
    return f"sqlite+pysqlite:///{tmp_path / 'desk.sqlite3'}"


@pytest.fixture
def env(monkeypatch, url, tmp_path):
    monkeypatch.setenv("GUSSETLINE_DATABASE_URL", url)
    monkeypatch.setenv("GUSSETLINE_ARCHIVE_ROOT", str(tmp_path / "raw"))
    Database(Settings(database_url=url)).create_schema()
    return url


def fare(
    carrier="LH",
    origin="FRA",
    destination="JFK",
    basis="KLWAP7RT",
    amount="420.00",
    owrt=OwRt.ROUND_TRIP_HALF,
    routing="0011",
    indicator=GlobalIndicator.AT,
) -> Fare:
    return Fare(
        carrier=carrier,
        origin=origin,
        destination=destination,
        fare_basis=basis,
        amount=Money.of(amount, "EUR"),
        owrt=owrt,
        global_indicator=indicator,
        rule_number="0100",
        routing_number=routing,
        tariff_number="1",
        fare_type=FareType.PUBLISHED,
        effective=date(2025, 1, 1),
        discontinue=date(2025, 12, 31),
        first_travel=date(2025, 1, 1),
        last_travel=date(2025, 12, 31),
    )


def load(url: str, *fares: Fare) -> None:
    database = Database(Settings(database_url=url))
    with database.unit() as work:
        row = work.tariff.open_load(
            carrier=fares[0].carrier,
            filename="test.dat",
            digest="d" * 32,
            loaded_at=datetime(2025, 3, 1, 6, 0),
            effective=date(2025, 3, 1),
        )
        work.tariff.add_fares(row, list(fares))
        work.commit()


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def test_the_price_command_shows_up_in_the_help(runner, env):
    assert "price" in run(runner, "--help").output


def test_an_entry_is_echoed_back_the_way_it_was_read(runner, env):
    load(env, fare())
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert RETURN in reply.output


def test_a_round_trip_prices_off_two_halves(runner, env):
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert "KLWAP7RT" in reply.output
    assert "base fare 840 EUR" in reply.output


def test_a_fare_with_no_combinability_filed_prices_and_says_it_cannot_be_sold(runner, env):
    # Still printed. The desk is looking at it because a supplier quoted
    # something, and a blank screen answers nothing.
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert reply.exit_code == 1
    assert "cannot be issued" in reply.output
    assert "no combinability filed" in reply.output


def test_the_fare_calculation_line_is_printed(runner, env):
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert "FRA LH NYC" in reply.output
    assert "NUC" in reply.output


def test_an_entry_with_no_fare_filed_says_so_rather_than_pricing(runner, env):
    load(env, fare(destination="ORD"))
    reply = run(runner, "price", OUT, "--on", TODAY)
    assert reply.exit_code != 0


def test_a_fare_outside_its_sale_window_is_not_used(runner, env):
    load(env, fare())
    reply = run(runner, "price", OUT, "--on", "2026-03-01")
    assert reply.exit_code != 0


def test_the_carrier_can_be_pinned(runner, env):
    load(env, fare(owrt=OwRt.ONE_WAY_ONLY, basis="KOW"))
    load(env, fare(carrier="UA", owrt=OwRt.ONE_WAY_ONLY, basis="KCHEAP", amount="300.00"))
    reply = run(runner, "price", OUT, "--on", TODAY, "--carrier", "LH")
    assert "KOW" in reply.output
    assert "KCHEAP" not in reply.output


def test_the_trail_is_off_unless_it_is_asked_for(runner, env):
    load(env, fare(owrt=OwRt.ONE_WAY_ONLY, basis="KOW"))
    quiet = run(runner, "price", OUT, "--on", TODAY)
    loud = run(runner, "price", OUT, "--on", TODAY, "--why")
    assert len(loud.output) > len(quiet.output)
    assert "selection" in loud.output


def test_the_breaks_can_be_given(runner, env):
    # Break after the first sector, so the connection at Munich becomes a
    # fare break rather than a point the through fare covers.
    load(
        env,
        fare(
            destination="MUC",
            basis="KSHORT",
            owrt=OwRt.ONE_WAY_ONLY,
            amount="90.00",
            indicator=GlobalIndicator.DO,
        ),
    )
    load(env, fare(origin="MUC", owrt=OwRt.ONE_WAY_ONLY, basis="KLONG", amount="600.00"))
    entry = "FRA LH100 K 04AUG MUC LH400 K 04AUG JFK"
    reply = run(runner, "price", entry, "--on", TODAY, "--breaks", "0")
    assert "KSHORT" in reply.output
    assert "KLONG" in reply.output


def test_a_break_past_the_end_is_refused(runner, env):
    load(env, fare())
    reply = run(runner, "price", RETURN, "--on", TODAY, "--breaks", "9")
    assert reply.exit_code != 0
    assert isinstance(reply.exception, ValidationError)


def test_breaks_that_are_not_numbers_are_a_usage_error(runner, env):
    load(env, fare())
    reply = run(runner, "price", OUT, "--on", TODAY, "--breaks", "middle")
    assert reply.exit_code == 2
    assert "element numbers" in reply.output


def test_an_entry_that_will_not_read_is_refused_with_the_token(runner, env):
    reply = run(runner, "price", "FRA LUFTHANSA K 04AUG JFK", "--on", TODAY)
    assert reply.exit_code != 0
    assert isinstance(reply.exception, ValidationError)
    assert "not a flight" in str(reply.exception)
    assert exit_code_for(reply.exception) == OPERATOR_CAN_FIX


def test_the_city_pairs_asked_for_cover_both_directions():
    itinerary = parse(RETURN, date(2025, 7, 22))
    pairs = _pairs(itinerary)
    assert ("FRA", "NYC") in pairs
    assert ("NYC", "FRA") in pairs


def test_the_city_pairs_are_asked_for_once_each():
    itinerary = parse(RETURN, date(2025, 7, 22))
    pairs = _pairs(itinerary)
    assert len(pairs) == len(set(pairs))


def test_a_point_never_pairs_with_itself():
    itinerary = parse(RETURN, date(2025, 7, 22))
    assert all(left != right for left, right in _pairs(itinerary))


def test_a_connection_adds_its_own_pairs():
    itinerary = parse("FRA LH100 K 04AUG MUC LH400 K 04AUG JFK", date(2025, 7, 22))
    pairs = _pairs(itinerary)
    assert ("FRA", "MUC") in pairs
    assert ("MUC", "NYC") in pairs
    assert ("FRA", "NYC") in pairs


def test_a_one_way_fare_prices_a_one_way_entry(runner, env):
    load(env, fare(owrt=OwRt.ONE_WAY_ONLY, basis="KOW", amount="620.00"))
    reply = run(runner, "price", OUT, "--on", TODAY)
    assert reply.exit_code == 0
    assert "KOW" in reply.output


def test_the_cheapest_fare_filed_is_the_one_used(runner, env):
    load(
        env,
        fare(owrt=OwRt.ONE_WAY_ONLY, basis="KOW", amount="620.00"),
        fare(owrt=OwRt.ONE_WAY_ONLY, basis="KOWSALE", amount="480.00"),
    )
    reply = run(runner, "price", OUT, "--on", TODAY)
    assert "KOWSALE" in reply.output
    assert "519.48" in reply.output


def test_the_selling_currency_comes_off_the_settings(runner, env, monkeypatch):
    load(env, fare(owrt=OwRt.ONE_WAY_ONLY, basis="KOW", amount="620.00"))
    monkeypatch.setenv("GUSSETLINE_SELLING_CURRENCY", "GBP")
    reply = run(runner, "price", OUT, "--on", TODAY)
    assert "GBP" in reply.output


def test_a_quiet_run_still_prints_the_refusals(runner, env):
    load(env, fare(destination="ORD"))
    reply = run(runner, "--quiet", "price", OUT, "--on", TODAY)
    assert "base fare" not in reply.output


def combinability(url: str, basis="KLWAP7RT", carrier="LH") -> None:
    database = Database(Settings(database_url=url))
    with database.unit() as work:
        work.combinability.save(
            Combinability(
                carrier=carrier,
                fare_basis=basis,
                round_trip=True,
                circle_trip=True,
                open_jaw=True,
                end_on_end=True,
            )
        )
        work.commit()


def test_a_round_trip_with_category_10_filed_can_be_issued(runner, env):
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    combinability(env)
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert reply.exit_code == 0
    assert "cannot be issued" not in reply.output
    assert "base fare 840 EUR" in reply.output


def test_a_record_filed_for_another_carrier_does_not_help(runner, env):
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    combinability(env, carrier="UA")
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert reply.exit_code == 1
    assert "no combinability filed" in reply.output


def test_a_record_filed_for_another_fare_basis_does_not_help(runner, env):
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    combinability(env, basis="MLWAP7RT")
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert reply.exit_code == 1


def test_the_load_group_now_takes_a_category_10_file(runner, env, tmp_path):
    path = tmp_path / "cat10.dat"
    path.write_text(
        write_record(
            COMBINABILITY,
            {
                "carrier": "LH",
                "fare_basis": "KLWAP7RT",
                "round_trip": "Y",
                "circle_trip": "Y",
                "open_jaw": None,
                "double_open_jaw": None,
                "end_on_end": "Y",
                "side_trip": None,
                "any_carrier": None,
                "same_rule_only": None,
                "action": None,
            },
        ),
        encoding="ascii",
    )
    reply = run(runner, "load", "combinability", str(path))
    assert reply.exit_code == 0
    assert "kept 1 records" in reply.output


def test_a_loaded_category_10_file_makes_the_round_trip_issuable(runner, env, tmp_path):
    load(env, fare(), fare(origin="JFK", destination="FRA"))
    path = tmp_path / "cat10.dat"
    path.write_text(
        write_record(
            COMBINABILITY,
            {
                "carrier": "LH",
                "fare_basis": "KLWAP7RT",
                "round_trip": "Y",
                "circle_trip": None,
                "open_jaw": None,
                "double_open_jaw": None,
                "end_on_end": None,
                "side_trip": None,
                "any_carrier": None,
                "same_rule_only": None,
                "action": None,
            },
        ),
        encoding="ascii",
    )
    run(runner, "load", "combinability", str(path))
    reply = run(runner, "price", RETURN, "--on", TODAY)
    assert reply.exit_code == 0
