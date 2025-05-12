"""The price command, which is the one that has to be right in front of people.

An agent types a sell entry, this prices it out of the tariff the desk holds,
and prints the fare calculation line. That line is the whole point. It is what
goes on the ticket, it is what a carrier's audit department reads back, and it
is the only output here that anybody else can check.

Two decisions in how it prints.

A price with a combinability refusal on it is still printed. It cannot be sold,
and it says so, but hiding it is the wrong call: the desk is looking at it
because a supplier quoted something and the question is what the supplier must
have done differently. A blank screen answers nothing.

And the trail is off by default. Every price records twenty or thirty steps and
an agent pricing forty entries an hour does not want them. The flag turns them
on, and the failures are printed either way, because a refusal with no reason
next to it is the one thing nobody can act on.

Where the fares are broken is asked for rather than searched for. Finding the
cheapest split is a search over every combination and it belongs above this,
so the command takes the breaks it is given and prices that split. Comparing
two of them is what the search command will do when there is one.
"""

from __future__ import annotations

from datetime import date, datetime

import click

from gussetline.cli.main import Desk, pass_desk
from gussetline.distance import default_mileage_table
from gussetline.engine import Priced, Tariffs, price
from gussetline.errors import GussetlineError
from gussetline.exchange import Converter, default_roe_table
from gussetline.geography import city_of
from gussetline.itinerary import Itinerary
from gussetline.spec import parse, render
from gussetline.tariff import TariffStore


@click.command("price")
@click.argument("entry")
@click.option("--on", "today", type=click.DateTime(["%Y-%m-%d"]), default=None)
@click.option("--carrier", default=None, help="Price against one carrier's fares.")
@click.option("--breaks", default="", help="Where the components split, as 1,3.")
@click.option("--why", is_flag=True, help="Print every step the price took.")
@pass_desk
def price_it(
    desk: Desk,
    entry: str,
    today: datetime | None,
    carrier: str | None,
    breaks: str,
    why: bool,
) -> None:
    """Price a sell entry out of the tariff."""
    when = today.date() if today else date.today()
    itinerary = parse(entry, when)
    desk.say(render(itinerary))

    tariffs = _tariffs(desk, itinerary, when, carrier)
    converter = Converter(default_roe_table())

    priced = price(
        itinerary,
        tariffs,
        converter,
        when,
        desk.settings.selling_currency,
        breaks=_breaks(breaks),
    )

    _show(desk, priced, why)
    if not priced.issuable:
        raise SystemExit(1)


def _tariffs(desk: Desk, itinerary: Itinerary, when: date, carrier: str | None) -> Tariffs:
    """Pull the fares this itinerary could possibly use, and no others.

    Every carrier on the itinerary is asked for, city pair by city pair, and
    the sale window is applied in the query rather than afterwards. Reading
    the whole fares table for one entry works on a laptop and stops working
    the week the second carrier is loaded.
    """
    store = TariffStore()
    wanted = list(dict.fromkeys([carrier.upper()] if carrier else list(itinerary.carriers)))

    with desk.database.unit() as work:
        for code in wanted:
            for origin, destination in _pairs(itinerary):
                store.add_many(work.tariff.search(code, origin, destination, when))
        joins = work.combinability.store_for(wanted)

    # The maximum permitted mileages ship with the package rather than coming
    # out of the database. A fare whose routing map is missing prices on
    # mileage instead, so an empty table does not refuse the fare, it makes
    # the check itself fail on a city pair it has never heard of.
    return Tariffs(fares=store, miles=default_mileage_table(), combinability=joins)


def _pairs(itinerary: Itinerary) -> list[tuple[str, str]]:
    """Every city pair a fare could be filed for on this itinerary.

    Cities and not airports, because the tariff files FRA to NYC and the
    itinerary says JFK. Asking for the airport finds nothing and the entry
    comes back as though no fare were published, which is the same answer a
    genuinely uncovered market gives and looks nothing like a bug.

    Both directions, and every point against every other, because a fare
    component can end anywhere the itinerary touches and the tariff files
    each direction separately.
    """
    points = [
        city_of(point) for point in [itinerary.origin, *[e.destination for e in itinerary.elements]]
    ]
    pairs: list[tuple[str, str]] = []
    for left in points:
        for right in points:
            if left != right:
                pairs.append((left, right))
    return list(dict.fromkeys(pairs))


def _breaks(raw: str) -> tuple[int, ...] | None:
    if not raw.strip():
        return None
    try:
        return tuple(int(part) for part in raw.replace(",", " ").split())
    except ValueError as exc:
        raise click.BadParameter("breaks are element numbers, as 1,3") from exc


def _show(desk: Desk, priced: Priced, why: bool) -> None:
    desk.say("")
    for component in priced.construction.components:
        fare = component.fare
        basis = fare.fare_basis if fare is not None else "no fare"
        desk.say(f"  {component.origin}{component.destination:<4} {basis:<10} {component.nuc}")

    desk.say("")
    desk.say(priced.line)
    desk.say(f"base fare {priced.base_fare}")
    desk.say(f"total nuc {priced.total_nuc}")

    if why:
        for step in priced.trace:
            desk.say(f"  {step}")
    else:
        for step in priced.trace.failures():
            desk.warn(f"  {step}")

    for refusal in priced.refusals:
        desk.warn(f"cannot be issued: {refusal}")


def price_or_say(desk: Desk, entry: str, when: date) -> None:
    """Price and print, turning a refusal into a line rather than a stack.

    Used by the commands that price a batch, where one entry the tariff
    cannot cover must not take the other thirty with it.
    """
    try:
        itinerary = parse(entry, when)
        tariffs = _tariffs(desk, itinerary, when, None)
        priced = price(
            itinerary,
            tariffs,
            Converter(default_roe_table()),
            when,
            desk.settings.selling_currency,
        )
    except GussetlineError as exc:
        desk.warn(f"{entry}: {exc.message}")
        return
    desk.say(f"{entry}: {priced}")
