package io.github.rkaushikdev.gussetline.util;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Text handling the whole desk agrees on.
 *
 * <p>White space here is every character Unicode treats as one, including the no-break spaces
 * (U+00A0, U+2007, U+202F) and NEXT LINE (U+0085) that {@link String#strip()} keeps. Supplier
 * payloads and filing files arrive from systems that pad with all of them.
 */
public final class Text {

    private Text() {}

    /** Whether one code point is white space, no-break spaces included. */
    public static boolean isSpace(int codePoint) {
        return Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint) || codePoint == 0x85;
    }

    /** The text with white space removed from both ends. */
    public static String strip(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isSpace(text.charAt(start))) {
            start++;
        }
        while (end > start && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    /** The text with white space removed from the start. */
    public static String stripLeading(String text) {
        int start = 0;
        while (start < text.length() && isSpace(text.charAt(start))) {
            start++;
        }
        return text.substring(start);
    }

    /** The text with white space removed from the end. */
    public static String stripTrailing(String text) {
        int end = text.length();
        while (end > 0 && isSpace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /** A code the way the lookup tables key it: without surrounding white space, in upper case. */
    public static String code(String text) {
        return strip(text).toUpperCase(Locale.ROOT);
    }

    /** Whether the text is empty or nothing but white space. */
    public static boolean isBlank(String text) {
        return strip(text).isEmpty();
    }

    /** The words of a text: split on runs of white space, with no empty words at either end. */
    public static List<String> words(String text) {
        List<String> words = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (isSpace(character)) {
                if (!current.isEmpty()) {
                    words.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(character);
            }
        }
        if (!current.isEmpty()) {
            words.add(current.toString());
        }
        return List.copyOf(words);
    }

    /**
     * The lines of a text, without their line breaks, and with no empty line after a final break.
     *
     * <p>A line ends at a line feed, a carriage return, the pair of the two, or any of the other
     * breaks Unicode and the old record formats use: vertical tab, form feed, the file, group and
     * record separators (U+001C to U+001E), NEXT LINE (U+0085) and the line and paragraph separators
     * (U+2028, U+2029). {@link String#lines()} knows only the first three.
     */
    public static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        int index = 0;
        while (index < text.length()) {
            char character = text.charAt(index);
            if (!isLineBreak(character)) {
                index++;
                continue;
            }
            lines.add(text.substring(start, index));
            boolean pair = character == '\r' && index + 1 < text.length() && text.charAt(index + 1) == '\n';
            index += pair ? 2 : 1;
            start = index;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return List.copyOf(lines);
    }

    /**
     * The characters from {@code start} up to but not including {@code end}, counted in code points
     * so that a character outside the Basic Multilingual Plane is one position, not two.
     *
     * <p>Positions past the end are taken as the end, a negative position counts back from the end,
     * and a range that ends before it starts is empty, so a slice never throws: {@code slice("F1KL",
     * 2, 10)} is {@code "KL"} and {@code slice("F1KL", -2, 4)} is {@code "KL"}.
     */
    public static String slice(String text, int start, int end) {
        int length = text.codePointCount(0, text.length());
        int from = bound(start, length);
        int to = bound(end, length);
        if (to <= from) {
            return "";
        }
        int begin = text.offsetByCodePoints(0, from);
        return text.substring(begin, text.offsetByCodePoints(begin, to - from));
    }

    /** How many characters a text holds, counted in code points. */
    public static int length(String text) {
        return text.codePointCount(0, text.length());
    }

    private static int bound(int position, int length) {
        return position < 0 ? Math.max(0, position + length) : Math.min(position, length);
    }

    private static boolean isLineBreak(char character) {
        return switch (character) {
            case '\n', '\r', '\u000B', '\u000C', '\u001C', '\u001D', '\u001E', '\u0085', ' ', ' ' -> true;
            default -> false;
        };
    }

    /** Whether the text is non-empty and every character in it is a decimal digit. */
    public static boolean isDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isDigit);
    }

    /** Whether the text is non-empty and every character in it is a letter. */
    public static boolean isLetters(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isLetter);
    }

    /** Whether the text is non-empty and every character in it is a letter or a digit. */
    public static boolean isLettersOrDigits(String text) {
        return !text.isEmpty() && text.chars().allMatch(Character::isLetterOrDigit);
    }

    /**
     * A value in the quoted form error details print it in.
     *
     * <p>Text is quoted, in single quotes unless it holds a single quote and no double one, with
     * backslashes, the quote and control characters escaped. An absent value reads {@code None}, a
     * flag {@code True} or {@code False}, and a collection or map shows each of its members in the
     * same quoted form. Anything else is shown as its plain text.
     */
    public static String repr(Object value) {
        if (value == null) {
            return "None";
        }
        if (value instanceof Boolean flag) {
            return flag ? "True" : "False";
        }
        if (value instanceof CharSequence text) {
            return quote(text.toString());
        }
        if (value instanceof Double number) {
            return Numbers.floatText(number);
        }
        if (value instanceof Collection<?> members) {
            return members.stream().map(Text::repr).collect(Collectors.joining(", ", "[", "]"));
        }
        if (value instanceof Map<?, ?> map) {
            return map.entrySet().stream()
                    .map(entry -> repr(entry.getKey()) + ": " + repr(entry.getValue()))
                    .collect(Collectors.joining(", ", "{", "}"));
        }
        if (value instanceof LocalDateTime moment) {
            return Times.iso(moment);
        }
        if (value instanceof LocalDate day) {
            return day.toString();
        }
        return value.toString();
    }

    /**
     * A value in the plain form a trace or a report prints it in.
     *
     * <p>Text is itself, unquoted. Everything else reads as {@link #repr} would show it, except a
     * time, which is written with a space between the date and the clock: {@code 2025-03-04
     * 10:25:00}. Members of a collection or a map are still shown quoted, so {@code ["DE"]} reads
     * {@code ['DE']}.
     */
    public static String plain(Object value) {
        if (value instanceof CharSequence text) {
            return text.toString();
        }
        if (value instanceof LocalDateTime moment) {
            return Times.iso(moment).replace('T', ' ');
        }
        return repr(value);
    }

    /** Text in quotes, escaped so that it reads back unambiguously. */
    public static String quote(String text) {
        char mark = text.indexOf('\'') >= 0 && text.indexOf('"') < 0 ? '"' : '\'';
        StringBuilder out = new StringBuilder(text.length() + 2).append(mark);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            switch (character) {
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character == mark) {
                        out.append('\\').append(character);
                    } else if (character < 0x20 || character == 0x7f) {
                        out.append(String.format(Locale.ROOT, "\\x%02x", (int) character));
                    } else {
                        out.append(character);
                    }
                }
            }
        }
        return out.append(mark).toString();
    }
}
