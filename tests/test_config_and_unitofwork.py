from __future__ import annotations

from datetime import datetime
from decimal import Decimal
from pathlib import Path

import pytest
from sqlalchemy import select
from sqlalchemy.exc import IntegrityError

from conftest import segment
from gussetline.config import Settings, from_env
from gussetline.errors import ConfigError
from gussetline.geography import airport
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.persistence.models import AirportRow
from gussetline.persistence.unitofwork import in_memory

SEEN = datetime(2025, 6, 15, 9, 0)


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


def test_the_class_defaults_are_readable_values_and_not_descriptors():
    # Under slots these come back as member descriptors and every fallback in
    # the reader below turns into an unparseable object.
    assert Settings.selling_currency == "EUR"
    assert Settings.daily_quota == 1000
    assert Settings.honour_robots is True


def test_the_defaults_are_a_working_configuration():
    settings = Settings()
    assert settings.is_sqlite
    assert settings.selling_currency == "EUR"
    assert settings.daily_quota == 1000


def test_an_empty_environment_gives_the_defaults():
    assert from_env({}) == Settings()


def test_a_setting_is_read_under_its_prefix():
    settings = from_env({"GUSSETLINE_SELLING_CURRENCY": "gbp"})
    assert settings.selling_currency == "GBP"


def test_a_number_that_is_not_one_names_the_setting():
    with pytest.raises(ConfigError) as caught:
        from_env({"GUSSETLINE_DAILY_QUOTA": "lots"})
    assert caught.value.details["setting"] == "GUSSETLINE_DAILY_QUOTA"


def test_a_rate_that_is_not_a_number_is_refused():
    with pytest.raises(ConfigError):
        from_env({"GUSSETLINE_RATE_PER_SECOND": "fast"})


def test_an_amount_that_is_not_one_is_refused():
    with pytest.raises(ConfigError):
        from_env({"GUSSETLINE_WATCH_THRESHOLD": "a lot"})


def test_a_yes_or_no_is_read_in_every_spelling():
    for text in ("1", "yes", "true", "on", "YES"):
        assert from_env({"GUSSETLINE_DRY_RUN": text}).dry_run
    for text in ("0", "no", "false", "off"):
        assert not from_env({"GUSSETLINE_DRY_RUN": text}).dry_run


def test_something_that_is_neither_a_yes_nor_a_no_is_refused():
    with pytest.raises(ConfigError):
        from_env({"GUSSETLINE_HONOUR_ROBOTS": "maybe"})


def test_a_quota_and_a_rate_have_to_make_sense():
    with pytest.raises(ConfigError):
        Settings(daily_quota=-1)
    with pytest.raises(ConfigError):
        Settings(rate_per_second=0)
    with pytest.raises(ConfigError):
        Settings(burst=0)
    with pytest.raises(ConfigError):
        Settings(retry_attempts=0)


def test_a_credit_is_worth_between_nothing_and_cash():
    with pytest.raises(ConfigError):
        Settings(credit_discount=Decimal("1.5"))
    assert Settings(credit_discount=Decimal(1)).credit_discount == Decimal(1)


def test_a_currency_code_is_three_letters():
    with pytest.raises(ConfigError):
        Settings(selling_currency="EURO")


def test_the_archive_root_comes_back_as_a_path():
    settings = from_env({"GUSSETLINE_ARCHIVE_ROOT": "/tmp/raw"})
    assert isinstance(settings.archive_root, Path)


def test_an_in_memory_url_is_recognised():
    assert Settings().with_database("sqlite+pysqlite:///:memory:").in_memory
    assert not Settings().in_memory


def test_pointing_at_another_database_keeps_every_other_setting():
    settings = Settings(selling_currency="GBP", daily_quota=42)
    moved = settings.with_database("sqlite+pysqlite:///:memory:")
    assert moved.selling_currency == "GBP"
    assert moved.daily_quota == 42
    assert moved.in_memory


def test_settings_read_back_in_one_line():
    assert str(Settings().with_database("sqlite+pysqlite:///:memory:")) == (
        "gussetline against memory, selling in EUR"
    )


def test_a_unit_that_commits_keeps_its_work():
    database = in_memory()
    with database.unit() as work:
        work.airports.save(airport("FRA"))
        work.commit()
    with database.unit() as work:
        assert work.airports.count() == 1


def test_a_unit_that_does_not_commit_keeps_nothing():
    database = in_memory()
    with database.unit() as work:
        work.airports.save(airport("FRA"))
    with database.unit() as work:
        assert work.airports.count() == 0


def test_a_unit_that_raises_rolls_back():
    database = in_memory()
    with pytest.raises(RuntimeError), database.unit() as work:
        work.airports.save(airport("FRA"))
        raise RuntimeError("something went wrong halfway")
    with database.unit() as work:
        assert work.airports.count() == 0


def test_every_repository_shares_the_one_session():
    database = in_memory()
    with database.unit() as work:
        assert work.airports.session is work.observations.session
        assert work.tariff.session is work.watches.session


def test_the_repositories_see_each_others_uncommitted_work():
    database = in_memory()
    with database.unit() as work:
        work.airports.save(airport("FRA"))
        work.flush()
        assert work.session.scalars(select(AirportRow)).one().code == "FRA"


def test_an_offer_written_through_a_unit_reads_back():
    database = in_memory()
    with database.unit() as work:
        work.observations.save(an_offer())
        work.commit()
    with database.unit() as work:
        assert work.observations.count() == 1


def test_two_units_do_not_share_state():
    database = in_memory()
    with database.unit() as first:
        first.airports.save(airport("FRA"))
        with database.unit() as second:
            assert second.airports.count() == 0


def test_a_database_reads_back_with_its_url():
    database = in_memory()
    assert "memory" in str(database)


def test_foreign_keys_are_enforced_on_sqlite():
    from gussetline.persistence.models import CouponRow

    database = in_memory()
    with database.unit() as work:
        work.session.add(
            CouponRow(
                ticket_id=999,
                position=0,
                carrier="LH",
                flight_number="400",
                origin="FRA",
                destination="JFK",
                departure=SEEN,
                booking_class="K",
                fare_basis="KLWAP7RT",
            )
        )
        with pytest.raises(IntegrityError):
            work.commit()


def test_a_database_can_be_disposed_of():
    database = in_memory()
    database.dispose()
