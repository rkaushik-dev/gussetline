package io.github.rkaushikdev.gussetline.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import io.github.rkaushikdev.gussetline.util.Text;
import io.github.rkaushikdev.gussetline.util.Times;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.Writer;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * What the desk writes down about itself, in the shape a person greps.
 *
 * <p>One line per event, key equals value, sorted. Not JSON: the two people who read these read
 * them over ssh at seven in the morning with grep and cut, and a line they have to pipe through a
 * tool to read is a line they do not read.
 *
 * <pre>2025-08-04 06:00:02,117 INFO event=night.priced run=3f2a9c1b7d4e looked_at=9 raised=2</pre>
 *
 * <p>The correlation is one identifier per command or per request, put on every line that run
 * writes. Without it a night run and a collection that overlap produce one interleaved file and no
 * way to pull either of them out, which is exactly the file somebody needs on the morning both of
 * them went wrong.
 *
 * <p>Three things never reach a line here.
 *
 * <p>A ticket number is a payment instrument. Anybody holding one can call a carrier and change a
 * booking, and a log file is copied to a laptop, mailed to a supplier, and pasted into a ticket
 * tracker. It is masked to its last four.
 *
 * <p>A passenger name is somebody's name. It is not needed to work out what went wrong, and a log
 * that carries it becomes a personal data holding with everything that follows from that.
 *
 * <p>An API key never appears at all, not even masked. A masked key is still a key narrowed down,
 * and there is no question a log answers that needs one.
 *
 * <p>Field names are part of the interface, because somebody's saved search reads them: they are
 * written in lower case with underscores, {@code looked_at}, whatever the caller's own names are.
 */
public final class Logs {

    /** The logger every desk line goes through. */
    public static final String LOGGER_NAME = "gussetline";

    /** What a masked ticket number keeps. */
    public static final int KEPT_DIGITS = 4;

    /** Field names that never reach a line whatever is in them. */
    public static final Set<String> NEVER = Set.of("key", "api_key", "secret", "token", "password");

    /**
     * Field names that are masked rather than dropped, because the shape of the value is what makes
     * a line readable and the value itself is not needed.
     */
    public static final Set<String> MASKED = Set.of("ticket", "ticket_number", "passenger", "passenger_name");

    private static final Logger LOGGER = LoggerFactory.getLogger(LOGGER_NAME);

    /** The one identifier every line in a run shares; empty outside a run. */
    private static final ThreadLocal<String> RUN = ThreadLocal.withInitial(() -> "");

    private static final String QUOTED = " \t=\"'";

    private Logs() {}

    /** A short identifier for one command or one request: twelve hexadecimal digits. */
    public static String newRunId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /**
     * Run {@code block} with every line it writes on this thread under one identifier, and put the
     * one before back afterwards, so a run inside a run restores the outer one.
     *
     * @param runId the identifier, or empty for a new one
     */
    public static <T> T inRun(String runId, Supplier<T> block) {
        String outer = RUN.get();
        RUN.set(runId.isEmpty() ? newRunId() : runId);
        try {
            return block.get();
        } finally {
            if (outer.isEmpty()) {
                RUN.remove();
            } else {
                RUN.set(outer);
            }
        }
    }

    /** {@link #inRun(String, Supplier)} for a block with nothing to hand back. */
    public static void inRun(String runId, Runnable block) {
        inRun(runId, () -> {
            block.run();
            return null;
        });
    }

    /** {@link #inRun(String, Supplier)} under a new identifier, which the block is handed. */
    public static <T> T inRun(Function<String, T> block) {
        String made = newRunId();
        return inRun(made, () -> block.apply(made));
    }

    /** The identifier of the run this thread is in, or empty outside one. */
    public static String currentRun() {
        return RUN.get();
    }

    /**
     * A ticket number reduced to what makes it recognisable and no more: every character but the
     * last four turned to a star, keeping the length. A value with four digits or fewer in it is
     * hidden whole.
     */
    public static String mask(String value) {
        int[] characters = value.codePoints().toArray();
        long digits = value.codePoints().filter(Character::isDigit).count();
        if (digits <= KEPT_DIGITS) {
            return "*".repeat(characters.length);
        }
        int hidden = characters.length - KEPT_DIGITS;
        return "*".repeat(hidden) + new String(characters, hidden, KEPT_DIGITS);
    }

    /**
     * One value, in the form that greps and sorts the way somebody expects.
     *
     * <p>A flag reads {@code yes} or {@code no}, a time is written to the second ({@code
     * 2025-08-04T10:25:00}), a date as {@code 2025-08-04}, and an amount keeps its trailing zeroes.
     * Anything else is its plain text, put in double quotes when it is empty or holds a space, a tab,
     * an equals sign or a quote, with any double quote inside turned into a single one so the field
     * still ends where it should.
     */
    public static String render(Object value) {
        return switch (value) {
            case Boolean flag -> flag ? "yes" : "no";
            case LocalDateTime moment -> Times.isoSeconds(moment);
            case LocalDate day -> day.toString();
            case BigDecimal amount -> amount.toString();
            case null, default -> quotedWhenItWouldSplit(Text.plain(value));
        };
    }

