package io.github.rkaushikdev.gussetline.cli;

import java.io.PrintWriter;
import java.util.List;
import java.util.stream.Collectors;
import picocli.CommandLine;
import picocli.CommandLine.MissingParameterException;
import picocli.CommandLine.Model.ArgSpec;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Model.OptionSpec;
import picocli.CommandLine.Model.PositionalParamSpec;
import picocli.CommandLine.ParameterException;
import picocli.CommandLine.TypeConversionException;
import picocli.CommandLine.UnmatchedArgumentException;

/**
 * How a command line that was not understood is answered, and how every command describes itself.
 *
 * <p>A usage error goes to standard error as the usage line, where to find the help, and one line
 * saying what was wrong, and the run exits {@link #MISUSED}:
 *
 * <pre>
 * Usage: gussetline load fares [OPTIONS] PATH
 * Try 'gussetline load fares -h' for help.
 *
 * Error: Invalid value for 'PATH': File 'lh.dat' does not exist.
 * </pre>
 */
final class Usage {

    /** The arguments were not understood. */
    static final int MISUSED = 2;

    /** What the help option says about itself. */
    static final String HELP = "Show this message and exit.";

    private Usage() {}

    /**
     * Give every command in the tree a {@code -h} and {@code --help} option, a usage line naming its
     * arguments, and its options listed in the order they are declared, the help option last.
     */
    static void dress(CommandLine line) {
        CommandSpec spec = line.getCommandSpec();
        spec.usageMessage()
                .sortOptions(false)
                .customSynopsis(synopsis(spec))
                .descriptionHeading("%n")
                .optionListHeading("%nOptions:%n")
                .commandListHeading("%nCommands:%n");
        if (spec.findOption("--help") == null) {
            spec.addOption(OptionSpec.builder("-h", "--help")
                    .usageHelp(true)
                    .description(HELP)
                    .build());
        }
        line.getSubcommands().values().forEach(Usage::dress);
    }

    /** {@code gussetline load fares [OPTIONS] PATH}, or {@code gussetline [OPTIONS] COMMAND [ARGS]...} for a group. */
    static String synopsis(CommandSpec spec) {
        StringBuilder line = new StringBuilder(spec.qualifiedName()).append(" [OPTIONS]");
        for (PositionalParamSpec positional : spec.positionalParameters()) {
            line.append(' ').append(positional.paramLabel());
        }
        if (!spec.subcommands().isEmpty()) {
            line.append(" COMMAND [ARGS]...");
        }
        return line.toString();
    }

    /** A value a command refused after the line was read, as {@code Invalid value: <why>}. */
    static ParameterException badParameter(CommandSpec spec, String why) {
        return new ParameterException(spec.commandLine(), "Invalid value: " + why);
    }

    /** Write a usage error for the command it was found in, and answer {@link #MISUSED}. */
    static int refuse(ParameterException misuse, PrintWriter err) {
        CommandSpec spec = misuse.getCommandLine().getCommandSpec();
        err.println("Usage: " + synopsis(spec));
        err.println("Try '" + spec.qualifiedName() + " -h' for help.");
        err.println();
        err.println("Error: " + message(misuse));
        return MISUSED;
    }

    /** The one line saying what was wrong. */
    static String message(ParameterException misuse) {
        return switch (misuse) {
            case UnmatchedArgumentException unmatched -> unmatched(unmatched);
            case MissingParameterException missing -> missing(missing);
            default -> converted(misuse);
        };
    }

    private static String unmatched(UnmatchedArgumentException unmatched) {
        List<String> extra = unmatched.getUnmatched();
        if (unmatched.isUnknownOption()) {
            return "No such option: " + extra.getFirst();
        }
        if (!unmatched.getCommandLine().getSubcommands().isEmpty()) {
            return "No such command '" + extra.getFirst() + "'.";
        }
        String plural = extra.size() == 1 ? "argument" : "arguments";
        return "Got unexpected extra " + plural + " (" + String.join(" ", extra) + ")";
    }

    private static String missing(MissingParameterException missing) {
        ArgSpec wanted = missing.getMissing().getFirst();
        if (wanted instanceof OptionSpec option) {
            return missing.getMessage().startsWith("Missing required parameter for option")
                    ? "Option '" + option.longestName() + "' requires an argument."
                    : "Missing option '" + option.longestName() + "'.";
        }
        return "Missing argument '" + wanted.paramLabel() + "'.";
    }

    private static String converted(ParameterException misuse) {
        if (misuse.getCause() instanceof TypeConversionException refused && misuse.getArgSpec() != null) {
            return "Invalid value for " + hint(misuse.getArgSpec()) + ": " + refused.getMessage();
        }
        return misuse.getMessage();
    }

    /** How the error names a value: each of an option's names quoted, or an argument's label. */
    private static String hint(ArgSpec spec) {
        if (spec instanceof OptionSpec option) {
            return List.of(option.names()).stream()
                    .map(name -> "'" + name + "'")
                    .collect(Collectors.joining(" / "));
        }
        return "'" + spec.paramLabel() + "'";
    }
}
