package io.github.rkaushikdev.gussetline.api;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The role a route refuses anything below.
 *
 * <p>On a controller it covers every route the controller holds; on a route it is checked after the
 * controller's own, so a write on a read-only screen names the role the write needs. A route with
 * neither is open to anybody, which is what health and readiness are.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface Requires {

    /** The least role that may call the route. */
    Role value();
}
