package io.github.rkaushikdev.gussetline.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * A small client over the service, so a route test reads as short as the request it makes: {@code
 * client.as("reader-key").get("/watches", "on", "2025-06-28").json()}.
 *
 * <p>Requests go through {@link MockMvc}, or over a real socket to a service started on a port.
 */
public final class Http {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient SOCKETS = HttpClient.newHttpClient();

    private final Optional<MockMvc> mvc;
    private final int port;
    private final Map<String, String> headers;

    private Http(Optional<MockMvc> mvc, int port, Map<String, String> headers) {
        this.mvc = mvc;
        this.port = port;
        this.headers = Map.copyOf(headers);
    }

    /** A client over a mocked servlet. */
    public static Http over(MockMvc mvc) {
        return new Http(Optional.of(mvc), 0, Map.of());
    }

    /** A client over a socket to a service listening on {@code port} on this machine. */
    public static Http on(int port) {
        return new Http(Optional.empty(), port, Map.of());
    }

    /** The same client presenting an API key. */
    public Http as(String key) {
        return with(Security.HEADER, key);
    }

    /** The same client sending one more header. */
    public Http with(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(headers);
        more.put(name, value);
        return new Http(mvc, port, more);
    }

    /** A GET, with query parameters as name and value pairs. */
    public Answer get(String path, String... query) {
        if (mvc.isEmpty()) {
            return overSocket(HttpRequest.newBuilder(uri(path, query)).GET());
        }
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.get(path);
        for (int index = 0; index < query.length; index += 2) {
            request.param(query[index], query[index + 1]);
        }
        return perform(request);
    }

    /** A POST of a JSON body, written from maps, lists and plain values. */
    public Answer post(String path, Object body) {
        return postText(path, MediaType.APPLICATION_JSON_VALUE, text(body));
    }

    /** A POST of a body exactly as given, under a content type. */
    public Answer postText(String path, String contentType, String body) {
        if (mvc.isEmpty()) {
            return overSocket(HttpRequest.newBuilder(uri(path))
                    .header("Content-Type", contentType)
                    .POST(HttpRequest.BodyPublishers.ofString(body)));
        }
        return perform(MockMvcRequestBuilders.post(path)
                .contentType(contentType)
                .characterEncoding(StandardCharsets.UTF_8)
                .content(body));
    }

    private Answer perform(MockHttpServletRequestBuilder request) {
        headers.forEach(request::header);
        try {
            MockHttpServletResponse response =
                    mvc.orElseThrow().perform(request).andReturn().getResponse();
            Map<String, List<String>> answered = new LinkedHashMap<>();
            for (String name : response.getHeaderNames()) {
                answered.put(name.toLowerCase(Locale.ROOT), response.getHeaders(name));
            }
            return new Answer(response.getStatus(), response.getContentAsString(StandardCharsets.UTF_8), answered);
        } catch (Exception failure) {
            throw new IllegalStateException("the request did not complete", failure);
        }
    }

    private Answer overSocket(HttpRequest.Builder request) {
        headers.forEach(request::header);
        try {
            HttpResponse<String> response =
                    SOCKETS.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            Map<String, List<String>> answered = new LinkedHashMap<>();
            response.headers().map().forEach((name, values) -> answered.put(name.toLowerCase(Locale.ROOT), values));
            return new Answer(response.statusCode(), response.body(), answered);
        } catch (IOException failure) {
            throw new IllegalStateException("the request did not complete", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the service", interrupted);
        }
    }

    private URI uri(String path, String... query) {
        StringBuilder target =
                new StringBuilder("http://127.0.0.1:").append(port).append(path);
        for (int index = 0; index < query.length; index += 2) {
            target.append(index == 0 ? '?' : '&')
                    .append(URLEncoder.encode(query[index], StandardCharsets.UTF_8))
                    .append('=')
                    .append(URLEncoder.encode(query[index + 1], StandardCharsets.UTF_8));
        }
        return URI.create(target.toString());
    }

    private static String text(Object body) {
        try {
            return JSON.writeValueAsString(body);
        } catch (JsonProcessingException unwritable) {
            throw new IllegalArgumentException("that body does not write as JSON", unwritable);
        }
    }

    /**
     * What the service answered.
     *
     * @param status the status code
     * @param body the body as text
     * @param headers every header, by its name in lower case
     */
    public record Answer(int status, String body, Map<String, List<String>> headers) {

        /** The body read as JSON. */
        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (JsonProcessingException unreadable) {
                throw new IllegalStateException("the answer is not JSON: " + body, unreadable);
            }
        }

        /** The body read as a JSON object, into plain maps, lists and values. */
        public Map<String, Object> map() {
            try {
                return JSON.readValue(
                        body, JSON.getTypeFactory().constructMapType(Map.class, String.class, Object.class));
            } catch (JsonProcessingException unreadable) {
                throw new IllegalStateException("the answer is not a JSON object: " + body, unreadable);
            }
        }

        /** The first value of a header, or empty when it was not sent. */
        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase(Locale.ROOT));
            return values == null || values.isEmpty() ? "" : values.get(0);
        }
    }
}
