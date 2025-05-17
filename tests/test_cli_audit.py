from __future__ import annotations

from datetime import date, datetime

import pytest
from click.testing import CliRunner

from conftest import segment
from gussetline.audit import Audit, Finding
from gussetline.cli.audit import summarise
from gussetline.cli.main import cli
from gussetline.combinability import Combinability
from gussetline.config import Settings
from gussetline.indicators import GlobalIndicator
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.persistence.unitofwork import Database
from gussetline.tariff import Fare, FareType, OwRt

TODAY = "2025-07-24"
SEEN = datetime(2025, 7, 23, 6, 0)


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


def offer(
    total="620.00",
    basis="KOW",
    source="gds",
    seen=SEEN,
    destination="JFK",
    day=4,
) -> Offer:
    return Offer(
        source=source,
        itinerary=Itinerary(
            (
                segment(
                    "LH",
                    "400",
                    "FRA",
                    destination,
                    f"2025-08-{day:02d} 10:25",
                    f"2025-08-{day:02d} 13:05",
                ),
            )
        ),
        breakdown=PriceBreakdown(base=Money.of(total, "EUR")),
        fare_bases=(basis,),
        observed_at=seen,
        validating_carrier="LH",
    )


def fare(basis="KOW", amount="620.00", destination="JFK") -> Fare:
    return Fare(
        carrier="LH",
        origin="FRA",
        destination=destination,
        fare_basis=basis,
        amount=Money.of(amount, "EUR"),
        owrt=OwRt.ONE_WAY_ONLY,
        global_indicator=GlobalIndicator.AT,
        rule_number="0100",
        routing_number="0011",
        tariff_number="1",
        fare_type=FareType.PUBLISHED,
        effective=date(2025, 1, 1),
        discontinue=date(2025, 12, 31),
        first_travel=date(2025, 1, 1),
        last_travel=date(2025, 12, 31),
    )


def store(url: str, offers: list[Offer], fares: list[Fare]) -> None:
    database = Database(Settings(database_url=url))
    with database.unit() as work:
        if offers:
            work.observations.save_all(offers)
        if fares:
            row = work.tariff.open_load(
                carrier="LH",
                filename="test.dat",
                digest="d" * 32,
                loaded_at=datetime(2025, 3, 1, 6, 0),
                effective=date(2025, 3, 1),
            )
            work.tariff.add_fares(row, fares)
        for filed in fares:
            work.combinability.save(
                Combinability(carrier=filed.carrier, fare_basis=filed.fare_basis, round_trip=True)
            )
        work.commit()


def run(runner, *args: str):
    return runner.invoke(cli, list(args))


def test_the_audit_command_shows_up_in_the_help(runner, env):
    assert "audit" in run(runner, "--help").output


def test_an_empty_desk_says_there_is_nothing_to_audit(runner, env):
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert reply.exit_code == 0
    assert "nothing stored since 2025-07-01" in reply.output


def test_an_offer_that_agrees_with_the_tariff_is_counted_and_goes_no_further(runner, env):
    store(env, [offer(total="620.00")], [fare(amount="620.00")])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert "1 offers since 2025-07-01" in reply.output
    assert "agreed         1" in reply.output


def test_one_offer_out_on_its_own_is_noise_and_not_a_letter(runner, env):
    store(env, [offer(total="640.00")], [fare(amount="620.00")])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert "claims         0" in reply.output
    assert "noise          1" in reply.output


def test_enough_offers_agreeing_about_the_gap_becomes_a_letter(runner, env):
    # Same carrier, same market, same fare basis, same gap, three departures.
    # That is a filing error and it is one letter, not three.
    offers = [offer(total="640.00", day=day) for day in (4, 5, 6)]
    store(env, offers, [fare(amount="620.00")])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert "claims         1" in reply.output
    assert "worth a letter" in reply.output


def test_an_offer_the_tariff_cannot_price_is_kept_apart_from_the_findings(runner, env):
    # Says something about us and nothing about the supplier. Mixing the two
    # buries thirty letters under nine hundred rows of our own gaps.
    store(env, [offer()], [])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert "not priceable  1" in reply.output
    assert "claims         0" in reply.output


def test_the_ones_we_could_not_price_never_reach_a_group(runner, env):
    store(env, [offer(day=day) for day in (4, 5, 6)], [])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert "not priceable  3" in reply.output
    assert "noise          0" in reply.output
    assert "worth a letter" not in reply.output


def test_the_window_narrows_what_is_audited(runner, env):
    store(
        env,
        [
            offer(day=4, seen=datetime(2025, 7, 10, 6, 0)),
            offer(day=5, seen=datetime(2025, 7, 23, 6, 0)),
        ],
        [fare()],
    )
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-20")
    assert "1 offers since 2025-07-20" in reply.output


def test_the_window_defaults_to_the_day_before(runner, env):
    store(env, [offer(seen=datetime(2025, 7, 23, 6, 0))], [fare()])
    reply = run(runner, "audit", "--on", TODAY)
    assert "1 offers since 2025-07-23" in reply.output


def test_the_source_can_be_pinned(runner, env):
    store(
        env,
        [offer(source="gds", day=4), offer(source="ndc", day=5)],
        [fare()],
    )
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01", "--source", "ndc")
    assert "1 offers since" in reply.output


def test_the_limit_stops_the_run_early(runner, env):
    offers = [offer(day=day) for day in (4, 5, 6)]
    store(env, offers, [fare()])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01", "--limit", "2")
    assert "2 offers since" in reply.output


def test_the_number_of_cases_printed_can_be_capped(runner, env):
    offers = [offer(total=f"{640 + index * 10}.00", day=4 + index) for index in range(5)]
    store(env, offers, [fare()])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01", "--show", "1")
    assert reply.exit_code == 0


def test_a_supplier_below_our_price_is_our_gap_and_never_a_claim(runner, env):
    offers = [offer(total="500.00", day=day) for day in (4, 5, 6)]
    store(env, offers, [fare(amount="620.00")])
    reply = run(runner, "audit", "--on", TODAY, "--since", "2025-07-01")
    assert "claims         0" in reply.output
    assert "fares we do not hold, which is ours to fix" in reply.output


def test_a_quiet_run_prints_nothing_when_all_is_well(runner, env):
    store(env, [offer(total="620.00")], [fare(amount="620.00")])
    reply = run(runner, "--quiet", "audit", "--on", TODAY, "--since", "2025-07-01")
    assert reply.output.strip() == ""


def test_a_run_is_summarised_in_the_words_the_daily_mail_uses():
    made = [
        Audit(offer=offer(), finding=Finding.AGREES),
        Audit(offer=offer(), finding=Finding.SUPPLIER_HIGH),
        Audit(offer=offer(), finding=Finding.SUPPLIER_HIGH),
    ]
    assert summarise(made) == {"agreed": 1, "supplier_high": 2}


def test_summarising_nothing_gives_nothing():
    assert summarise([]) == {}
