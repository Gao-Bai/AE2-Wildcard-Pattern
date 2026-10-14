"""Faithful simulation of WildcardExpander/TagIndex/TemplateParser (Java) used to validate the
matching semantics offline, because neither Gradle nor a JDK 17 is available here.

It mirrors the Java code 1:1 for:
  * generic family names + affix stripping      (TagIndex.variantKey / stripAffix)
  * tag family collection incl. sub tags        (TagIndex.collectVariants)
  * wildcard token -> tag root resolution       (TemplateParser.parseSource + WildcardAliases)
  * variant intersection + representative pick  (WildcardExpander)

Run:  python tools/simulate_expansion.py
"""

from __future__ import annotations

import re
from dataclasses import dataclass, field

# ---------------------------------------------------------------------------
# WildcardAliases
# ---------------------------------------------------------------------------

ALIASES = {
    "ingot": "forge:ingots", "ingots": "forge:ingots",
    "nugget": "forge:nuggets", "nuggets": "forge:nuggets",
    "dust": "forge:dusts", "dusts": "forge:dusts", "powder": "forge:dusts", "powders": "forge:dusts",
    "plate": "forge:plates", "plates": "forge:plates", "sheet": "forge:plates", "sheets": "forge:plates",
    "gear": "forge:gears", "gears": "forge:gears",
    "rod": "forge:rods", "rods": "forge:rods", "stick": "forge:rods", "sticks": "forge:rods",
    "block": "forge:storage_blocks", "blocks": "forge:storage_blocks",
    "storage_block": "forge:storage_blocks", "storage_blocks": "forge:storage_blocks",
    "raw": "forge:raw_materials", "raws": "forge:raw_materials",
    "raw_material": "forge:raw_materials", "raw_materials": "forge:raw_materials",
    "raw_ore": "forge:raw_materials", "raw_ores": "forge:raw_materials",
    "ore": "forge:ores", "ores": "forge:ores",
    "gem": "forge:gems", "gems": "forge:gems",
    "wire": "forge:wires", "wires": "forge:wires",
    "foil": "forge:foils", "foils": "forge:foils",
    "log": "minecraft:logs", "logs": "minecraft:logs",
    "plank": "minecraft:planks", "planks": "minecraft:planks",
    "sapling": "minecraft:saplings", "saplings": "minecraft:saplings",
    "leaves": "minecraft:leaves",
}

GENERIC = {
    "ingot", "nugget", "dust", "powder", "plate", "sheet", "gear", "rod", "stick",
    "storage_block", "block", "raw_material", "raw_ore", "raw", "ore", "gem",
    "wire", "foil", "crystal", "clump", "shard", "fragment",
}
GENERIC |= {target.split(":")[1].split("/")[-1] for target in ALIASES.values()}

AFFIXES = {
    "ingots": ["ingot"],
    "nuggets": ["nugget", "bit"],
    "dusts": ["dust", "powder"],
    "plates": ["plate", "sheet"],
    "gears": ["gear"],
    "rods": ["rod", "stick"],
    "storage_blocks": ["block", "storage_block"],
    "raw_materials": ["raw", "raw_material", "raw_ore", "crushed", "cluster", "ore"],
    "ores": ["ore"],
    "gems": ["gem", "crystal"],
    "wires": ["wire"],
    "foils": ["foil"],
}


def strip_affix(item_path: str, family: str) -> str:
    tokens = AFFIXES.get(family) or [family[:-1] if family.endswith("s") else family]
    for token in tokens:
        if len(item_path) > len(token) + 1:
            if item_path.endswith("_" + token):
                return item_path[: -(len(token) + 1)]
            if item_path.startswith(token + "_"):
                return item_path[len(token) + 1:]
    return item_path


# ---------------------------------------------------------------------------
# TagIndex
# ---------------------------------------------------------------------------

