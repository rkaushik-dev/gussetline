package io.github.rkaushikdev.gussetline.api;

import jakarta.servlet.http.HttpServletRequest;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;

/**
 * Keeps the body of a request as it was sent, so that a refusal can quote the value it refused.
 *
 * <p>A body is read once, into the shape the route takes, and what failed to read is gone by the time
 * anybody explains why. Bodies here are a few hundred bytes, so keeping a copy for the length of the
 * request costs nothing.
 */
@ControllerAdvice
final class BodyKept extends RequestBodyAdviceAdapter {

    private static final String ATTRIBUTE = BodyKept.class.getName() + ".body";

    /** The body the request sent, when it sent one and a route read it. */
    static Optional<byte[]> of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof byte[] body ? Optional.of(body) : Optional.empty();
    }

    @Override
    public boolean supports(
            MethodParameter parameter, Type targetType, Class<? extends HttpMessageConverter<?>> converterType) {
        return true;
    }

    @Override
    public HttpInputMessage beforeBodyRead(
            HttpInputMessage message,
            MethodParameter parameter,
            Type targetType,
            Class<? extends HttpMessageConverter<?>> converterType)
            throws IOException {
        byte[] body = message.getBody().readAllBytes();
        RequestContextHolder.currentRequestAttributes().setAttribute(ATTRIBUTE, body, RequestAttributes.SCOPE_REQUEST);
        HttpHeaders headers = message.getHeaders();
        return new HttpInputMessage() {
            @Override
            public InputStream getBody() {
                return new ByteArrayInputStream(body);
            }

            @Override
            public HttpHeaders getHeaders() {
                return headers;
            }
        };
    }
}