    private static String quotedWhenItWouldSplit(String text) {
        if (text.isEmpty() || text.chars().anyMatch(character -> QUOTED.indexOf(character) >= 0)) {
            return '"' + text.replace('"', '\'') + '"';
        }
        return text;
    }

    /** {@link #line(String, Map)} with no fields. */
    public static String line(String event) {
        return line(event, Map.of());
    }

    /**
     * One event as the line that goes in the file: the event, the run when there is one, then the
     * fields sorted by name.
     *
     * <p>The fields are sorted so two lines for the same event line up under each other in a
     * terminal, which is the whole reason anybody reads these. A field whose value is null or an
     * empty {@link Optional} is left out, a present {@code Optional} is written as what it holds, a
     * {@link #NEVER} field is dropped whatever it holds, and a {@link #MASKED} one is masked.
     */
    public static String line(String event, Map<String, ?> fields) {
        List<String> parts = new ArrayList<>();
        parts.add("event=" + render(event));
        String runId = currentRun();
        if (!runId.isEmpty()) {
            parts.add("run=" + runId);
        }
        for (String name : new TreeSet<>(fields.keySet())) {
            if (NEVER.contains(name)) {
                continue;
            }
            Object value = fields.get(name);
            if (value instanceof Optional<?> held) {
                value = held.orElse(null);
            }
            if (value == null) {
                continue;
            }
            if (MASKED.contains(name)) {
                value = mask(Text.plain(value));
            }
            parts.add(name + "=" + render(value));
        }
        return String.join(" ", parts);
    }

    /** Write one line at the ordinary level. */
    public static void event(String name) {
        event(name, Map.of());
    }

    /** Write one line at the ordinary level. */
    public static void event(String name, Map<String, ?> fields) {
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info(line(name, fields));
        }
    }

    /** Write one line for something somebody has to look at. */
    public static void problem(String name) {
        problem(name, Map.of());
    }

    /** Write one line for something somebody has to look at. */
    public static void problem(String name, Map<String, ?> fields) {
        if (LOGGER.isWarnEnabled()) {
            LOGGER.warn(line(name, fields));
        }
    }

    /** {@link #setup(String, Writer)} at {@code INFO} to standard error. */
    public static void setup() {
        setup("INFO");
    }

    /** {@link #setup(String, Writer)} to standard error. */
    public static void setup(String level) {
        PrintStream standardError = System.err;
        attach(level, standardError, () -> new OutputStreamWriter(standardError, standardError.charset()));
    }

    /** {@link #setup(String, Writer)} at {@code INFO}. */
    public static void setup(Writer stream) {
        setup("INFO", stream);
    }

    /**
     * Point the desk's logger at one writer, once, without duplicating lines.
     *
     * <p>Called by the commands and by the service. Safe to call twice, because a process that sets
     * up twice otherwise writes every line twice and the second copy is the one that makes a file
     * unreadable. A second setup with the same writer changes only the level; one with another writer
     * stops writing to the first. Lines are not handed on to the root logger as well.
     *
     * <p>The writer is never closed here: it belongs to the caller.
     *
     * @param level {@code DEBUG}, {@code INFO}, {@code WARNING} (or {@code WARN}), {@code ERROR},
     *     {@code CRITICAL} (or {@code FATAL}, both as high as {@code ERROR}), or {@code NOTSET} to
     *     follow the root logger, written in any case
     * @param stream where the lines go; the command line hands over its error writer
     * @throws IllegalArgumentException when the level is none of those
     */
    public static void setup(String level, Writer stream) {
        attach(level, stream, () -> stream);
    }

    private static synchronized void attach(String level, Object target, Supplier<Writer> writer) {
        ch.qos.logback.classic.Logger desk = deskLogger();
        desk.setLevel(levelNamed(level));
        desk.setAdditive(false);

        List<Appender<ILoggingEvent>> existing = new ArrayList<>();
        for (Iterator<Appender<ILoggingEvent>> each = desk.iteratorForAppenders(); each.hasNext(); ) {
            existing.add(each.next());
        }
        for (Appender<ILoggingEvent> appender : existing) {
            if (appender instanceof LineAppender ours && ours.writesTo(target)) {
                return;
            }
            desk.detachAppender(appender);
            if (appender instanceof LineAppender ours) {
                ours.stop();
            }
        }

        LineAppender appender = new LineAppender(target, writer.get());
        appender.setContext(desk.getLoggerContext());
        appender.setName("gussetline");
        appender.start();
        desk.addAppender(appender);
    }

    private static Level levelNamed(String level) {
        String name = level.toUpperCase(Locale.ROOT);
        return switch (name) {
            case "DEBUG" -> Level.DEBUG;
            case "INFO" -> Level.INFO;
            case "WARNING", "WARN" -> Level.WARN;
            case "ERROR", "CRITICAL", "FATAL" -> Level.ERROR;
            case "NOTSET" -> null;
            default -> throw new IllegalArgumentException("Unknown level: " + Text.quote(name));
        };
    }

    private static ch.qos.logback.classic.Logger deskLogger() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        return context.getLogger(LOGGER_NAME);
    }
}
