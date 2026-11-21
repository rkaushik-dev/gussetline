package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import org.springframework.context.annotation.Bean;

/**
 * What {@link GussetlineApplication#run} would hand a route test's service: the default settings and
 * the suite's shared database. Tests that want keys import a ring of their own.
 *
 * <p>Brought in with {@code @Import} and deliberately not a configuration class: the service scans
 * its own package for components, and a test that starts the whole service through {@code run} must
 * not find these beans beside the ones it handed in.
 */
public class TestService {

    /** Settings that name an in-memory database; the database itself is the suite's. */
    public static Settings settings() {
        return new Settings().withDatabase(Database.IN_MEMORY);
    }

    @Bean
    Settings serviceSettings() {
        return settings();
    }

    /** The suite's database. The suite closes it, not the context, so nothing is destroyed here. */
    @Bean(destroyMethod = "")
    Database serviceDatabase() {
        return TestDatabase.database();
    }
}
