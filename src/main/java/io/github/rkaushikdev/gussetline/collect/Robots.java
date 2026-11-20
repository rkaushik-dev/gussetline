package io.github.rkaushikdev.gussetline.collect;

import io.github.rkaushikdev.gussetline.util.Numbers;
import io.github.rkaushikdev.gussetline.util.Text;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A parsed robots.txt, or the absence of one, because the airline calendar site has one.
 *
 * <p>Only one of the three suppliers is a website rather than an API, and it publishes a robots
 * file with a crawl delay in it. Honouring that is not optional politeness, it is the difference
 * between a desk that keeps its access and one that gets a letter.
 *
 * <p>The parsing rules that matter and that a naive reader gets wrong:
 *
 * <ul>
 *   <li>Groups are keyed on user agent and a record applies to the most specific matching group
 *       only. A file with a group for our agent and a group for star does not mean both apply, it
 *       means ours does and star is ignored.
 *   <li>Inside a group the longest matching path wins, not the first one. A file that disallows
 *       slash and allows slash calendar permits the calendar, and a first match reader refuses it.
 *   <li>An empty disallow means allow everything, which reads like a typo and is the documented way
 *       to open a section back up.
 *   <li>A robots file we could not fetch is not permission. It is unknown, and {@link
 *       #unavailable()} returns a policy that allows, because a supplier we have a contract with is
 *       not the case robots.txt was written for, but the caller can tell the two apart from {@link
 *       #fetched()}.
 * </ul>
 *
 * @param groups the groups, in the order the file wrote them
 * @param sitemaps the sitemap addresses, in the order the file wrote them
 * @param fetched whether there was a file to read at all
 */
public record Robots(List<Group> groups, List<String> sitemaps, boolean fetched) {

    public Robots {
        groups = List.copyOf(groups);
        sitemaps = List.copyOf(sitemaps);
    }

    /**
     * Read a robots file. Never throws, because a broken one is common: a line it cannot use is
     * passed over.
     */
    public static Robots parse(String text) {
        List<GroupDraft> drafts = new ArrayList<>();
        List<String> sitemaps = new ArrayList<>();
        GroupDraft current = null;
        boolean startingGroup = true;

        for (String raw : Text.lines(text)) {
            int hash = raw.indexOf('#');
            String line = Text.strip(hash < 0 ? raw : raw.substring(0, hash));
            int colon = line.indexOf(':');
            if (line.isEmpty() || colon < 0) {
                continue;
            }
            String key = Text.strip(line.substring(0, colon)).toLowerCase(Locale.ROOT);
            String value = Text.strip(line.substring(colon + 1));

            if (key.equals("user-agent")) {
                if (current == null || !startingGroup) {
                    current = new GroupDraft();
                    drafts.add(current);
                    startingGroup = true;
                }
                current.agents.add(value.toLowerCase(Locale.ROOT));
                continue;
            }
            if (key.equals("sitemap")) {
                sitemaps.add(value);
                continue;
            }
            if (current == null) {
                continue;
            }
            startingGroup = false;

            if ((key.equals("disallow") || key.equals("allow")) && !value.isEmpty()) {
                current.rules.add(new Rule(value, key.equals("allow")));
            } else if (key.equals("crawl-delay")) {
                current.readCrawlDelay(value);
            }
        }

        return new Robots(drafts.stream().map(GroupDraft::freeze).toList(), sitemaps, true);
    }

    /**
     * What to use when the file could not be fetched.
     *
     * <p>Permissive, and marked, so a caller that wants to be strict about it can be and one that
     * does not is not silently blocked by a network blip.
     */
    public static Robots unavailable() {
        return new Robots(List.of(), List.of(), false);
    }

    /** The group that applies to an agent: the most specific match, or none when nothing matches. */
    public Optional<Group> groupFor(String agent) {
        Group best = null;
        int bestScore = 0;
        for (Group group : groups) {
            int score = group.specificityFor(agent);
            if (score > bestScore) {
                best = group;
                bestScore = score;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Whether any agent may fetch a path. */
    public boolean allows(String path) {
        return allows(path, "*");
    }

    /** Whether an agent may fetch a path. A file with no group for the agent allows everything. */
    public boolean allows(String path, String agent) {
        return groupFor(agent).map(group -> group.verdictFor(path)).orElse(true);
    }

    /** The crawl delay for any agent, in seconds, or zero when the file names none. */
    public double crawlDelay() {
        return crawlDelay("*");
    }

    /** The crawl delay for an agent, in seconds, or zero when its group names none. */
    public double crawlDelay(String agent) {
        return crawlDelay(agent, 0.0);
    }

    /** The crawl delay for an agent, in seconds, or the fallback when its group names none. */
    public double crawlDelay(String agent, double fallback) {
        return groupFor(agent).flatMap(Group::crawlDelay).orElse(fallback);
    }

    /** How much the file held, or that there was no file. */
    @Override
    public String toString() {
        if (!fetched) {
            return "robots.txt not fetched";
        }
        return groups.size() + " groups, " + sitemaps.size() + " sitemaps";
    }

    /**
     * One allow or disallow line.
     *
     * @param path the path pattern, which may hold a star for any run of characters and end in a
     *     dollar to anchor the end
     * @param allow whether the line allows rather than disallows
     */
    public record Rule(String path, boolean allow) {

        /** How long the pattern is, which is what decides between two lines that both match. */
        public int length() {
            return path.codePointCount(0, path.length());
        }

        /** Whether this line covers a path, honouring star and dollar. An empty pattern covers nothing. */
        public boolean matches(String target) {
            String pattern = path;
            if (pattern.isEmpty()) {
                return false;
            }
            boolean anchoredEnd = pattern.endsWith("$");
            if (anchoredEnd) {
                pattern = pattern.substring(0, pattern.length() - 1);
            }
            if (pattern.indexOf('*') < 0) {
                return anchoredEnd ? target.equals(pattern) : target.startsWith(pattern);
            }
            return wildcardMatch(pattern, target, anchoredEnd);
        }

        /**
         * Each run between stars must appear in order, the first at the very start. Each run is
         * taken at its first occurrence after the one before.
         */
        private static boolean wildcardMatch(String pattern, String target, boolean anchoredEnd) {
            String[] parts = pattern.split("\\*", -1);
            int position = 0;
            for (int index = 0; index < parts.length; index++) {
                String part = parts[index];
                if (part.isEmpty()) {
                    continue;
                }
                if (index == 0) {
                    if (!target.startsWith(part)) {
                        return false;
                    }
                    position = part.length();
                    continue;
                }
                int found = target.indexOf(part, position);
                if (found < 0) {
                    return false;
                }
                position = found + part.length();
            }
            return !anchoredEnd || position == target.length();
        }
    }

    /**
     * The rules for one set of user agents.
     *
     * @param agents the agent names, in lower case, as the file wrote them
     * @param rules the allow and disallow lines, in the order written
     * @param crawlDelay the seconds to leave between requests, when the group names it
     */
    public record Group(List<String> agents, List<Rule> rules, Optional<Double> crawlDelay) {

        public Group {
            agents = List.copyOf(agents);
            rules = List.copyOf(rules);
        }

        /**
         * How well this group matches an agent. Zero means not at all, a star scores one, and a
         * name the agent starts with scores one more than its length.
         */
        public int specificityFor(String agent) {
            String wanted = agent.toLowerCase(Locale.ROOT);
            int best = 0;
            for (String name : agents) {
                if (name.equals("*")) {
                    best = Math.max(best, 1);
                } else if (wanted.startsWith(name)) {
                    best = Math.max(best, 1 + name.codePointCount(0, name.length()));
                }
            }
            return best;
        }

        /** Whether the group lets a path through. The longest matching rule wins and allow wins a tie. */
        public boolean verdictFor(String path) {
            Rule winner = null;
            for (Rule rule : rules) {
                if (!rule.matches(path)) {
                    continue;
                }
                boolean longer = winner == null || rule.length() > winner.length();
                boolean tiesAndAllows = winner != null && rule.length() == winner.length() && rule.allow();
                if (longer || tiesAndAllows) {
                    winner = rule;
                }
            }
            return winner == null || winner.allow();
        }
    }

    /** A group while its lines are still being read. */
    private static final class GroupDraft {
        private final List<String> agents = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private Double crawlDelay;

        /** A delay that is not a number is passed over, and the one before it stands. */
        void readCrawlDelay(String value) {
            seconds(value).ifPresent(delay -> crawlDelay = delay);
        }

        private static Optional<Double> seconds(String value) {
            try {
                return Optional.of(Numbers.parseFloat(value));
            } catch (NumberFormatException unreadable) {
                return Optional.empty();
            }
        }

        Group freeze() {
            return new Group(agents, rules, Optional.ofNullable(crawlDelay));
        }
    }
}
