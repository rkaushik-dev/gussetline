package io.github.rkaushikdev.gussetline.api;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.rkaushikdev.gussetline.util.Text;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindingResult;
import org.springframework.validation.Errors;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.HandlerMapping;

/**
 * What was wrong with a request the web layer could not hand to its route, written as {@link
 * Problem}s.
 *
 * <p>The body a request sent is quoted from the copy {@link BodyKept} took, so a refused value is
 * shown as the caller wrote it. Problems in a body come out in the order the shape declares its
 * fields, whatever order they were found in.
 */
final class Problems {

    static final String FIELD_REQUIRED = "Field required";

    private static final ObjectMapper PLAIN = JsonMapper.builder().build();

    private Problems() {}

    /** The body a request sent, read back into plain values, when it sent one that reads. */
    static Optional<Object> sent(HttpServletRequest request) {
        return BodyKept.of(request).flatMap(Problems::plain);
    }

    /** A body that is not JSON, or JSON that is not the shape the route reads. */
    static Problem unreadable(HttpMessageNotReadableException failure, Optional<Object> body) {
        Throwable cause = failure.getCause();
        if (cause instanceof JsonParseException parse) {
            return notJson(parse.getLocation(), decodeError(parse.getOriginalMessage()));
        }
        if (cause instanceof MismatchedInputException trailing
                && trailing.getOriginalMessage().startsWith("Trailing token")) {
            return notJson(trailing.getLocation(), "Extra data");
        }
        if (cause instanceof JsonMappingException mapping) {
            List<Object> path = new ArrayList<>();
            for (JsonMappingException.Reference step : mapping.getPath()) {
                path.add(step.getFieldName() != null ? step.getFieldName() : step.getIndex());
            }
            List<Object> loc = in("body", path);
            Object input = at(body.orElse(null), path);
            if (mapping instanceof Refused refused) {
                Unfit unfit = refused.unfit();
                return new Problem(unfit.type(), loc, unfit.getMessage(), input, unfit.context());
            }
            Class<?> wanted =
                    mapping instanceof MismatchedInputException mismatched ? mismatched.getTargetType() : null;
            return notA(wanted, loc, input);
        }
        return new Problem("missing", List.of("body"), FIELD_REQUIRED, null);
    }

    /** A body that was sent as something other than JSON, quoted as text. */
    static Problem notAnObject(String text) {
        return new Problem(
                "model_attributes_type",
                List.of("body"),
                "Input should be a valid dictionary or object to extract fields from",
                text);
    }

    /** A body whose fields broke their limits, in the order the shape declares them. */
    static List<Problem> invalid(BindingResult result, Optional<Object> body) {
        Class<?> shape =
                result.getTarget() == null ? Object.class : result.getTarget().getClass();
        return fieldProblems(result, body.orElse(null), shape);
    }

    /** Route arguments that broke their limits: query and path values, or a body's fields. */
    static List<Problem> outOfBounds(
            HandlerMethodValidationException failure, HttpServletRequest request, Optional<Object> body) {
        List<Problem> found = new ArrayList<>();
        for (ParameterValidationResult result : failure.getParameterValidationResults()) {
            MethodParameter parameter = result.getMethodParameter();
            if (parameter.hasParameterAnnotation(RequestBody.class) && result instanceof Errors errors) {
                Class<?> shape = parameter.getParameterType();
                found.addAll(fieldProblems(errors, body.orElse(null), shape));
                continue;
            }
            String where = where(parameter);
            String name = nameOf(parameter);
            String raw = sentValue(request, where, name);
            for (var error : result.getResolvableErrors()) {
                ConstraintViolation<?> violation = result.unwrap(error, ConstraintViolation.class);
                found.add(constraint(violation, List.of(where, name), raw, null));
            }
        }
        return found;
    }

    /** A query value the route needs and did not get. */
    static Problem missing(String where, String name) {
        return new Problem("missing", List.of(where, name), FIELD_REQUIRED, null);
    }

    /** A query or path value that does not read as the type the route takes. */
    static Problem mismatched(MethodArgumentTypeMismatchException failure) {
        List<Object> loc = List.of(where(failure.getParameter()), failure.getName());
        Object raw = failure.getValue();
        Class<?> wanted = failure.getRequiredType();
        for (Throwable cause = failure.getCause(); cause != null; cause = cause.getCause()) {
            if (cause instanceof Unfit unfit) {
                return new Problem(unfit.type(), loc, unfit.getMessage(), raw, unfit.context());
            }
        }
        // A value bound to a primitive is retried by the binder's own readers once the service's
        // have refused it, and the last refusal is the one that arrives here; read it again for the
        // reason.
        if (raw instanceof String text) {
            try {
                reread(wanted, text);
            } catch (Unfit unfit) {
                return new Problem(unfit.type(), loc, unfit.getMessage(), raw, unfit.context());
            }
        }
        return notA(wanted, loc, raw);
    }

