package io.github.rkaushikdev.gussetline.api;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.format.FormatterRegistry;
import org.springframework.stereotype.Component;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * The service's additions to the web layer: the {@link Gate} in front of every route, over the key
 * ring the service was started with, if it was started with one, and query and path values read the
 * way {@link WireText} reads them, so a day or a number in a query string is read exactly as it would
 * be in a body.
 */
@Component
class ApiWeb implements WebMvcConfigurer {

    private final Gate gate;

    ApiWeb(Optional<KeyRing> ring) {
        this.gate = new Gate(ring);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(gate);
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(gate);
    }

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, LocalDate.class, WireText::date);
        registry.addConverter(String.class, Integer.class, WireText::integer);
        registry.addConverter(String.class, Long.class, WireText::whole);
    }
}
