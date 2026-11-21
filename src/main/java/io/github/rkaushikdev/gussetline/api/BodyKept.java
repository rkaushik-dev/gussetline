package io.github.rkaushikdev.gussetline.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Keeps the body of a request as it was sent, so that a refusal can quote the value it refused.
 *
 * <p>A body is read once, into the shape the route takes, and what failed to read is gone by the time
 * anybody explains why; a body in a form the service does not read at all is not read by anything.
 * Bodies here are a few hundred bytes, so keeping a copy for the length of the request costs nothing,
 * and the route reads the copy.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
final class BodyKept extends OncePerRequestFilter {

    private static final String ATTRIBUTE = BodyKept.class.getName() + ".body";

    /** The body the request sent, when it sent one. */
    static Optional<byte[]> of(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof byte[] body && body.length > 0
                ? Optional.of(body)
                : Optional.empty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body = request.getInputStream().readAllBytes();
        request.setAttribute(ATTRIBUTE, body);
        chain.doFilter(new Kept(request, body), response);
    }

    /** A request whose body reads from the copy, as many times as anybody asks. */
    private static final class Kept extends HttpServletRequestWrapper {

        private final byte[] body;

        Kept(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream copy = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return copy.read();
                }

                @Override
                public int read(byte[] into, int offset, int length) {
                    return copy.read(into, offset, length);
                }

                @Override
                public boolean isFinished() {
                    return copy.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new IllegalStateException("a kept body is read as it is asked for, never asynchronously");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            String encoding = getCharacterEncoding();
            Charset charset = encoding == null ? StandardCharsets.UTF_8 : Charset.forName(encoding);
            return new BufferedReader(new InputStreamReader(getInputStream(), charset));
        }
    }
}
