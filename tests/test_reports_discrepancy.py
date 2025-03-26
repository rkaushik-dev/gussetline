from __future__ import annotations

from decimal import Decimal

from conftest import at, segment
from gussetline.audit import Audit, Finding
from gussetline.itinerary import Itinerary
from gussetline.money import Money
from gussetline.offers import Offer, PriceBreakdown
from gussetline.reports.discrepancy import (
    CASE_THRESHOLD,
    build,
    key_for,
    render,
)

SEEN = at("2025-07-16 07:00")


def offer(
    source="gds",
    origin="FRA",
    destination="JFK",
    carrier="LH",
    basis="KLWAP7RT",
    total="900.00",
    currency="EUR",
) -> Offer:
    return Offer(
        source=source,
        itinerary=Itinerary(
            (
                segment(
                    carrier,
                    "400",
                    origin,
                    destination,
                    "2025-08-04 10:00",
                    "2025-08-04 12:40",
                ),
            )
        ),
        breakdown=PriceBreakdown(base=Money.of(total, currency)),
        fare_bases=(basis,),
        observed_at=SEEN,
        validating_carrier=carrier,
    )


def finding(
    kind=Finding.SUPPLIER_HIGH,
    gap="3.00",
    currency="EUR",
    **over,
) -> Audit:
    made = offer(currency=currency, **over)
    return Audit(
        offer=made,
        finding=kind,
        ours=Money.of("897.00", currency),
        theirs=Money.of("900.00", currency),
        difference=Money.of(gap, currency),
    )


def agreed(**over) -> Audit:
    made = offer(**over)
    return Audit(offer=made, finding=Finding.AGREES, difference=Money.zero(made.currency))


def test_nothing_audited_reads_as_nothing():
    report = build([], "EUR")
    assert report.audited == 0
    assert report.claims == []
    assert report.claim_value == Money.zero("EUR")


def test_the_ones_that_agree_are_counted_and_go_no_further():
    report = build([agreed(), agreed(), finding()], "EUR")
    assert report.audited == 3
    assert report.agreed == 2
    assert report.noise == 1


def test_one_offer_on_its_own_is_noise_and_not_a_letter():
    report = build([finding()], "EUR")
    assert report.claims == []
    assert report.noise == 1
    assert len(report.singles) == 1


def test_enough_offers_agreeing_makes_it_a_case():
    report = build([finding() for _ in range(CASE_THRESHOLD)], "EUR")
    assert len(report.claims) == 1
    case = report.claims[0]
    assert case.offers == CASE_THRESHOLD
    assert case.worth == Money.of("9.00", "EUR")
    assert report.noise == 0


def test_offers_that_disagree_about_the_size_do_not_group():
    # Same carrier, same market, same fare, but one is three euros out and the
    # other is ninety. Those are two problems and grouping them hides both.
    report = build([finding(gap="3.00"), finding(gap="3.00"), finding(gap="90.00")], "EUR")
    assert report.claims == []
    assert len(report.singles) == 2


def test_a_gap_is_rounded_before_it_is_compared():
    rows = [finding(gap="3.10"), finding(gap="3.40"), finding(gap="2.90")]
    report = build(rows, "EUR")
    assert len(report.claims) == 1
    assert report.claims[0].key.gap == Decimal("3")


def test_the_worth_is_the_real_amounts_and_not_the_rounded_ones():
    rows = [finding(gap="3.10"), finding(gap="3.40"), finding(gap="2.90")]
    assert build(rows, "EUR").claims[0].worth == Money.of("9.40", "EUR")


def test_two_carriers_are_two_groups_however_alike_the_gap_is():
    rows = [finding(carrier="LH") for _ in range(3)] + [finding(carrier="UA") for _ in range(3)]
    report = build(rows, "EUR")
    assert len(report.claims) == 2
    assert {case.key.carrier for case in report.claims} == {"LH", "UA"}


def test_two_markets_are_two_groups():
    rows = [finding(destination="JFK") for _ in range(3)]
    rows += [finding(destination="ORD") for _ in range(3)]
    assert len(build(rows, "EUR").claims) == 2


