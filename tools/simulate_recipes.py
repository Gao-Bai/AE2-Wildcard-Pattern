"""Mirror of ConfigRecipes' shape logic, so the trimming/row-major contract is asserted offline.

The Java side builds a ShapedRecipe from nine config entries; the width/height and the row-major order
have to match exactly or a configured recipe silently never matches. This checks the algorithm and the
default config lists (parsed straight out of WildcardConfig.java).

Run:  python tools/simulate_recipes.py
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / "src/main/java/com/ae2wildcard/encoder/WildcardConfig.java"


def build(grid):
    """Same logic as ConfigRecipes.addShaped: returns (width, height, [entries row major]) or None."""
    def entry(index):
        return grid[index].strip() if index < len(grid) else ""

    min_row, max_row, min_col, max_col = 3, -1, 3, -1
    for row in range(3):
        for col in range(3):
            if not entry(row * 3 + col):
                continue
            min_row, max_row = min(min_row, row), max(max_row, row)
            min_col, max_col = min(min_col, col), max(max_col, col)
    if max_row < 0:
        return None
    width, height = max_col - min_col + 1, max_row - min_row + 1
    out = []
    for row in range(min_row, max_row + 1):
        for col in range(min_col, max_col + 1):
            out.append(entry(row * 3 + col))
    assert len(out) == width * height, "row major list must hold width*height entries"
    return width, height, out


def default_grid(name: str) -> list:
    """Reads one defineList default out of WildcardConfig.java."""
    text = CONFIG.read_text(encoding="utf-8")
    match = re.search(r'defineList\("' + name + r'", List\.of\((.*?)\),\s*value ->', text, re.S)
    assert match, f"could not find {name} in WildcardConfig.java"
    return re.findall(r'"([^"]*)"', match.group(1))


def check(grid, expected_shape, expected_entries, label):
    result = build(grid)
    assert result is not None, f"{label}: expected a recipe"
    width, height, entries = result
    assert (width, height) == expected_shape, f"{label}: shape {width}x{height} != {expected_shape}"
    assert entries == expected_entries, f"{label}: {entries} != {expected_entries}"
    print(f"  ok  {label}: {width}x{height} {entries}")


def main():
    print("default recipes from WildcardConfig.java:")
    check(default_grid("patternEncoderIngredients"), (3, 3),
          ["ae2:quartz_glass", "#forge:ingots/gold", "ae2:quartz_glass",
           "ae2:calculation_processor", "ae2:blank_pattern", "ae2:calculation_processor",
           "ae2:quartz_glass", "#forge:ingots/gold", "ae2:quartz_glass"], "encoder")
    check(default_grid("patternProviderIngredients"), (3, 3),
          ["ae2:quartz_glass", "ae2:calculation_processor", "ae2:quartz_glass",
           "#forge:ingots/gold", "ae2:pattern_provider", "#forge:ingots/gold",
           "ae2:quartz_glass", "ae2:calculation_processor", "ae2:quartz_glass"], "provider")
    check(default_grid("wildcardPatternIngredients"), (3, 3),
          ["#forge:ingots/gold", "ae2:quartz_glass", "#forge:ingots/gold",
           "ae2:quartz_glass", "ae2:blank_pattern", "ae2:quartz_glass",
           "#forge:ingots/gold", "ae2:quartz_glass", "#forge:ingots/gold"], "pattern")

    print("edge cases (what a user config can look like):")
    # bottom row empty -> 3x2, and the shape must not include the empty row
    check(["A", "B", "C", "D", "E", "F", "", "", ""], (3, 2), ["A", "B", "C", "D", "E", "F"], "trim bottom row")
    # left column empty -> 2x3 starting from column 1
    check(["", "A", "B", "", "C", "D", "", "E", "F"], (2, 3), ["A", "B", "C", "D", "E", "F"], "trim left column")
    # a single ingredient in the middle -> 1x1
    check(["", "", "", "", "X", "", "", "", ""], (1, 1), ["X"], "single middle slot")
    # a diagonal -> 3x3 with the corners empty
    check(["A", "", "", "", "B", "", "", "", "C"], (3, 3), ["A", "", "", "", "B", "", "", "", "C"], "diagonal")
    # fewer than nine entries are treated as empty
    check(["A", "B"], (2, 1), ["A", "B"], "short list")
    # nothing at all -> the recipe is skipped
    assert build(["", "", "", "", "", "", "", "", ""]) is None
    assert build([]) is None
    print("  ok  empty grid -> recipe skipped")
    print("\nAll recipe shape assertions passed.")


if __name__ == "__main__":
    main()
