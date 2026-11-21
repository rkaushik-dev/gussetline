package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.Details;
import io.github.rkaushikdev.gussetline.Gussetline;
import io.github.rkaushikdev.gussetline.GussetlineError;
import io.github.rkaushikdev.gussetline.api.routers.HealthRoutes;
import io.github.rkaushikdev.gussetline.config.Settings;
import io.github.rkaushikdev.gussetline.logs.Logs;
import io.github.rkaushikdev.gussetline.persistence.UnitOfWork;
import io.github.rkaushikdev.gussetline.util.Numbers;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.ParseResult;

/**
 * The root command, {@code gussetline}, which assembles the others and owns the exit code.
 *
 * <p>Nothing here does any work. It reads the settings once, hands them and the one database they
 * name to whichever command was asked for, and turns whatever comes back out of that command into
 * the number cron will read ({@link Desk}). No command starts the service's framework: each one
 * opens the database the settings name when it first needs it, and the root closes it when the
 * command is done.
 *
 * <p>Every line one command writes to the log carries one run identifier, so two runs that overlap
 * can still be pulled apart. A run told to be quiet writes no log line below a warning, unless the
 * level is named as well.
 *
 * <p>A usage error exits {@link Usage#MISUSED}. A command that declines to go on ({@link
 * CommandError}) prints {@code Error: <message>} and exits {@link Desk#OPERATOR_CAN_FIX}. A refusal
 * of the desk's own prints its message and the details beside it, and exits with the code {@link
 * Desk#exitCodeFor} gives it. Anything else is not caught here, so it ends the process with its stack
 * trace.
 *
 * <p>Adding a command is one entry in the list of subcommands below: the command is a class with a
 * constructor taking the {@link Desk}, implementing {@link DeskCommand}.
 */
@Command(
        name = "gussetline",
        version = "gussetline, version " + Gussetline.VERSION,
        description = "Fare audit and reprice desk.",
        subcommands = {
            AuditCommand.class,
            Main.CheckDesk.class,
            CollectCommands.Collect.class,
            Main.CreateSchema.class,
            LoadCommands.Load.class,
            NightCommand.class,
            PriceCommand.class,
            SeedCommand.class,
            Main.ShowSettings.class,
        })
public final class Main {

    @Option(names = "--database", paramLabel = "TEXT", description = "Override the database url.")
    private String databaseUrl;

    @Option(names = "--quiet", description = "Say nothing on success.")
    private boolean quiet;

    @Option(names = "--log-level", paramLabel = "TEXT", description = "How much to write to the log.")
    private String logLevel;

    @Option(names = "--version", versionHelp = true, description = "Show the version and exit.")
    private boolean versionAsked;

    private final Desk desk;

    private Main(Desk desk) {
        this.desk = desk;
    }

    /** The entry point of the {@code gussetline} command. */
    public static void main(String... args) {
        System.exit(run(Terminal.system(), args));
    }

    /**
     * Run one command line against a terminal and answer the code the process exits with.
     *
     * @throws RuntimeException whatever a command threw that is neither a usage error nor a refusal
     */
    public static int run(Terminal terminal, String... args) {
        return execute(terminal, Wiring.standard(), args).code();
    }

    /**
     * {@link #run} with the commands reaching outside the process through {@code wiring}, also handing
     * back the refusal the code was chosen for, when there was one.
     */
    static Finished execute(Terminal terminal, Wiring wiring, String... args) {
        Desk desk = new Desk(terminal, wiring);
        Main main = new Main(desk);
        CommandLine line = new CommandLine(main, new Commands(desk))
                .setExpandAtFiles(false)
                .setOverwrittenOptionsAllowed(true)
                .setAllowOptionsAsOptionParameters(true)
                .setAllowSubcommandsAsOptionParameters(true)
                .setOut(terminal.out())
                .setErr(terminal.err());
        Usage.dress(line);
        try {
            return main.dispatch(line, args);
        } finally {
            desk.close();
            terminal.out().flush();
            terminal.err().flush();
        }
    }

    /**
     * How one command line ended.
     *
     * @param code what the process exits with
     * @param refusal the refusal behind the code, when the command line ended in one
     */
    record Finished(int code, Optional<RuntimeException> refusal) {

        Finished {
            Objects.requireNonNull(refusal, "refusal");
        }

        Finished(int code) {
            this(code, Optional.empty());
        }
    }

    private Finished dispatch(CommandLine line, String... args) {
        ParseResult parsed;
        try {
            parsed = line.parseArgs(args);
        } catch (ParameterException misuse) {
            return new Finished(Usage.refuse(misuse, desk.terminal().err()));
        }
        Integer helped = CommandLine.executeHelpRequest(parsed);
        if (helped != null) {
            return new Finished(helped);
        }
        if (!parsed.hasSubcommand()) {
            if (args.length == 0) {
                line.usage(desk.terminal().out());
                return new Finished(Desk.OK);
            }
            return new Finished(Usage.refuse(
                    new ParameterException(line, "Missing command."),
                    desk.terminal().err()));
        }
        try {
            return new Finished(Logs.inRun("", () -> begin(parsed)));
        } catch (ParameterException misuse) {
            return new Finished(Usage.refuse(misuse, desk.terminal().err()), Optional.of(misuse));
        } catch (CommandError declined) {
            desk.warn("Error: " + declined.getMessage());
            return new Finished(Desk.OPERATOR_CAN_FIX, Optional.of(declined));
        } catch (GussetlineError refused) {
            desk.warn(refused.getMessage());
            return new Finished(Desk.exitCodeFor(refused), Optional.of(refused));
        }
    }