@dataclass
class TagIndex:
    tags: dict[str, list[str]] = field(default_factory=dict)

    def variant_key(self, tag: str, item: str) -> str:
        path = tag.split(":", 1)[1]
        last = path.rsplit("/", 1)[-1].lower()
        if last not in GENERIC:
            return last
        item_path = item.split(":", 1)[1]
        return strip_affix(item_path, last)

    def collect_variants(self, root: str) -> dict[str, list[str]]:
        ns, root_path = root.split(":", 1)
        out: dict[str, list[str]] = {}
        for tag in sorted(self.tags):
            tns, tpath = tag.split(":", 1)
            if tns != ns:
                continue
            if tpath != root_path and not tpath.startswith(root_path + "/"):
                continue
            for item in self.tags[tag]:
                key = self.variant_key(tag, item)
                self._add(out, key, item)
                path_key = item.split(":", 1)[1]
                if path_key != key:
                    self._add(out, path_key, item)
        return out

    @staticmethod
    def _add(out: dict[str, list[str]], key: str, item: str) -> None:
        bucket = out.setdefault(key, [])
        if item not in bucket:
            bucket.append(item)

    def minimal_roots(self, namespace: str | None, glob: str) -> list[str]:
        regex = re.compile("".join(".*" if i else re.escape(p) for i, p in enumerate(glob.split("*"))))
        if namespace is not None:
            return self._minimal([t for t in self.tags if t.split(":", 1)[0] == namespace
                                  and regex.fullmatch(t.split(":", 1)[1])])
        forge = self._minimal([t for t in self.tags if t.startswith("forge:")
                               and regex.fullmatch(t.split(":", 1)[1])])
        if forge:
            return forge
        return self._minimal([t for t in self.tags if regex.fullmatch(t.split(":", 1)[1])])

    @staticmethod
    def _minimal(tags: list[str]) -> list[str]:
        out = []
        for candidate in tags:
            cns, cpath = candidate.split(":", 1)
            if any(other != candidate and other.split(":", 1)[0] == cns
                   and cpath.startswith(other.split(":", 1)[1] + "/") for other in tags):
                continue
            out.append(candidate)
        return out


# ---------------------------------------------------------------------------
# wildcard -> roots
# ---------------------------------------------------------------------------

def resolve_roots(index: TagIndex, token: str) -> list[str]:
    if token.startswith("#"):
        text = token[1:]
        return [text if ":" in text else "forge:" + text]
    if "*" in token:
        namespace, glob = (token.split(":", 1) + [None])[:2] if ":" in token else (None, token)
        if namespace is None and token.endswith("*") and len(token) > 1:
            alias = ALIASES.get(token[:-1].lower())
            if alias:
                return [alias]
        if not glob.replace("*", ""):
            return []
        return index.minimal_roots(namespace, glob)
    return []


def sort_candidates(items: list[str], preferred: list[str]) -> list[str]:
    def key(item: str):
        ns = item.split(":", 1)[0]
        return (preferred.index(ns) if ns in preferred else 10**6, item)

    return sorted(items, key=key)


