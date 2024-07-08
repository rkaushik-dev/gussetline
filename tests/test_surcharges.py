from __future__ import annotations

from datetime import date
from decimal import Decimal

import pytest

from conftest import fare, segment
from gussetline.components import FareComponent
from gussetline.construction import price_component
from gussetline.errors import ValidationError
from gussetline.money import Money
from gussetline.surcharges import (
    QSurcharge,
    Scope,
    SurchargeTable,
    apply_all,
    assess_component,
    total_nuc,
)
from gussetline.trace import Stage, Trace

TICKETING = date(2025, 2, 14)


def charge(**over) -> QSurcharge:
    base = {
        "carrier": "LH",
        "origin": "FRA",
        "destination": "JFK",
        "amount": Money.of("30.00", "EUR"),
    }
    base.update(over)
    return QSurcharge(**base)


def direct():
    return FareComponent(
        (segment("LH", "400", "FRA", "JFK", "2025-03-04 10:25", "2025-03-04 13:05"),)
    )


def connecting():
    return FareComponent(
        (
            segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
            segment("LH", "400", "LHR", "JFK", "2025-03-04 11:05", "2025-03-04 14:00"),
        )
    )


def test_a_surcharge_folds_its_market_to_cities():
    assert charge(origin="fra", destination="ewr").destination == "NYC"


def test_a_surcharge_covers_a_market_and_not_a_point():
    with pytest.raises(ValidationError):
        charge(destination="FRA")


def test_a_surcharge_is_positive():
    with pytest.raises(ValidationError):
        charge(amount=Money.of("0", "EUR"))


def test_a_travel_window_that_closes_before_it_opens_is_refused():
    with pytest.raises(ValidationError):
        charge(first_travel=date(2025, 6, 1), last_travel=date(2025, 5, 1))


def test_a_surcharge_reads_either_way_round_unless_it_is_directional():
    both = charge()
    assert both.covers("FRA", "JFK")
    assert both.covers("JFK", "FRA")

    one_way = charge(directional=True)
    assert one_way.covers("FRA", "JFK")
    assert not one_way.covers("JFK", "FRA")


def test_a_surcharge_reads_back_as_its_market_and_amount():
    assert str(charge()) == "Q FRANYC 30.00 EUR"


def test_the_table_indexes_by_carrier():
    table = SurchargeTable()
    table.add(charge())
    table.add(charge(carrier="BA"))
    assert len(table.for_carrier("LH")) == 1
    assert len(table.for_carrier("ba")) == 1
    assert table.for_carrier("AF") == ()
    assert len(table) == 2


def test_a_component_surcharge_is_charged_once_however_many_sectors(flat_converter):
    table = SurchargeTable()
    table.add(charge())
    priced = price_component(connecting(), fare(), flat_converter, TICKETING)
    applied = assess_component(priced, table, flat_converter, TICKETING)
    assert len(applied) == 1
    assert applied[0].times == 1
    assert applied[0].nuc == Decimal("30.00")


def test_a_sector_surcharge_is_charged_for_each_matching_sector(flat_converter):
    table = SurchargeTable()
    table.add(charge(origin="FRA", destination="LHR", scope=Scope.SECTOR))
    priced = price_component(connecting(), fare(), flat_converter, TICKETING)
    applied = assess_component(priced, table, flat_converter, TICKETING)
    assert applied[0].times == 1
    assert applied[0].nuc == Decimal("30.00")


def test_a_sector_surcharge_doubles_when_two_sectors_match(flat_converter):
    elements = (
        segment("LH", "992", "FRA", "LHR", "2025-03-04 07:00", "2025-03-04 07:40"),
        segment("LH", "993", "LHR", "FRA", "2025-03-06 09:00", "2025-03-06 11:35"),
    )
    table = SurchargeTable()
    table.add(charge(origin="FRA", destination="LHR", scope=Scope.SECTOR))
    priced = price_component(
        FareComponent(elements), fare(destination="LHR"), flat_converter, TICKETING
    )
    applied = assess_component(priced, table, flat_converter, TICKETING)
    assert applied[0].times == 2
    assert applied[0].nuc == Decimal("60.00")


