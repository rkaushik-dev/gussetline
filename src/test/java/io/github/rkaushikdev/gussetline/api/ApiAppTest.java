package io.github.rkaushikdev.gussetline.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.gussetline.ConflictError;
import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import(TestService.class)
class ApiAppTest {

    @Autowired
    private MockMvc mvc;

    private Http client;
    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
        client = Http.over(mvc);
    }

    private void recordRun(LocalDateTime started) {
        try (UnitOfWork work = database.unit()) {
            work.runs()
                    .record(new RunReport(
                            started,
                            List.of(Result.builder(
                                            new Task("gds", new Market("FRA", "JFK"), LocalDate.of(2025, 8, 4), 1),
                                            Outcome.OK)
                                    .attempts(1)
                                    .build())));
            work.commit();
        }
    }

    @Test
    void theErrorTableMapsEachKindToItsOwnStatus() {
        assertThat(Errors.statusFor(new NotFoundError("x"))).isEqualTo(404);
        assertThat(Errors.statusFor(new ConflictError("x"))).isEqualTo(409);
        assertThat(Errors.statusFor(new ValidationError("x"))).isEqualTo(422);
        assertThat(Errors.statusFor(new NoFareFound("x"))).isEqualTo(422);
        assertThat(Errors.statusFor(new ConstructionError("x"))).isEqualTo(422);
        assertThat(Errors.statusFor(new SupplierBlocked("x"))).isEqualTo(502);
    }

    @Test
    void healthIsUpWithoutTouchingStorage() {
        Http.Answer reply = client.get("/health");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.map()).isEqualTo(Map.of("status", "up"));
    }

    @Test
    void readySaysOnlyThatItCanReachTheDatabase() {
        // Cheap on purpose. Something polls this every few seconds, and the
        // counts it used to carry read two tables for a number nobody looked at.
        Http.Answer reply = client.get("/ready");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.map()).isEqualTo(Map.of("status", "ready"));
    }

    @Test
    void everyResponseCarriesARequestId() {
        Http.Answer reply = client.get("/health");
        assertThat(reply.header("X-Request-Id")).isNotEmpty();
    }

    @Test
    void aRequestIdThatWasGivenIsKept() {
        Http.Answer reply = client.with("X-Request-Id", "abc123").get("/health");
        assertThat(reply.header("X-Request-Id")).isEqualTo("abc123");
    }

    @Test
    void twoRequestsGetTwoDifferentIds() {
        String first = client.get("/health").header("X-Request-Id");
        String second = client.get("/health").header("X-Request-Id");
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theOpenapiDocumentIsServed() {
        try (ConfigurableApplicationContext service = GussetlineApplication.run(
                TestService.settings(), database, "--server.port=0", "--server.address=127.0.0.1")) {
            Http.Answer reply = Http.on(GussetlineApplication.port(service)).get("/openapi.json");
            assertThat(reply.status()).isEqualTo(200);
            assertThat(reply.json().path("info").path("title").asText()).isEqualTo("gussetline");
        }
    }

    @Test
    void anEmptyDeskIsReachableAndNotFit() {
        // Up, reachable and completely useless. Readiness answers yes to all of
        // that, because the database is right there and it is empty.
        assertThat(client.get("/ready").status()).isEqualTo(200);

        Http.Answer reply = client.get("/fit");
        assertThat(reply.status()).isEqualTo(503);
        assertThat(reply.json().path("status").asText()).isEqualTo("unfit");
        assertThat(reply.json().path("missing")).extracting(JsonNode::asText).contains("tariff");
    }

    @Test
    void everyFindingNamesTheCommandThatFixesIt() {
        // The person reading this at seven is not the person who wrote it.
        JsonNode checks = client.get("/fit").json().path("checks");
        assertThat(checks.path("tariff").path("detail").asText()).contains("run load fares");
        assertThat(checks.path("rules").path("detail").asText()).contains("run load rules");
        assertThat(checks.path("combinability").path("detail").asText()).contains("run load combinability");
        assertThat(checks.path("collecting").path("detail").asText()).contains("run collect run");
    }

    @Test
    void aDeskThatLastCollectedInMarchIsNotCollecting() {
        // However many rows it holds. The count is the reassuring number and the
        // date is the true one.
        recordRun(LocalDateTime.of(2025, 3, 1, 6, 0));

        JsonNode checks = client.get("/fit").json().path("checks");
        assertThat(checks.path("collecting").path("ok").asBoolean()).isFalse();
        assertThat(checks.path("collecting").path("detail").asText()).contains("2025-03-01");
    }

    @Test
    void aDeskThatCollectedTodayIsCollecting() {
        recordRun(LocalDateTime.now());

        JsonNode checks = client.get("/fit").json().path("checks");
        assertThat(checks.path("collecting").path("ok").asBoolean()).isTrue();
    }

    @Test
    void fitnessNamesTheCurrencyTheDeskQuotesIn() {
        assertThat(client.get("/fit").json().path("currency").asText()).isEqualTo("EUR");
    }
}
