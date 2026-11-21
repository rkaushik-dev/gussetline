package io.github.rkaushikdev.gussetline.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.logs.Logs;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.StaticApplicationContext;

/** The one way the service starts, as the command line relies on it. */
class GussetlineApplicationTest {

    private static ConfigurableApplicationContext serve(Database database) {
        return GussetlineApplication.run(
                TestService.settings(), database, "--server.port=0", "--server.address=127.0.0.1");
    }

    @Test
    void theDesksOwnLoggingStandsOnceTheServiceIsUp() {
        StringWriter stream = new StringWriter();
        Logs.setup(stream);
        try (ConfigurableApplicationContext service = serve(TestDatabase.database())) {
            Logs.event("service.up", Map.of("port", GussetlineApplication.port(service)));
        }
        assertThat(stream.toString()).contains("event=service.up");
    }

    @Test
    void stoppingTheServiceLeavesTheDatabaseToWhoeverOpenedIt() {
        // A database of its own, which closing would shut, unlike the suite's shared one.
        try (Database database = Database.inMemory()) {
            try (ConfigurableApplicationContext service = serve(database)) {
                assertThat(Http.on(GussetlineApplication.port(service))
                                .get("/health")
                                .status())
                        .isEqualTo(200);
            }
            try (UnitOfWork work = database.unit()) {
                assertThat(work.runs().count()).isZero();
            }
        }
    }

    @Test
    void aContextWithNoServerHasNoPort() {
        try (StaticApplicationContext plain = new StaticApplicationContext()) {
            assertThatThrownBy(() -> GussetlineApplication.port(plain))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no web server");
        }
    }
}
