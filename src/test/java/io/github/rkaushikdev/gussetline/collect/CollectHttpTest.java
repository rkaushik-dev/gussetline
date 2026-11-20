package io.github.rkaushikdev.gussetline.collect;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withException;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierThrottled;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ResponseCreator;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

class CollectHttpTest {

    private static final String LOOPBACK = "127.0.0.1";

    /** One request as the transport saw it. */
    private record Call(URI uri, HttpHeaders headers, byte[] content) {}

    /**
     * A transport that answers from a script and records what it was asked. The answers go out in
     * order and the last one repeats, however many calls come.
     */
    private static final class Recorder {
        private final RestClient.Builder transport = RestClient.builder();
        private final List<Call> calls = new ArrayList<>();

        Recorder(ResponseCreator... answers) {
            transport.requestInterceptor((request, body, execution) -> {
                HttpHeaders headers = new HttpHeaders();
                headers.putAll(request.getHeaders());
                calls.add(new Call(request.getURI(), headers, body.clone()));
                return execution.execute(request, body);
            });
            MockRestServiceServer server =
                    MockRestServiceServer.bindTo(transport).build();
            for (int index = 0; index < answers.length; index++) {
                ExpectedCount count = index == answers.length - 1 ? ExpectedCount.manyTimes() : ExpectedCount.once();
                server.expect(count, anything()).andRespond(answers[index]);
            }
        }
    }

    /** A client over a recorder: no real waiting, a clock that ticks a second a reading, a fixed seed. */
    private static HttpClient.Builder scripted(Recorder recorder) {
        AtomicInteger ticks = new AtomicInteger();
        return HttpClient.builder()
                .baseUrl("https://supplier.example")
                .transport(recorder.transport)
                .sleep(seconds -> {})
                .clock(ticks::getAndIncrement)
                .seed(1);
    }

    private static DefaultResponseCreator ok() {
        return answer(200, "{}");
    }

    private static DefaultResponseCreator answer(int status, String body) {
        return withStatus(HttpStatusCode.valueOf(status)).body(body);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    /** The document a JSON text describes, read by a reader with every default, to compare against. */
    private static JsonNode document(String text) throws IOException {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void aPolicyTriesAtLeastOnce() {
        assertThatThrownBy(() -> new RetryPolicy(0)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theDelayDoublesAndIsCapped() {
        RetryPolicy policy =
                new RetryPolicy().withBaseDelay(1.0).withMaxDelay(4.0).withJitter(0.0);
        Random rng = new Random(1);
        assertThat(policy.delayFor(1, rng)).isEqualTo(1.0);
        assertThat(policy.delayFor(2, rng)).isEqualTo(2.0);
        assertThat(policy.delayFor(3, rng)).isEqualTo(4.0);
        assertThat(policy.delayFor(9, rng)).isEqualTo(4.0);
    }

    @Test
    void jitterMovesTheDelayWithoutMakingItNegative() {
        RetryPolicy policy = new RetryPolicy().withBaseDelay(1.0).withJitter(0.5);
        Random rng = new Random(7);
        Set<Double> delays = new HashSet<>();
        for (int i = 0; i < 20; i++) {
            delays.add(policy.delayFor(1, rng));
        }
        assertThat(delays.size()).isGreaterThan(1);
        assertThat(delays).allSatisfy(delay -> assertThat(delay).isBetween(0.0, 1.5));
    }

    @Test
    void aResponseReadsItsBodyAndItsHeaders() throws IOException {
        Response response =
                new Response(200, bytes("{\"a\":1}"), Map.of("Content-Type", "application/json")).withUrl("x");
        assertThat(response.ok()).isTrue();
        assertThat(response.text()).isEqualTo("{\"a\":1}");
        assertThat(response.json()).isEqualTo(document("{\"a\": 1}"));
        assertThat(response.header("content-type")).isEqualTo("application/json");
        assertThat(response.header("missing", "fallback")).isEqualTo("fallback");
    }

    @Test
    void aBodyThatIsNotJsonIsAMalformedPayload() {
        Response response = new Response(200, bytes("not json at all")).withUrl("x");
        assertThatThrownBy(response::json).isInstanceOf(MalformedPayload.class);
    }

    @Test
    void retryAfterIsReadWhenItIsANumber() {
        assertThat(new Response(429, new byte[0], Map.of("Retry-After", "12")).retryAfterSeconds())
                .contains(12.0);
        assertThat(new Response(429, new byte[0], Map.of("Retry-After", "Wed, 21 Oct")).retryAfterSeconds())
                .isEmpty();
        assertThat(new Response(429, new byte[0]).retryAfterSeconds()).isEmpty();
    }

    @Test
    void aResponseReadsBackWithItsAttemptCount() {
        assertThat(new Response(200, new byte[0]).withAttempts(3).withUrl("/x")).hasToString("200 /x in 3 attempts");
    }

    @Test
    void aGoodAnswerComesBackOnTheFirstAttempt() throws IOException {
        HttpClient c = scripted(new Recorder(answer(200, "{\"fares\":[]}"))).build();
        Response response = c.get("/search");
        assertThat(response.ok()).isTrue();
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.json()).isEqualTo(document("{\"fares\": []}"));
        assertThat(c.slept()).isEmpty();
    }

    @Test
    void aFiveHundredIsRetriedAndThenSucceeds() {
        HttpClient c =
                scripted(new Recorder(answer(503, ""), answer(200, "done"))).build();
        Response response = c.get("/search");
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(response.body()).isEqualTo(bytes("done"));
        assertThat(c.slept()).hasSize(1);
    }

    @Test
    void aFiveHundredThatNeverClearsGivesUpAndSaysSo() {
        Recorder recorder = new Recorder(answer(503, ""));
        HttpClient c = scripted(recorder).build();
        SupplierUnavailable caught = catchThrowableOfType(SupplierUnavailable.class, () -> c.get("/search"));
        assertThat(caught.details()).containsEntry("attempts", 3);
        assertThat(recorder.calls).hasSize(3);
    }

    @Test
    void aThrottleThatNeverClearsIsAThrottleAndNotAnOutage() {
        HttpClient c = scripted(new Recorder(answer(429, ""))).build();
        assertThatThrownBy(() -> c.get("/search")).isInstanceOf(SupplierThrottled.class);
    }

    @Test
    void aRefusalIsNotRetriedAtAll() {
        Recorder recorder = new Recorder(answer(403, ""));
        HttpClient c = scripted(recorder).build();
        assertThatThrownBy(() -> c.get("/search")).isInstanceOf(SupplierBlocked.class);
        assertThat(recorder.calls).hasSize(1);
    }

    @Test
    void aFourHundredIsReturnedRatherThanRetried() {
        Recorder recorder = new Recorder(answer(400, "bad market"));
        HttpClient c = scripted(recorder).build();
        Response response = c.get("/search");
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.ok()).isFalse();
        assertThat(recorder.calls).hasSize(1);
    }

    @Test
    void aFourOhFourIsAnAnswerAndNotAFailure() {
        HttpClient c = scripted(new Recorder(answer(404, ""))).build();
        assertThat(c.get("/search").status()).isEqualTo(404);
    }

    @Test
    void theSuppliersOwnRetryAfterIsHonouredOverOurBackoff() {
        HttpClient c = scripted(new Recorder(answer(429, "").header("Retry-After", "7"), answer(200, "done")))
                .build();
        c.get("/search");
        assertThat(c.slept()).containsExactly(7.0);
    }

    @Test
    void aRetryAfterLargerThanTheCapIsCapped() {
        HttpClient c = scripted(new Recorder(answer(429, "").header("Retry-After", "9000"), answer(200, "done")))
                .policy(new RetryPolicy(2).withMaxDelay(30.0))
                .build();
        c.get("/search");
        assertThat(c.slept()).containsExactly(30.0);
    }

    /**
     * Nothing listens on the port, so the machine itself refuses every attempt: a transport failure
     * through the client's own connections, not a scripted one.
     */
    @Test
    void aTransportFailureIsRetriedAndThenReported() throws IOException {
        int closed;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getByName(LOOPBACK))) {
            closed = probe.getLocalPort();
        }
        try (HttpClient c = HttpClient.builder()
                .baseUrl("http://" + LOOPBACK + ":" + closed)
                .sleep(seconds -> {})
                .seed(1)
                .build()) {
            SupplierUnavailable caught = catchThrowableOfType(SupplierUnavailable.class, () -> c.get("/search"));
            assertThat(caught.details()).containsEntry("attempts", 3);
            assertThat(caught.getCause())
                    .isInstanceOf(ResourceAccessException.class)
                    .hasCauseInstanceOf(ConnectException.class);
            assertThat(c.slept()).hasSize(2);
        }
    }