def test_two_fare_bases_are_two_groups():
    rows = [finding(basis="KLWAP7RT") for _ in range(3)]
    rows += [finding(basis="MLWAP7RT") for _ in range(3)]
    assert len(build(rows, "EUR").claims) == 2


def test_two_suppliers_are_two_groups_because_the_letters_go_to_two_places():
    rows = [finding(source="gds") for _ in range(3)] + [finding(source="ndc") for _ in range(3)]
    assert {case.key.source for case in build(rows, "EUR").claims} == {"gds", "ndc"}


def test_a_supplier_coming_in_low_is_our_gap_and_never_a_claim():
    rows = [finding(kind=Finding.SUPPLIER_LOW) for _ in range(4)]
    report = build(rows, "EUR")
    assert report.claims == []
    assert len(report.our_gaps) == 1
    assert report.our_gaps[0].offers == 4


def test_a_tax_difference_groups_but_is_not_a_claim_either():
    rows = [finding(kind=Finding.TAX_DIFFERENCE) for _ in range(3)]
    report = build(rows, "EUR")
    assert report.claims == []
    assert len(report.cases) == 1


def test_something_that_would_not_price_is_counted_and_set_aside():
    report = build([Audit(offer=offer(), finding=Finding.NOT_PRICEABLE)], "EUR")
    assert report.not_priceable == 1
    assert report.cases == []


def test_a_gap_too_small_to_chase_never_reaches_a_group():
    rows = [finding(gap="0.40") for _ in range(5)]
    report = build(rows, "EUR")
    assert report.cases == []
    assert report.noise == 0


def test_another_currency_is_set_aside_rather_than_converted():
    # Converting here would use today's rate on a fare quoted in March, and
    # the resulting total reconciles against nothing.
    rows = [finding(currency="GBP") for _ in range(4)]
    report = build(rows, "EUR")
    assert report.other_currency == 4
    assert report.cases == []
    assert report.claim_value == Money.zero("EUR")


def test_the_claim_value_only_counts_the_groups_big_enough_to_send():
    rows = [finding() for _ in range(3)] + [finding(gap="50.00")]
    report = build(rows, "EUR")
    assert report.claim_value == Money.of("9.00", "EUR")


def test_the_biggest_groups_come_first():
    rows = [finding(carrier="LH") for _ in range(5)] + [finding(carrier="UA") for _ in range(3)]
    assert [case.key.carrier for case in build(rows, "EUR").cases] == ["LH", "UA"]


def test_a_key_says_what_would_have_to_match():
    key = key_for(finding())
    assert key.source == "gds"
    assert key.carrier == "LH"
    assert key.market == "FRA-JFK"
    assert key.fare_basis == "KLWAP7RT"
    assert str(key) == "gds LH FRA-JFK KLWAP7RT off by 3"


def test_a_case_reads_back_with_its_size_and_its_worth():
    case = build([finding() for _ in range(3)], "EUR").claims[0]
    assert str(case) == "gds LH FRA-JFK KLWAP7RT off by 3 on 3 offers worth 9.00 EUR"


def test_the_report_reads_back_with_the_three_numbers_that_matter():
    report = build([finding() for _ in range(3)] + [finding(gap="70.00")], "EUR")
    assert str(report) == "4 audited, 1 claims worth 9.00 EUR, 1 left as noise"


def test_the_rendered_report_separates_the_letters_from_our_own_gaps():
    rows = [finding() for _ in range(3)]
    rows += [finding(kind=Finding.SUPPLIER_LOW, carrier="UA") for _ in range(3)]
    out = render(build(rows, "EUR"))
    assert "worth a letter" in out
    assert "fares we do not hold" in out
    assert "claims         1 worth 9.00 EUR" in out


def test_the_rendered_report_leaves_out_the_sections_it_has_nothing_for():
    out = render(build([agreed()], "EUR"))
    assert "worth a letter" not in out
    assert "fares we do not hold" not in out
    assert "audited        1" in out


def test_the_rendered_report_honours_the_limit():
    rows = []
    for index in range(6):
        rows += [finding(basis=f"KLWAP7R{index}") for _ in range(3)]
    out = render(build(rows, "EUR"), limit=2)
    assert out.count("worth 9.00 EUR") == 2
