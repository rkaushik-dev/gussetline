package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierThrottled;
import io.github.rkaushikdev.gussetline.SupplierUnavailable;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriUtils;

/**
 * The HTTP client, with the retry policy the suppliers actually need. It owns the retry decision and
 * nothing else.
 *
 * <p>Three rules, learned in this order.
 *
 * <p>Retry only what can succeed on a second try. A timeout, a connection reset and a 503 are all
 * worth repeating. A 400 is not, and neither is a 404, and a client that retries those spends its
 * budget arguing with a supplier that has already given its final answer. A 401, 403 or 451 means
 * stop asking rather than slow down.
 *
 * <p>Back off with jitter and not on a fixed schedule ({@link RetryPolicy}), and when the supplier
 * says how long to wait, wait that long instead, up to the policy's cap.
 *
 * <p>And count the attempts on the {@link Response}.
 *
 * <p>The clock and the sleep are injected so the tests do not wait. That is not a testing
 * convenience bolted on, it is the same seam the scheduler uses to run a whole day of collection in
 * a second. Both count in seconds.
 *
 * <p>A client may be shared between threads.
 */
public final class HttpClient implements AutoCloseable {

    /** Status codes worth trying again. Everything else is the supplier's answer. */
    public static final Set<Integer> RETRYABLE_STATUS = Set.of(408, 425, 429, 500, 502, 503, 504);

    /** Status codes that mean stop asking rather than slow down. */
    public static final Set<Integer> BLOCKING_STATUS = Set.of(401, 403, 451);

    /** Seconds to wait for a connection, and then for each read, unless told otherwise. */
    public static final double TIMEOUT = 10.0;

    private static final int TOO_MANY_REQUESTS = 429;

    private static final Pattern ABSOLUTE = Pattern.compile("[A-Za-z][A-Za-z0-9+.-]*://[^/?#].*", Pattern.DOTALL);

    private final String baseUrl;
    private final RetryPolicy policy;
    private final Map<String, String> headers;
    private final DoubleConsumer sleep;
    private final DoubleSupplier clock;
    private final RandomGenerator random;
    private final RestClient rest;
    private final Runnable release;
    private final List<Double> slept = new CopyOnWriteArrayList<>();

    private HttpClient(Builder builder) {
        this.baseUrl = stripTrailingSlashes(builder.baseUrl);
        this.policy = builder.policy;
        this.headers = Map.copyOf(builder.headers);
        this.sleep = builder.sleep;
        this.clock = builder.clock;
        this.random = builder.seed != null ? new Random(builder.seed) : new Random();
        if (builder.transport != null) {
            this.rest = builder.transport.build();
            this.release = () -> {};
        } else {
            java.net.http.HttpClient connections = java.net.http.HttpClient.newBuilder()
                    .version(java.net.http.HttpClient.Version.HTTP_1_1)
                    .connectTimeout(seconds(builder.timeout))
                    .build();
            JdkClientHttpRequestFactory requests = new JdkClientHttpRequestFactory(connections);
            requests.setReadTimeout(seconds(builder.timeout));
            this.rest = RestClient.builder().requestFactory(requests).build();
            this.release = connections::close;
        }
    }

    /** A client to configure: no base address, the usual timeout and policy, and the real clock. */
    public static Builder builder() {
        return new Builder();
    }

    /** The address every relative path is taken from, without a trailing slash. */
    public String baseUrl() {
        return baseUrl;
    }

    public RetryPolicy policy() {
        return policy;
    }

    /** Every wait between attempts so far, in seconds, in the order waited. */
    public List<Double> slept() {
        return List.copyOf(slept);
    }

    /** Let go of the connections this client opened. A transport it was given is the giver's to close. */
    @Override
    public void close() {
        release.run();
    }

    public Response get(String path) {
        return get(path, Map.of());
    }

    /** A GET with query parameters. */
    public Response get(String path, Map<String, String> params) {
        return get(path, params, Map.of());
    }

    /** A GET with query parameters and headers of its own on top of the client's. */
    public Response get(String path, Map<String, String> params, Map<String, String> headers) {
        return request("GET", path, params, new byte[0], headers);
    }

