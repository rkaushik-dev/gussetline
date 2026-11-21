package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.MalformedPayload;
import io.github.rkaushikdev.gussetline.geography.Geography;
import io.github.rkaushikdev.gussetline.money.Money;
import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.web.util.HtmlUtils;

/**
 * The low fare calendar, which is a web page and not an interface.
 *
 * <p>The third supplier has no API. It has a month grid of dates and prices meant for a person, and
 * the desk reads it because a shopping request against the pricing gateway costs money and this does
 * not. What it gives back is not an offer, because a calendar cell has no flights in it. It is a
 * {@link Quote}: a market, a date, and a number the airline is prepared to advertise.
 *
 * <p>That distinction is the whole point of keeping this separate. A quote cannot be audited,
 * repriced from the tariff or watched for a reissue. What it can do is tell the scheduler which
 * dates are worth spending a real shopping request on, which is worth a great deal when the quota is
 * a thousand a day and the calendar covers three hundred and thirty dates.
 *
 * <p>The page is read as markup rather than matched with a pattern. It has attributes in either
 * quoting style and in any order, and a thousand separator that changes with the locale header, and
 * none of that is worth fighting with a pattern. The page names its market, currency and carrier in
 * {@code meta} tags ({@code fare-market} as {@code FRA-JFK}, {@code fare-currency}, {@code
 * fare-carrier}), and each day is a {@code td} or {@code div} whose class includes {@code fare-day},
 * with the date in {@code data-date}, the price in {@code data-amount} or in its text, and the seats
 * left, if the page says, in {@code data-seats}. Character references are decoded as HTML 4.01
 * defines them.
 */
public final class Calendar {

    /** The name the harvests from this reader carry. */
    public static final String SOURCE = "calendar";

    private Calendar() {}