    private static void reread(Class<?> wanted, String text) {
        if (wanted == int.class || wanted == Integer.class || wanted == long.class || wanted == Long.class) {
            WireText.whole(text);
        } else if (wanted == LocalDate.class) {
            WireText.date(text);
        } else if (wanted == LocalDateTime.class) {
            WireText.dateTime(text);
        }
    }

    private static List<Problem> fieldProblems(Errors errors, Object body, Class<?> shape) {
        List<Problem> found = new ArrayList<>();
        for (FieldError error : errors.getFieldErrors()) {
            List<Object> path = javaPath(error.getField());
            List<Object> loc = in("body", path);
            Object container = at(body, path.subList(0, path.size() - 1));
            Object value = at(body, path);
            ConstraintViolation<?> violation;
            try {
                violation = error.unwrap(ConstraintViolation.class);
            } catch (IllegalArgumentException notAConstraint) {
                found.add(new Problem("value_error", loc, String.valueOf(error.getDefaultMessage()), value));
                continue;
            }
            found.add(constraint(violation, loc, value, container));
        }
        found.sort(Comparator.comparing(
                problem -> rank(shape, problem.loc().subList(1, problem.loc().size())), Problems::compareRanks));
        return found;
    }

    /** One broken constraint, in the words a caller is shown. */
    private static Problem constraint(
            ConstraintViolation<?> violation, List<Object> loc, Object value, Object container) {
        Annotation broken = violation.getConstraintDescriptor().getAnnotation();
        if (broken instanceof NotNull && loc.get(loc.size() - 1) instanceof Integer) {
            return notA(raw(typeAt(violation.getRootBeanClass(), loc.subList(1, loc.size()))), loc, null);
        }
        if (broken instanceof NotNull) {
            return new Problem("missing", loc, FIELD_REQUIRED, container);
        }
        if (broken instanceof Length length) {
            int size = value instanceof String text ? Text.length(text) : 0;
            if (size < length.min()) {
                return new Problem(
                        "string_too_short",
                        loc,
                        "String should have at least " + characters(length.min()),
                        value,
                        Map.of("min_length", length.min()));
            }
            return new Problem(
                    "string_too_long",
                    loc,
                    "String should have at most " + characters(length.max()),
                    value,
                    Map.of("max_length", length.max()));
        }
        if (broken instanceof Min min) {
            return new Problem(
                    "greater_than_equal",
                    loc,
                    "Input should be greater than or equal to " + min.value(),
                    value,
                    Map.of("ge", min.value()));
        }
        if (broken instanceof Max max) {
            return new Problem(
                    "less_than_equal",
                    loc,
                    "Input should be less than or equal to " + max.value(),
                    value,
                    Map.of("le", max.value()));
        }
        return new Problem("value_error", loc, violation.getMessage(), value);
    }

    private static String characters(long count) {
        return count + (count == 1 ? " character" : " characters");
    }

    /** A value that is not the type at all, named for the type wanted. */
    private static Problem notA(Class<?> wanted, List<Object> loc, Object input) {
        if (wanted == String.class) {
            return new Problem("string_type", loc, "Input should be a valid string", input);
        }
        if (wanted == Integer.class || wanted == int.class || wanted == Long.class || wanted == long.class) {
            return new Problem("int_type", loc, "Input should be a valid integer", input);
        }
        if (wanted == LocalDate.class) {
            return new Problem("date_type", loc, "Input should be a valid date", input);
        }
        if (wanted == LocalDateTime.class) {
            return new Problem("datetime_type", loc, "Input should be a valid datetime", input);
        }
        if (wanted != null && Collection.class.isAssignableFrom(wanted)) {
            return new Problem("list_type", loc, "Input should be a valid list", input);
        }
        return new Problem(
                "model_attributes_type",
                loc,
                "Input should be a valid dictionary or object to extract fields from",
                input);
    }

    private static Problem notJson(JsonLocation location, String error) {
        long position = location.getCharOffset() >= 0 ? location.getCharOffset() : location.getByteOffset();
        return new Problem(
                "json_invalid",
                List.of("body", Math.max(0, position)),
                "JSON decode error",
                Map.of(),
                Map.of("error", error));
    }

    /** What the decoder ran into, in a short sentence. */
    private static String decodeError(String message) {
        if (message.contains("double-quote to start field name")) {
            return "Expecting property name enclosed in double quotes";
        }
        if (message.contains("expecting comma")) {
            return "Expecting ',' delimiter";
        }
        if (message.contains("expecting a colon")) {
            return "Expecting ':' delimiter";
        }
        if (message.contains("Illegal unquoted character")) {
            return "Invalid control character";
        }
        if (message.contains("end-of-input") && message.contains("String")) {
            return "Unterminated string";
        }
        return "Expecting value";
    }

