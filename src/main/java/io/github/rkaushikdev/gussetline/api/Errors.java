package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.BudgetExhausted;
import io.github.rkaushikdev.gussetline.ConfigError;
import io.github.rkaushikdev.gussetline.ConflictError;
import io.github.rkaushikdev.gussetline.ConstructionError;
import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.SupplierBlocked;
import io.github.rkaushikdev.gussetline.SupplierError;
import io.github.rkaushikdev.gussetline.TariffError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.logs.Logs;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One error shape, so a client never has to guess which kind it got.
 *
 * <p>Every failure the desk refuses on purpose leaves this service looking the same: a code, a
 * sentence, the details the domain attached and the request's id, {@code {"code": "record.not_found",
 * "message": "...", "details": {...}, "request_id": "..."}}. The code is the one from the error
 * taxonomy and not an invented HTTP-flavoured name, because the desk's own screens log it and a code
 * that changes when the transport changes is a code nobody can grep for.
 *
 * <p>The status is derived from the exception class in exactly one table, {@link #STATUS_FOR}.
 * Deriving it at each raise site is how a not found ends up as a five hundred on one route and a four
 * hundred on another, and the client that has to handle both stops trusting either.
 *
 * <p>A request the web layer cannot hand to its route at all is answered 422 with {@code {"detail":
 * [...]}}, one {@link Problem} per thing wrong with it; a path nothing answers is a 404 and a method a
 * path does not take a 405, each with {@code {"detail": "Not Found"}} or {@code {"detail": "Method Not
 * Allowed"}}; anything else that escapes a route is a plain text 500.
 */
@RestControllerAdvice
public final class Errors {

    /** The one place an exception becomes a status code, tried in order. */
    public static final Map<Class<? extends GussetlineError>, Integer> STATUS_FOR = statuses();

    private static Map<Class<? extends GussetlineError>, Integer> statuses() {
        Map<Class<? extends GussetlineError>, Integer> table = new LinkedHashMap<>();
        table.put(Unauthorised.class, 401);
        table.put(Forbidden.class, 403);
        table.put(NotFoundError.class, 404);
        table.put(ConflictError.class, 409);
        table.put(ValidationError.class, 422);
        table.put(TariffError.class, 422);
        table.put(ConstructionError.class, 422);
        table.put(BudgetExhausted.class, 429);
        table.put(SupplierBlocked.class, 502);
        table.put(SupplierError.class, 502);
        table.put(ConfigError.class, 500);
        return Collections.unmodifiableMap(table);
    }

    /** The status one of our errors becomes. Anything unmapped is a 500. */
    public static int statusFor(GussetlineError error) {
        for (Map.Entry<Class<? extends GussetlineError>, Integer> entry : STATUS_FOR.entrySet()) {
            if (entry.getKey().isInstance(error)) {
                return entry.getValue();
            }
        }
        return 500;
    }

    /** {@link #bodyFor(GussetlineError, String)} with no request id. */
    public static Map<String, Object> bodyFor(GussetlineError error) {
        return bodyFor(error, "");
    }

    /** The body an error is answered with; the {@code request_id} only when there is one. */
    public static Map<String, Object> bodyFor(GussetlineError error, String requestId) {
        Map<String, Object> body = error.asMap();
        if (!requestId.isEmpty()) {
            body.put("request_id", requestId);
        }
        return body;
    }

    /** Any of our errors, wherever it was raised: in a route, or at the door. */
    @ExceptionHandler(GussetlineError.class)
    public ResponseEntity<Map<String, Object>> domain(GussetlineError error, HttpServletRequest request) {
        String requestId = RequestIds.of(request);
        ResponseEntity.BodyBuilder reply = ResponseEntity.status(statusFor(error));
        if (!requestId.isEmpty()) {
            reply.header(RequestIds.HEADER, requestId);
        }
        return reply.body(bodyFor(error, requestId));
    }

    /** A body that is not JSON, or not the shape the route reads. */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadable(
            HttpMessageNotReadableException failure, HttpServletRequest request) {
        return refused(List.of(Problems.unreadable(failure, Problems.sent(request))));
    }

    /** A body whose fields broke their limits. */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(
            MethodArgumentNotValidException failure, HttpServletRequest request) {
        return refused(Problems.invalid(failure.getBindingResult(), Problems.sent(request)));
    }

    /** Query or path values, or a body, that broke their limits. */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> outOfBounds(
            HandlerMethodValidationException failure, HttpServletRequest request) {
        return refused(Problems.outOfBounds(failure, request, Problems.sent(request)));
    }

    /** A query value the route needs and did not get. */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missing(MissingServletRequestParameterException failure) {
        return refused(List.of(Problems.missing("query", failure.getParameterName())));
    }

    /** A query or path value that does not read as the type the route takes. */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<Map<String, Object>> mismatched(MethodArgumentTypeMismatchException failure) {
        return refused(List.of(Problems.mismatched(failure)));
    }

    /** A body sent as something other than JSON, which is read as text and is not an object. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> notJson(HttpServletRequest request) {
        return refused(List.of(Problems.notAnObject(text(request))));
    }

    /** A path nothing answers. */
    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<Map<String, Object>> nowhere() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(detail("Not Found"));
    }

    /** A path that is there, asked with a method it does not take. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> wrongMethod(HttpRequestMethodNotSupportedException failure) {
        String allowed = Objects.requireNonNullElse(failure.getSupportedHttpMethods(), Set.<HttpMethod>of()).stream()
                .filter(method -> !method.equals(HttpMethod.HEAD) && !method.equals(HttpMethod.OPTIONS))
                .map(HttpMethod::name)
                .sorted()
                .collect(Collectors.joining(", "));
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED)
                .header(HttpHeaders.ALLOW, allowed)
                .body(detail("Method Not Allowed"));
    }

    /**
     * Anything else that escaped a route: logged, and answered as a plain server error, or with its
     * own status when the web layer raised it with one.
     */
    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Object> unexpected(RuntimeException failure, HttpServletRequest request) {
        if (failure instanceof ErrorResponse answered) {
            HttpStatus status = HttpStatus.valueOf(answered.getStatusCode().value());
            return ResponseEntity.status(status).body(detail(status.getReasonPhrase()));
        }
        Logs.problem(
                "request failed",
                Details.of(
                        "path",
                        request.getRequestURI(),
                        "error",
                        failure.getClass().getSimpleName(),
                        "reason",
                        String.valueOf(failure.getMessage())));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.TEXT_PLAIN)
                .body("Internal Server Error");
    }

    private static ResponseEntity<Map<String, Object>> refused(List<Problem> problems) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", problems.stream().map(Problem::asMap).toList());
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY).body(body);
    }

    private static Map<String, Object> detail(String text) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("detail", text);
        return body;
    }

    private static String text(HttpServletRequest request) {
        return BodyKept.of(request)
                .map(body -> new String(body, StandardCharsets.UTF_8))
                .orElse("");
    }
}
