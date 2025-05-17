"""The audit command, which is the one that pays for the rest of this.

Every offer a collector stored is repriced against the tariff the desk holds,
the disagreements are grouped, and what comes out is a short list of letters
worth writing. That is the whole product.

The part that needed deciding is what an offer the tariff cannot price at all
should do. It is not a finding. A market with no fare loaded, a fare basis the
carrier withdrew last week, a construction the engine has no rule for: all of
those say something about us and nothing about the supplier, and putting them
in with the real disagreements buries thirty letters under nine hundred rows
of our own gaps. So they are counted, kept apart, and printed under their own
heading.

The tariff is read once for the whole run and not once per offer. An audit
over a day touches a few thousand offers across a handful of carriers, and
loading the fares for each one turns a two second job into a twenty minute
one. The cost is that a fare filed halfway through the run is not seen until
the next one, which is right: an audit has to price everything against one
snapshot or the findings cannot be compared with each other.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta

import click

from gussetline.audit import Audit, Finding, audit
from gussetline.cli.desk import Desk, pass_desk
from gussetline.distance import default_mileage_table
from gussetline.engine import Tariffs
from gussetline.errors import GussetlineError
from gussetline.exchange import Converter, default_roe_table
from gussetline.offers import Offer
from gussetline.reports.discrepancy import build, render
from gussetline.tariff import TariffStore, filing_pairs

# How far back an audit looks when nobody says.
DEFAULT_WINDOW_DAYS = 1


@click.command("audit")
@click.option("--since", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--source", default=None, help="Only this supplier's offers.")
@click.option("--limit", type=int, default=None, help="Stop after this many offers.")
@click.option("--show", type=int, default=10, help="How many cases to print.")
@pass_desk
def audit_them(
    desk: Desk,
    since: datetime | None,
    today: datetime | None,
    source: str | None,
    limit: int | None,
    show: int,
) -> None:
    """Reprice what the collectors stored and group the disagreements."""
    when = today.date() if today else date.today()
    first = (
        since
        if since
        else datetime.combine(when, datetime.min.time()) - timedelta(days=DEFAULT_WINDOW_DAYS)
    )

    with desk.database.unit() as work:
        offers = work.observations.offers_since(first, source, limit)

    if not offers:
        desk.say(f"nothing stored since {first:%Y-%m-%d}")
        return

    desk.say(f"{len(offers)} offers since {first:%Y-%m-%d}")
    tariffs = _tariffs(desk, offers, when)
    converter = Converter(default_roe_table())

    audits: list[Audit] = []
    unpriceable: list[tuple[Offer, str]] = []
    for offer in offers:
        try:
            audits.append(audit(offer, tariffs, converter, when))
        except GussetlineError as exc:
            unpriceable.append((offer, exc.message))

    report = build(audits, desk.settings.selling_currency)
    desk.say("")
    desk.say(render(report, limit=show))
    _our_gaps(desk, unpriceable, show)


def _tariffs(desk: Desk, offers: list[Offer], when: date) -> Tariffs:
    """One snapshot of the tariff for the whole run.

    Every carrier that appears on any offer, and every city pair any of them
    touched. Read once, because an audit that reprices against a moving
    tariff produces findings that cannot be compared with each other.
    """
    carriers: list[str] = []
    points: list[str] = []
    for offer in offers:
        carriers.extend(c for c in offer.carriers if c)
        if offer.validating_carrier:
            carriers.append(offer.validating_carrier)
        points.extend([offer.origin, offer.destination])

    store = TariffStore()
    wanted = list(dict.fromkeys(c.upper() for c in carriers))
    pairs = filing_pairs(points)
    with desk.database.unit() as work:
        for code in wanted:
            for origin, destination in pairs:
                store.add_many(work.tariff.search(code, origin, destination, when))
        joins = work.combinability.store_for(wanted)

    return Tariffs(fares=store, miles=default_mileage_table(), combinability=joins)


def _our_gaps(desk: Desk, unpriceable: list[tuple[Offer, str]], show: int) -> None:
    """The offers we could not price, which are a reading about us."""
    if not unpriceable:
        return
    desk.say("")
    desk.say(f"{len(unpriceable)} offers the tariff could not price, which is ours to fix")
    reasons: dict[str, int] = {}
    for _, reason in unpriceable:
        reasons[reason] = reasons.get(reason, 0) + 1
    for reason, count in sorted(reasons.items(), key=lambda row: -row[1])[:show]:
        desk.say(f"  {count:>5}  {reason}")


def summarise(audits: list[Audit]) -> dict[str, int]:
    """How a run came out, in the words the daily mail uses."""
    counts: dict[str, int] = {}
    for item in audits:
        key = item.finding.value if item.finding is not Finding.AGREES else "agreed"
        counts[key] = counts.get(key, 0) + 1
    return counts