    /**
     * Read an advertised price out of whatever the page wrapped it in.
     *
     * <p>Handles a currency symbol on either side, a non breaking space between the number and the
     * symbol, and both thousand separator conventions: when a comma and a point both appear,
     * whichever comes last is the decimal one, and a comma alone is a decimal point only when two
     * digits follow it. A cell that says sold out has no price and gives none rather than zero.
     *
     * @throws io.github.rkaushikdev.gussetline.ValidationError when the currency is not one the desk
     *     sells in
     */
    public static Optional<Money> readPrice(String text, String currency) {
        // Everything that is not a digit or a separator goes first, which covers the currency
        // symbol, the non breaking space and the Swiss apostrophe in one pass and leaves only the
        // separator question to decide.
        StringBuilder kept = new StringBuilder();
        text.codePoints()
                .filter(character -> Character.isDigit(character) || ".,-".indexOf(character) >= 0)
                .forEach(kept::appendCodePoint);
        String cleaned = kept.toString();
        if (cleaned.codePoints().noneMatch(Character::isDigit)) {
            return Optional.empty();
        }
        int comma = cleaned.lastIndexOf(',');
        int point = cleaned.lastIndexOf('.');
        if (comma >= 0 && point >= 0) {
            cleaned = comma > point ? cleaned.replace(".", "").replace(",", ".") : cleaned.replace(",", "");
        } else if (comma >= 0) {
            String head = cleaned.substring(0, comma);
            String tail = cleaned.substring(comma + 1);
            cleaned = Text.length(tail) == 2 ? head + "." + tail : cleaned.replace(",", "");
        }
        BigDecimal amount;
        try {
            amount = Numbers.parseDecimal(cleaned);
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
        return Optional.of(new Money(amount, currency));
    }

    /** Read a month grid sent as UTF-8 bytes, a sequence that is not UTF-8 becoming a replacement character. */
    public static Harvest parse(byte[] payload, LocalDateTime observedAt) {
        return parse(new String(payload, StandardCharsets.UTF_8), observedAt);
    }

    /**
     * Read a month grid into quotes.
     *
     * <p>A day with a date that does not read, or with text but no price that reads, is a problem on
     * the harvest and the rest of the month still reads. A day marked sold out, or with no text at
     * all, is a sold out quote.
     *
     * @throws MalformedPayload when the page does not say which market or which currency it is for
     * @throws io.github.rkaushikdev.gussetline.ValidationError when an end of the market is not a
     *     point the desk knows, or the currency is not one it sells in
     */
    public static Harvest parse(String payload, LocalDateTime observedAt) {
        Grid grid = new Grid();
        new Markup(payload, grid).read();

        if (grid.origin.isEmpty() || grid.destination.isEmpty()) {
            throw new MalformedPayload("the page does not say which market it is for");
        }
        if (grid.currency.isEmpty()) {
            throw new MalformedPayload("the page does not say which currency it is in");
        }

        String origin = Geography.cityOf(grid.origin);
        String destination = Geography.cityOf(grid.destination);
        Harvest harvest = new Harvest(SOURCE);

        for (Cell cell : grid.cells) {
            String rawDate = cell.attribute("data-date");
            LocalDate departure;
            try {
                departure = LocalDate.parse(rawDate);
            } catch (DateTimeParseException unreadable) {
                harvest.addProblem("cell with an unreadable date " + Text.repr(rawDate));
                continue;
            }

            boolean soldOut = cell.attribute("class").contains("sold-out") || Text.isBlank(cell.text());
            String advertised = cell.attribute("data-amount");
            Optional<Money> amount = readPrice(advertised.isEmpty() ? cell.text() : advertised, grid.currency);
            if (amount.isEmpty() && !soldOut) {
                harvest.addProblem("cell on " + rawDate + " has no price we could read");
                continue;
            }

            harvest.addQuote(new Quote(
                    origin,
                    destination,
                    departure,
                    amount.orElseGet(() -> Money.zero(grid.currency)),
                    observedAt,
                    grid.carrier,
                    seats(cell.attribute("data-seats")),
                    soldOut || amount.isEmpty()));
        }

        return harvest;
    }

    private static Optional<Integer> seats(String raw) {
        try {
            return Optional.of(Numbers.parseInt(raw));
        } catch (NumberFormatException unreadable) {
            return Optional.empty();
        }
    }

    /**
     * One day of the grid: its attributes by name and the text inside it, without surrounding white
     * space.
     */
    private record Cell(Map<String, String> attributes, String text) {

        /** An attribute's value, or empty text when the cell has none by that name. */
        String attribute(String name) {
            return attributes.getOrDefault(name, "");
        }
    }

    /** Pulls the market, the currency, the carrier and the fare cells out of the month grid. */
    private static final class Grid {

        private static final Set<String> CELL_TAGS = Set.of("td", "div");

        private String currency = "";
        private String origin = "";
        private String destination = "";
        private String carrier = "";
        private final List<Cell> cells = new ArrayList<>();
        private Map<String, String> open;
        private final StringBuilder text = new StringBuilder();

        void start(String tag, Map<String, String> values) {
            String name = values.get("name");
            if (tag.equals("meta") && "fare-currency".equals(name)) {
                currency = upper(values.getOrDefault("content", ""));
            }
            if (tag.equals("meta") && "fare-market".equals(name)) {
                String market = values.getOrDefault("content", "");
                int dash = market.indexOf('-');
                if (dash >= 0) {
                    origin = upper(Text.strip(market.substring(0, dash)));
                    destination = upper(Text.strip(market.substring(dash + 1)));
                }
            }
            if (tag.equals("meta") && "fare-carrier".equals(name)) {
                carrier = upper(values.getOrDefault("content", ""));
            }
            if (CELL_TAGS.contains(tag) && values.getOrDefault("class", "").contains("fare-day")) {
                open = new LinkedHashMap<>(values);
                text.setLength(0);
            }
        }

        void data(String data) {
            if (open != null) {
                text.append(data);
            }
        }

        /** Any closing {@code td} or {@code div} closes the open cell, whichever element opened it. */
        void end(String tag) {
            if (open != null && CELL_TAGS.contains(tag)) {
                cells.add(new Cell(open, Text.strip(text.toString())));
                open = null;
                text.setLength(0);
            }
        }

        private static String upper(String value) {
            return value.toUpperCase(Locale.ROOT);
        }
    }

    /**
     * Walks a page's markup and hands its start tags, text and end tags to the grid, in order.
     *
     * <p>The tokens are the ones HTML5 defines. Tag and attribute names are read in lower case, and
     * inside a tag only space, tab, line feed, carriage return and form feed separate things. An
     * attribute's value follows a single {@code =}, quoted either way or bare; an attribute with no
     * value reads as empty text, and when a tag names an attribute twice the last one counts. A tag
     * closed with {@code />} starts and ends at once. An end tag's name follows the {@code </}
     * directly, and anything else there is a comment. Comments, declarations, marked sections and
     * processing instructions are passed over, and the content of a {@code script} or {@code style}
     * element is text rather than markup. A construct the page never finishes ends the read where it
     * starts.
     */
    private static final class Markup {

        private static final Set<String> RAW_TEXT = Set.of("script", "style");

        private final String page;
        private final Grid grid;
        private int at;

        Markup(String page, Grid grid) {
            this.page = page;
            this.grid = grid;
        }

        void read() {
            int length = page.length();
            while (at < length) {
                int opening = page.indexOf('<', at);
                if (opening < 0) {
                    text(page.substring(at));
                    return;
                }
                if (opening > at) {
                    text(page.substring(at, opening));
                }
                at = opening;
                if (!readConstruct()) {
                    return;
                }
            }
        }

        /** Read whatever opens at a {@code <}. False when the page ends before it does. */
        private boolean readConstruct() {
            if (page.startsWith("<!--", at)) {
                // From the second character, so that <!--> and <!---> close where they stand.
                return skipPast("-->", at + 2);
            }
            if (page.startsWith("</", at)) {
                return readEndTag();
            }
            if (page.startsWith("<![", at)) {
                return skipPast("]]>", at + 3);
            }
            if (page.startsWith("<!", at) || page.startsWith("<?", at)) {
                return skipPast(">", at + 2);
            }
            if (at + 1 < page.length() && isAsciiLetter(page.charAt(at + 1))) {
                return readStartTag();
            }
            text("<");
            at++;
            return true;
        }

        private boolean skipPast(String closing, int from) {
            int found = page.indexOf(closing, from);
            if (found < 0) {
                return false;
            }
            at = found + closing.length();
            return true;
        }

        private boolean readEndTag() {
            int closing = page.indexOf('>', at + 2);
            if (closing < 0) {
                return false;
            }
            String inside = page.substring(at + 2, closing);
            if (!inside.isEmpty() && isAsciiLetter(inside.charAt(0))) {
                int end = 0;
                while (end < inside.length() && !endsTagName(inside.charAt(end))) {
                    end++;
                }
                grid.end(inside.substring(0, end).toLowerCase(Locale.ROOT));
            }
            at = closing + 1;
            return true;
        }

        private boolean readStartTag() {
            int length = page.length();
            int cursor = at + 1;
            int nameEnd = cursor;
            while (nameEnd < length && !endsTagName(page.charAt(nameEnd))) {
                nameEnd++;
            }
            String tag = page.substring(cursor, nameEnd).toLowerCase(Locale.ROOT);
            Map<String, String> attributes = new LinkedHashMap<>();
            cursor = nameEnd;

            while (true) {
                cursor = skipSeparators(cursor);
                if (cursor >= length) {
                    return false;
                }
                char next = page.charAt(cursor);
                if (next == '>' || next == '/') {
                    boolean closed = next == '/';
                    at = cursor + (closed ? 2 : 1);
                    grid.start(tag, attributes);
                    if (closed) {
                        grid.end(tag);
                        return true;
                    }
                    return !RAW_TEXT.contains(tag) || readRawText(tag);
                }

                int nameStart = cursor++;
                while (cursor < length && !endsAttributeName(page.charAt(cursor))) {
                    cursor++;
                }
                String attribute = page.substring(nameStart, cursor).toLowerCase(Locale.ROOT);
                String value = "";
                int indicator = skipSpace(cursor);
                if (indicator < length && page.charAt(indicator) == '=') {
                    int valueStart = skipSpace(indicator + 1);
                    if (valueStart >= length) {
                        return false;
                    }
                    char quote = page.charAt(valueStart);
                    if (quote == '\'' || quote == '"') {
                        int closing = page.indexOf(quote, valueStart + 1);
                        if (closing < 0) {
                            return false;
                        }
                        value = page.substring(valueStart + 1, closing);
                        cursor = closing + 1;
                    } else {
                        int valueEnd = valueStart;
                        while (valueEnd < length
                                && page.charAt(valueEnd) != '>'
                                && !isTagSpace(page.charAt(valueEnd))) {
                            valueEnd++;
                        }
                        value = page.substring(valueStart, valueEnd);
                        cursor = valueEnd;
                    }
                    value = HtmlUtils.htmlUnescape(value);
                }
                attributes.put(attribute, value);
            }
        }

        /** The content of a script or style element, up to its end tag in any case, as text. */
        private boolean readRawText(String tag) {
            int from = at;
            while (true) {
                int found = page.indexOf("</", from);
                int after = found + 2 + tag.length();
                if (found < 0 || after >= page.length()) {
                    return false;
                }
                if (page.regionMatches(true, found + 2, tag, 0, tag.length()) && endsTagName(page.charAt(after))) {
                    grid.data(page.substring(at, found));
                    at = found;
                    return true;
                }
                from = found + 1;
            }
        }

        private void text(String raw) {
            grid.data(HtmlUtils.htmlUnescape(raw));
        }

        /** Past white space, and past any slash that does not close the tag. */
        private int skipSeparators(int from) {
            int cursor = from;
            while (cursor < page.length()) {
                char next = page.charAt(cursor);
                boolean slash = next == '/' && !(cursor + 1 < page.length() && page.charAt(cursor + 1) == '>');
                if (!isTagSpace(next) && !slash) {
                    break;
                }
                cursor++;
            }
            return cursor;
        }

        private int skipSpace(int from) {
            int cursor = from;
            while (cursor < page.length() && isTagSpace(page.charAt(cursor))) {
                cursor++;
            }
            return cursor;
        }

        /** The white space that separates things inside a tag. */
        private static boolean isTagSpace(char character) {
            return switch (character) {
                case '\t', '\n', '\r', '\f', ' ' -> true;
                default -> false;
            };
        }

        private static boolean endsTagName(char character) {
            return isTagSpace(character) || character == '/' || character == '>';
        }

        private static boolean endsAttributeName(char character) {
            return isTagSpace(character) || character == '/' || character == '=' || character == '>';
        }

        private static boolean isAsciiLetter(char character) {
            return (character >= 'a' && character <= 'z') || (character >= 'A' && character <= 'Z');
        }
    }
}
