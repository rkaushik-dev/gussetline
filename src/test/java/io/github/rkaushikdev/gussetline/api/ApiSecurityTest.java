package io.github.rkaushikdev.gussetline.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.persistence.models.TicketRow;
import io.github.rkaushikdev.gussetline.persistence.models.WatchRow;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({TestService.class, ApiSecurityTest.Keys.class})
class ApiSecurityTest {

    private static final LocalDate TODAY = LocalDate.of(2025, 6, 29);

    /** The three keys the service in this class accepts. Imported, never scanned; see {@link TestService}. */
    static class Keys {

        @Bean
        KeyRing ring() {
            return ApiSecurityTest.ring();
        }
    }

    @Autowired
    private MockMvc mvc;

    private Http client;
    private Database database;
    private KeyRing ring;

    static KeyRing ring() {
        KeyRing made = new KeyRing();
        made.issue("screens", "reader-key", Role.READER);
        made.issue("anna", "agent-key", Role.AGENT);
        made.issue("ops", "operator-key", Role.OPERATOR);
        return made;
    }

    @BeforeEach
    void open() {
        database = TestDatabase.database();
        try (UnitOfWork work = database.unit()) {
            TicketRow ticket = Rows.ticket(LocalDate.of(2025, 8, 4));
            WatchRow watch = Rows.watch("FRA-NYC-2025-08-04-Y", LocalDate.of(2025, 8, 4));
            watch.addRecommendation(Rows.recommendation(TODAY, "open", "90.00"));
            ticket.addWatch(watch);
            work.session().persist(ticket);
            work.commit();
        }
        ring = ring();
        client = Http.over(mvc);
    }

    /** The whole service, started on a free port over the seeded database. */
    private ConfigurableApplicationContext serve(Optional<KeyRing> keys) {
        return GussetlineApplication.run(
                TestService.settings(), database, keys, "--server.port=0", "--server.address=127.0.0.1");
    }

    @Test
    void aKeyIsStoredAsAHashAndNeverAsItself() {
        ApiKey key = new ApiKey("x", Security.fingerprint("secret"));
        assertThat(key.digest()).isNotEqualTo("secret");
        assertThat(key.digest()).hasSize(64);
        assertThat(key.matches("secret")).isTrue();
        assertThat(key.matches("Secret")).isFalse();
    }

    @Test
    void aKeyIsComparedAfterTheSameNormalising() {
        ApiKey key = new ApiKey("x", Security.fingerprint("secret"));
        assertThat(key.matches("  secret  ")).isTrue();
    }

    @Test
    void theRolesAreOrderedAndTheOrderIsMeaningful() {
        assertThat(Role.OPERATOR.covers(Role.READER)).isTrue();
        assertThat(Role.OPERATOR.covers(Role.AGENT)).isTrue();
        assertThat(Role.AGENT.covers(Role.READER)).isTrue();
        assertThat(Role.READER.covers(Role.AGENT)).isFalse();
        assertThat(Role.AGENT.covers(Role.OPERATOR)).isFalse();
    }

    @Test
    void everyRoleCoversItself() {
        for (Role role : Role.values()) {
            assertThat(role.covers(role)).isTrue();
        }
    }

    @Test
    void aKeyReadsBackWithItsRole() {
        assertThat(new ApiKey("anna", "x", Role.AGENT)).hasToString("anna as agent");
    }

    @Test
    void theRingFindsAKeyItHolds() {
        ApiKey found = ring.find("agent-key").orElseThrow();
        assertThat(found.name()).isEqualTo("anna");
        assertThat(ring.size()).isEqualTo(3);
    }

    @Test
    void theRingDoesNotFindOneItDoesNot() {
        assertThat(ring.find("nonsense")).isEmpty();
    }

    @Test
    void noKeyAtAllIsUnauthorised() {
        Http.Answer reply = client.get("/observations/series/anything");
        assertThat(reply.status()).isEqualTo(401);
        assertThat(reply.json().path("code").asText()).isEqualTo("auth.unauthorised");
    }

    @Test
    void aKeyWeDoNotHoldIsUnauthorised() {
        Http.Answer reply = client.as("nonsense").get("/observations/series/anything");
        assertThat(reply.status()).isEqualTo(401);
    }

    @Test
    void aReaderCanReadObservations() {
        String key = "FRA-LON-2099-01-01-Y";
        Http.Answer reply = client.as("reader-key").get("/observations/series/" + key);
        // Not found rather than forbidden, which means the key got through.
        assertThat(reply.status()).isEqualTo(404);
    }

    @Test
    void anAgentCannotSeeTheSupplierScreen() {
        Http.Answer reply = client.as("agent-key").get("/sources");
        assertThat(reply.status()).isEqualTo(403);
        assertThat(reply.json().path("details").path("needs").asText()).isEqualTo("operator");
    }

    @Test
    void healthNeedsNoKeyAtAll() {
        assertThat(client.get("/health").status()).isEqualTo(200);
        assertThat(client.get("/ready").status()).isEqualTo(200);
    }

    @Test
    void aDisabledRingAlsoLetsEverythingThrough() {
        ring.setEnabled(false);
        try (ConfigurableApplicationContext service = serve(Optional.of(ring))) {
            Http open = Http.on(GussetlineApplication.port(service));
            assertThat(open.get("/sources").status()).isEqualTo(200);
        }
    }

    @Test
    void theRefusalsCarryTheKeyNameSoALogSaysWho() {
        Http.Answer reply = client.as("reader-key").get("/sources");
        assertThat(reply.json().path("details").path("key").asText()).isEqualTo("screens");
    }

    @Test
    void theAuthErrorsAreOurOwnKind() {
        assertThat(new Unauthorised("x").code()).isEqualTo("auth.unauthorised");
        assertThat(new Forbidden("x").code()).isEqualTo("auth.forbidden");
    }
}
