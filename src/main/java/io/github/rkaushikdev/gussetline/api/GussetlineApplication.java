package io.github.rkaushikdev.gussetline.api;

import io.github.rkaushikdev.gussetline.Gussetline;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import java.util.Objects;
import java.util.Optional;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;

/**
 * The service, built around the dependencies it is handed rather than ones it makes.
 *
 * <p>Nothing here reaches for a global. The settings and the database are passed in, which is what
 * lets a test build the whole service against an in-memory database in one line, and it is also what
 * makes the two deployments the desk runs, one against the live database and one against last night's
 * copy, a configuration difference rather than a code branch.
 *
 * <p>{@link #run} is the one way the service starts. Spring Boot's own data source, JPA and Flyway set
 * up are switched off: the database is the one handed in, and with H2 on the class path and no data
 * source of its own Boot would otherwise build an embedded database and migrate that. Boot's logging
 * set up is switched off too, so the desk's own ({@link io.github.rkaushikdev.gussetline.logs.Logs})
 * stands.
 */
@SpringBootApplication(
        exclude = {
            DataSourceAutoConfiguration.class,
            DataSourceTransactionManagerAutoConfiguration.class,
            HibernateJpaAutoConfiguration.class,
            FlywayAutoConfiguration.class
        })
public class GussetlineApplication {

    /** The service's name in its API document. */
    public static final String TITLE = "gussetline";

    /** What the service is, in its API document. */
    public static final String SUMMARY = "Fare audit and reprice desk";

    /** The system property that picks Spring Boot's logging system; {@code none} leaves logging alone. */
    static final String LOGGING_SYSTEM = "org.springframework.boot.logging.LoggingSystem";

    /** {@link #run(Settings, Database, Optional, String...)} with no keys: everything is let through. */
    public static ConfigurableApplicationContext run(Settings settings, Database database, String... args) {
        return run(settings, database, Optional.empty(), args);
    }

    /** {@link #run(Settings, Database, Optional, String...)} behind a key ring. */
    public static ConfigurableApplicationContext run(
            Settings settings, Database database, KeyRing keyring, String... args) {
        return run(settings, database, Optional.of(keyring), args);
    }

    /**
     * Start the service and hand back its running context.
     *
     * <p>The settings, the database and the key ring, when there is one, become the beans {@code
     * settings}, {@code database} and {@code keyRing}; nothing else is shared between requests. The
     * context does not own them: closing it stops the server and leaves the database open for whoever
     * opened it to close. No ring, or a ring that is switched off, lets every request through, which
     * is right on a laptop and wrong everywhere else.
     *
     * @param args Spring arguments, such as {@code --server.port=0} for any free port ({@link #port})
     *     and {@code --server.address=127.0.0.1}
     */
    public static ConfigurableApplicationContext run(
            Settings settings, Database database, Optional<KeyRing> keyring, String... args) {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(database, "database");
        Objects.requireNonNull(keyring, "keyring");
        if (System.getProperty(LOGGING_SYSTEM) == null) {
            System.setProperty(LOGGING_SYSTEM, "none");
        }
        SpringApplication application = new SpringApplicationBuilder(GussetlineApplication.class)
                .initializers(context -> {
                    context.getBeanFactory().registerSingleton("settings", settings);
                    context.getBeanFactory().registerSingleton("database", database);
                    keyring.ifPresent(ring -> context.getBeanFactory().registerSingleton("keyRing", ring));
                })
                .build();
        return application.run(args);
    }

    /**
     * The port a running service listens on, which is the one it was given or, for {@code
     * --server.port=0}, the one it was handed.
     *
     * @throws IllegalArgumentException when the context runs no web server
     */
    public static int port(ApplicationContext context) {
        if (context instanceof WebServerApplicationContext web) {
            return web.getWebServer().getPort();
        }
        throw new IllegalArgumentException("that context runs no web server");
    }

    /** What every route may ask for. */
    @Bean
    Deps deps(Settings settings, Database database) {
        return new Deps(settings, database);
    }

    /** The service's JSON rules, applied to the mapper the web layer uses. */
    @Bean
    Jackson2ObjectMapperBuilderCustomizer wire() {
        return Wire::configure;
    }

    /** The head of the API document. */
    @Bean
    OpenAPI document() {
        return new OpenAPI().info(new Info().title(TITLE).summary(SUMMARY).version(Gussetline.VERSION));
    }
}
