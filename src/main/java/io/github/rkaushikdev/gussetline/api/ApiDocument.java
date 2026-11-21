package io.github.rkaushikdev.gussetline.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.rkaushikdev.gussetline.Gussetline;
import io.swagger.v3.core.jackson.ModelResolver;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.Schema;
import java.lang.annotation.Annotation;
import java.util.Arrays;
import org.springdoc.core.customizers.ParameterCustomizer;
import org.springdoc.core.customizers.PropertyCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The service's API document, served at {@code /openapi.json} with its page at {@code /docs}.
 *
 * <p>The document describes the wire as it is: the fields under the names {@link Wire} writes, and a
 * text's {@link Length} as its least and greatest length, in a body and in a query alike.
 */
@Configuration(proxyBeanMethods = false)
class ApiDocument {

    /** The service's name in the document. */
    static final String TITLE = "gussetline";

    /** What the service is, in the document. */
    static final String SUMMARY = "Fare audit and reprice desk";

    /** The head of the document. */
    @Bean
    OpenAPI document() {
        return new OpenAPI().info(new Info().title(TITLE).summary(SUMMARY).version(Gussetline.VERSION));
    }

    /** The shapes, read through the mapper the service writes them with, so their names agree. */
    @Bean
    ModelResolver shapes(ObjectMapper mapper) {
        return new ModelResolver(mapper).openapi31(true);
    }

    /** A body field's length limits. */
    @Bean
    PropertyCustomizer bodyLengths() {
        return (schema, type) -> {
            Annotation[] around = type.getCtxAnnotations();
            if (around != null) {
                Arrays.stream(around)
                        .filter(Length.class::isInstance)
                        .map(Length.class::cast)
                        .findFirst()
                        .ifPresent(length -> limit(schema, length));
            }
            return schema;
        };
    }

    /** A query value's length limits. */
    @Bean
    ParameterCustomizer queryLengths() {
        return (parameter, method) -> {
            Length length = method.getParameterAnnotation(Length.class);
            if (length != null && parameter != null && parameter.getSchema() != null) {
                limit(parameter.getSchema(), length);
            }
            return parameter;
        };
    }

    private static void limit(Schema<?> schema, Length length) {
        if (length.min() > 0) {
            schema.setMinLength(length.min());
        }
        if (length.max() != Integer.MAX_VALUE) {
            schema.setMaxLength(length.max());
        }
    }
}
