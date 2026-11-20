package io.github.rkaushikdev.gussetline.collect;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One answer from a supplier, with what it took to get it.
 *
 * <p>The attempts are counted on the response because a market that took four tries is not the same
 * reading as one that answered immediately, even when the payload is identical, and the reliability
 * report is built entirely out of that number.
 *
 * <p>Two responses are equal when every part is, the bytes of the body compared one by one.
 *
 * @param status the HTTP status code
 * @param body the payload exactly as it arrived; the response keeps its own copy
 * @param headers the response headers by name, each name once
 * @param attempts how many calls it took, this one included
 * @param elapsedMs milliseconds from the first call going out to this answer coming back
 * @param url the address that answered, query included
 */
public record Response(int status, byte[] body, Map<String, String> headers, int attempts, long elapsedMs, String url) {

    /** Reads a body as a whole document: one value, nothing after it, and the non-finite numbers allowed. */
    private static final ObjectMapper JSON = JsonMapper.builder()
            .enable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    public Response {
        body = body.clone();
        headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
    }

    /** An answer on the first attempt with no headers, from nowhere in particular. */
    public Response(int status, byte[] body) {
        this(status, body, Map.of());
    }

    /** An answer on the first attempt, from nowhere in particular. */
    public Response(int status, byte[] body, Map<String, String> headers) {
        this(status, body, headers, 1, 0, "");
    }

    /** A copy of the payload, so the response itself never changes. */
    @Override
    public byte[] body() {
        return body.clone();
    }

    /** The same answer from another address. */
    public Response withUrl(String value) {
        return new Response(status, body, headers, attempts, elapsedMs, value);
    }

    /** The same answer, taking another number of attempts. */
    public Response withAttempts(int value) {
        return new Response(status, body, headers, value, elapsedMs, url);
    }

    /** Whether the status is a success, two hundred to two ninety nine. */
    public boolean ok() {
        return status >= 200 && status < 300;
    }

    /** The body as UTF-8 text, a sequence that is not UTF-8 becoming a replacement character. */
    public String text() {
        return new String(body, StandardCharsets.UTF_8);
    }

    /**
     * The body read as one JSON document.
     *
     * @throws MalformedPayload when it is not one, naming the address
     */
    public JsonNode json() {
        JsonNode document;
        try {
            document = JSON.readTree(body);
        } catch (IOException unreadable) {
            MalformedPayload refusal = notJson();
            refusal.initCause(unreadable);
            throw refusal;
        }
        if (document == null || document.isMissingNode()) {
            throw notJson();
        }
        return document;
    }

    /** A header's value, whatever the case of its name, or empty text when there is none. */
    public String header(String name) {
        return header(name, "");
    }

    /** A header's value, whatever the case of its name, or the fallback when there is none. */
    public String header(String name, String fallback) {
        String wanted = name.toLowerCase(Locale.ROOT);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            if (entry.getKey().toLowerCase(Locale.ROOT).equals(wanted)) {
                return entry.getValue();
            }
        }
        return fallback;
    }

    /**
     * The supplier's own instruction on how long to wait, when it sends one as a number of seconds.
     * A date, or anything else that is not a number, is no instruction.
     */
    public Optional<Double> retryAfterSeconds() {
        String raw = header("retry-after");
        if (raw.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Numbers.parseFloat(raw));
        } catch (NumberFormatException notSeconds) {
            return Optional.empty();
        }
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Response that
                && status == that.status
                && Arrays.equals(body, that.body)
                && headers.equals(that.headers)
                && attempts == that.attempts
                && elapsedMs == that.elapsedMs
                && url.equals(that.url);
    }

    @Override
    public int hashCode() {
        return Objects.hash(status, Arrays.hashCode(body), headers, attempts, elapsedMs, url);
    }

    /** {@code 200 /search in 3 attempts}. */
    @Override
    public String toString() {
        return status + " " + url + " in " + attempts + " attempts";
    }

    private MalformedPayload notJson() {
        return new MalformedPayload("response was not json", Details.of("url", url));
    }
}
