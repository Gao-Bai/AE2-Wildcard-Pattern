"""Square (900x900) covers for MC百科, which wants a square mod cover.

Two options, same composition, different palette:
  01-cover-900.png           light AE2 container style
  01b-cover-compare-900.png  dark tech style (recommended for a mod list thumbnail)

Reuses the drawing helpers from generate_mcmod_images.py.

Run:  python tools/generate_mcmod_cover_square.py
"""

import sys
from pathlib import Path

from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))

from generate_mcmod_images import (  # noqa: E402
    ACCENT, BORDER, DARK, GOLD, LIGHT, MUTED, PANEL, SLOT, SLOT_DARK, TEXT,
    OUT, centred, font, pattern_icon,
)

SIZE = 900

# metal colours for the pile of concrete patterns
PLATES = [
    (0xD8, 0xD8, 0xD8), (0xE8, 0x8A, 0x5A), (0xC9, 0xD2, 0xDA), (0x8A, 0x8A, 0x9A),
    (0xE8, 0xB4, 0x6A), (0xC0, 0xC6, 0xCE), (0xB6, 0xC2, 0xA8), (0xD2, 0xC8, 0xA0),
    (0x9A, 0xC4, 0x9A), (0x8F, 0xB6, 0xD8), (0xD8, 0xA8, 0xC0), (0xB0, 0xA8, 0xD8),
    (0xD8, 0xC8, 0x8A), (0xA8, 0xD0, 0xC8), (0xC8, 0xB0, 0x90), (0xA0, 0xA8, 0xB8),
    (0xC8, 0xC0, 0xB0), (0x9C, 0xB0, 0xC8), (0xD0, 0xB8, 0xA8), (0xA8, 0xB8, 0xA0),
    (0xB8, 0xA8, 0xB0), (0xC0, 0xB0, 0xA0), (0xA0, 0xC0, 0xC0), (0xB0, 0xB8, 0xC8),
]

DARK_BG_TOP = (0x1A, 0x1E, 0x24)
DARK_BG_BOTTOM = (0x27, 0x2C, 0x34)
DARK_GRID = (0x2E, 0x35, 0x3E)
CYAN = (0x00, 0xD5, 0xFF)
TEXT_LIGHT = (0xE8, 0xF4, 0xFF)
TEXT_DIM = (0x93, 0xA8, 0xBC)
PANEL_DARK = (0x14, 0x18, 0x1D)
PANEL_DARK2 = (0x1D, 0x22, 0x29)
DARK_LINE = (0x3A, 0x44, 0x50)
RED = (0xE0, 0x5A, 0x4A)
GREEN = (0x5A, 0xC8, 0x6A)


class Palette:
    def __init__(self, dark: bool):
        self.dark = dark
        if dark:
            self.bg = None  # gradient, drawn separately
            self.border = (0x0C, 0x0F, 0x13)
            self.accent = CYAN
            self.title = TEXT_LIGHT
            self.subtitle = TEXT_DIM
            self.body = TEXT_LIGHT
            self.dim = TEXT_DIM
            self.panel = PANEL_DARK
            self.panel_light = PANEL_DARK2
            self.line = DARK_LINE
            self.chip_bg = PANEL_DARK
            self.highlight = CYAN
            self.wild_bg = (0x2A, 0x21, 0x14)
            self.wild_edge = GOLD
        else:
            self.bg = PANEL
            self.border = BORDER
            self.accent = ACCENT
            self.title = TEXT
            self.subtitle = MUTED
            self.body = TEXT
            self.dim = MUTED
            self.panel = (0xD4, 0xD4, 0xD4)
            self.panel_light = (0xE2, 0xE2, 0xE2)
            self.line = DARK
            self.chip_bg = (0xE2, 0xE2, 0xE2)
            self.highlight = (0x1F, 0x6F, 0xB2)
            self.wild_bg = (0x3A, 0x2E, 0x1E)
            self.wild_edge = GOLD


def background(p: Palette) -> Image.Image:
    img = Image.new("RGB", (SIZE, SIZE), p.bg if p.bg else DARK_BG_TOP)
    d = ImageDraw.Draw(img)
    if p.dark:
        for y in range(SIZE):
            t = y / (SIZE - 1)
            d.line([(0, y), (SIZE, y)], fill=tuple(
                int(DARK_BG_TOP[i] + (DARK_BG_BOTTOM[i] - DARK_BG_TOP[i]) * t) for i in range(3)))
        for x in range(0, SIZE, 40):
            d.line([(x, 0), (x, SIZE)], fill=DARK_GRID)
        for y in range(0, SIZE, 40):
            d.line([(0, y), (SIZE, y)], fill=DARK_GRID)
    d.rectangle([0, 0, SIZE - 1, SIZE - 1], outline=p.border, width=6)
    d.rectangle([7, 7, SIZE - 8, SIZE - 8], outline=p.accent, width=2)
    return img


