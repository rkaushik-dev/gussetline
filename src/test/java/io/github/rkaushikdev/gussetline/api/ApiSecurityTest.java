package io.github.rkaushikdev.gussetline.api;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.persistence.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest
@Import({TestService.class, ApiSecurityTest.Keys.class})
class ApiSecurityTest {

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
        ring = ring();
        client = Http.over(mvc);
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
    void healthNeedsNoKeyAtAll() {
        assertThat(client.get("/health").status()).isEqualTo(200);
        assertThat(client.get("/ready").status()).isEqualTo(200);
    }

    @Test
    void theAuthErrorsAreOurOwnKind() {
        assertThat(new Unauthorised("x").code()).isEqualTo("auth.unauthorised");
        assertThat(new Forbidden("x").code()).isEqualTo("auth.forbidden");
    }
}
