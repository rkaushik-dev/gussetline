package io.github.rkaushikdev.gussetline.logs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.AppenderBase;
import io.github.rkaushikdev.gussetline.util.Text;
import java.io.IOException;
import java.io.Writer;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * Writes each event as one line to a writer it was handed: the local time to the millisecond, the
 * level, then the message.
 *
 * <pre>2025-08-04 10:25:00,123 INFO event=night.started run=3f2a9c1b7d4e watches=9</pre>
 *
 * <p>Stopping the appender never closes the writer. The writer belongs to whoever handed it over,
 * which for the command line is its error stream, and closing that would silence the process.
 */
final class LineAppender extends AppenderBase<ILoggingEvent> {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss,SSS", Locale.ROOT);

    private final Object target;
    private final Writer writer;

    /**
     * @param target what the caller named as the place to write, which decides whether a second
     *     setup is the same one
     * @param writer where the lines go
     */
    LineAppender(Object target, Writer writer) {
        this.target = target;
        this.writer = writer;
    }

    /** Whether this appender writes to the very place named, not merely an equal one. */
    boolean writesTo(Object place) {
        return target == place;
    }

    @Override
    protected void append(ILoggingEvent event) {
        try {
            writer.write(format(event, ZoneId.systemDefault()));
            writer.flush();
        } catch (IOException failure) {
            addError("a log line could not be written", failure);
        }
    }

    /**
     * One event as its line, newline included. A failure attached to the event follows on the lines
     * below as its stack trace.
     */
    static String format(ILoggingEvent event, ZoneId zone) {
        StringBuilder line = new StringBuilder()
                .append(STAMP.format(Instant.ofEpochMilli(event.getTimeStamp()).atZone(zone)))
                .append(' ')
                .append(levelName(event.getLevel()))
                .append(' ')
                .append(event.getFormattedMessage());
        IThrowableProxy failure = event.getThrowableProxy();
        if (failure != null) {
            line.append('\n').append(Text.stripTrailing(ThrowableProxyUtil.asString(failure)));
        }
        return line.append('\n').toString();
    }

    /** The level as the desk's lines spell it: {@code WARNING} rather than {@code WARN}. */
    static String levelName(Level level) {
        return level.equals(Level.WARN) ? "WARNING" : level.toString();
    }
}
