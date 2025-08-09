"""One read of everything the pricing engine needs, taken at one moment.

Three commands were building this and a fourth was about to. They had drifted
already: the price command folded its city pairs and the audit command did not,
which meant a fare found by one was invisible to the other and the audit
reported the desk's own tariff as a market with nothing filed.

So it is one function. Everything the engine reads travels together by design,
because a price built from a fare store read at nine and a routing store read
at ten cannot be reproduced by anybody, and this is the only place that decides
what one moment means.

The mileage table is not read from the database. Maximum permitted mileages
change about once a decade and ship with the package, and an empty table is not
a table with no rows in it, it is a mileage check that fails on a city pair it
has never heard of. That is a different failure from a market with no fare and
it must not be reachable by forgetting to load something.
"""

from __future__ import annotations

from collections.abc import Sequence
from datetime import date

from gussetline.distance import default_mileage_table
from gussetline.engine import Tariffs
from gussetline.itinerary import Itinerary
from gussetline.offers import Offer
from gussetline.persistence.unitofwork import UnitOfWork
from gussetline.tariff import TariffStore, filing_pairs


def take(
    work: UnitOfWork,
    carriers: Sequence[str],
    points: Sequence[str],
    on: date,
) -> Tariffs:
    """Everything a price over these carriers and points could possibly read.

    Narrow on purpose. Reading the whole fares table works on a laptop and
    stops working the week the second carrier is loaded, and the queries here
    are the ones the indexes were built for.
    """
    wanted = list(dict.fromkeys(code.upper() for code in carriers if code))
    pairs = filing_pairs(points)

    store = TariffStore()
    for carrier in wanted:
        for origin, destination in pairs:
            store.add_many(work.tariff.search(carrier, origin, destination, on))

    return Tariffs(
        fares=store,
        miles=default_mileage_table(),
        combinability=work.combinability.store_for(wanted),
        rules=work.rules.store_for(wanted),
    )


def for_itinerary(
    work: UnitOfWork,
    itinerary: Itinerary,
    on: date,
    carrier: str | None = None,
) -> Tariffs:
    """The snapshot one itinerary needs, with its own carriers worked out.

    A carrier may be named, which is what an agent pricing against one airline
    wants. Naming one does not narrow the points: a component can still end
    anywhere the journey touches.
    """
    carriers = [carrier] if carrier else list(itinerary.carriers)
    points = [itinerary.origin, *[e.destination for e in itinerary.elements]]
    return take(work, carriers, points, on)


def for_offers(work: UnitOfWork, offers: Sequence[Offer], on: date) -> Tariffs:
    """One snapshot for a whole run of offers.

    Read once for the run and not once per offer. An audit over a day touches
    a few thousand offers across a handful of carriers, and the cost of the
    single snapshot is that a fare filed halfway through is not seen until the
    next run. That is the right trade: an audit has to price everything
    against one tariff or its findings cannot be compared with each other.
    """
    carriers: list[str] = []
    points: list[str] = []
    for offer in offers:
        carriers.extend(code for code in offer.carriers if code)
        if offer.validating_carrier:
            carriers.append(offer.validating_carrier)
        points.extend([offer.origin, offer.destination])
    return take(work, carriers, points, on)
