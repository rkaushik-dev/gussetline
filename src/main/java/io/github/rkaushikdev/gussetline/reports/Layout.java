package io.github.rkaushikdev.gussetline.reports;

import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.StringJoiner;

/**
 * How a report is put on the page, in one place rather than in every report.
 *
 * <p>Every report has the same shape: a heading, a block of name and value pairs, then some number
 * of lists that are usually too long to print whole.
 *
 * <p>The names align on the widest name rather than on a width somebody typed after counting the
 * longest word at the time. An operator reads these down the left edge at eight in the morning, and
 * a column that lands somewhere else in each report is one they have to find again every time.
 *
 * <p>A list that is cut says what it cut. A report with eleven claims in it that prints ten and says
 * nothing looks complete, and the number that matters, how many there were, is the one not on the
 * page.
 *
 * <p>Nothing here knows what a report means. It is where the spacing lives, so that the reports can
 * be about airfare. Widths are counted in characters (code points), and values are printed in their
 * plain form ({@link Text#plain}).
 */
public final class Layout {

    /**
     * Space between the longest name and the values. Two, because one reads as a single wide word and
     * three starts to look like two columns of unrelated things.
     */
    public static final int GUTTER = 2;

    /**
     * What goes in front of a row inside a section. Deep enough to see, shallow enough that a long
     * line still fits a terminal somebody has not maximised.
     */
    public static final String INDENT = "  ";

    /**
     * One line of a report's block of figures.
     *
     * @param name what the figure is, printed on the left
     * @param value the figure itself, printed in its plain form; absent reads {@code None}
     */
    public record Figure(String name, Object value) {

        public Figure {
            Objects.requireNonNull(name, "name");
        }
    }

    private Layout() {}

    /**
     * Name and value on each line, aligned on the widest name.
     *
     * <p>Derived rather than typed, so the alignment follows whatever the report decides to report,
     * which is the only version of this that survives a figure being added. No figures is no lines.
     */
    public static List<String> figures(List<Figure> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        int width = rows.stream().mapToInt(row -> Text.length(row.name())).max().orElseThrow() + GUTTER;
        return rows.stream()
                .map(row -> row.name() + " ".repeat(width - Text.length(row.name())) + Text.plain(row.value()))
                .toList();
    }

    /** Name and value on each line, aligned on the widest name. */
    public static List<String> figures(Figure... rows) {
        return figures(List.of(rows));
    }

    /**
     * An indented list under its own title, every row of it on the page.
     *
     * <p>Nothing at all when there is nothing to show, because an empty heading reads as a report that
     * failed rather than as a desk with no problems.
     */
    public static List<String> section(String title, Iterable<?> rows) {
        List<Object> kept = listed(rows);
        return section(title, kept, kept.size());
    }

    /**
     * An indented list under its own title, cut at {@code limit} rows and honest about what it left
     * out.
     *
     * <p>A list that fits prints its title plainly. A list that does not says how much of it is on
     * the page, {@code gaps, showing 10 of 200}, since the count is usually the finding: ten claims
     * is a busy week and two hundred is a supplier having an outage, and both of them print ten rows.
     * A negative limit leaves that many rows off the end. Nothing at all when there is nothing to
     * show.
     */
    public static List<String> section(String title, Iterable<?> rows, int limit) {
        List<Object> kept = listed(rows);
        int shown = limit < 0 ? Math.max(0, kept.size() + limit) : Math.min(limit, kept.size());
        return section(title, kept, shown);
    }

    /** The blocks of a report, in order, as the one string a command prints: one line per line. */
    @SafeVarargs
    public static String page(List<String>... blocks) {
        StringJoiner lines = new StringJoiner("\n");
        for (List<String> block : blocks) {
            block.forEach(lines::add);
        }
        return lines.toString();
    }

    /** A title, whatever qualifies it, and the blank line before the figures. */
    public static List<String> heading(String title, String... under) {
        List<String> lines = new ArrayList<>(under.length + 2);
        lines.add(title);
        lines.addAll(List.of(under));
        lines.add("");
        return List.copyOf(lines);
    }

    private static List<String> section(String title, List<Object> kept, int shown) {
        if (kept.isEmpty()) {
            return List.of();
        }
        String head = shown == kept.size() ? title : title + ", showing " + shown + " of " + kept.size();
        List<String> lines = new ArrayList<>(shown + 2);
        lines.add("");
        lines.add(head);
        for (Object row : kept.subList(0, shown)) {
            lines.add(INDENT + Text.plain(row));
        }
        return List.copyOf(lines);
    }

    /** The rows in order, read once, so a one-pass source is read exactly once. */
    private static List<Object> listed(Iterable<?> rows) {
        List<Object> kept = new ArrayList<>();
        rows.forEach(kept::add);
        return kept;
    }
}