def expand(template: str, index: TagIndex, representative_only: bool = True,
           preferred: list[str] | None = None, max_patterns: int = 256) -> list[dict]:
    preferred = preferred or ["minecraft"]
    arrow = next((a for a in ("->", "=>", "\u2192") if a in template), None)
    if arrow is None:
        raise ValueError("missing arrow")
    left, right = template.split(arrow, 1)

    def parse_side(side: str):
        specs = []
        for element in side.split("+"):
            element = element.strip()
            if not element:
                continue
            match = re.match(r"^(\d+)\s*[xX]\s*(.+)$", element) or re.match(r"^(\d+)\s+(.+)$", element)
            amount, body = (int(match.group(1)), match.group(2).strip()) if match else (1, element)
            filt = None
            fm = re.match(r"^(.*?)\s*\{([^}]*)\}\s*$", body)
            if fm:
                body, filt = fm.group(1).strip(), fm.group(2)
            includes, excludes = set(), set()
            if filt is not None:
                for raw in filt.split(","):
                    entry = raw.strip().lower()
                    if not entry:
                        continue
                    (excludes if entry.startswith("!") else includes).add(entry.lstrip("!"))
            specs.append({"amount": amount, "token": body, "roots": resolve_roots(index, body),
                          "includes": includes, "excludes": excludes,
                          "fixed": not body.startswith("#") and "*" not in body})
        return specs

    specs = parse_side(left) + parse_side(right)
    input_count = len(parse_side(left))

    wildcards = [spec for spec in specs if not spec["fixed"]]
    if not wildcards:
        return [{"variant": "", "stacks": [(s["token"], s["amount"]) for s in specs],
                 "inputs": input_count}]

    variant_maps = []
    for spec in wildcards:
        variants: dict[str, list[str]] = {}
        for root in spec["roots"]:
            for key, items in index.collect_variants(root).items():
                variants.setdefault(key, [])
                for item in items:
                    if item not in variants[key]:
                        variants[key].append(item)
        filtered = {}
        for key, items in variants.items():
            if not items:
                continue
            if key in spec["excludes"]:
                continue
            if spec["includes"] and key not in spec["includes"]:
                continue
            filtered[key] = items
        if not filtered:
            raise ValueError(f"empty family {spec['token']}")
        variant_maps.append(filtered)

    common = set(variant_maps[0])
    for mapping in variant_maps[1:]:
        common &= set(mapping)
    if not common:
        raise ValueError("no common variant")

    patterns = []
    for variant in sorted(common):
        candidates = []
        for index_in_specs, spec in enumerate(specs):
            if spec["fixed"]:
                candidates.append([spec["token"]])
                continue
            slot = wildcards.index(spec)
            items = sort_candidates(variant_maps[slot][variant], preferred)
            candidates.append(items[:1] if representative_only and len(items) > 1 else items)
        combo: list[str] = [""] * len(specs)

        def product(depth: int):
            if len(patterns) >= max_patterns:
                return
            if depth == len(specs):
                stacks = [(combo[i], specs[i]["amount"]) for i in range(len(specs))]
                patterns.append({"variant": variant, "stacks": stacks, "inputs": input_count})
                return
            for item in candidates[depth]:
                combo[depth] = item
                product(depth + 1)
                if len(patterns) >= max_patterns:
                    return

        product(0)
    return patterns


# ---------------------------------------------------------------------------
# tests
# ---------------------------------------------------------------------------

def build_index() -> TagIndex:
    tags = {
        "forge:ingots": ["minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot",
                         "testmod:tin_ingot"],
        "forge:ingots/iron": ["minecraft:iron_ingot", "othermod:iron_ingot"],
        "forge:ingots/copper": ["minecraft:copper_ingot"],
        "forge:ingots/gold": ["minecraft:gold_ingot"],
        "forge:ingots/tin": ["testmod:tin_ingot"],
        "forge:ingots/lead": ["testmod:lead_ingot"],
        "forge:plates": ["testmod:iron_plate", "testmod:copper_plate", "testmod:tin_plate",
                         "othermod:iron_plate"],
        "forge:plates/iron": ["testmod:iron_plate", "othermod:iron_plate"],
        "forge:plates/copper": ["testmod:copper_plate"],
        "forge:plates/tin": ["testmod:tin_plate"],
        "forge:gears": ["testmod:iron_gear", "testmod:copper_gear"],
        "forge:gears/iron": ["testmod:iron_gear"],
        "forge:rods": ["testmod:iron_rod", "testmod:copper_rod"],
        "forge:rods/iron": ["testmod:iron_rod"],
        "forge:rods/copper": ["testmod:copper_rod"],
        "forge:gems/diamond": ["minecraft:diamond"],
        "forge:storage_blocks": ["minecraft:iron_block", "testmod:tin_block"],
        "minecraft:logs": ["minecraft:oak_log", "minecraft:birch_log"],
        "minecraft:logs/oak": ["minecraft:oak_log"],
        "minecraft:logs/birch": ["minecraft:birch_log"],
        "minecraft:planks/oak": ["minecraft:oak_planks"],
        "minecraft:planks/birch": ["minecraft:birch_planks"],
    }
    return TagIndex(tags)


