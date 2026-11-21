package io.github.rkaushikdev.gussetline.cli;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.Charset;
import java.util.Map;
import java.util.Objects;

/**
 * What a command talks to: the environment it was started in, the keyboard, and the two streams.
 *
 * <p>What a command reports goes to {@code out}. What went wrong, and the desk's own log lines, go
 * to {@code err}, so a cron job can keep the report and mail the rest.
 *
 * @param environment the variables the process was started with, which is where the settings come
 *     from
 * @param in what the operator types
 * @param out where a command's report goes
 * @param err where warnings, refusals and the log go
 */
public record Terminal(Map<String, String> environment, BufferedReader in, PrintWriter out, PrintWriter err) {

    /** Keeps a copy of the environment, so a caller that changes its map cannot reach a running command. */
    public Terminal {
        environment = Map.copyOf(environment);
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(err, "err");
    }

    /** The process's own environment, keyboard and streams. */
    public static Terminal system() {
        Charset charset = Charset.defaultCharset();
        return new Terminal(
                System.getenv(),
                new BufferedReader(new InputStreamReader(System.in, charset)),
                new PrintWriter(System.out, true, charset),
                new PrintWriter(System.err, true, charset));
    }
}
