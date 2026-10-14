"""Second cover option: the dark "27 patterns vs 1 pattern" comparison cover.

Reuses the drawing helpers from generate_mcmod_images.py (importing it does not regenerate anything -
its main() only runs when it is executed directly).

Run:  python tools/generate_mcmod_cover_alt.py
Out:  mcmod/images/01b-cover-compare.png
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))

from generate_mcmod_images import (  # noqa: E402  (path set up above)
    ACCENT, BORDER, DARK, GOLD, LIGHT, MUTED, PANEL, SLOT, SLOT_DARK, TEXT,
    OUT, bevel, card, centred, font, pattern_icon, slot,
)

BG_TOP = (0x1A, 0x1E, 0x24)
BG_BOTTOM = (0x27, 0x2C, 0x34)
GRID = (0x2E, 0x35, 0x3E)
CYAN = (0x00, 0xD5, 0xFF)
TEXT_LIGHT = (0xE8, 0xF4, 0xFF)
TEXT_DIM = (0x93, 0xA8, 0xBC)
PANEL_DARK = (0x14, 0x18, 0x1D)
PANEL_DARK2 = (0x1D, 0x22, 0x29)
RED = (0xE0, 0x5A, 0x4A)
GREEN = (0x5A, 0xC8, 0x6A)

# a spread of "metal plate" colours for the left hand pile
PLATES = [
    (0xD8, 0xD8, 0xD8), (0xE8, 0x8A, 0x5A), (0xC9, 0xD2, 0xDA), (0x8A, 0x8A, 0x9A),
    (0xE8, 0xB4, 0x6A), (0xC0, 0xC6, 0xCE), (0xB6, 0xC2, 0xA8), (0xD2, 0xC8, 0xA0),
    (0x9A, 0xC4, 0x9A), (0x8F, 0xB6, 0xD8), (0xD8, 0xA8, 0xC0), (0xB0, 0xA8, 0xD8),
    (0xD8, 0xC8, 0x8A), (0xA8, 0xD0, 0xC8), (0xC8, 0xB0, 0x90),
]


def background(w: int, h: int) -> Image.Image:
    img = Image.new("RGB", (w, h), BG_TOP)
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(
            int(BG_TOP[i] + (BG_BOTTOM[i] - BG_TOP[i]) * t) for i in range(3)))
    for x in range(0, w, 40):
        d.line([(x, 0), (x, h)], fill=GRID)
    for y in range(0, h, 40):
        d.line([(0, y), (w, y)], fill=GRID)
    return img


def plate(d, x, y, size, colour):
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=colour)
    d.rectangle([x, y, x + size - 1, y + max(1, size // 8)], fill=LIGHT)
    d.rectangle([x, y + size - max(1, size // 8), x + size - 1, y + size - 1],
                fill=tuple(max(0, c - 40) for c in colour))


def mini_pattern(d, x, y, colour, size=40):
    """One concrete pattern in the pile: a dark card with a plate and a tiny pattern slot."""
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=PANEL_DARK2)
    d.rectangle([x, y, x + size - 1, y], fill=(0x3A, 0x42, 0x4C))
    d.rectangle([x, y + size - 1, x + size - 1, y + size - 1], fill=(0x0C, 0x0F, 0x13))
    plate(d, x + 7, y + 8, size - 14, colour)


def badge(d, cx, cy, colour, mark):
    d.ellipse([cx - 17, cy - 17, cx + 17, cy + 17], fill=colour)
    d.ellipse([cx - 17, cy - 17, cx + 17, cy + 17], outline=(0x0C, 0x0F, 0x13), width=2)
    centred(d, mark, cx - 17, cy - 13, 34, font(24, True), (0x0C, 0x0F, 0x13))


def cover() -> Image.Image:
    W, H = 1200, 600
    img = background(W, H)
    d = ImageDraw.Draw(img)

    d.rectangle([0, 0, W - 1, H - 1], outline=(0x0C, 0x0F, 0x13), width=6)
    d.rectangle([7, 7, W - 8, H - 8], outline=CYAN, width=2)

    # ---- title
    pattern_icon(d, 56, 44, 84)
    d.text((164, 46), "通配样板", font=font(58, True), fill=TEXT_LIGHT)
    d.text((168, 114), "AE2 Wildcard Pattern", font=font(26), fill=TEXT_DIM)

    chip_x = W - 300
    d.rectangle([chip_x, 46, W - 60, 84], fill=PANEL_DARK, outline=CYAN, width=2)
    centred(d, "v2.2.0", chip_x, 52, 240, font(22, True), CYAN)
    centred(d, "MC 1.20.1 · Forge 47 · AE2 15", chip_x, 96, 240, font(18), TEXT_DIM)

    d.line([56, 176, W - 56, 176], fill=(0x3A, 0x44, 0x50), width=2)

    # ---- left: the old way
    d.text((60, 196), "以前", font=font(30, True), fill=RED)
    d.rectangle([56, 236, 540, 470], fill=PANEL_DARK, outline=(0x3A, 0x44, 0x50), width=2)
    for i in range(15):
        col, row = i % 5, i // 5
        mini_pattern(d, 80 + col * 92, 262 + row * 62, PLATES[i])
    badge(d, 508, 266, RED, "×")
    d.text((80, 438), "每种金属各编一张 · 以前一共 27 张", font=font(19), fill=TEXT_DIM)

    # ---- middle arrow
    d.line([556, 352, 626, 352], fill=CYAN, width=5)
    d.polygon([(626, 336), (626, 368), (654, 352)], fill=CYAN)

    # ---- right: the new way
    d.text((672, 196), "现在", font=font(30, True), fill=GREEN)
    d.rectangle([668, 236, 1144, 470], fill=PANEL_DARK, outline=GREEN, width=3)
    pattern_icon(d, 700, 292, 128)
    d.text((856, 300), "ingot* → plate*", font=font(34, True), fill=CYAN)
    d.text((858, 350), "一张样板，自动适配所有已注册金属", font=font(19), fill=TEXT_LIGHT)
    d.text((858, 382), "材质、配对、排除全部由规则描述", font=font(17), fill=TEXT_DIM)
    badge(d, 1112, 268, GREEN, "√")
    d.text((700, 438), "27 种金属 → 仍然是 1 张样板", font=font(19), fill=TEXT_LIGHT)

    # ---- footer
    centred(d, "不用再为每种材料各编一张样板", 0, 496, W, font(30, True), TEXT_LIGHT)
    centred(d, "运行时按规则展开 · 不生成具体样板 · 与 AE2 原版供应器同款返回槽",
            0, 540, W, font(19), TEXT_DIM)
    return img


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    target = OUT / "01b-cover-compare.png"
    cover().save(target)
    print("wrote", target, cover().size)
