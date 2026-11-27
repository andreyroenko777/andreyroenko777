"""Compare Markdown versions by section rather than by file position."""

import argparse
from bisect import bisect_left
from collections import defaultdict
from dataclasses import dataclass
from difflib import SequenceMatcher
import json
from pathlib import Path
import re
import sys


HEADING = re.compile(r"^ {0,3}(#{1,6})(?:[ \t]+(.*)|$)")
FENCE = re.compile(r"^ {0,3}([\x60]{3,}|~{3,})(.*)$")
CLOSING_HASHES = re.compile(r"[ \t]+#+[ \t]*$")


@dataclass(frozen=True)
class Section:
    title: str
    trail: tuple[str, ...]
    parent: int | None
    line: int
    body_line: int
    body: tuple[str, ...]


def normalized_title(title):
    return " ".join(title.casefold().split())


def parse(text):
    lines = re.split(r"\r\n|\n|\r", text)
    headings = []
    fence = None
    ancestors = []
    preamble = False
    for position, line in enumerate(lines):
        fence_match = FENCE.fullmatch(line)
        if fence is not None:
            if (fence_match and fence_match[1][0] == fence[0]
                    and len(fence_match[1]) >= len(fence) and not fence_match[2].strip()):
                fence = None
            continue
        if fence_match and (fence_match[1][0] == "~" or chr(96) not in fence_match[2]):
            fence = fence_match[1]
            continue
        match = HEADING.fullmatch(line)
        if not match:
            continue
        if not headings:
            preamble = any(value.strip() for value in lines[:position])
        level = len(match[1])
        raw_title = (match[2] or "").strip()
        title = "" if raw_title and not raw_title.strip("#") else CLOSING_HASHES.sub("", raw_title)
        while ancestors and ancestors[-1][0] >= level:
            ancestors.pop()
        parent = ancestors[-1][1] if ancestors else None
        trail = tuple(item[2] for item in ancestors) + (title,)
        index = len(headings) + int(preamble)
        headings.append((position, title, trail, parent))
        ancestors.append((level, index, title))

    if not headings:
        preamble = any(line.strip() for line in lines)
    if preamble:
        headings.insert(0, (-1, "(preamble)", (), None))

    result = []
    for index, (position, title, trail, parent) in enumerate(headings):
        end = headings[index + 1][0] if index + 1 < len(headings) else len(lines)
        start = position + 1
        while start < end and not lines[start].strip():
            start += 1
        while end > start and not lines[end - 1].strip():
            end -= 1
        result.append(Section(title, trail, parent, max(1, position + 1),
                              start + 1, tuple(lines[start:end])))
    return result


def grouped(sections, indices, key):
    groups = defaultdict(list)
    for index in sorted(indices):
        value = key(sections[index])
        if value is not None:
            groups[value].append(index)
    return groups


def align(before, after):
    pairs = {}
    old_open, new_open = set(range(len(before))), set(range(len(after)))

    def match_unique(old_key, new_key):
        old_groups = grouped(before, old_open, old_key)
        new_groups = grouped(after, new_open, new_key)
        for key, old_ids in old_groups.items():
            new_ids = new_groups.get(key, [])
            if len(old_ids) == len(new_ids) == 1:
                old_id, new_id = old_ids[0], new_ids[0]
                pairs[old_id] = new_id
                old_open.remove(old_id)
                new_open.remove(new_id)

    def signature(section):
        return (bool(section.trail), normalized_title(section.title), section.body)

    match_unique(signature, signature)

    def old_context(section):
        if section.parent is not None and section.parent not in pairs:
            return None
        return (pairs.get(section.parent), bool(section.trail), normalized_title(section.title))

    def new_context(section):
        return (section.parent, bool(section.trail), normalized_title(section.title))

    def match_contexts():
        while True:
            count = len(pairs)
            match_unique(old_context, new_context)
            if len(pairs) == count:
                break

    match_contexts()
    match_unique(lambda s: s.body if s.trail and s.body else None,
                 lambda s: s.body if s.trail and s.body else None)
    match_contexts()

    # An edited section can move to another parent. Require a unique title on
    # both sides and substantial text similarity before pairing it.
    old_names = grouped(before, old_open, lambda s: normalized_title(s.title))
    new_names = grouped(after, new_open, lambda s: normalized_title(s.title))
    for title, old_ids in old_names.items():
        new_ids = new_names.get(title, [])
        if len(old_ids) == len(new_ids) == 1:
            old_id, new_id = old_ids[0], new_ids[0]
            old_body = "\n".join(before[old_id].body)
            new_body = "\n".join(after[new_id].body)
            if old_body and new_body and SequenceMatcher(None, old_body, new_body).ratio() >= 0.65:
                pairs[old_id] = new_id
                old_open.remove(old_id)
                new_open.remove(new_id)
    match_contexts()
    return pairs, old_open, new_open


