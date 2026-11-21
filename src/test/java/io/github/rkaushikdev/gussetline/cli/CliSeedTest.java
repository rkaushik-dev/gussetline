package io.github.rkaushikdev.gussetline.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliSeedTest {

    private static final String TODAY = "2025-11-03";

    @TempDir
    Path tmp;

    private String env;
    private Shell runner;

    @BeforeEach
    void anEnvironment() {
        env = Shell.fileUrl(tmp);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", env)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", tmp.resolve("raw").toString());
        Shell.createSchema(env);
    }

    private static List<TicketRow> tickets(UnitOfWork work) {
        return work.session()
                .createQuery("select t from TicketRow t", TicketRow.class)
                .getResultList();
    }

    @Test
    void aSeededDeskHoldsEverythingTheOtherCommandsRead() {
        Shell.Result reply = runner.run("seed", "--on", TODAY);
        assertThat(reply.code()).isZero();

        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            assertThat(work.tariff().countFares()).isEqualTo(12);
            assertThat(work.rules().count()).isEqualTo(2);
            // One record per fare basis, not per fare.
            assertThat(work.combinability().count()).isEqualTo(3);
            // Two markets, two distinct prices each. One row per price and not one per morning, which
            // is what a real run writes.
            assertThat(work.observations().count()).isEqualTo(4);
        }
    }

    @Test
    void itSaysWhatToTryNext() {
        Shell.Result reply = runner.run("seed", "--on", TODAY);
        assertThat(reply.output()).contains("gussetline price", "gussetline night");
    }

    @Test
    void aSeededDeskPricesARoundTrip() {
        runner.run("seed", "--on", TODAY);
        Shell.Result reply = runner.run("price", "FRA LH400 K 18DEC JFK LH401 K 28DEC FRA", "--on", TODAY);
        assertThat(reply.output()).contains("base fare");
        assertThat(reply.code()).isZero();
    }

    @Test
    void aDeskThatAlreadyHoldsSomethingIsLeftAlone() {
        // Invented fares next to filed ones with nothing to tell them apart.
        runner.run("seed", "--on", TODAY);
        Shell.Result again = runner.run("seed", "--on", TODAY);
        assertThat(again.code()).isNotZero();
        assertThat(again.output()).contains("already holds", "12 fares");
    }

    @Test
    void itCanBeForcedWhenSomebodyMeansIt() {
        runner.run("seed", "--on", TODAY);
        assertThat(runner.run("seed", "--on", TODAY, "--force").code()).isZero();
    }

    @Test
    void forcingItTakesOutWhatTheLastSeedWroteAndNothingElse() {
        // Anything else on the desk is somebody's real data, and forcing a seed is not a licence to
        // take it out.
        runner.run("seed", "--on", TODAY);
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            TicketRow real = new TicketRow();
            real.setNumber("220-9999999999");
            real.setRecordLocator("REAL01");
            real.setPassengerName("SOMEBODY/REAL");
            real.setOrigin("FRA");
            real.setDestination("JFK");
            real.setDeparture(LocalDate.of(2026, 1, 1));
            real.setIssuedOn(LocalDate.of(2025, 1, 1));
            real.setValidatingCarrier("LH");
            real.setBaseAmount(new BigDecimal("840.00"));
            real.setBaseCurrency("EUR");
            real.setTotalAmount(new BigDecimal("840.00"));
            work.session().persist(real);
            work.commit();
        }

        assertThat(runner.run("seed", "--on", TODAY, "--force").code()).isZero();

        Set<String> numbers;
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            numbers = tickets(work).stream().map(TicketRow::getNumber).collect(Collectors.toSet());
        }
        assertThat(numbers).contains("220-9999999999");
        assertThat(numbers).hasSize(3);
    }

    @Test
    void everythingItWritesIsObviouslyInvented() {
        // Seed data that looks real ends up quoted in a meeting.
        runner.run("seed", "--on", TODAY);
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            List<TicketRow> tickets = tickets(work);
            assertThat(tickets)
                    .allSatisfy(row -> assertThat(row.getPassengerName()).isEqualTo("TEST/PASSENGER"));
            assertThat(tickets)
                    .allSatisfy(row -> assertThat(row.getRecordLocator()).startsWith("SEED"));
        }
    }

    @Test
    void theDepartureItInventsIsFarEnoughOutToBeWatched() {
        runner.run("seed", "--on", TODAY);
        List<LocalDate> departures;
        try (Database database = Shell.database(env);
                UnitOfWork work = database.unit()) {
            departures = work.watches().live(LocalDate.of(2025, 11, 3)).stream()
                    .map(WatchRow::getDeparture)
                    .toList();
        }
        assertThat(departures).hasSize(2);
        assertThat(departures).allSatisfy(departure -> assertThat(departure).isAfter(LocalDate.of(2025, 11, 3)));
    }

    @Test
    void aSeededDeskPricesAOneWayOffTheOneWayFare() {
        // Not the cheaper round trip half, which cannot stand on its own.
        runner.run("seed", "--on", TODAY);
        Shell.Result reply = runner.run("price", "FRA LH400 K 18DEC JFK", "--on", TODAY);
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("KOW");
        assertThat(reply.output()).doesNotContain("KLWAP7RT");
    }
}
