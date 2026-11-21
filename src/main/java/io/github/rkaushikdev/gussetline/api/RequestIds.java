package io.github.rkaushikdev.gussetline.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Every request gets an id.
 *
 * <p>It goes on the response header and into the error body, so a screenshot of a failure from the
 * desk is enough to find the request in the log without asking what time it was. A caller that sends
 * one of its own keeps it, which is how a request is followed from a screen through the service.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestIds extends OncePerRequestFilter {

    /** The header the id is read from and written to. */
    static final String HEADER = "X-Request-Id";

    /** How many hexadecimal digits a made up id has. */
    private static final int LENGTH = 16;

    private static final String ATTRIBUTE = RequestIds.class.getName() + ".id";

    /** The id of a request, or empty when it never came through this filter. */
    static String of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String id ? id : "";
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String given = request.getHeader(HEADER);
        String id = given == null || given.isEmpty() ? made() : given;
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader(HEADER, id);
        chain.doFilter(request, response);
    }

    private static String made() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, LENGTH);
    }
}
