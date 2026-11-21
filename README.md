# gussetline

[![CI](https://github.com/rkaushik-dev/gussetline/actions/workflows/ci.yml/badge.svg)](https://github.com/rkaushik-dev/gussetline/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Maven](https://img.shields.io/badge/build-Maven-C71A36)
![JUnit 5](https://img.shields.io/badge/tests-JUnit%205-25A162)

Fare audit and reprice desk for a small airfare consolidator.

Supplier feeds are polled on a politeness budget, their payloads archived raw
and normalised into offers, and every offer repriced from the tariff so the desk
can tell a real fare from a quoted number. Ticketed itineraries carry watches,
and a watch that finds a drop worth more than the penalty raises a reissue
recommendation for an agent to phone about.

## Tech stack

- Java 21, built with Maven through the wrapper (`./mvnw`)
- Spring Boot 3.5 (Web MVC, Bean Validation) for the service, springdoc for its API document
- JPA (Hibernate) on H2, with the schema owned by Flyway migrations
- picocli for the command line, which never starts Spring
- JUnit 5, AssertJ and Spring's MockMvc for the suite; Spotless (palantir-java-format) and a
  JaCoCo line coverage gate of 80% run in every build

## Ten minutes from a clean checkout

    ./mvnw -B -DskipTests package
    java -jar target/gussetline-0.1.0.jar create-schema
    java -jar target/gussetline-0.1.0.jar seed
    java -jar target/gussetline-0.1.0.jar night --dry-run
    java -jar target/gussetline-0.1.0.jar report queue

`seed` writes an obviously invented carrier, a fortnight of readings ending in a
drop, and two watched tickets, so every other command has something to say. It
refuses to run against a database that already holds anything.

The examples below write `gussetline` for `java -jar target/gussetline-0.1.0.jar`.

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
| `settings` / `create-schema` / `check` | show the settings, build the schema, check the desk is fit to run |

Everything takes `--on` so a run can be reproduced against a past day, and
`--quiet` so cron mail stays empty on a good night. `gussetline --help` and
`gussetline <command> --help` describe every option.

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

    GUSSETLINE_DATABASE_URL      where the desk lives (a JDBC URL; jdbc:h2:file:./var/gussetline by default)
    GUSSETLINE_ARCHIVE_ROOT      where raw payloads are kept (var/raw by default)
    GUSSETLINE_SELLING_CURRENCY  what the desk quotes in
    GUSSETLINE_DAILY_QUOTA       calls per supplier per day
    GUSSETLINE_API_KEYS          name:role:secret, comma separated

`--database` on any command overrides the database URL for that run.

No keys set means the service lets everything through, which is right on a
laptop and wrong anywhere else. `gussetline issue-key` makes one and shows it
once; what is stored is a digest, so a dump of the table is a list of names.

## The service

    gussetline serve --host 127.0.0.1 --port 8000

`--port 0` takes any free port and prints the one it got. A key goes in the
`X-Api-Key` header; each route needs at least the role shown. The API document is
at `/openapi.json` and a browsable page at `/docs`.

| method | path | role | what it answers |
| --- | --- | --- | --- |
| GET | `/health` | none | up; says nothing about whether anything else works |
| GET | `/ready` | none | up and able to reach storage |
| GET | `/fit` | none | whether this desk can do its job, and what is missing if not |
| GET | `/observations/series/{series_key}` | reader | every offer in one series, cheapest first |
| GET | `/observations/market` | reader | the same, addressed by `origin`, `destination`, `departure` and `cabin` |
| POST | `/pricing` | reader | price the sectors given, from the tariff, and show the working |
| GET | `/queue` | reader | the open recommendations, in the order they should be worked |
| GET | `/watches` | reader | every live watch, closest departure first |
| GET | `/watches/ticket/{number}` | reader | every watch on one ticket |
| GET | `/watches/recommendations/{identifier}` | reader | one recommendation, with every number behind it |
| POST | `/watches/recommendations/{identifier}/take` | agent | the desk made the reissue |
| POST | `/watches/recommendations/{identifier}/kill` | agent | the desk looked and said no |
| GET | `/sources` | operator | every supplier, by name |
| GET | `/sources/{name}` | operator | one supplier, by name in any case |
| GET | `/sources/-/process` | operator | which process answered, because breaker state is per process |

## Layout

    src/main/java/io/github/rkaushikdev/gussetline/
      money, geography, carriers, ...   the domain, one package per subject
      tariff, rules, combinability      what is filed and what it permits
      construction, selection, minimums,
      engine, fcl, audit                building a price and checking a quoted one
      reissue, involuntary              what a change of ticket costs
      collect/                          polling, parsing, archiving
      load/                             reading filing files into the tariff
      desk/                             tickets, watches, recommendations, the night run
      reports/                          savings, reliability, coverage, filing, discrepancies
      persistence/                      rows, mappers, repositories and the unit of work
      api/                              the read mostly service
      cli/                              the commands cron calls
    src/main/resources/db/migration/    the schema, one Flyway migration per revision
    src/test/java/                      the suite, one test class per module

## Running the suite

    ./mvnw -B verify

That runs Spotless, every test and the coverage gate. One test class or method
at a time:

    ./mvnw -B test -Dtest=AWeekOnTheDeskTest

`cli/AWeekOnTheDeskTest` drives a whole week through the command line against
one database on disk, and it is the one to read first: every part below it is
tested on its own, and the ways a desk actually breaks are all in the joins
between them. `./mvnw spotless:apply` formats the code before a commit.

## Migrations

    gussetline create-schema

builds the schema, or brings an older one up to date, from
`src/main/resources/db/migration`. The suite compares the migrated schema against
the JPA mapping and fails on any difference, so a column added without a
migration is a named failure rather than one red cross among a thousand.
