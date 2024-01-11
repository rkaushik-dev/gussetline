# gussetline

Fare audit and reprice desk for a small airfare consolidator.

Supplier feeds are polled on a politeness budget, their payloads archived raw
and normalised into offers, and every offer repriced from the tariff so the
desk can tell a real fare from a quoted number. Ticketed itineraries carry
watches, and a watch that finds a drop worth more than the penalty raises a
reissue recommendation.

## Layout

    src/gussetline/     the package
    tests/              the suite

## Running the suite

    pip install -e ".[dev]"
    pytest
