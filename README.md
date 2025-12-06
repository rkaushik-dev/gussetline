# gussetline

Fare audit and reprice desk for a small airfare consolidator.

Supplier feeds are polled on a politeness budget, their payloads archived raw
and normalised into offers, and every offer repriced from the tariff so the desk
can tell a real fare from a quoted number. Ticketed itineraries carry watches,
and a watch that finds a drop worth more than the penalty raises a reissue
recommendation for an agent to phone about.

## Ten minutes from a clean checkout

    pip install -e ".[dev]"
    gussetline create-schema
    gussetline seed
    gussetline night --dry-run
    gussetline report queue

`seed` writes an obviously invented carrier, a fortnight of readings ending in a
drop, and two watched tickets, so every other command has something to say. It
refuses to run against a database that already holds anything.

## What the commands do

| command | what it is for |
| --- | --- |
| `load fares` / `load rules` / `load combinability` | take a filing file in |
| `collect plan` / `run` / `replay` | poll the suppliers, or say what polling would do |
| `price` | price a sell entry and print the fare calculation line |
| `audit` | reprice what the collectors stored and group the disagreements |
| `night` | reprice every live watch and raise what is worth raising |
| `watch add` / `list` / `retire` | put a ticket on the desk, or take it off |
| `report savings` / `reliability` / `coverage` / `queue` | read history, write nothing |
| `report filing CARRIER` | what moved between a carrier's last two filings |
| `prune` | drop what has aged out, dry by default |
| `serve` / `issue-key` | run the service and issue the keys into it |

Everything takes `--on` so a run can be reproduced against a past day, and
`--quiet` so cron mail stays empty on a good night.

## How a price is built

The ladder runs in one order and the order is the whole thing:

1. side trips come out, so an excursion is not on the mileage path
2. the journey is split into fare components at its breaks
3. a fare is selected at its filed amount, with no band on it
4. the higher intermediate point and backhaul checks run, and may replace it
5. the mileage band applies to whichever fare ended up governing
6. Q surcharges go on
7. components are grouped into pricing units and combinability is checked
8. unit minimums apply
9. one conversion into the selling currency, and the line is written

Putting the band on at selection time instead surcharges every component the
minimum checks touch on the wrong base. It is a small number, and it is wrong in
the supplier's favour, which is why nobody notices.

## Exit codes

Nought is success. One is something the operator can fix tonight: a market with
no fares filed, a ticket number that does not exist, a setting that is not a
number. Two is something they cannot: a supplier is down, the database is
unreachable, a payload did not parse.

These run under cron. A command line that returns one code for both produces
either a pager that goes off every night or one that never goes off.

## Configuration

Everything is read from the environment at start up, prefixed `GUSSETLINE_`.
`gussetline settings` prints what a run would use, which is the first thing to
check when a run does something surprising.

    GUSSETLINE_DATABASE_URL      where the desk lives
    GUSSETLINE_ARCHIVE_ROOT      where raw payloads are kept
    GUSSETLINE_SELLING_CURRENCY  what the desk quotes in
    GUSSETLINE_DAILY_QUOTA       calls per supplier per day
    GUSSETLINE_API_KEYS          name:role:secret, comma separated

No keys set means the service lets everything through, which is right on a
laptop and wrong anywhere else. `gussetline issue-key` makes one and shows it
once; what is stored is a digest, so a dump of the table is a list of names.

## Layout

    src/gussetline/             the domain: money, geography, fares, rules
    src/gussetline/collect/     polling, parsing, archiving
    src/gussetline/load/        reading filing files into the tariff
    src/gussetline/desk/        tickets, watches, recommendations, the night run
    src/gussetline/reports/     savings, reliability, coverage, discrepancies
    src/gussetline/persistence/ rows, mappers and the queries
    src/gussetline/api/         the read mostly service
    src/gussetline/cli/         the commands cron calls
    tests/                      the suite, one file per module

## Running the suite

    pytest

It splits across cores by file. `tests/test_a_week_on_the_desk.py` drives a
whole week through the command line against one database on disk, and it is the
one to read first: every part below it is tested on its own, and the ways a desk
actually breaks are all in the joins between them.

## Migrations

    alembic upgrade head

The suite compares the migrated schema against the models and fails on any
difference, so a column added without a migration is a named failure rather than
one red cross among a thousand.
