package io.github.rkaushikdev.gussetline.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.rkaushikdev.gussetline.Fixtures;
import io.github.rkaushikdev.gussetline.indicators.GlobalIndicator;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.LoadRow;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

/**
 * What a request that cannot reach its route is told, entry by entry, and the pricing screen's route,
 * which takes the richest body the service reads.
 */
@WebMvcTest
@Import(TestService.class)
class ApiRequestsTest {

    @Autowired
    private MockMvc mvc;

    private Http client;
    private Database database;

    @BeforeEach
    void open() {
        database = TestDatabase.database();
        client = Http.over(mvc);
    }

    /**
     * Frankfurt to Singapore. The route looks fares up by the journey's two airports and fares are
     * filed by city, so the journey is between two airports that are their own cities.
     */
    private static Map<String, Object> sector(String departure, String arrival) {
        Map<String, Object> sector = new LinkedHashMap<>();
        sector.put("carrier", "LH");
        sector.put("flight_number", "778");
        sector.put("origin", "FRA");
        sector.put("destination", "SIN");
        sector.put("departure", departure);
        sector.put("arrival", arrival);
        return sector;
    }

    private static Map<String, Object> priceRequest(Map<String, Object> sector) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ticketing", "2025-02-14");
        request.put("sectors", List.of(sector));
        return request;
    }

    private void fileAFare() {
        try (UnitOfWork work = database.unit()) {
            LoadRow load = work.tariff()
                    .openLoad(
                            "LH",
                            "LH.dat",
                            "a".repeat(32),
                            LocalDateTime.of(2025, 1, 2, 6, 0),
                            LocalDate.of(2025, 1, 1));
            work.tariff()
                    .addFares(
                            load,
                            List.of(Fixtures.oneWayFare("FRA", "SIN", "650.00")
                                    .withGlobalIndicator(GlobalIndicator.EH)));
            work.commit();
        }
    }

    /** The one entry of a refusal, as plain values. */
    private static Map<String, Object> onlyProblem(Http.Answer reply) {
        assertThat(reply.status()).isEqualTo(422);
        List<?> detail = (List<?>) reply.map().get("detail");
        assertThat(detail).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> problem = (Map<String, Object>) detail.get(0);
        return problem;
    }

    private static Map<String, Object> problem(String type, List<Object> loc, String msg, Object input) {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", type);
        problem.put("loc", loc);
        problem.put("msg", msg);
        problem.put("input", input);
        return problem;
    }

    private static Map<String, Object> problem(
            String type, List<Object> loc, String msg, Object input, Map<String, Object> ctx) {
        Map<String, Object> problem = problem(type, loc, msg, input);
        problem.put("ctx", ctx);
        return problem;
    }

    @Test
    void aQueryValueTooLongIsRefusedWithItsLimit() {
        Http.Answer reply = client.get(
                "/observations/market", "origin", "FRANKFURT", "destination", "JFK", "departure", "2025-08-04");
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "string_too_long",
                        List.of("query", "origin"),
                        "String should have at most 3 characters",
                        "FRANKFURT",
                        Map.of("max_length", 3)));
    }

    @Test
    void aQueryValueLeftOutIsMissing() {
        Http.Answer reply = client.get("/observations/market", "origin", "FRA", "destination", "JFK");
        assertThat(onlyProblem(reply))
                .isEqualTo(problem("missing", List.of("query", "departure"), "Field required", null));
    }

    @Test
    void aDayThatIsNoDayIsRefusedSayingWhy() {
        Http.Answer reply =
                client.get("/observations/market", "origin", "FRA", "destination", "JFK", "departure", "2025-13-01");
        String why = "month value is outside expected range of 1-12";
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "date_from_datetime_parsing",
                        List.of("query", "departure"),
                        "Input should be a valid date or datetime, " + why,
                        "2025-13-01",
                        Map.of("error", why)));
    }

    @Test
    void aLimitBelowTheLeastIsRefusedWithTheValueAsSent() {
        Http.Answer reply = client.get("/queue", "limit", "0");
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "greater_than_equal",
                        List.of("query", "limit"),
                        "Input should be greater than or equal to 1",
                        "0",
                        Map.of("ge", 1)));
    }

    @Test
    void aRecommendationNumberThatIsNoNumberIsRefused() {
        Http.Answer reply = client.get("/watches/recommendations/abc");
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "int_parsing",
                        List.of("path", "identifier"),
                        "Input should be a valid integer, unable to parse string as an integer",
                        "abc"));
    }

    @Test
    void aDecisionWithAnEmptyNameIsRefusedWithItsLimit() {
        Http.Answer reply = client.post("/watches/recommendations/1/take", Map.of("by", ""));
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "string_too_short",
                        List.of("body", "by"),
                        "String should have at least 1 character",
                        "",
                        Map.of("min_length", 1)));
    }

    @Test
    void aDecisionWithNoBodyIsMissing() {
        Http.Answer reply = client.postText("/watches/recommendations/1/take", "application/json", "");
        assertThat(onlyProblem(reply)).isEqualTo(problem("missing", List.of("body"), "Field required", null));
    }

    @Test
    void aDecisionThatIsNotJsonIsRefusedWhereTheReadingStopped() {
        Http.Answer reply = client.postText("/watches/recommendations/1/take", "application/json", "{\"by\": }");
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "json_invalid",
                        List.of("body", 7),
                        "JSON decode error",
                        Map.of(),
                        Map.of("error", "Expecting value")));
    }

    @Test
    void aPathNothingAnswersIsNotFound() {
        Http.Answer reply = client.get("/nowhere");
        assertThat(reply.status()).isEqualTo(404);
        assertThat(reply.map()).isEqualTo(Map.of("detail", "Not Found"));
    }

    @Test
    void aMethodAPathDoesNotTakeIsNotAllowed() {
        Http.Answer reply = client.get("/pricing");
        assertThat(reply.status()).isEqualTo(405);
        assertThat(reply.map()).isEqualTo(Map.of("detail", "Method Not Allowed"));
        assertThat(reply.header("Allow")).isEqualTo("POST");
    }

    @Test
    void aJourneyPricesWithItsWorkingShown() {
        fileAFare();
        Map<String, Object> sector = sector("2025-08-04 21:55", "2025-08-05T16:00:00");
        sector.put("booking_class", "K");
        Http.Answer reply = client.post("/pricing", priceRequest(sector));
        assertThat(reply.status()).as(reply.body()).isEqualTo(200);
        JsonNode body = reply.json();
        assertThat(body.path("fare_calculation").asText()).startsWith("FRA LH SIN");
        assertThat(body.path("base_fare").path("currency").asText()).isEqualTo("EUR");
        assertThat(body.path("total_nuc").isTextual()).isTrue();
        assertThat(body.path("components"))
                .extracting(component -> component.path("fare_basis").asText())
                .containsExactly("KLWAP7RT");
        assertThat(body.path("trail")).isNotEmpty();
    }

    @Test
    void aJourneyNothingIsFiledForIsRefusedInTheOneErrorShape() {
        Http.Answer reply = client.post("/pricing", priceRequest(sector("2025-08-04T10:25", "2025-08-04T13:05")));
        assertThat(reply.status()).isEqualTo(422);
        assertThat(reply.json().path("code").asText()).isEqualTo("tariff.no_fare");
    }

    @Test
    void everyFieldLeftOutOfABodyIsNamedInTheOrderTheShapeDeclaresThem() {
        Http.Answer reply = client.post("/pricing", Map.of("selling_currency", "EUR"));
        assertThat(reply.status()).isEqualTo(422);
        List<Object> found = new ArrayList<>((List<?>) reply.map().get("detail"));
        Map<String, Object> sent = Map.of("selling_currency", "EUR");
        assertThat(found)
                .containsExactly(
                        problem("missing", List.of("body", "ticketing"), "Field required", sent),
                        problem("missing", List.of("body", "sectors"), "Field required", sent));
    }

    @Test
    void aSectorTimeThatIsNoTimeIsRefusedWhereItSits() {
        Http.Answer reply = client.post("/pricing", priceRequest(sector("tomorrow", "2025-08-04T13:05")));
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "datetime_from_date_parsing",
                        List.of("body", "sectors", 0, "departure"),
                        "Input should be a valid datetime or date, input is too short",
                        "tomorrow",
                        Map.of("error", "input is too short")));
    }

    @Test
    void aSplitIsAWholeNumberHoweverItIsWritten() {
        Map<String, Object> request = priceRequest(sector("2025-08-04T21:55", "2025-08-05T16:00"));
        request.put("breaks", List.of("0", 0.0, true, 0.5));
        assertThat(onlyProblem(client.post("/pricing", request)))
                .isEqualTo(problem(
                        "int_from_float",
                        List.of("body", "breaks", 3),
                        "Input should be a valid integer, got a number with a fractional part",
                        0.5));
    }

    @Test
    void anEmptyPlaceAmongTheSectorsIsNamed() {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("ticketing", "2025-02-14");
        request.put("sectors", Arrays.asList((Object) null));
        assertThat(onlyProblem(client.post("/pricing", request)))
                .isEqualTo(problem(
                        "model_attributes_type",
                        List.of("body", "sectors", 0),
                        "Input should be a valid dictionary or object to extract fields from",
                        null));
    }

    @Test
    void aBodySentAsTextIsQuotedAndRefused() {
        Http.Answer reply = client.postText("/pricing", "text/plain", "hello");
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "model_attributes_type",
                        List.of("body"),
                        "Input should be a valid dictionary or object to extract fields from",
                        "hello"));
    }

    @Test
    void aNumberSentForANameIsRefusedRatherThanReadAsOne() {
        Map<String, Object> sector = sector("2025-08-04T10:25", "2025-08-04T13:05");
        sector.put("carrier", 7);
        Http.Answer reply = client.post("/pricing", priceRequest(sector));
        assertThat(onlyProblem(reply))
                .isEqualTo(problem(
                        "string_type", List.of("body", "sectors", 0, "carrier"), "Input should be a valid string", 7));
    }
}