def plate(d, x, y, size, colour):
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=colour)
    d.rectangle([x, y, x + size - 1, y + max(1, size // 8)], fill=LIGHT)
    d.rectangle([x, y + size - max(1, size // 8), x + size - 1, y + size - 1],
                fill=tuple(max(0, c - 40) for c in colour))


def mini_pattern(d, p: Palette, x, y, colour, size=48):
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=p.panel_light)
    d.rectangle([x, y, x + size - 1, y], fill=LIGHT if not p.dark else DARK_LINE)
    d.rectangle([x, y + size - 1, x + size - 1, y + size - 1], fill=SLOT_DARK)
    plate(d, x + 9, y + 9, size - 18, colour)


def badge(d, p: Palette, cx, cy, colour, mark):
    d.ellipse([cx - 19, cy - 19, cx + 19, cy + 19], fill=colour)
    d.ellipse([cx - 19, cy - 19, cx + 19, cy + 19], outline=p.border, width=2)
    centred(d, mark, cx - 19, cy - 15, 38, font(26, True), p.border)


def panel(d, p: Palette, x, y, w, h, outline=None):
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=p.panel)
    d.rectangle([x, y, x + w - 1, y + h - 1], outline=outline or p.line, width=2)
    d.line([x + 2, y + 2, x + w - 3, y + 2], fill=LIGHT if not p.dark else DARK_LINE)
    d.line([x + 2, y + 2, x + 2, y + h - 3], fill=LIGHT if not p.dark else DARK_LINE)


def down_arrow(d, p: Palette, x, y_from, y_to):
    d.line([x, y_from, x, y_to - 16], fill=p.accent, width=5)
    d.polygon([(x - 16, y_to - 16), (x + 16, y_to - 16), (x, y_to)], fill=p.accent)


def cover(dark: bool) -> Image.Image:
    p = Palette(dark)
    img = background(p)
    d = ImageDraw.Draw(img)

    # ---------------------------------------------------------------- title
    pattern_icon(d, 46, 42, 76)
    d.text((136, 44), "通配样板", font=font(52, True), fill=p.title)
    d.text((140, 104), "AE2 Wildcard Pattern", font=font(22), fill=p.subtitle)

    chip_x, chip_w = SIZE - 250, 200
    d.rectangle([chip_x, 44, chip_x + chip_w, 80], fill=p.chip_bg, outline=p.accent, width=2)
    centred(d, "v2.2.0", chip_x, 50, chip_w, font(20, True), p.accent)
    centred(d, "MC 1.20.1 · Forge 47 · AE2 15", chip_x - 20, 90, chip_w + 40, font(16), p.subtitle)

    d.line([44, 156, SIZE - 44, 156], fill=p.line, width=2)

    # ---------------------------------------------------------------- before
    px, pw = 44, SIZE - 88
    d.text((px + 6, 172), "以前", font=font(26, True), fill=RED)
    panel(d, p, px, 208, pw, 250, outline=p.line if p.dark else DARK)
    badge(d, p, px + pw - 32, 232, RED, "×")
    for i in range(24):
        col, row = i % 8, i // 8
        mini_pattern(d, p, px + 16 + col * 96, 244 + row * 62, PLATES[i])
    d.text((px + 20, 424), "每种金属各编一张 · 以前一共 27 张", font=font(18), fill=p.dim)

    # ---------------------------------------------------------------- arrow
    down_arrow(d, p, SIZE // 2, 468, 534)

    # ---------------------------------------------------------------- now
    d.text((px + 6, 500), "现在", font=font(26, True), fill=GREEN)
    panel(d, p, px, 536, pw, 250, outline=GREEN)
    badge(d, p, px + pw - 32, 560, GREEN, "√")
    pattern_icon(d, 74, 606, 112)
    d.text((222, 618), "ingot* → plate*", font=font(32, True), fill=p.highlight)
    d.text((224, 664), "一张样板，自动适配所有已注册金属", font=font(19), fill=p.body)
    d.text((224, 694), "材质、配对、排除全部由规则描述", font=font(17), fill=p.dim)
    d.text((74, 736), "27 种金属 → 仍然是 1 张样板", font=font(19), fill=p.body)

    # ---------------------------------------------------------------- footer
    d.line([44, 806, SIZE - 44, 806], fill=p.line, width=2)
    centred(d, "不用再为每种材料各编一张样板", 0, 820, SIZE, font(26, True), p.title)
    centred(d, "运行时按规则展开 · 不生成具体样板 · 与 AE2 原版供应器同款返回槽",
            0, 860, SIZE, font(16), p.dim)
    return img


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    for name, dark in (("01-cover-900.png", False), ("01b-cover-compare-900.png", True)):
        target = OUT / name
        cover(dark).save(target)
        print("wrote", target, f"{SIZE}x{SIZE}")
