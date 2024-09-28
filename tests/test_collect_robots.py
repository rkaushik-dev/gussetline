from __future__ import annotations

from gussetline.collect.robots import Rule, parse, unavailable

SAMPLE = """
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
"""


def test_a_prefix_rule_matches_anything_under_it():
    rule = Rule("/calendar", allow=True)
    assert rule.matches("/calendar")
    assert rule.matches("/calendar/2025-03")
    assert not rule.matches("/booking")


def test_an_empty_pattern_matches_nothing():
    assert not Rule("", allow=False).matches("/anything")


def test_a_dollar_anchors_the_end():
    rule = Rule("/calendar$", allow=True)
    assert rule.matches("/calendar")
    assert not rule.matches("/calendar/2025-03")


def test_a_star_matches_a_run_of_anything():
    rule = Rule("/fares/*/detail", allow=False)
    assert rule.matches("/fares/FRA-JFK/detail")
    assert rule.matches("/fares/a/b/detail")
    assert not rule.matches("/fares/FRA-JFK/summary")


def test_a_star_and_a_dollar_together():
    rule = Rule("/*.pdf$", allow=False)
    assert rule.matches("/reports/march.pdf")
    assert not rule.matches("/reports/march.pdf.html")


def test_a_trailing_star_is_the_same_as_a_prefix():
    assert Rule("/calendar*", allow=True).matches("/calendar/2025")


def test_groups_are_read_and_counted():
    robots = parse(SAMPLE)
    assert len(robots.groups) == 2
    assert robots.sitemaps == ["https://fares.example/sitemap.xml"]
    assert str(robots) == "2 groups, 1 sitemaps"


def test_the_most_specific_group_wins_and_the_star_one_is_ignored():
    robots = parse(SAMPLE)
    # Our own group disallows only /admin and /internal, so the star group's
    # blanket disallow does not apply to us at all.
    assert robots.allows("/calendar", "gussetline/1.0")
    assert robots.allows("/booking", "gussetline/1.0")
    assert not robots.allows("/admin", "gussetline/1.0")


def test_another_agent_falls_to_the_star_group():
    robots = parse(SAMPLE)
    assert not robots.allows("/booking", "somebodyelse/2.0")
    assert robots.allows("/calendar", "somebodyelse/2.0")


def test_the_longest_matching_rule_wins_and_not_the_first():
    robots = parse("User-agent: *\nDisallow: /\nAllow: /calendar\n")
    assert not robots.allows("/booking")
    assert robots.allows("/calendar/2025-03")


def test_an_allow_wins_a_tie_of_equal_length():
    robots = parse("User-agent: *\nDisallow: /fares\nAllow: /fares\n")
    assert robots.allows("/fares")


def test_an_empty_disallow_opens_everything():
    robots = parse("User-agent: *\nDisallow:\n")
    assert robots.allows("/anything")


def test_a_group_with_no_rules_allows():
    robots = parse("User-agent: *\nCrawl-delay: 3\n")
    assert robots.allows("/anything")
    assert robots.crawl_delay("*") == 3.0


def test_the_crawl_delay_is_read_per_group():
    robots = parse(SAMPLE)
    assert robots.crawl_delay("gussetline/1.0") == 2.0
    assert robots.crawl_delay("somebodyelse/2.0") == 5.0


def test_a_missing_crawl_delay_takes_the_default():
    robots = parse("User-agent: *\nDisallow: /admin\n")
    assert robots.crawl_delay("*", default=1.5) == 1.5


def test_a_crawl_delay_that_is_not_a_number_is_ignored():
    robots = parse("User-agent: *\nCrawl-delay: soon\n")
    assert robots.crawl_delay("*", default=1.0) == 1.0


def test_consecutive_user_agent_lines_share_one_group():
    robots = parse("User-agent: one\nUser-agent: two\nDisallow: /x\n")
    assert len(robots.groups) == 1
    assert not robots.allows("/x", "one")
    assert not robots.allows("/x", "two")


def test_a_rule_before_any_user_agent_line_is_dropped():
    robots = parse("Disallow: /everything\nUser-agent: *\nDisallow: /admin\n")
    assert robots.allows("/everything")
    assert not robots.allows("/admin")


def test_comments_and_blank_lines_are_ignored():
    robots = parse("# nothing here\n\n   \nUser-agent: *\nDisallow: /x  # trailing\n")
    assert not robots.allows("/x")


def test_a_line_with_no_colon_is_ignored():
    robots = parse("User-agent: *\nthis is not a directive\nDisallow: /x\n")
    assert not robots.allows("/x")


def test_an_empty_file_allows_everything():
    robots = parse("")
    assert robots.allows("/anything")
    assert robots.group_for("gussetline") is None


def test_a_file_we_could_not_fetch_allows_and_says_so():
    robots = unavailable()
    assert robots.allows("/anything")
    assert not robots.fetched
    assert str(robots) == "robots.txt not fetched"
