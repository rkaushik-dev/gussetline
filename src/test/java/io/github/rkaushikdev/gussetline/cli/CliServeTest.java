package io.github.rkaushikdev.gussetline.cli;

import static io.github.rkaushikdev.gussetline.cli.ServeCommands.KEYS_ENVIRONMENT;
import static io.github.rkaushikdev.gussetline.cli.ServeCommands.ringFromEnvironment;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.rkaushikdev.gussetline.api.ApiKey;
import io.github.rkaushikdev.gussetline.api.KeyRing;
import io.github.rkaushikdev.gussetline.api.Role;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CliServeTest {

    private static final Pattern LISTENING = Pattern.compile("listening on 127\\.0\\.0\\.1:(\\d+)");

    @TempDir
    Path tmp;

    private String env;
    private Shell runner;

    @BeforeEach
    void anEnvironment() {
        env = Shell.fileUrl(tmp);
        runner = new Shell()
                .exporting("GUSSETLINE_DATABASE_URL", env)
                .exporting("GUSSETLINE_ARCHIVE_ROOT", tmp.resolve("raw").toString());
        Shell.createSchema(env);
    }

    /**
     * Where a stand-in server was asked to listen.
     *
     * @param host the address
     * @param port the port
     */
    private record Bound(String host, int port) {}

    @Test
    void theTwoCommandsShowUpInTheHelp() {
        String out = runner.run("--help").output();
        assertThat(out).contains("serve");
        assertThat(out).contains("issue-key");
    }

    @Test
    void aKeyIsShownOnceAndNeverStored() {
        Shell.Result reply = runner.run("issue-key", "anna", "--role", "agent");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("anna as agent");
        assertThat(reply.output()).contains("that is the only time it will be shown");
    }

    @Test
    void aKeyComesBackReadyToPasteIntoTheEnvironment() {
        Shell.Result reply = runner.run("issue-key", "screens");
        assertThat(reply.output()).contains(KEYS_ENVIRONMENT + "=screens:reader:");
    }

    @Test
    void twoKeysAreNeverTheSame() {
        String first = runner.run("issue-key", "anna").output();
        String second = runner.run("issue-key", "anna").output();
        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void aRoleNobodyHasIsAUsageError() {
        Shell.Result reply = runner.run("issue-key", "anna", "--role", "admiral");
        assertThat(reply.code()).isEqualTo(2);
    }

    @Test
    void noKeysAtAllMeansNoRing() {
        // Which is a service that lets everything through. A ring with nothing in it would refuse
        // everything, and the difference matters on a laptop.
        assertThat(ringFromEnvironment(Map.of())).isEmpty();
    }

    @Test
    void anEmptySettingMeansNoRingEither() {
        assertThat(ringFromEnvironment(Map.of(KEYS_ENVIRONMENT, "   "))).isEmpty();
    }

    @Test
    void aRingReadsItsKeysOutOfTheEnvironment() {
        Optional<KeyRing> ring =
                ringFromEnvironment(Map.of(KEYS_ENVIRONMENT, "anna:agent:secret-one,ops:operator:secret-two"));
        assertThat(ring).isPresent();
        assertThat(ring.get().size()).isEqualTo(2);
        Optional<ApiKey> found = ring.get().find("secret-one");
        assertThat(found).isPresent();
        assertThat(found.get().name()).isEqualTo("anna");
        assertThat(found.get().role()).isSameAs(Role.AGENT);
    }

    @Test
    void aKeyThatIsNotThreePartsIsRefusedWithWhatItSaw() {
        assertThatThrownBy(() -> ringFromEnvironment(Map.of(KEYS_ENVIRONMENT, "anna:secret")))
                .hasMessageContaining("name:role:secret");
    }

    @Test
    void aKeyWithAnEmptyPartIsRefused() {
        assertThatThrownBy(() -> ringFromEnvironment(Map.of(KEYS_ENVIRONMENT, "anna::secret")))
                .hasMessageContaining("name:role:secret");
    }

    @Test
    void aRoleTheServiceDoesNotHaveIsRefusedByName() {
        assertThatThrownBy(() -> ringFromEnvironment(Map.of(KEYS_ENVIRONMENT, "anna:admiral:secret")))
                .hasMessageContaining("not a role");
    }

    @Test
    void reloadWithKeysSetIsRefusedRatherThanQuietlyUnprotected() {
        // The ring is built in memory when the service starts, so a restarted one comes up with an empty
        // one.
        Shell.Result reply =
                runner.exporting(KEYS_ENVIRONMENT, "anna:agent:secret").run("serve", "--reload");
        assertThat(reply.code()).isNotZero();
        assertThat(reply.output()).contains("reload cannot carry the keys");
    }

    @Test
    void servingSaysWhenNothingIsProtectingIt() {
        List<Bound> started = new ArrayList<>();
        Wiring fake = Wiring.standard().withServer((settings, database, keys, host, port, listening) -> {
            started.add(new Bound(host, port));
            listening.accept(port);
        });
        Shell.Result reply = runner.wiredWith(fake).run("serve", "--port", "9001");
        assertThat(reply.code()).isZero();
        assertThat(reply.output()).contains("no keys are set");
        assertThat(reply.output()).contains("listening on 127.0.0.1:9001");
        assertThat(started).containsExactly(new Bound("127.0.0.1", 9001));
    }

    @Test
    void servingWithKeysSaysHowMany() {
        Wiring fake = Wiring.standard().withServer((settings, database, keys, host, port, listening) -> {});
        Shell.Result reply = runner.wiredWith(fake)
                .exporting(KEYS_ENVIRONMENT, "anna:agent:secret,ops:operator:other")
                .run("serve");
        assertThat(reply.output()).contains("2 keys loaded");
        assertThat(reply.output()).doesNotContain("no keys are set");
    }

    @Test
    void theServiceAnswersUntilItIsStoppedAndThenTheCommandReturns() throws Exception {
        StringWriter out = new StringWriter();
        Terminal terminal = new Terminal(
                Map.of(
                        "GUSSETLINE_DATABASE_URL",
                        env,
                        "GUSSETLINE_ARCHIVE_ROOT",
                        tmp.resolve("raw").toString()),
                new BufferedReader(new StringReader("")),
                new PrintWriter(out, true),
                new PrintWriter(new StringWriter(), true));
        AtomicInteger code = new AtomicInteger(-1);
        Thread serving = new Thread(() -> code.set(Main.run(terminal, "serve", "--port", "0")));
        serving.start();
        try {
            int port = portOnceListening(out);
            try (HttpClient http = HttpClient.newHttpClient()) {
                HttpResponse<String> health = http.send(
                        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                                .build(),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(health.statusCode()).isEqualTo(200);
                assertThat(health.body()).contains("\"status\":\"up\"");
            }
        } finally {
            serving.interrupt();
            serving.join(Duration.ofSeconds(60));
        }
        assertThat(serving.isAlive()).isFalse();
        assertThat(code.get()).isZero();
    }

    private static int portOnceListening(StringWriter out) throws InterruptedException {
        Instant giveUp = Instant.now().plusSeconds(120);
        while (Instant.now().isBefore(giveUp)) {
            Matcher listening = LISTENING.matcher(out.toString());
            if (listening.find()) {
                return Integer.parseInt(listening.group(1));
            }
            Thread.sleep(100);
        }
        throw new AssertionError("serve never said where it was listening: " + out);
    }
}
