package io.github.rkaushikdev.gussetline.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.gussetline.ConflictError;
import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Fixtures;
import io.github.rkaushikdev.gussetline.NoFareFound;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.collect.Market;
import io.github.rkaushikdev.gussetline.collect.Outcome;
import io.github.rkaushikdev.gussetline.collect.Result;
import io.github.rkaushikdev.gussetline.collect.RunReport;
import io.github.rkaushikdev.gussetline.collect.Task;
import io.github.rkaushikdev.gussetline.itinerary.Itinerary;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.offers.Offer;
import io.github.rkaushikdev.gussetline.offers.PriceBreakdown;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.RecommendationRow;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
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

    private static final LocalDateTime SEEN = LocalDateTime.of(2025, 6, 28, 9, 0);
    private static final LocalDate TODAY = LocalDate.of(2025, 6, 28);

    @Autowired
    private MockMvc mvc;

    private Http client;
    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
        client = Http.over(mvc);
    }

    private static Offer anOffer(String amount) {
        return new Offer(
                "gds",
                new Itinerary(
                        List.of(Fixtures.segment("LH", "400", "FRA", "JFK", "2025-08-04 10:25", "2025-08-04 13:05"))),
                new PriceBreakdown(Money.of(amount, "EUR")),
                List.of("KLWAP7RT"),
                SEEN);
    }

    private void saveOffers(Offer... offers) {
        try (UnitOfWork work = database.unit()) {
            work.observations().saveAll(List.of(offers));
            work.commit();
        }
    }

    private int seedTicket() {
        return seedTicket("open");
    }

    private int seedTicket(String state) {
        try (UnitOfWork work = database.unit()) {
            TicketRow ticket = Rows.ticket(LocalDate.of(2025, 8, 4));
            WatchRow watch = Rows.watch("FRA-NYC-2025-08-04-Y", LocalDate.of(2025, 8, 4));
            RecommendationRow made = Rows.recommendation(TODAY, state, "90.00");
            made.setResidual("refundable");
            watch.addRecommendation(made);
            ticket.addWatch(watch);
            work.session().persist(ticket);
            work.commit();
            return made.getId();
        }
    }

    private void aQueueRow() {
        aQueueRow(LocalDate.of(2025, 7, 29), "open");
    }

    private void aQueueRow(LocalDate raised, String state) {
        try (UnitOfWork work = database.unit()) {
            TicketRow ticket = work.session()
                    .createQuery("select t from TicketRow t", TicketRow.class)
                    .setMaxResults(1)
                    .getResultStream()
                    .findFirst()
                    .orElseGet(() -> {
                        TicketRow made = Rows.ticket(LocalDate.of(2025, 8, 24));
                        work.session().persist(made);
                        work.flush();
                        return made;
                    });
            WatchRow watch = Rows.watch("FRA-NYC-2025-08-24-Y", LocalDate.of(2025, 8, 24));
            watch.setTicketId(ticket.getId());
            work.session().persist(watch);
            work.flush();
            RecommendationRow made = Rows.recommendation(raised, state, "90.00");
            made.setWatchId(watch.getId());
            made.setResidual("refundable");
            work.session().persist(made);
            work.commit();
        }
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
    void aNotFoundComesBackInTheOneErrorShape() {
        Http.Answer reply = client.get("/observations/series/FRA-LON-2099-01-01-Y");
        assertThat(reply.status()).isEqualTo(404);
        JsonNode body = reply.json();
        assertThat(body.path("code").asText()).isEqualTo("record.not_found");
        assertThat(body.path("details").path("series_key").asText()).isEqualTo("FRA-LON-2099-01-01-Y");
        assertThat(body.path("request_id").asText()).isNotEmpty();
    }

    @Test
    void aSeriesKeyThatIsNotOneIsRefusedRatherThanLookedUp() {
        // Both answers are empty, but a 404 tells a caller who mistyped the
        // departure that the desk holds nothing for that series, which is not
        // what happened.
        Http.Answer reply = client.get("/observations/series/nothing-at-all");
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.json().path("details").path("key").asText()).isEqualTo("nothing-at-all");
    }

    @Test
    void theErrorBodyCarriesTheDomainCodeAndNotAnHttpOne() {
        Http.Answer reply = client.get("/watches/ticket/220-nothing");
        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.json().path("code").asText()).isEqualTo("record.not_found");
    }

    @Test
    void aSeriesReadsBackCheapestFirst() {
        saveOffers(anOffer("840.00"), anOffer("790.00"));
        Http.Answer reply = client.get("/observations/series/FRA-NYC-2025-08-04-Y");
        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = reply.json();
        assertThat(body.path("offers"))
                .extracting(offer -> offer.path("total").path("amount").asText())
                .containsExactly("790.00", "840.00");
        assertThat(body.path("cheapest").path("amount").asText()).isEqualTo("790.00");
    }

    @Test
    void anAmountCrossesTheWireAsAString() {
        saveOffers(anOffer("840.00"));
        JsonNode amount = client.get("/observations/series/FRA-NYC-2025-08-04-Y")
                .json()
                .path("offers")
                .path(0)
                .path("base")
                .path("amount");
        assertThat(amount.asText()).isEqualTo("840.00");
        assertThat(amount.isTextual()).isTrue();
    }

    @Test
    void aMarketCanBeAddressedTheWayAPersonSaysIt() {
        saveOffers(anOffer("840.00"));
        Http.Answer reply =
                client.get("/observations/market", "origin", "FRA", "destination", "JFK", "departure", "2025-08-04");
        assertThat(reply.status()).isEqualTo(200);
        // An agent asks for JFK and gets the market, because that is what a
        // series is keyed on and what the tariff prices.
        assertThat(reply.json().path("series_key").asText()).isEqualTo("FRA-NYC-2025-08-04-Y");
    }

    @Test
    void aMarketQueryWithABadCodeIsRefusedBeforeItReachesStorage() {
        Http.Answer reply = client.get(
                "/observations/market", "origin", "FRANKFURT", "destination", "JFK", "departure", "2025-08-04");
        assertThat(reply.status()).isEqualTo(422);
    }

    @Test
    void theSourcesScreenListsEverySupplier() {
        Http.Answer reply = client.get("/sources");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json())
                .extracting(supplier -> supplier.path("name").asText())
                .containsExactly("calendar", "gds", "ndc");
    }

    @Test
    void oneSupplierCanBeReadOnItsOwn() {
        Http.Answer reply = client.get("/sources/gds");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().path("cost_per_call").asText()).isEqualTo("0.04");
        assertThat(reply.json().path("capabilities"))
                .extracting(JsonNode::asText)
                .contains("shop");
    }

    @Test
    void aSupplierWeDoNotHaveIsANotFound() {
        assertThat(client.get("/sources/nobody").status()).isEqualTo(404);
    }

    @Test
    void theProcessIdIsReportedBecauseTheBreakerIsPerProcess() {
        assertThat(client.get("/sources/-/process").json().path("pid").asText()).isNotEmpty();
    }

    @Test
    void theLiveWatchesComeBack() {
        seedTicket();
        Http.Answer reply = client.get("/watches", "on", "2025-06-28");
        assertThat(reply.status()).isEqualTo(200);
        JsonNode body = reply.json();
        assertThat(body).hasSize(1);
        assertThat(body.path(0).path("ticket_number").asText()).isEqualTo("220-1234567890");
        assertThat(body.path(0).path("threshold").path("amount").asText()).isEqualTo("50.00");
    }

    @Test
    void theWatchesOfOneTicketCanBeFound() {
        seedTicket();
        Http.Answer reply = client.get("/watches/ticket/220-1234567890");
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json()).hasSize(1);
    }

    @Test
    void aRecommendationReadsBackWithEveryNumber() {
        int identifier = seedTicket();
        JsonNode body = client.get("/watches/recommendations/" + identifier).json();
        assertThat(body.path("state").asText()).isEqualTo("open");
        assertThat(body.path("paid").path("amount").asText()).isEqualTo("840.00");
        assertThat(body.path("benefit").path("amount").asText()).isEqualTo("90.00");
    }

    @Test
    void takingARecommendationRecordsWhoDidIt() {
        int identifier = seedTicket();
        Http.Answer reply = client.post(
                "/watches/recommendations/" + identifier + "/take", Map.of("by", "anna", "note", "reissued on LH"));
        assertThat(reply.status()).isEqualTo(200);
        assertThat(reply.json().path("state").asText()).isEqualTo("taken");
        assertThat(reply.json().path("decided_by").asText()).isEqualTo("anna");
    }

    @Test
    void killingARecommendationIsRecordedToo() {
        int identifier = seedTicket();
        Http.Answer reply = client.post("/watches/recommendations/" + identifier + "/kill", Map.of("by", "anna"));
        assertThat(reply.json().path("state").asText()).isEqualTo("killed");
    }

    @Test
    void aDecisionWithNoNameAgainstItIsRefused() {
        int identifier = seedTicket();
        Http.Answer reply = client.post("/watches/recommendations/" + identifier + "/take", Map.of("by", ""));
        assertThat(reply.status()).isEqualTo(422);
    }

    @Test
    void settlingASettledRecommendationIsAConflict() {
        int identifier = seedTicket("taken");
        Http.Answer reply = client.post("/watches/recommendations/" + identifier + "/kill", Map.of("by", "bruno"));
        assertThat(reply.status()).isEqualTo(409);
        assertThat(reply.json().path("code").asText()).isEqualTo("record.conflict");
    }

    @Test
    void aRecommendationWeDoNotHaveIsANotFound() {
        assertThat(client.get("/watches/recommendations/999").status()).isEqualTo(404);
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
    void theQueueOfAnEmptyDeskIsEmpty() {
        JsonNode body = client.get("/queue", "on", "2025-07-30").json();
        assertThat(body.path("open").asInt()).isZero();
        assertThat(body.path("places")).isEmpty();
        assertThat(body.path("worth").path("amount").asText()).isEqualTo("0");
    }

    @Test
    void theQueueCarriesTheThreeNumbersTheScreenShows() {
        aQueueRow();
        Map<String, Object> body = client.get("/queue", "on", "2025-07-30").map();
        assertThat(body.get("open")).isEqualTo(1);
        assertThat(body.get("worth")).isEqualTo(Map.of("amount", "90.00", "currency", "EUR"));
        assertThat(body.get("as_of")).isEqualTo("2025-07-30");
    }

    @Test
    void aPlaceSaysWhyItIsWhereItIs() {
        aQueueRow(LocalDate.of(2025, 7, 28), "open");
        JsonNode place =
                client.get("/queue", "on", "2025-07-30").json().path("places").path(0);
        assertThat(place.path("ticket_number").asText()).isEqualTo("220-1234567890");
        assertThat(place.path("urgent").isBoolean()).isTrue();
        assertThat(place.path("urgent").asBoolean()).isTrue();
        assertThat(place.path("why").asText()).contains("expires today");
    }

    @Test
    void aScoreCrossesTheWireAsAString() {
        // A float would round it differently in every client that read it.
        aQueueRow();
        JsonNode place =
                client.get("/queue", "on", "2025-07-30").json().path("places").path(0);
        assertThat(place.path("score").isTextual()).isTrue();
    }

    @Test
    void aSettledRecommendationIsNotInTheQueue() {
        aQueueRow(LocalDate.of(2025, 7, 29), "taken");
        assertThat(client.get("/queue", "on", "2025-07-30").json().path("open").asInt())
                .isZero();
    }

    @Test
    void theQueueCanBeCappedForACallerThatWantsTheTopOfIt() {
        aQueueRow();
        JsonNode body = client.get("/queue", "on", "2025-07-30", "limit", "1").json();
        assertThat(body.path("places")).hasSize(1);
    }

    @Test
    void aLimitOfNothingIsABadRequest() {
        assertThat(client.get("/queue", "limit", "0").status()).isEqualTo(422);
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