def ordered_members(sequence):
    """Return indices in one longest increasing subsequence in O(n log n)."""
    tails = []
    positions = []
    previous = []
    for index, value in enumerate(sequence):
        place = bisect_left(tails, value)
        previous.append(positions[place - 1] if place else -1)
        if place == len(tails):
            tails.append(value)
            positions.append(index)
        else:
            tails[place] = value
            positions[place] = index
    members = set()
    index = positions[-1] if positions else -1
    while index != -1:
        members.add(index)
        index = previous[index]
    return members


def relocated(before, after, pairs):
    siblings = defaultdict(list)
    moved = set()
    for old_id, new_id in sorted(pairs.items()):
        parent = before[old_id].parent
        new_parent = after[new_id].parent
        if (parent is not None and parent not in pairs) or pairs.get(parent) != new_parent:
            moved.add(old_id)
        else:
            siblings[new_parent].append(old_id)
    for old_ids in siblings.values():
        stable = ordered_members([pairs[old_id] for old_id in old_ids])
        moved.update(old_id for index, old_id in enumerate(old_ids) if index not in stable)
    return moved


def location(section):
    return {"path": list(section.trail), "line": section.line}


def line_changes(before, after):
    matcher = SequenceMatcher(None, before.body, after.body, autojunk=False)
    return [
        {"before": [{"line": before.body_line + index, "text": before.body[index]}
                    for index in range(old_start, old_end)],
         "after": [{"line": after.body_line + index, "text": after.body[index]}
                   for index in range(new_start, new_end)]}
        for operation, old_start, old_end, new_start, new_end in matcher.get_opcodes()
        if operation != "equal"
    ]


def compare(old_text, new_text):
    before, after = parse(old_text), parse(new_text)
    pairs, old_open, new_open = align(before, after)
    moved = relocated(before, after, pairs)
    report = {"changes": [], "ambiguous": []}
    changes = report["changes"]
    for old_id, new_id in sorted(pairs.items()):
        old, new = before[old_id], after[new_id]
        kinds = []
        if old_id in moved:
            kinds.append("moved")
        if old.title != new.title:
            kinds.append("renamed")
        edits = line_changes(old, new) if old.body != new.body else []
        if edits:
            kinds.append("modified")
        if kinds:
            changes.append({"kinds": kinds, "before": location(old),
                            "after": location(new), "edits": edits})

    old_names = grouped(before, old_open, lambda s: normalized_title(s.title))
    new_names = grouped(after, new_open, lambda s: normalized_title(s.title))
    for title in old_names.keys() & new_names.keys():
        old_ids, new_ids = old_names[title], new_names[title]
        if len(old_ids) > 1 or len(new_ids) > 1:
            report["ambiguous"].append({
                "before": [location(before[index]) for index in old_ids],
                "after": [location(after[index]) for index in new_ids],
            })
            old_open.difference_update(old_ids)
            new_open.difference_update(new_ids)
    report["ambiguous"].sort(key=lambda group: group["before"][0]["line"])
    changes.extend({"kinds": ["removed"], "before": location(before[index])}
                   for index in sorted(old_open))
    changes.extend({"kinds": ["added"], "after": location(after[index])}
                   for index in sorted(new_open))
    return report


def display(location):
    if location is None:
        return "—"
    path = " / ".join(title or "(untitled)" for title in location["path"]) or "(preamble)"
    return f"{path} (L{location['line']})"


def show(report):
    if not report["changes"] and not report["ambiguous"]:
        print("No section changes.")
    for change in report["changes"]:
        kinds = ", ".join(change["kinds"])
        print(f"[{kinds}] {display(change.get('before'))} → {display(change.get('after'))}")
        for edit in change.get("edits", []):
            for sign, version in (("-", "before"), ("+", "after")):
                for line in edit[version]:
                    print(f"  {sign} L{line['line']} {line['text']}")
    for group in report["ambiguous"]:
        old = ", ".join(map(display, group["before"]))
        new = ", ".join(map(display, group["after"]))
        print(f"[ambiguous] {old} → {new}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("before", type=Path)
    parser.add_argument("after", type=Path)
    parser.add_argument("--json", action="store_true", help="write the report as JSON")
    args = parser.parse_args()
    try:
        before, after = (file.read_text(encoding="utf-8-sig") for file in (args.before, args.after))
    except (OSError, UnicodeError) as error:
        print(f"Document error: {error}", file=sys.stderr)
        return 2
    report = compare(before, after)
    if args.json:
        print(json.dumps(report, indent=2))
    else:
        show(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
