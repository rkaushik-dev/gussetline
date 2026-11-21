package io.github.rkaushikdev.gussetline.cli;

import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import picocli.CommandLine.ITypeConverter;
import picocli.CommandLine.TypeConversionException;

/**
 * How the values on a command line are read, each refusing in the sentence the operator sees after
 * {@code Invalid value for '--on': }.
 */
final class Values {

    private Values() {}

    /** A day, written {@code 2025-08-04}; the month and the day may drop a leading nought. */
    static final class Day implements ITypeConverter<LocalDate> {

        private static final DateTimeFormatter FORMAT =
                DateTimeFormatter.ofPattern("uuuu-M-d", Locale.ROOT).withResolverStyle(ResolverStyle.STRICT);

        @Override
        public LocalDate convert(String text) {
            try {
                return LocalDate.parse(text, FORMAT);
            } catch (DateTimeParseException notADay) {
                throw new TypeConversionException(Text.repr(text) + " does not match the format '%Y-%m-%d'.");
            }
        }
    }

    /** A whole number, white space at the ends and underscores between digits allowed. */
    static final class WholeNumber implements ITypeConverter<Integer> {

        @Override
        public Integer convert(String text) {
            try {
                return Numbers.parseInt(text);
            } catch (NumberFormatException notANumber) {
                throw new TypeConversionException(Text.repr(text) + " is not a valid integer.");
            }
        }
    }

    /** A file that is there, is not a directory, and can be read. */
    static final class ExistingFile implements ITypeConverter<Path> {

        @Override
        public Path convert(String text) {
            Path path;
            try {
                path = Path.of(text);
            } catch (InvalidPathException unusable) {
                throw refused(text, "does not exist");
            }
            if (!Files.exists(path)) {
                throw refused(text, "does not exist");
            }
            if (Files.isDirectory(path)) {
                throw refused(text, "is a directory");
            }
            if (!Files.isReadable(path)) {
                throw refused(text, "is not readable");
            }
            return path;
        }

        private static TypeConversionException refused(String text, String why) {
            return new TypeConversionException("File " + Text.repr(text) + " " + why + ".");
        }
    }
}
