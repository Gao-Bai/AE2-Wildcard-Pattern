"""Generates the mod's textures.

Run with the bundled Python:  python tools/generate_texture.py
Outputs:
  src/main/resources/assets/wildcardencoder/textures/block/wildcard_pattern_encoder.png
  src/main/resources/assets/wildcardencoder/textures/item/wildcard_pattern.png
"""

from pathlib import Path

from PIL import Image

SIZE = 16
ASSETS = Path(__file__).resolve().parent.parent / "src/main/resources/assets/wildcardencoder/textures"
OUT = ASSETS / "block/wildcard_pattern_encoder.png"
OUT_PROVIDER = ASSETS / "block/wildcard_pattern_provider.png"
OUT_ITEM = ASSETS / "item/wildcard_pattern.png"

BASE = (42, 48, 58, 255)
BASE_A = (46, 52, 63, 255)
LIGHT = (74, 82, 96, 255)
DARK = (24, 28, 34, 255)
OUTLINE = (16, 19, 23, 255)
ACCENT = (94, 220, 205, 255)
ACCENT_HI = (176, 246, 236, 255)

# classic 7x7 asterisk glyph
ASTERISK = [
    "X..X..X",
    ".X.X.X.",
    "..XXX..",
    "XXXXXXX",
    "..XXX..",
    ".X.X.X.",
    "X..X..X",
]

RIVETS = [(2, 2), (12, 2), (2, 12), (12, 12)]


def main() -> None:
    img = Image.new("RGBA", (SIZE, SIZE), BASE)
    px = img.load()

    # subtle checker so the plate is not perfectly flat
    for y in range(SIZE):
        for x in range(SIZE):
            if (x + y) % 2 == 0:
                px[x, y] = BASE_A

    # bevel
    for x in range(SIZE):
        px[x, 0] = LIGHT
        px[x, SIZE - 1] = DARK
    for y in range(SIZE):
        px[0, y] = LIGHT
        px[SIZE - 1, y] = DARK

    # corner rivets
    for rx, ry in RIVETS:
        px[rx, ry] = LIGHT
        px[rx + 1, ry] = LIGHT
        px[rx, ry + 1] = LIGHT
        px[rx + 1, ry + 1] = DARK

    # asterisk mask
    offset = (SIZE - len(ASTERISK)) // 2  # 4
    mask = set()
    for row, line in enumerate(ASTERISK):
        for col, char in enumerate(line):
            if char == "X":
                mask.add((offset + col, offset + row))

    # dark outline around the glyph for contrast
    for gx, gy in list(mask):
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                point = (gx + dx, gy + dy)
                if point not in mask and 0 < point[0] < SIZE - 1 and 0 < point[1] < SIZE - 1:
                    px[point] = OUTLINE

    for gx, gy in mask:
        px[gx, gy] = ACCENT

    # small highlight in the upper left of the glyph
    px[offset + 3, offset] = ACCENT_HI
    px[offset + 3, offset + 1] = ACCENT_HI

    OUT.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT)
    print(f"wrote {OUT} ({img.size[0]}x{img.size[1]} {img.mode})")
    make_item()
    make_provider()


def make_provider() -> None:
    """The wildcard pattern provider: a dark plate with a teal chip motif."""
    img = Image.new("RGBA", (SIZE, SIZE), BASE)
    px = img.load()

    for y in range(SIZE):
        for x in range(SIZE):
            if (x + y) % 2 == 0:
                px[x, y] = BASE_A
    for x in range(SIZE):
        px[x, 0] = LIGHT
        px[x, SIZE - 1] = DARK
    for y in range(SIZE):
        px[0, y] = LIGHT
        px[SIZE - 1, y] = DARK

    chip = (26, 32, 38, 255)
    for y in range(5, 11):
        for x in range(5, 11):
            px[x, y] = chip
    for i in range(5, 11):
        px[i, 5] = ACCENT
        px[i, 10] = ACCENT
        px[5, i] = ACCENT
        px[10, i] = ACCENT
    for i in range(6, 10, 2):
        px[i, 4] = ACCENT_HI
        px[i, 11] = ACCENT_HI
        px[4, i] = ACCENT_HI
        px[11, i] = ACCENT_HI
    px[7, 7] = ACCENT_HI
    px[8, 8] = ACCENT_HI
    px[8, 7] = ACCENT
    px[7, 8] = ACCENT

    OUT_PROVIDER.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT_PROVIDER)
    print(f"wrote {OUT_PROVIDER} ({img.size[0]}x{img.size[1]} {img.mode})")


def make_item() -> None:
    """The wildcard pattern item: a little amber rule card with an asterisk."""
    img = Image.new("RGBA", (SIZE, SIZE), (0, 0, 0, 0))
    px = img.load()

    body = (58, 46, 30, 255)
    body_alt = (44, 35, 23, 255)
    edge = (126, 99, 56, 255)
    accent = (255, 214, 122, 255)
    highlight = (255, 248, 220, 255)

    for y in range(1, SIZE - 1):
        for x in range(1, SIZE - 1):
            px[x, y] = body if (x + y) % 2 == 0 else body_alt
    for x in range(1, SIZE - 1):
        px[x, 1] = edge
        px[x, SIZE - 2] = edge
    for y in range(1, SIZE - 1):
        px[1, y] = edge
        px[SIZE - 2, y] = edge

    offset = (SIZE - len(ASTERISK)) // 2
    for row, line in enumerate(ASTERISK):
        for col, char in enumerate(line):
            if char == "X":
                px[offset + col, offset + row] = accent

    px[offset + 3, offset] = highlight
    px[offset + 3, offset + 1] = highlight

    OUT_ITEM.parent.mkdir(parents=True, exist_ok=True)
    img.save(OUT_ITEM)
    print(f"wrote {OUT_ITEM} ({img.size[0]}x{img.size[1]} {img.mode})")


if __name__ == "__main__":
    main()
