package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import java.util.Objects;
import java.util.function.Function;

/**
 * What a route is allowed to ask for.
 *
 * <p>Two things and no more: a unit of work, and the settings. A route that needs anything else is a
 * route with logic in it, and the logic belongs a layer down where it can be tested without a client.
 *
 * <p>The unit of work is a call around the route's body rather than something opened before it and
 * closed after: the route's answer is built inside it, from rows that are still attached, and a route
 * that writes commits before the answer is written, never after a 200 has gone.
 */
public final class Deps {

    private final Settings settings;
    private final Database database;

    public Deps(Settings settings, Database database) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.database = Objects.requireNonNull(database, "database");
    }

    /** The settings the service was started with. */
    public Settings settings() {
        return settings;
    }

    /** The database the service was started over. */
    public Database database() {
        return database;
    }

    /**
     * Run a route's body in one transaction, rolled back unless the route commits, and answer what it
     * answers. The unit is closed whether the body finished or threw.
     */
    public <T> T unit(Function<UnitOfWork, T> route) {
        try (UnitOfWork work = database.unit()) {
            return route.apply(work);
        }
    }
}