    /** Point the log at the terminal, read the settings once, begin the desk, then run the command. */
    private int begin(ParseResult parsed) {
        // Quiet means quiet. A cron run told to say nothing on success that still writes a line per
        // event fills the night shift's mail, and the level can still be named when somebody wants both.
        boolean named = logLevel != null && !logLevel.isEmpty();
        Logs.setup(
                named ? logLevel : quiet ? "WARNING" : "INFO", desk.terminal().err());

        Settings settings = Settings.fromEnv(desk.terminal().environment());
        if (databaseUrl != null && !databaseUrl.isEmpty()) {
            settings = settings.withDatabase(databaseUrl);
        }
        desk.begin(settings, quiet);
        Logs.event(
                "command.started",
                Details.of("command", parsed.subcommand().commandSpec().name(), "database", settings.databaseUrl()));
        return call(parsed);
    }

    /** Run the command the line asked for, or show the help of a group nothing was asked of. */
    private int call(ParseResult parsed) {
        ParseResult asked = parsed;
        while (asked.hasSubcommand()) {
            asked = asked.subcommand();
        }
        if (asked.commandSpec().userObject() instanceof DeskCommand command) {
            return command.call();
        }
        asked.commandSpec().commandLine().usage(desk.terminal().out());
        return Desk.OK;
    }

    /** Makes every command, handing the desk to those that take it. */
    private record Commands(Desk desk) implements CommandLine.IFactory {

        @Override
        public <K> K create(Class<K> type) throws Exception {
            try {
                return type.getDeclaredConstructor(Desk.class).newInstance(desk);
            } catch (NoSuchMethodException takesNoDesk) {
                return CommandLine.defaultFactory().create(type);
            }
        }
    }

    /** Print what this run would use, which is the first thing to check. */
    @Command(name = "settings", description = "Print what this run would use, which is the first thing to check.")
    static final class ShowSettings implements DeskCommand {

        private final Desk desk;

        ShowSettings(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            Settings settings = desk.settings();
            desk.echo(settings.toString());
            desk.echo("quota      " + settings.dailyQuota());
            desk.echo("rate       " + Numbers.floatText(settings.ratePerSecond()) + "/s burst " + settings.burst());
            desk.echo("archive    " + settings.archiveRoot());
            desk.echo("threshold  " + settings.watchThreshold() + " " + settings.sellingCurrency());
            desk.echo("dry run    " + (settings.dryRun() ? "yes" : "no"));

            // What was actually chosen, rather than a screen of defaults with the one that matters
            // buried in it.
            Map<String, String> chosen =
                    new TreeMap<>(Settings.whatIsSet(desk.terminal().environment()));
            desk.echo("");
            if (chosen.isEmpty()) {
                desk.echo("nothing is set in the environment, so these are all defaults");
            } else {
                desk.echo("set in the environment:");
                chosen.forEach((name, value) -> desk.echo("  " + name + "=" + value));
            }
            return Desk.OK;
        }
    }

    /** Build the schema. For a laptop and a first run, not for a deploy. */
    @Command(name = "create-schema", description = "Build the schema. For a laptop and a first run, not for a deploy.")
    static final class CreateSchema implements DeskCommand {

        private final Desk desk;

        CreateSchema(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            desk.database().createSchema();
            desk.say("schema built");
            return Desk.OK;
        }
    }

    /**
     * Say what is in the desk, and optionally whether that is enough.
     *
     * <p>The counts on their own are a reading and not a verdict: two hundred thousand observations is
     * a reassuring number whether or not any of them arrived this month. {@code --fit} asks the same
     * questions the service answers on its fitness route ({@link HealthRoutes#checks}), and exits
     * {@link Desk#OPERATOR_CAN_FIX} when something is missing, so a cron job can gate the night run on
     * it.
     */
    @Command(
            name = "check",
            description = {
                "Say what is in the desk, and optionally whether that is enough.",
                "",
                "The counts on their own are a reading and not a verdict: two hundred thousand observations is a"
                        + " reassuring number whether or not any of them arrived this month. The flag asks the"
                        + " same questions the service answers on its fitness endpoint, and exits one when"
                        + " something is missing, so a cron job can gate the night run on it."
            })
    static final class CheckDesk implements DeskCommand {

        @Option(names = "--fit", description = "Ask whether the desk can do its job.")
        private boolean fit;

        private final Desk desk;

        CheckDesk(Desk desk) {
            this.desk = desk;
        }

        @Override
        public Integer call() {
            long airports;
            long observations;
            long fares;
            long joins;
            long runs;
            Map<String, HealthRoutes.Check> checks;
            try (UnitOfWork work = desk.database().unit()) {
                airports = work.airports().count();
                observations = work.observations().count();
                fares = work.tariff().countFares();
                joins = work.combinability().count();
                runs = work.runs().count();
                checks = fit ? HealthRoutes.checks(work) : Map.of();
            }

            desk.echo("airports      " + airports);
            desk.echo("fares         " + fares);
            desk.echo("combinability " + joins);
            desk.echo("observations  " + observations);
            desk.echo("runs          " + runs);

            if (!fit) {
                return Desk.OK;
            }

            desk.echo("");
            List<String> missing = new ArrayList<>();
            checks.forEach((name, check) -> {
                if (!check.ok()) {
                    missing.add(name);
                }
                desk.echo(String.format(Locale.ROOT, "%s %-14s %s", check.ok() ? "ok " : "no ", name, check.detail()));
            });

            if (!missing.isEmpty()) {
                desk.warn("");
                desk.warn("not fit: " + String.join(", ", missing));
                return Desk.OPERATOR_CAN_FIX;
            }
            return Desk.OK;
        }
    }
}
