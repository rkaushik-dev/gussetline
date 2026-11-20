package io.github.rkaushikdev.gussetline.collect;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.rkaushikdev.gussetline.collect.Robots.Rule;
import org.junit.jupiter.api.Test;

class CollectRobotsTest {

    private static final String SAMPLE =
            """

            # the airline calendar site
            User-agent: *
            Disallow: /
            Allow: /calendar
            Crawl-delay: 5

            User-agent: gussetline
            Disallow: /admin
            Disallow: /internal/
            Crawl-delay: 2

            Sitemap: https://fares.example/sitemap.xml
            """;

    @Test
    void aPrefixRuleMatchesAnythingUnderIt() {
        Rule rule = new Rule("/calendar", true);
        assertThat(rule.matches("/calendar")).isTrue();
        assertThat(rule.matches("/calendar/2025-03")).isTrue();
        assertThat(rule.matches("/booking")).isFalse();
    }

    @Test
    void anEmptyPatternMatchesNothing() {
        assertThat(new Rule("", false).matches("/anything")).isFalse();
    }

    @Test
    void aDollarAnchorsTheEnd() {
        Rule rule = new Rule("/calendar$", true);
        assertThat(rule.matches("/calendar")).isTrue();
        assertThat(rule.matches("/calendar/2025-03")).isFalse();
    }

    @Test
    void aStarMatchesARunOfAnything() {
        Rule rule = new Rule("/fares/*/detail", false);
        assertThat(rule.matches("/fares/FRA-JFK/detail")).isTrue();
        assertThat(rule.matches("/fares/a/b/detail")).isTrue();
        assertThat(rule.matches("/fares/FRA-JFK/summary")).isFalse();
    }

    @Test
    void aStarAndADollarTogether() {
        Rule rule = new Rule("/*.pdf$", false);
        assertThat(rule.matches("/reports/march.pdf")).isTrue();
        assertThat(rule.matches("/reports/march.pdf.html")).isFalse();
    }

    @Test
    void aTrailingStarIsTheSameAsAPrefix() {
        assertThat(new Rule("/calendar*", true).matches("/calendar/2025")).isTrue();
    }

    @Test
    void groupsAreReadAndCounted() {
        Robots robots = Robots.parse(SAMPLE);
        assertThat(robots.groups()).hasSize(2);
        assertThat(robots.sitemaps()).containsExactly("https://fares.example/sitemap.xml");
        assertThat(robots).hasToString("2 groups, 1 sitemaps");
    }

    @Test
    void theMostSpecificGroupWinsAndTheStarOneIsIgnored() {
        Robots robots = Robots.parse(SAMPLE);
        // Our own group disallows only /admin and /internal, so the star group's
        // blanket disallow does not apply to us at all.
        assertThat(robots.allows("/calendar", "gussetline/1.0")).isTrue();
        assertThat(robots.allows("/booking", "gussetline/1.0")).isTrue();
        assertThat(robots.allows("/admin", "gussetline/1.0")).isFalse();
    }

    @Test
    void anotherAgentFallsToTheStarGroup() {
        Robots robots = Robots.parse(SAMPLE);
        assertThat(robots.allows("/booking", "somebodyelse/2.0")).isFalse();
        assertThat(robots.allows("/calendar", "somebodyelse/2.0")).isTrue();
    }

    @Test
    void theLongestMatchingRuleWinsAndNotTheFirst() {
        Robots robots = Robots.parse("User-agent: *\nDisallow: /\nAllow: /calendar\n");
        assertThat(robots.allows("/booking")).isFalse();
        assertThat(robots.allows("/calendar/2025-03")).isTrue();
    }

    @Test
    void anAllowWinsATieOfEqualLength() {
        Robots robots = Robots.parse("User-agent: *\nDisallow: /fares\nAllow: /fares\n");
        assertThat(robots.allows("/fares")).isTrue();
    }

    @Test
    void anEmptyDisallowOpensEverything() {
        Robots robots = Robots.parse("User-agent: *\nDisallow:\n");
        assertThat(robots.allows("/anything")).isTrue();
    }

    @Test
    void aGroupWithNoRulesAllows() {
        Robots robots = Robots.parse("User-agent: *\nCrawl-delay: 3\n");
        assertThat(robots.allows("/anything")).isTrue();
        assertThat(robots.crawlDelay("*")).isEqualTo(3.0);
    }

    @Test
    void theCrawlDelayIsReadPerGroup() {
        Robots robots = Robots.parse(SAMPLE);
        assertThat(robots.crawlDelay("gussetline/1.0")).isEqualTo(2.0);
        assertThat(robots.crawlDelay("somebodyelse/2.0")).isEqualTo(5.0);
    }

    @Test
    void aMissingCrawlDelayTakesTheDefault() {
        Robots robots = Robots.parse("User-agent: *\nDisallow: /admin\n");
        assertThat(robots.crawlDelay("*", 1.5)).isEqualTo(1.5);
    }

    @Test
    void aCrawlDelayThatIsNotANumberIsIgnored() {
        Robots robots = Robots.parse("User-agent: *\nCrawl-delay: soon\n");
        assertThat(robots.crawlDelay("*", 1.0)).isEqualTo(1.0);
    }

    @Test
    void consecutiveUserAgentLinesShareOneGroup() {
        Robots robots = Robots.parse("User-agent: one\nUser-agent: two\nDisallow: /x\n");
        assertThat(robots.groups()).hasSize(1);
        assertThat(robots.allows("/x", "one")).isFalse();
        assertThat(robots.allows("/x", "two")).isFalse();
    }

    @Test
    void aRuleBeforeAnyUserAgentLineIsDropped() {
        Robots robots = Robots.parse("Disallow: /everything\nUser-agent: *\nDisallow: /admin\n");
        assertThat(robots.allows("/everything")).isTrue();
        assertThat(robots.allows("/admin")).isFalse();
    }

    @Test
    void commentsAndBlankLinesAreIgnored() {
        Robots robots = Robots.parse("# nothing here\n\n   \nUser-agent: *\nDisallow: /x  # trailing\n");
        assertThat(robots.allows("/x")).isFalse();
    }

    @Test
    void aLineWithNoColonIsIgnored() {
        Robots robots = Robots.parse("User-agent: *\nthis is not a directive\nDisallow: /x\n");
        assertThat(robots.allows("/x")).isFalse();
    }

    @Test
    void anEmptyFileAllowsEverything() {
        Robots robots = Robots.parse("");
        assertThat(robots.allows("/anything")).isTrue();
        assertThat(robots.groupFor("gussetline")).isEmpty();
    }

    @Test
    void aFileWeCouldNotFetchAllowsAndSaysSo() {
        Robots robots = Robots.unavailable();
        assertThat(robots.allows("/anything")).isTrue();
        assertThat(robots.fetched()).isFalse();
        assertThat(robots).hasToString("robots.txt not fetched");
    }
}
