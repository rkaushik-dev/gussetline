package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import java.io.BufferedReader;
import java.io.PrintWriter;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * A terminal for the command line tests: an environment, and what a command printed on each stream
 * and on both together, in the order it was written.
 *
 * <p>A failure that escapes the command line ends a real process with status one once the runtime
 * has printed it, so a run that throws comes back here as code one with the failure beside it.
 */
final class Shell {

    private final Map<String, String> environment;
    private final Wiring wiring;

    Shell() {
        this(Map.of(), Wiring.standard());
    }

    private Shell(Map<String, String> environment, Wiring wiring) {
        this.environment = Map.copyOf(environment);
        this.wiring = wiring;
    }

    /** The same shell with one more variable exported, replacing any value it had. */
    Shell exporting(String name, String value) {
        Map<String, String> more = new LinkedHashMap<>(environment);
        more.put(name, value);
        return new Shell(more, wiring);
    }

    /** The same shell with the commands reaching outside the process through {@code other}. */
    Shell wiredWith(Wiring other) {
        return new Shell(environment, other);
    }

    /** Run one command line. */
    Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        StringWriter both = new StringWriter();
        Terminal terminal = new Terminal(
                environment,
                new BufferedReader(new StringReader("")),
                new PrintWriter(new Both(out, both), true),
                new PrintWriter(new Both(err, both), true));
        try {
            Main.Finished finished = Main.execute(terminal, wiring, args);
            return new Result(finished.code(), out.toString(), err.toString(), both.toString(), finished.refusal());
        } catch (RuntimeException crashed) {
            return new Result(1, out.toString(), err.toString(), both.toString(), Optional.of(crashed));
        }
    }

    /** A file database under {@code directory}, named {@code desk}. */
    static String fileUrl(Path directory) {
        return "jdbc:h2:file:" + directory.resolve("desk");
    }

    /** The database at {@code url}, made the way the command line makes it. The caller closes it. */
    static Database database(String url) {
        return new Database(new Settings().withDatabase(url));
    }

    /** Build the schema in the database at {@code url}. */
    static void createSchema(String url) {
        try (Database database = database(url)) {
            database.createSchema();
        }
    }

    /**
     * What one command line came back with.
     *
     * @param code what the process would exit with
     * @param out what it wrote to standard output
     * @param err what it wrote to standard error
     * @param output both streams together, the way a terminal shows them
     * @param failure the refusal the code was chosen for, or the failure that escaped
     */
    record Result(int code, String out, String err, String output, Optional<RuntimeException> failure) {}

    /** A writer that writes everything to two others. Closing it leaves both open to be read. */
    private static final class Both extends Writer {

        private final StringWriter one;
        private final StringWriter two;

        Both(StringWriter one, StringWriter two) {
            this.one = one;
            this.two = two;
        }

        @Override
        public void write(char[] buffer, int offset, int length) {
            one.write(buffer, offset, length);
            two.write(buffer, offset, length);
        }

        @Override
        public void flush() {
            one.flush();
            two.flush();
        }

        @Override
        public void close() {
            flush();
        }
    }
}