    /** A POST of the body's bytes exactly. */
    public Response post(String path, byte[] content) {
        return post(path, content, Map.of());
    }

    /** A POST of text, sent as UTF-8. */
    public Response post(String path, String content) {
        return post(path, content.getBytes(StandardCharsets.UTF_8));
    }

    /** A POST with headers of its own on top of the client's. */
    public Response post(String path, byte[] content, Map<String, String> headers) {
        return request("POST", path, Map.of(), content, headers);
    }

    /**
     * Try until it works, the attempts run out, or the answer is final.
     *
     * <p>A path that is not a whole address is taken from the base address. A transport failure and
     * a retryable status are tried again after a wait; any other status is the answer and comes
     * back, however unwelcome.
     *
     * @param method the HTTP method, in any case
     * @param path the path under the base address, or a whole address of its own
     * @param params query parameters, added after any query the path has
     * @param content the body to send, or no bytes for none
     * @param headers headers for this call, which win over the client's own of the same name
     * @throws SupplierBlocked at once on a 401, 403 or 451
     * @throws SupplierThrottled when the last attempt was a 429
     * @throws SupplierUnavailable when the last attempt was another retryable status or failed in
     *     transport, with the attempts made
     */
    public Response request(
            String method, String path, Map<String, String> params, byte[] content, Map<String, String> headers) {
        URI target = target(path, params);
        double started = clock.getAsDouble();
        RestClientException last = null;

        for (int attempt = 1; attempt <= policy.attempts(); attempt++) {
            Answer answer;
            try {
                answer = send(method, target, content, headers);
            } catch (RestClientException failure) {
                last = failure;
                if (attempt == policy.attempts()) {
                    throw causedBy(
                            new SupplierUnavailable("transport failed", Details.of("url", path, "attempts", attempt)),
                            failure);
                }
                backOff(attempt, Optional.empty());
                continue;
            }
            Response response = new Response(
                    answer.status(),
                    answer.body(),
                    answer.headers(),
                    attempt,
                    (long) ((clock.getAsDouble() - started) * 1000),
                    answer.url());

            int status = response.status();
            if (BLOCKING_STATUS.contains(status)) {
                throw new SupplierBlocked(
                        "the supplier refused us", Details.of("status", status, "url", response.url()));
            }
            if (RETRYABLE_STATUS.contains(status)) {
                if (attempt == policy.attempts()) {
                    if (status == TOO_MANY_REQUESTS) {
                        throw new SupplierThrottled(
                                "throttled and out of attempts", Details.of("status", status, "attempts", attempt));
                    }
                    throw new SupplierUnavailable(
                            "the supplier did not answer", Details.of("status", status, "attempts", attempt));
                }
                backOff(attempt, response.retryAfterSeconds());
                continue;
            }
            return response;
        }

        throw causedBy(new SupplierUnavailable("out of attempts", Details.of("url", path)), last);
    }

    /** One call, as it came back. A failure to get any answer at all comes out as a client exception. */
    private Answer send(String method, URI target, byte[] content, Map<String, String> extra) {
        if (!ABSOLUTE.matcher(target.toString()).matches()
                || !Set.of("http", "https").contains(target.getScheme().toLowerCase(Locale.ROOT))) {
            throw new ResourceAccessException("the address names no http or https host: " + target);
        }
        RestClient.RequestBodySpec call = rest.method(HttpMethod.valueOf(method.toUpperCase(Locale.ROOT)))
                .uri(target)
                .headers(sent -> {
                    headers.forEach(sent::set);
                    extra.forEach(sent::set);
                });
        if (content.length > 0) {
            call.contentLength(content.length).body(out -> out.write(content));
        }
        return call.exchange((request, received) -> new Answer(
                received.getStatusCode().value(),
                received.getBody().readAllBytes(),
                flattened(received.getHeaders()),
                request.getURI().toString()));
    }

    /** What one call brought back, before it is counted as an attempt. */
    private record Answer(int status, byte[] body, Map<String, String> headers, String url) {}

