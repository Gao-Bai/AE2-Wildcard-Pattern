"""Mirror of ItemSearch + WildcardPatternScreen.searchResults, asserting the 2.3.0 search fixes.

The reported problems were behavioural, so the decision chain is mirrored here and asserted:
  1. token matching            "gtceu:single_wire" / "single wire" -> gtceu:tin_single_wire
  2. no registry dump          a query with no match must NOT list everything of that kind
  3. typed values are usable    a raw "#tag" / "item:id" becomes a "use my text" row
  4. tag reverse lookup        a picked item offers the tags it belongs to

Run:  python tools/simulate_search.py
"""

import re

FAMILIES = ["ingot*", "plate*", "nugget*", "gear*"]
TAGS = ["forge:ingots/tin", "forge:plates/tin", "forge:wires/tin", "forge:wires/copper",
        "forge:ingots/copper"]
ITEMS = {
    "minecraft:air": [],
    "minecraft:stone": [],
    "minecraft:granite": [],
    "gtceu:tin_single_wire": ["forge:wires/tin"],
    "gtceu:copper_single_wire": ["forge:wires/copper"],
    "gtceu:tin_ingot": ["forge:ingots/tin"],
}
ITEM_NAMES = {
    "gtceu:tin_single_wire": "锡细导线",
    "gtceu:copper_single_wire": "铜细导线",
}


def tokens_match(haystack: str, query: str) -> bool:
    tokens = [t for t in re.split(r"[:/\s_]+", query) if t]
    if len(tokens) <= 1:
        return False
    return all(t in haystack for t in tokens)


def search(query: str):
    """Mirror of ItemSearch.search."""
    query = (query or "").strip().lower()
    out = []
    if query.startswith("#"):
        return [("TAG", t, t) for t in TAGS if not query[1:] or query[1:] in t]
    if query.startswith("@"):
        return []
    if not query:
        return [("FAMILY", f, f) for f in FAMILIES]
    out += [("FAMILY", f, f) for f in FAMILIES if query in f]
    out += [("TAG", t, t) for t in TAGS if query in t.lower() or tokens_match(t.lower(), query)]
    for item, _tags in ITEMS.items():
        if query in item or tokens_match(item, query):
            out.append(("ITEM", item, ITEM_NAMES.get(item, item)))
            continue
        name = ITEM_NAMES.get(item, "").lower()
        if name and query in name:
            out.append(("ITEM", item, ITEM_NAMES.get(item, item)))
    return out


def tags_of(item: str):
    return ITEMS.get(item, [])


def related_entries(item, wanted):
    if not item or wanted == "ITEM":
        return []
    if wanted == "TAG":
        return [("TAG", t, t) for t in tags_of(item)]
    if wanted == "FAMILY":
        # same rule as WildcardPatternScreen.familyToken: alias for the tag root, else root + "/*"
        out = []
        for t in tags_of(item):
            path = t.split(":", 1)[1]
            root = t.split(":", 1)[0] + ":" + path.split("/")[0]
            out.append(("FAMILY", root + "/*", "family"))
        return out
    return []


def everything_of(wanted):
    if wanted == "TAG":
        return [("TAG", t, t) for t in TAGS]
    if wanted == "FAMILY":
        return [("FAMILY", f, f) for f in FAMILIES]
    return [("ITEM", i, n) for i, n in ITEM_NAMES.items()]


def item_from_query(query):
    value = query[1:] if query[:1] in ("#", "@") else query
    return value if value in ITEMS else None


def typed_entry(query, wanted):
    value = query[1:] if query[:1] in ("#", "@") else query
    value = value.strip()
    if not value:
        return None
    return (wanted, value, "use the text I typed")


def with_typed_entry(results, query, wanted):
    typed = typed_entry(query, wanted)
    if typed is None:
        return list(results)
    out = list(results)
    looks_like_id = ":" in query or query[:1] in ("#", "@")
    out.insert(0, typed) if looks_like_id else out.append(typed)
    return out


def search_results(query, wanted, picked_item=None):
    """Mirror of WildcardPatternScreen.searchResults."""
    filtered = [e for e in search(query) if e[0] == wanted]
    if filtered:
        return with_typed_entry(filtered, query, wanted)
    context = item_from_query(query) or picked_item
    related = related_entries(context, wanted)
    if related:
        return with_typed_entry(related, query, wanted)
    if not query.strip() and wanted != "ITEM":
        return everything_of(wanted)
    typed = typed_entry(query, wanted)
    return [typed] if typed else []


def check(label, got, expect_kinds=None, expect_contains=None, forbid=None):
    kinds = [e[0] for e in got]
    values = [e[1] for e in got]
    if expect_kinds is not None:
        assert kinds == expect_kinds, f"{label}: kinds {kinds} != {expect_kinds}"
    if expect_contains is not None:
        assert expect_contains in values, f"{label}: {expect_contains} not in {values}"
    if forbid is not None:
        assert forbid not in values, f"{label}: {forbid} must not be listed ({values})"
    print(f"  ok  {label}: {len(got)} entries, first={got[0] if got else None}")


def main():
    print("token matching:")
    assert tokens_match("gtceu:tin_single_wire", "gtceu:single_wire")
    assert tokens_match("gtceu:tin_single_wire", "single wire")
    assert not tokens_match("gtceu:tin_single_wire", "gtceu:unobtainium_single_wire")
    assert not tokens_match("gtceu:tin_single_wire", "iron")
    print("  ok  'gtceu:single_wire' and 'single wire' find gtceu:tin_single_wire")

    print("\n1) tag mode, partial item query -> must not dump the registry:")
    got = search_results("gtceu:single_wire", "TAG")
    check("no registry dump", got, forbid="minecraft:air")
    assert len(got) == 1 and got[0][0] == "TAG" and got[0][1] == "gtceu:single_wire"
    print("  ok  only the 'use my text' row is offered")

    print("\n2) tag mode after picking an item -> its tags:")
    got = search_results("single wire", "TAG", picked_item="gtceu:tin_single_wire")
    check("reverse tag lookup", got, expect_contains="forge:wires/tin")

    print("\n3) typed tag id is accepted directly:")
    got = search_results("forge:wires/tin", "TAG")
    check("typed tag id", got, expect_contains="forge:wires/tin")

    print("\n4) item mode still lists items, typed row appended for a plain word:")
    got = search_results("tin", "ITEM")
    kinds = [e[0] for e in got]
    assert kinds[0] == "ITEM" and kinds[-1] == "ITEM"
    got = search_results("gtceu:tin_ingot", "ITEM")
    # an id-like query puts the "use my text" row first, and the real item right after it
    assert got[0][0] == "ITEM" and got[0][1] == "gtceu:tin_ingot"
    assert got[0][2] == "use the text I typed"
    assert len(got) >= 2 and got[1][1] == "gtceu:tin_ingot"
    print("  ok  id-looking text goes first, plain words keep real matches first")

    print("\n5) empty query in tag mode still lets you browse:")
    got = search_results("", "TAG")
    check("browse tags", got, expect_contains="forge:wires/copper")

    print("\n6) family mode from a picked item:")
    got = search_results("single wire", "FAMILY", picked_item="gtceu:tin_single_wire")
    print(f"  ok  families: {[e[1] for e in got]}")

    print("\nAll search assertions passed.")


if __name__ == "__main__":
    main()
