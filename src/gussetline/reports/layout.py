"""How a report is put on the page, in one place rather than in four.

Every report in this package has the same shape. A heading, a block of name
and value pairs, then some number of lists that are usually too long to print
whole. The four of them built that shape independently and it shows.

The columns drifted. Savings pads its names to ten, coverage to ten by a
different count, discrepancy to fifteen, and every one of those numbers is a
literal somebody typed after counting the longest word at the time. Add a
figure with a longer name and the block is crooked until somebody notices.
An operator reads these down the left edge at eight in the morning, and a
column that lands somewhere else in each report is one they have to find
again every time.

The truncation is the part that actually cost something. Two of these lists
were cut at the limit with nothing said about it, so a report with eleven
claims in it printed ten and looked complete. The person who writes the claim
letters had no way to know a twelfth existed, and the number that mattered,
how many there were, was the one not on the page.

So: names align on the widest name rather than on a guess, and a list that is
cut says what it cut. Nothing here knows what a report means, which is the
point. It is where the spacing lives so that the report modules can be about
airfare.
"""

from __future__ import annotations

from collections.abc import Iterable, Sequence
from typing import Any

# Space between the longest name and the values. Two, because one reads as a
# single wide word and three starts to look like two columns of unrelated
# things.
GUTTER = 2

# What goes in front of a row inside a section. Deep enough to see, shallow
# enough that a long line still fits a terminal somebody has not maximised.
INDENT = "  "


def figures(rows: Sequence[tuple[str, Any]]) -> list[str]:
    """Name and value on each line, aligned on the widest name.

    Derived rather than typed. The alignment then follows whatever the report
    decides to report, which is the only version of this that survives a
    figure being added.
    """
    if not rows:
        return []
    width = max(len(name) for name, _ in rows) + GUTTER
    return [f"{name:<{width}}{value}" for name, value in rows]


def section(title: str, rows: Iterable[Any], limit: int | None = None) -> list[str]:
    """An indented list under its own title, honest about what it left out.

    Nothing at all when there is nothing to show, because an empty heading
    reads as a report that failed rather than as a desk with no problems.

    A list that fits prints its title plainly. A list that does not says how
    much of it is on the page, since the count is usually the finding: ten
    claims is a busy week and two hundred is a supplier having an outage, and
    both of them print ten rows.
    """
    kept = list(rows)
    if not kept:
        return []

    shown = kept if limit is None else kept[:limit]
    head = title if len(shown) == len(kept) else f"{title}, showing {len(shown)} of {len(kept)}"
    return ["", head, *(f"{INDENT}{row}" for row in shown)]


def page(*blocks: Iterable[str]) -> str:
    """The blocks of a report, in order, as the one string a command prints."""
    lines: list[str] = []
    for block in blocks:
        lines.extend(block)
    return "\n".join(lines)


def heading(title: str, *under: str) -> list[str]:
    """A title, whatever qualifies it, and the blank line before the figures."""
    return [title, *under, ""]