    private static Optional<Object> plain(byte[] body) {
        try {
            return Optional.ofNullable(PLAIN.readValue(body, Object.class));
        } catch (IOException unreadable) {
            return Optional.of(new String(body, StandardCharsets.UTF_8));
        }
    }

    /** The value at a path of field names and list positions, or {@code null} when nothing is there. */
    private static Object at(Object body, List<Object> path) {
        Object here = body;
        for (Object step : path) {
            if (step instanceof String name && here instanceof Map<?, ?> fields) {
                here = fields.get(name);
            } else if (step instanceof Integer index
                    && here instanceof List<?> items
                    && index >= 0
                    && index < items.size()) {
                here = items.get(index);
            } else {
                return null;
            }
        }
        return here;
    }

    private static List<Object> in(String root, List<Object> path) {
        List<Object> loc = new ArrayList<>();
        loc.add(root);
        loc.addAll(path);
        return loc;
    }

    /** A bean path as the wire names it: {@code sectors[0].flightNumber} is {@code sectors, 0, flight_number}. */
    private static List<Object> javaPath(String field) {
        List<Object> path = new ArrayList<>();
        for (String step : field.replaceAll("\\[(\\d+)]", ".$1").split("\\.")) {
            if (!step.isEmpty()) {
                path.add(step.chars().allMatch(Character::isDigit) ? (Object) Integer.parseInt(step) : snake(step));
            }
        }
        return path;
    }

    private static String snake(String name) {
        return name.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }

    /** Where each step of a path sits in its shape: a field by its place in the record, a list item by its index. */
    private static List<Integer> rank(Class<?> shape, List<Object> path) {
        List<Integer> ranks = new ArrayList<>();
        Type here = shape;
        for (Object step : path) {
            if (step instanceof Integer index) {
                ranks.add(index);
            } else {
                ranks.add(place(here, step));
            }
            here = step(here, step);
        }
        return ranks;
    }

    /** The declared type of the value at a path, or {@code Object} when the path leaves the shape. */
    private static Type typeAt(Class<?> shape, List<Object> path) {
        Type here = shape;
        for (Object step : path) {
            here = step(here, step);
        }
        return here;
    }

    /** The type one step down: a list's items, or a record's field, seen through an {@code Optional}. */
    private static Type step(Type here, Object step) {
        if (step instanceof Integer) {
            return argument(here);
        }
        Class<?> type = raw(here);
        if (type != null && type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                if (snake(component.getName()).equals(step)) {
                    Type next = component.getGenericType();
                    return raw(next) == Optional.class ? argument(next) : next;
                }
            }
        }
        return Object.class;
    }

    /** Where a field sits among its record's fields; past the end when it is not one of them. */
    private static int place(Type here, Object step) {
        Class<?> type = raw(here);
        if (type != null && type.isRecord()) {
            RecordComponent[] components = type.getRecordComponents();
            for (int index = 0; index < components.length; index++) {
                if (snake(components[index].getName()).equals(step)) {
                    return index;
                }
            }
        }
        return Integer.MAX_VALUE;
    }

    private static int compareRanks(List<Integer> left, List<Integer> right) {
        for (int index = 0; index < Math.min(left.size(), right.size()); index++) {
            int order = Integer.compare(left.get(index), right.get(index));
            if (order != 0) {
                return order;
            }
        }
        return Integer.compare(left.size(), right.size());
    }

    private static Type argument(Type type) {
        return type instanceof ParameterizedType generic ? generic.getActualTypeArguments()[0] : Object.class;
    }

    private static Class<?> raw(Type type) {
        if (type instanceof Class<?> plain) {
            return plain;
        }
        return type instanceof ParameterizedType generic && generic.getRawType() instanceof Class<?> plain
                ? plain
                : null;
    }

    private static String where(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(PathVariable.class) ? "path" : "query";
    }

    private static String nameOf(MethodParameter parameter) {
        RequestParam query = parameter.getParameterAnnotation(RequestParam.class);
        if (query != null && !query.name().isEmpty()) {
            return query.name();
        }
        PathVariable path = parameter.getParameterAnnotation(PathVariable.class);
        if (path != null && !path.name().isEmpty()) {
            return path.name();
        }
        return String.valueOf(parameter.getParameterName());
    }

    private static String sentValue(HttpServletRequest request, String where, String name) {
        if (where.equals("query")) {
            return request.getParameter(name);
        }
        Object variables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE);
        return variables instanceof Map<?, ?> found && found.get(name) instanceof String value ? value : null;
    }
}
