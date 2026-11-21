package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.ConfigError;
import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.NotFoundError;
import io.github.rkaushikdev.gussetline.TariffError;
import io.github.rkaushikdev.gussetline.ValidationError;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.persistence.Database;
import java.util.List;
import java.util.Objects;

/**
 * What every command is handed, and the three codes it can exit with.
 *
 * <p>{@link #OK} is success. {@link #OPERATOR_CAN_FIX} is something the operator can fix: a market
 * with no fares filed, a ticket number that does not exist, a setting that is not a number. {@link
 * #SOMEBODY_SHOULD_LOOK} is something they cannot: a supplier is down, the database is unreachable,
 * a payload did not parse.
 *
 * <p>The split matters because these commands run under cron. A one means the run found nothing to
 * do and the night shift can ignore it; a two means somebody should look. A command line that
 * returns one code for both produces either a pager that goes off every night or one that never goes
 * off.
 *
 * <p>The root command reads the settings once and begins the desk with them before any command
 * runs, so every command takes the same settings and the same database, and a command cannot quietly
 * open a second one. Nothing connects until a command asks for the database, and the root closes it
 * once the command is done.
 */
final class Desk implements AutoCloseable {

    /** It worked. */
    static final int OK = 0;

    /** Something the operator can fix tonight. */
    static final int OPERATOR_CAN_FIX = 1;

    /** Something somebody should look at. */
    static final int SOMEBODY_SHOULD_LOOK = 2;

    /** Which failures the operator can do something about tonight. */
    static final List<Class<? extends GussetlineError>> FIXABLE =
            List.of(NotFoundError.class, ValidationError.class, TariffError.class, ConfigError.class);

    private final Terminal terminal;
    private final Wiring wiring;
    private Settings settings;
    private boolean quiet;
    private Database database;

    Desk(Terminal terminal, Wiring wiring) {
        this.terminal = Objects.requireNonNull(terminal, "terminal");
        this.wiring = Objects.requireNonNull(wiring, "wiring");
    }

    /**
     * Turn a failure into the code cron will read: {@link #OPERATOR_CAN_FIX} for one of the {@link
     * #FIXABLE} kinds, and {@link #SOMEBODY_SHOULD_LOOK} for anything else, a supplier's failure
     * included.
     */
    static int exitCodeFor(Throwable failure) {
        for (Class<? extends GussetlineError> fixable : FIXABLE) {
            if (fixable.isInstance(failure)) {
                return OPERATOR_CAN_FIX;
            }
        }
        return SOMEBODY_SHOULD_LOOK;
    }

    /** Begin a run against these settings. The root command calls this once, before any command runs. */
    void begin(Settings chosen, boolean quietly) {
        this.settings = Objects.requireNonNull(chosen, "chosen");
        this.quiet = quietly;
    }

    /** The settings this run uses. */
    Settings settings() {
        if (settings == null) {
            throw new IllegalStateException("the desk is asked for its settings before the run began");
        }
        return settings;
    }

    /** The database the settings name, made the first time a command asks and closed when the run ends. */
    Database database() {
        if (database == null) {
            database = new Database(settings());
        }
        return database;
    }

    /** Whether the run was told to say nothing on success. */
    boolean quiet() {
        return quiet;
    }

    /** The streams and the environment the run was started with. */
    Terminal terminal() {
        return terminal;
    }

    /** Where the commands reach outside the process. */
    Wiring wiring() {
        return wiring;
    }

    /** Report a line, unless the run was told to be quiet. */
    void say(String message) {
        if (!quiet) {
            terminal.out().println(message);
        }
    }

    /** Report a line whether or not the run was told to be quiet, because it is what was asked for. */
    void echo(String message) {
        terminal.out().println(message);
    }

    /** Say what went wrong. Never quietened: a quiet run still says what went wrong. */
    void warn(String message) {
        terminal.err().println(message);
    }

    /** Let go of the database, if a command opened it. Closing twice does nothing more. */
    @Override
    public void close() {
        if (database != null) {
            database.close();
            database = null;
        }
    }
}