def show(title: str, template: str, index: TagIndex, **kwargs) -> None:
    print(f"\n=== {title}\n    template: {template}")
    try:
        patterns = expand(template, index, **kwargs)
    except ValueError as error:
        print(f"    ERROR: {error}")
        return
    print(f"    {len(patterns)} pattern(s)")
    for pattern in patterns:
        left = " + ".join(f"{amount}x {item}" for item, amount in pattern["stacks"][:pattern["inputs"]])
        right = " + ".join(f"{amount}x {item}" for item, amount in pattern["stacks"][pattern["inputs"]:])
        print(f"      [{pattern['variant'] or '-'}] {left}  ->  {right}")


def main() -> None:
    index = build_index()

    show("basic material pairing", "ingot* -> plate*", index)
    show("explicit tag family", "#forge:ingots -> #forge:plates", index)
    show("filter whitelist", "ingot*{iron,copper} -> plate*", index)
    show("filter blacklist", "ingot*{!iron,!gold} -> plate*", index)
    show("fixed item mixed with wildcard", "2x ingot* + minecraft:coal -> 2x plate*", index)
    show("all items instead of representative", "ingot* -> plate*", index, representative_only=False)
    show("vanilla families", "log* -> plank*", index)
    show("no plate family for lead -> lead is skipped", "ingot*{lead} -> plate*", index)
    show("gear/rod pairing", "gear* -> rod*", index)
    show("reverse pairing", "plate* -> ingot*", index)

    # assertions on the semantics that matter most
    basic = expand("ingot* -> plate*", index)
    pairs = {pattern["variant"]: tuple(item for item, _ in pattern["stacks"]) for pattern in basic}
    assert set(pairs) == {"iron", "copper", "tin"}, pairs
    # minecraft: wins the namespace preference, othermod: wins the plate tie-break by name
    assert pairs["iron"] == ("minecraft:iron_ingot", "othermod:iron_plate"), pairs["iron"]
    assert pairs["tin"] == ("testmod:tin_ingot", "testmod:tin_plate"), pairs["tin"]

    # without the representative shortcut the two iron ingots/plates are combined
    all_items = expand("ingot* -> plate*", index, representative_only=False)
    iron = [pattern for pattern in all_items if pattern["variant"] == "iron"]
    assert len(iron) == 4, iron
    assert {item for pattern in iron for item, _ in pattern["stacks"]} == {
        "minecraft:iron_ingot", "othermod:iron_ingot", "testmod:iron_plate", "othermod:iron_plate"}

    # a representative can also be forced by namespace preference order
    other_first = expand("ingot* -> plate*", index, preferred=["othermod"])
    other_iron = [pattern for pattern in other_first if pattern["variant"] == "iron"][0]
    assert other_iron["stacks"][0][0] == "othermod:iron_ingot", other_iron

    filtered = expand("ingot*{iron,copper} -> plate*", index)
    assert {pattern["variant"] for pattern in filtered} == {"iron", "copper"}

    blacklist = expand("ingot*{!iron,!gold} -> plate*", index)
    assert {pattern["variant"] for pattern in blacklist} == {"copper", "tin"}

    fixed = expand("minecraft:iron_ingot -> minecraft:gold_ingot", index)
    assert len(fixed) == 1 and fixed[0]["variant"] == ""

    logs = expand("log* -> plank*", index)
    assert [pattern["variant"] for pattern in logs] == ["birch", "oak"], logs

    # the cap is respected
    capped = expand("ingot* -> plate*", index, representative_only=False, max_patterns=2)
    assert len(capped) == 2, capped

    print("\nAll assertions passed.")


if __name__ == "__main__":
    main()
