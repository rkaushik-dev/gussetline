package io.github.rkaushikdev.gussetline.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.core.MethodParameter;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * The door every route that {@link Requires} a role goes through.
 *
 * <p>It runs before a single argument of the route is read, so a caller who may not be here is told
 * so before anybody looks at what they sent: a 401 or a 403 beats a 422. The key that got through is
 * handed to any route that asks for an {@link ApiKey}.
 */
final class Gate implements HandlerInterceptor, HandlerMethodArgumentResolver {

    private static final String CALLER = Gate.class.getName() + ".caller";

    private final Optional<KeyRing> ring;

    Gate(Optional<KeyRing> ring) {
        this.ring = Objects.requireNonNull(ring, "ring");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (handler instanceof HandlerMethod route) {
            List<Role> needs = needs(route);
            if (!needs.isEmpty()) {
                String presented =
                        Optional.ofNullable(request.getHeader(Security.HEADER)).orElse("");
                ApiKey key = Security.caller(ring, presented);
                for (Role role : needs) {
                    Security.require(key, role);
                }
                request.setAttribute(CALLER, key);
            }
        }
        return true;
    }

    /** The roles a route needs: its controller's first, then its own. */
    private static List<Role> needs(HandlerMethod route) {
        List<Role> needs = new ArrayList<>();
        Requires onController = AnnotatedElementUtils.findMergedAnnotation(route.getBeanType(), Requires.class);
        if (onController != null) {
            needs.add(onController.value());
        }
        Requires onRoute = route.getMethodAnnotation(Requires.class);
        if (onRoute != null) {
            needs.add(onRoute.value());
        }
        return needs;
    }

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.getParameterType() == ApiKey.class;
    }

    @Override
    public ApiKey resolveArgument(
            MethodParameter parameter,
            ModelAndViewContainer container,
            NativeWebRequest request,
            WebDataBinderFactory binders) {
        if (request.getAttribute(CALLER, NativeWebRequest.SCOPE_REQUEST) instanceof ApiKey key) {
            return key;
        }
        throw new IllegalStateException(
                parameter.getExecutable().getName() + " asks for the caller's key and requires no role to check one");
    }
}