def test_a_sector_surcharge_that_matches_nothing_is_not_charged(flat_converter):
    table = SurchargeTable()
    table.add(charge(origin="AMS", destination="JFK", scope=Scope.SECTOR))
    priced = price_component(connecting(), fare(), flat_converter, TICKETING)
    assert assess_component(priced, table, flat_converter, TICKETING) == ()


def test_a_component_surcharge_reads_the_ends_and_not_the_sectors(flat_converter):
    # The component is Frankfurt to New York through London. A surcharge filed
    # on Frankfurt to London does not attach, because the component is not a
    # Frankfurt to London component.
    table = SurchargeTable()
    table.add(charge(origin="FRA", destination="LHR"))
    priced = price_component(connecting(), fare(), flat_converter, TICKETING)
    assert assess_component(priced, table, flat_converter, TICKETING) == ()


def test_a_surcharge_outside_its_travel_window_is_not_charged(flat_converter):
    table = SurchargeTable()
    table.add(charge(first_travel=date(2025, 6, 1), last_travel=date(2025, 9, 30)))
    priced = price_component(direct(), fare(), flat_converter, TICKETING)
    assert assess_component(priced, table, flat_converter, TICKETING) == ()


def test_a_surcharge_inside_its_travel_window_is_charged(flat_converter):
    table = SurchargeTable()
    table.add(charge(first_travel=date(2025, 3, 1), last_travel=date(2025, 3, 31)))
    priced = price_component(direct(), fare(), flat_converter, TICKETING)
    assert len(assess_component(priced, table, flat_converter, TICKETING)) == 1


def test_another_carriers_filing_does_not_apply(flat_converter):
    table = SurchargeTable()
    table.add(charge(carrier="BA"))
    priced = price_component(direct(), fare(), flat_converter, TICKETING)
    assert assess_component(priced, table, flat_converter, TICKETING) == ()


def test_a_surcharge_check_runs_after_the_component_is_priced(flat_converter):
    with pytest.raises(ValidationError):
        assess_component(direct(), SurchargeTable(), flat_converter, TICKETING)


def test_every_charge_is_recorded(flat_converter):
    table = SurchargeTable()
    table.add(charge())
    table.add(charge(amount=Money.of("12.00", "EUR"), reason="QS"))
    trace = Trace()
    priced = price_component(direct(), fare(), flat_converter, TICKETING)
    assess_component(priced, table, flat_converter, TICKETING, trace)
    assert len(trace.for_stage(Stage.SURCHARGE)) == 2
    assert not trace.failed


def test_the_total_adds_the_charges_up(flat_converter):
    table = SurchargeTable()
    table.add(charge())
    table.add(charge(amount=Money.of("12.00", "EUR"), reason="QS"))
    priced = price_component(direct(), fare(), flat_converter, TICKETING)
    applied = assess_component(priced, table, flat_converter, TICKETING)
    assert total_nuc(applied) == Decimal("42.00")
    assert total_nuc([]) == Decimal(0)


def test_applying_the_charges_puts_each_under_its_own_note(flat_converter):
    table = SurchargeTable()
    table.add(charge())
    table.add(charge(amount=Money.of("12.00", "EUR"), reason="QS"))
    priced = price_component(direct(), fare(), flat_converter, TICKETING)
    applied = assess_component(priced, table, flat_converter, TICKETING)
    charged = apply_all(priced, applied)
    assert charged.notes == ("Q", "QS")
    assert charged.total_nuc == Decimal("462.00")


def test_an_applied_charge_reads_back_with_its_count(flat_converter):
    table = SurchargeTable()
    table.add(charge(origin="FRA", destination="LHR", scope=Scope.SECTOR))
    priced = price_component(connecting(), fare(), flat_converter, TICKETING)
    applied = assess_component(priced, table, flat_converter, TICKETING)
    assert str(applied[0]) == "Q 30.00 NUC"