    /** Honour the supplier's own delay when it sends one, else back off; either way, never past the cap. */
    private void backOff(int attempt, Optional<Double> askedFor) {
        double delay = askedFor.orElseGet(() -> policy.delayFor(attempt, random));
        delay = Math.min(delay, policy.maxDelay());
        slept.add(delay);
        sleep.accept(delay);
    }

    /**
     * The address a path names: a whole address as it stands, anything else appended to the base
     * address, then the parameters percent encoded onto the query.
     */
    private URI target(String path, Map<String, String> params) {
        StringBuilder address = new StringBuilder();
        if (ABSOLUTE.matcher(path).matches()) {
            address.append(path);
        } else {
            address.append(baseUrl).append('/').append(stripLeadingSlashes(path));
        }
        if (!params.isEmpty()) {
            address.append(address.indexOf("?") < 0 ? '?' : '&')
                    .append(params.entrySet().stream()
                            .map(entry -> encoded(entry.getKey()) + "=" + encoded(entry.getValue()))
                            .collect(Collectors.joining("&")));
        }
        return URI.create(address.toString());
    }

    /** Every character but the unreserved ones percent encoded, so a value never reads as syntax. */
    private static String encoded(String value) {
        return UriUtils.encode(value, StandardCharsets.UTF_8);
    }

    /** One value per header, names in lower case, repeated headers joined with a comma. */
    private static Map<String, String> flattened(HttpHeaders received) {
        Map<String, String> flat = new LinkedHashMap<>();
        received.forEach((name, values) ->
                flat.merge(name.toLowerCase(Locale.ROOT), String.join(", ", values), (one, two) -> one + ", " + two));
        return flat;
    }

    private static <E extends RuntimeException> E causedBy(E error, Throwable cause) {
        error.initCause(cause);
        return error;
    }

    private static Duration seconds(double value) {
        return Duration.ofNanos((long) (value * 1_000_000_000L));
    }

    private static String stripTrailingSlashes(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }

    private static String stripLeadingSlashes(String text) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == '/') {
            start++;
        }
        return text.substring(start);
    }

    /**
     * Waits on the real clock. An interrupt ends the wait early and stays set on the thread, so the
     * attempt after it fails at once rather than going out.
     */
    private static void pause(double seconds) {
        if (!(seconds > 0)) {
            return;
        }
        try {
            Thread.sleep(seconds(seconds));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** The settings a client is built from. */
    public static final class Builder {

        private String baseUrl = "";
        private double timeout = TIMEOUT;
        private RetryPolicy policy = new RetryPolicy();
        private RestClient.Builder transport;
        private Map<String, String> headers = Map.of();
        private DoubleConsumer sleep = HttpClient::pause;
        private DoubleSupplier clock = () -> System.nanoTime() / 1e9;
        private Long seed;

        private Builder() {}

        /** The address every relative path is taken from. A trailing slash is dropped. */
        public Builder baseUrl(String value) {
            this.baseUrl = value;
            return this;
        }

        /** Seconds to wait for a connection, and then for each read. Not used with a given transport. */
        public Builder timeout(double value) {
            this.timeout = value;
            return this;
        }

        public Builder policy(RetryPolicy value) {
            this.policy = value;
            return this;
        }

        /**
         * The client the calls go out through, in place of one this client would open and close
         * itself. It brings its own connections and its own timeouts.
         */
        public Builder transport(RestClient.Builder value) {
            this.transport = value;
            return this;
        }

        /** Headers sent on every call, such as the user agent. */
        public Builder headers(Map<String, String> value) {
            this.headers = Map.copyOf(value);
            return this;
        }

        /** How to wait a number of seconds between attempts. */
        public Builder sleep(DoubleConsumer value) {
            this.sleep = value;
            return this;
        }

        /** A clock in seconds that only moves forward, for timing the attempts. */
        public Builder clock(DoubleSupplier value) {
            this.clock = value;
            return this;
        }

        /** A seed for the jitter, so the waits come out the same on every run. */
        public Builder seed(long value) {
            this.seed = value;
            return this;
        }

        public HttpClient build() {
            return new HttpClient(this);
        }
    }
}