    @Test
    void aTransportFailureThatClearsReturnsTheGoodAnswer() {
        HttpClient c = scripted(new Recorder(withException(new ConnectException("reset")), answer(200, "done")))
                .build();
        assertThat(c.get("/search").body()).isEqualTo(bytes("done"));
    }

    @Test
    void theRetryableAndBlockingSetsDoNotOverlap() {
        assertThat(HttpClient.RETRYABLE_STATUS).doesNotContainAnyElementsOf(HttpClient.BLOCKING_STATUS);
    }

    @Test
    void queryParametersReachTheSupplier() {
        Recorder recorder = new Recorder(ok());
        scripted(recorder).build().get("/search", Map.of("from", "FRA", "to", "JFK"));
        assertThat(recorder.calls.get(0).uri().toString()).contains("from=FRA");
    }

    @Test
    void aPostCarriesItsBody() {
        Recorder recorder = new Recorder(ok());
        scripted(recorder).build().post("/price", bytes("<request/>"));
        assertThat(recorder.calls.get(0).content()).isEqualTo(bytes("<request/>"));
    }

    @Test
    void defaultHeadersAreSentOnEveryCall() {
        Recorder recorder = new Recorder(ok());
        scripted(recorder)
                .headers(Map.of("User-Agent", "gussetline/1.0"))
                .build()
                .get("/search");
        assertThat(recorder.calls.get(0).headers().getFirst("user-agent")).isEqualTo("gussetline/1.0");
    }

    /** Through the client's own connections to a real listener, so closing has something to close. */
    @Test
    void theClientClosesCleanlyAsAResource() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(LOOPBACK, 0), 0);
        server.createContext("/", exchange -> {
            byte[] body = bytes("{}");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        try (HttpClient c = HttpClient.builder()
                .baseUrl("http://" + LOOPBACK + ":" + server.getAddress().getPort())
                .sleep(seconds -> {})
                .build()) {
            assertThat(c.get("/search").ok()).isTrue();
        } finally {
            server.stop(0);
        }
    }
}
