package io.github.rkaushikdev.gussetline.api;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import io.github.rkaushikdev.gussetline.util.Times;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.function.Function;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

/**
 * How the service reads and writes JSON, in one place so the service and the tests of its shapes
 * cannot drift apart.
 *
 * <p>Field names are snake case. A field nobody declared is ignored. Days are {@code 2025-08-04} and
 * moments {@code 2025-08-04T10:25:00}, with the microseconds only when there are some.
 *
 * <p>Reading is strict about kinds and lenient about spelling. Text has to arrive as a JSON string;
 * a number sent for a name is refused rather than turned into one. A whole number may arrive as a
 * number, as a number with no fraction, as text ({@code "3"}) or as {@code true} and {@code false};
 * days and moments arrive as text in the forms {@link WireText} reads. A {@code null} is refused for
 * any text, number, day or moment that cannot be empty, and a field left out takes its default or is
 * reported missing.
 */
public final class Wire {

    private static final String STRING_TYPE = "string_type";
    private static final String NOT_A_STRING = "Input should be a valid string";

    private Wire() {}

    /** Apply the service's JSON rules to a builder: the service's own, or a test's. */
    public static void configure(Jackson2ObjectMapperBuilder builder) {
        builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
                .featuresToDisable(
                        SerializationFeature.WRITE_DATES_AS_TIMESTAMPS,
                        DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .featuresToEnable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .postConfigurer(Wire::finish);
    }

    /** A mapper built the way the service builds its own. */
    public static ObjectMapper mapper() {
        Jackson2ObjectMapperBuilder builder = Jackson2ObjectMapperBuilder.json();
        configure(builder);
        return builder.build();
    }

    /** The readers and writers of the service's own, registered last so they win. */
    private static void finish(ObjectMapper mapper) {
        mapper.registerModule(module());
    }

    private static SimpleModule module() {
        SimpleModule module = new SimpleModule("gussetline-wire");
        module.addSerializer(LocalDateTime.class, new MomentWriter());
        module.addDeserializer(String.class, new Strict<>(STRING_TYPE, NOT_A_STRING, text -> text));
        module.addDeserializer(Integer.class, new WholeNumber());
        module.addDeserializer(int.class, new WholeNumber());
        module.addDeserializer(
                LocalDate.class, new Strict<>("date_type", "Input should be a valid date", WireText::date));
        module.addDeserializer(
                LocalDateTime.class,
                new Strict<>("datetime_type", "Input should be a valid datetime", WireText::dateTime));
        return module;
    }

    /** A moment written the way every stamp the desk prints is written. */
    private static final class MomentWriter extends JsonSerializer<LocalDateTime> {

        @Override
        public void serialize(LocalDateTime moment, JsonGenerator out, SerializerProvider provider) throws IOException {
            out.writeString(Times.iso(moment));
        }
    }

    /**
     * A reader that refuses an explicit {@code null} in the words of its type, and reads a field left
     * out as nothing, so the field's default or a missing field report can follow.
     */
    private abstract static class Refusing<T> extends JsonDeserializer<T> {

        private final String type;
        private final String message;

        Refusing(String type, String message) {
            this.type = type;
            this.message = message;
        }

        /** A refusal of the value the parser is on, in this reader's words. */
        final Refused refusal(JsonParser in) {
            return new Refused(in, new Unfit(type, message));
        }

        @Override
        public T getNullValue(DeserializationContext context) throws JsonMappingException {
            throw refusal(context.getParser());
        }

        @Override
        public Object getAbsentValue(DeserializationContext context) {
            return null;
        }
    }

    /** A value that only ever arrives as a JSON string, read from the text by {@code read}. */
    private static final class Strict<T> extends Refusing<T> {

        private final Function<String, T> read;

        Strict(String type, String message, Function<String, T> read) {
            super(type, message);
            this.read = read;
        }

        @Override
        public T deserialize(JsonParser in, DeserializationContext context) throws IOException {
            if (!in.hasToken(JsonToken.VALUE_STRING)) {
                throw refusal(in);
            }
            try {
                return read.apply(in.getText());
            } catch (Unfit unfit) {
                throw new Refused(in, unfit);
            }
        }
    }

    /**
     * Text that may also be {@code null}, for a field that can be empty: {@code @JsonDeserialize(contentUsing
     * = Wire.NullableText.class) Optional<String>}. Anything but a string or {@code null} is refused.
     */
    public static final class NullableText extends JsonDeserializer<String> {

        @Override
        public String deserialize(JsonParser in, DeserializationContext context) throws IOException {
            if (!in.hasToken(JsonToken.VALUE_STRING)) {
                throw new Refused(in, new Unfit(STRING_TYPE, NOT_A_STRING));
            }
            return in.getText();
        }
    }

    /** A whole number, from any of the forms that say one exactly. */
    private static final class WholeNumber extends Refusing<Integer> {

        WholeNumber() {
            super("int_type", "Input should be a valid integer");
        }

        @Override
        public Integer deserialize(JsonParser in, DeserializationContext context) throws IOException {
            try {
                return switch (in.currentToken()) {
                    case VALUE_NUMBER_INT -> held(in.getBigIntegerValue());
                    case VALUE_NUMBER_FLOAT -> exact(in.getDecimalValue());
                    case VALUE_STRING -> WireText.integer(in.getText());
                    case VALUE_TRUE -> 1;
                    case VALUE_FALSE -> 0;
                    default -> throw refusal(in);
                };
            } catch (Unfit unfit) {
                throw new Refused(in, unfit);
            }
        }

        private static int exact(BigDecimal number) {
            BigDecimal whole = number.stripTrailingZeros();
            if (whole.scale() > 0) {
                throw new Unfit(
                        "int_from_float", "Input should be a valid integer, got a number with a fractional part");
            }
            return held(whole.toBigIntegerExact());
        }

        private static int held(BigInteger number) {
            return number.max(BigInteger.valueOf(Integer.MIN_VALUE))
                    .min(BigInteger.valueOf(Integer.MAX_VALUE))
                    .intValue();
        }
    }
}
