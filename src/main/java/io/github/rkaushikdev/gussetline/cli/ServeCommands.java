package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.api.ApiKey;
import io.github.rkaushikdev.gussetline.api.GussetlineApplication;
import io.github.rkaushikdev.gussetline.api.KeyRing;
import io.github.rkaushikdev.gussetline.api.Role;
import io.github.rkaushikdev.gussetline.api.Security;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import io.github.rkaushikdev.gussetline.util.Text;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.function.IntConsumer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

/**
 * Running the service, and issuing the keys that get into it.
 *
 * <p>Two commands and they are together because they are the same conversation. A service with no
 * keys issued lets everything through, which is right on a laptop and wrong everywhere else, and the
 * command that starts it is where somebody finds that out.
 *
 * <p>The keys are held as digests and the plain text is shown once. That is not a policy borrowed from
 * somewhere, it is the only arrangement where a key leaking out of the database is not a key: what is
 * stored cannot be presented, so a dump of the table is a list of names and not a way in.
 *
 * <p>Reload is refused when a key ring is loaded. The ring is built in memory when the service starts,
 * so a service restarted on a code change would come up with an empty one. That is a service that
 * looks like it is protected and is not, which is worse than one that plainly is not.
 */
final class ServeCommands {

    /**
     * How many bytes of randomness a key carries. Thirty two is more than anybody needs and it costs
     * nothing, and a key nobody types by hand may as well be long.
     */
    static final int KEY_BYTES = 32;

    /** Where a ring is read from when one is not built by hand. */
    static final String KEYS_ENVIRONMENT = "GUSSETLINE_API_KEYS";

    private static final SecureRandom RANDOM = new SecureRandom();

    private ServeCommands() {}

    /** Run the service. */
    @Command(name = "serve", description = "Run the service.")
    static final class Serve implements DeskCommand {

        @Option(names = "--host", paramLabel = "TEXT", description = "What to bind to.")
        private String host = "127.0.0.1";

        @Option(
                names = "--port",
                paramLabel = "INTEGER",
                converter = Values.WholeNumber.class,
                description = "What to listen on.")
        private int port = 8000;

        @Option(names = "--reload", description = "Restart on a code change.")
        private boolean reloading;

        private final Desk desk;

        Serve(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            Optional<KeyRing> ring = ringFromEnvironment(desk.terminal().environment());
            if (reloading && ring.isPresent()) {
                // The ring is built in memory when the service starts, so a restarted one would come up
                // with an empty one.
                throw new CommandError("reload cannot carry the keys, so it is refused with them set");
            }

            if (ring.isEmpty()) {
                desk.warn("no keys are set, so everything is allowed through");
            } else {
                desk.say(ring.get().size() + " keys loaded");
            }

            desk.wiring()
                    .server()
                    .serve(
                            desk.settings(),
                            desk.database(),
                            ring,
                            host,
                            port,
                            listening -> desk.say("listening on " + host + ":" + listening));
            return Desk.OK;
        }
    }

    /** Make a key. The plain text is shown once and never stored. */
    @Command(name = "issue-key", description = "Make a key. The plain text is shown once and never stored.")
    static final class IssueKey implements DeskCommand {

        @Parameters(paramLabel = "NAME")
        private String name;

        @Option(
                names = "--role",
                paramLabel = Values.RoleName.CHOICES,
                converter = Values.RoleName.class,
                description = "What the key may do.")
        private Role role = Role.READER;

        private final Desk desk;

        IssueKey(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            String secret = secret();
            ApiKey made = new ApiKey(Text.strip(name), Security.fingerprint(secret), role);

            desk.say(made.toString());
            desk.say("");
            desk.say("  " + secret);
            desk.say("");
            // Said out loud rather than left to be discovered. What is stored cannot be presented, so
            // there is no way to show this again.
            desk.say("that is the only time it will be shown");
            desk.say("");
            desk.say("add it to the environment as:");
            desk.say("  " + KEYS_ENVIRONMENT + "=" + made.name() + ":"
                    + made.role().value() + ":" + secret);
            return Desk.OK;
        }
    }

    /** {@link #KEY_BYTES} random bytes, written in the URL safe base 64 alphabet without padding. */
    static String secret() {
        byte[] random = new byte[KEY_BYTES];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }

    /**
     * The keys, read as {@code name:role:secret} triples separated by commas.
     *
     * <p>Nothing at all means no ring, which is a service that lets everything through. A ring with
     * nothing in it would be a service that refuses everything, and the difference matters on a laptop.
     *
     * @param environment the variables the process was started with
     * @throws CommandError saying what it saw when an entry is not three parts, each with something in
     *     it, or names a role the service does not have
     */
    static Optional<KeyRing> ringFromEnvironment(Map<String, String> environment) {
        String raw = Text.strip(environment.getOrDefault(KEYS_ENVIRONMENT, ""));
        if (raw.isEmpty()) {
            return Optional.empty();
        }

        KeyRing ring = new KeyRing();
        for (String entry : raw.split(",", -1)) {
            List<String> parts =
                    Arrays.stream(entry.split(":", -1)).map(Text::strip).toList();
            if (parts.size() != 3 || parts.stream().anyMatch(String::isEmpty)) {
                throw new CommandError("a key reads as name:role:secret, not " + Text.repr(entry));
            }
            String name = parts.get(0);
            String role = parts.get(1);
            String secret = parts.get(2);
            Role granted;
            try {
                granted = Role.of(role);
            } catch (IllegalArgumentException notARole) {
                throw new CommandError(Text.repr(role) + " is not a role");
            }
            ring.issue(name, secret, granted);
        }
        return Optional.of(ring);
    }

    /**
     * Start the service, say where it listens, and hold it until it stops.
     *
     * <p>The service starts through {@link GussetlineApplication#run}, on the settings, the database and
     * the keys it is handed, at {@code host} and {@code port}; nought for a port takes any free one, and
     * {@code listening} is told which. It stops when its context closes, which a signal to the process
     * does, or when the thread running it is interrupted, which closes it here. Either way it is closed
     * before this returns, and the database is left to whoever opened it.
     *
     * <p>An interrupt is how a caller that runs {@code serve} on a thread of its own tells it to stop,
     * and stopping is the whole of the answer: the run then ends as it does after a signal, and the
     * database is closed behind it on a thread that is not being interrupted, so its connections are
     * let go of rather than abandoned halfway.
     */
    static void untilStopped(
            Settings settings,
            Database database,
            Optional<KeyRing> keys,
            String host,
            int port,
            IntConsumer listening) {
        ConfigurableApplicationContext service = GussetlineApplication.run(
                settings, database, keys, "--server.address=" + host, "--server.port=" + port);
        try {
            CountDownLatch closed = new CountDownLatch(1);
            service.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    closed.countDown();
                }
            });
            listening.accept(GussetlineApplication.port(service));
            awaitClosing(service, closed);
        } finally {
            service.close();
        }
    }

    /**
     * Wait until the service's context has closed. An interrupt is the request to close it, and closing
     * it is the whole of the answer.
     */
    private static void awaitClosing(ConfigurableApplicationContext service, CountDownLatch closed) {
        if (!service.isActive()) {
            return;
        }
        try {
            closed.await();
        } catch (InterruptedException stopped) {
            service.close();
        }
    }
}
