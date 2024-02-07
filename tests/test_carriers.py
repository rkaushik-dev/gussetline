from __future__ import annotations

import pytest

from gussetline.carriers import (
    CARRIERS,
    Alliance,
    alliance_of,
    carrier,
    interlines,
    is_known,
    low_cost_carriers,
    members,
    normalise_code,
    same_alliance,
)
from gussetline.errors import ValidationError
from gussetline.geography import COUNTRIES


def test_every_carrier_names_a_country_we_hold():
    assert sorted({c.country for c in CARRIERS.values()} - set(COUNTRIES)) == []


@pytest.mark.parametrize("code", ["lh", " LH ", "LH"])
def test_codes_normalise(code):
    assert normalise_code(code) == "LH"


@pytest.mark.parametrize("code", ["L", "LHX", "L-", "", "l h"])
def test_a_designator_that_is_not_two_characters_is_refused(code):
    with pytest.raises(ValidationError):
        normalise_code(code)


def test_a_numeric_designator_is_still_a_designator():
    assert normalise_code("6e") == "6E"
    assert carrier("6E").name == "IndiGo"
    assert carrier("U2").low_cost


def test_is_known_separates_a_bad_shape_from_an_unlisted_carrier():
    assert is_known("LH")
    assert not is_known("ZZ")
    assert not is_known("nonsense")


def test_unknown_carrier_names_what_was_asked_for():
    with pytest.raises(ValidationError) as caught:
        carrier("ZZ")
    assert caught.value.details["code"] == "ZZ"


def test_alliance_lookup():
    assert alliance_of("LH") is Alliance.STAR
    assert alliance_of("BA") is Alliance.ONEWORLD
    assert alliance_of("AF") is Alliance.SKYTEAM
    assert alliance_of("EK") is Alliance.NONE


def test_is_aligned_is_false_only_for_the_unaligned():
    assert carrier("LH").is_aligned
    assert not carrier("EK").is_aligned


def test_same_alliance_needs_both_sides_to_be_in_one():
    assert same_alliance("LH", "UA")
    assert same_alliance("BA", "QF")
    assert not same_alliance("LH", "BA")


def test_two_unaligned_carriers_do_not_share_an_alliance_of_nobody():
    assert not same_alliance("EK", "EY")
    assert not same_alliance("EK", "EK")


def test_a_carrier_always_interlines_with_itself():
    assert interlines("LH", "LH")
    assert interlines("EK", "ek")


def test_alliance_membership_implies_an_agreement():
    assert interlines("LH", "SQ")
    assert interlines("AA", "QR")


def test_the_paper_list_covers_pairs_no_alliance_does():
    assert interlines("LH", "EK")
    assert interlines("EK", "LH")
    assert interlines("VS", "DL")


def test_a_pair_on_neither_list_does_not_interline():
    assert not interlines("EK", "EY")
    assert not interlines("FR", "LH")
    assert not interlines("U2", "BA")


def test_interline_refuses_a_shape_that_is_not_a_designator():
    with pytest.raises(ValidationError):
        interlines("LHX", "BA")


def test_members_lists_an_alliance_sorted():
    star = members(Alliance.STAR)
    assert star == tuple(sorted(star))
    assert "LH" in star
    assert "BA" not in star


def test_unaligned_carriers_are_reachable_as_a_group():
    assert "EK" in members(Alliance.NONE)


def test_low_cost_carriers_are_marked_and_none_of_them_are_aligned():
    lccs = low_cost_carriers()
    assert "FR" in lccs
    assert "LH" not in lccs
    for code in lccs:
        assert not carrier(code).is_aligned
