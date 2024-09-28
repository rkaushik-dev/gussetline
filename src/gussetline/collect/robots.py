"""robots.txt, parsed properly, because the airline calendar site has one.

Only one of the three suppliers is a website rather than an API, and it
publishes a robots file with a crawl delay in it. Honouring that is not
optional politeness, it is the difference between a desk that keeps its access
and one that gets a letter.

The parsing rules that matter and that a naive reader gets wrong:

Groups are keyed on user agent and a record applies to the most specific
matching group only. A file with a group for our agent and a group for star
does not mean both apply, it means ours does and star is ignored.

Inside a group the longest matching path wins, not the first one. A file that
disallows slash and allows slash calendar permits the calendar, and a first
match reader refuses it.

An empty disallow means allow everything, which reads like a typo and is the
documented way to open a section back up.

And a robots file we could not fetch is not permission. It is unknown, and
this returns a policy that allows, because a supplier we have a contract with
is not the case robots.txt was written for, but the caller can tell the two
apart from ``fetched``.
"""

from __future__ import annotations

from dataclasses import dataclass, field


@dataclass(frozen=True, slots=True)
class Rule:
    """One allow or disallow line."""

    path: str
    allow: bool

    @property
    def length(self) -> int:
        return len(self.path)

    def matches(self, target: str) -> bool:
        """Whether this line covers a path, honouring star and dollar."""
        pattern = self.path
        if not pattern:
            return False
        anchored_end = pattern.endswith("$")
        if anchored_end:
            pattern = pattern[:-1]
        if "*" not in pattern:
            if anchored_end:
                return target == pattern
            return target.startswith(pattern)
        return _wildcard_match(pattern, target, anchored_end)


def _wildcard_match(pattern: str, target: str, anchored_end: bool) -> bool:
    parts = pattern.split("*")
    position = 0
    for index, part in enumerate(parts):
        if not part:
            continue
        if index == 0:
            if not target.startswith(part):
                return False
            position = len(part)
            continue
        found = target.find(part, position)
        if found < 0:
            return False
        position = found + len(part)
    if anchored_end:
        return position == len(target)
    return True


@dataclass
class Group:
    """The rules for one set of user agents."""

    agents: list[str] = field(default_factory=list)
    rules: list[Rule] = field(default_factory=list)
    crawl_delay: float | None = None

    def specificity_for(self, agent: str) -> int:
        """How well this group matches an agent. Zero means not at all."""
        wanted = agent.lower()
        best = 0
        for name in self.agents:
            if name == "*":
                best = max(best, 1)
            elif wanted.startswith(name):
                best = max(best, 1 + len(name))
        return best

    def verdict_for(self, path: str) -> bool:
        """Longest matching rule wins. Allow wins a tie."""
        winner: Rule | None = None
        for rule in self.rules:
            if not rule.matches(path):
                continue
            longer = winner is None or rule.length > winner.length
            ties_and_allows = winner is not None and rule.length == winner.length and rule.allow
            if longer or ties_and_allows:
                winner = rule
        if winner is None:
            return True
        return winner.allow


@dataclass
class Robots:
    """A parsed robots file, or the absence of one."""

    groups: list[Group] = field(default_factory=list)
    sitemaps: list[str] = field(default_factory=list)
    fetched: bool = True

    def group_for(self, agent: str) -> Group | None:
        best: Group | None = None
        best_score = 0
        for group in self.groups:
            score = group.specificity_for(agent)
            if score > best_score:
                best, best_score = group, score
        return best

    def allows(self, path: str, agent: str = "*") -> bool:
        group = self.group_for(agent)
        if group is None:
            return True
        return group.verdict_for(path)

    def crawl_delay(self, agent: str = "*", default: float = 0.0) -> float:
        group = self.group_for(agent)
        if group is None or group.crawl_delay is None:
            return default
        return group.crawl_delay

    def __str__(self) -> str:
        if not self.fetched:
            return "robots.txt not fetched"
        return f"{len(self.groups)} groups, {len(self.sitemaps)} sitemaps"


def parse(text: str) -> Robots:
    """Read a robots file. Never raises, because a broken one is common."""
    robots = Robots()
    current: Group | None = None
    starting_group = True

    for raw in text.splitlines():
        line = raw.split("#", 1)[0].strip()
        if not line or ":" not in line:
            continue
        field_name, _, value = line.partition(":")
        key = field_name.strip().lower()
        value = value.strip()

        if key == "user-agent":
            if current is None or not starting_group:
                current = Group()
                robots.groups.append(current)
                starting_group = True
            current.agents.append(value.lower())
            continue

        if key == "sitemap":
            robots.sitemaps.append(value)
            continue

        if current is None:
            continue
        starting_group = False

        if key == "disallow":
            if value:
                current.rules.append(Rule(value, allow=False))
            continue
        if key == "allow":
            if value:
                current.rules.append(Rule(value, allow=True))
            continue
        if key == "crawl-delay":
            try:
                current.crawl_delay = float(value)
            except ValueError:
                continue

    return robots


def unavailable() -> Robots:
    """What to use when the file could not be fetched.

    Permissive, and marked, so a caller that wants to be strict about it can
    be and one that does not is not silently blocked by a network blip.
    """
    return Robots(fetched=False)
