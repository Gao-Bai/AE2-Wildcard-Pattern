"""Renders a mock-up of the editor screen (same constants as WildcardPatternScreen).

This is a preview, not a screenshot: the sandbox cannot launch Minecraft. Colours, coordinates and
sizes are taken from the Java constants so the layout can be checked without the game.

Run:  python tools/ui_preview.py
Out:  docs/preview-editor.png, docs/preview-help.png
"""

from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

SCALE = 3
W, H = 220, 236

TAB_Y, TAB_H, TAB_W = 16, 15, 68
CONTENT_Y, ROW_H, ROWS, ROW_X, ROW_W = 34, 20, 4, 8, 204
STATUS_Y, BUTTON_Y, INV_LABEL_Y = 116, 127, 149
INV_X, INV_ROW_Y, HOTBAR_Y = 8, 160, 216
IN_MINUS_X, IN_PLUS_X = 72, 112
MATCHER_ROW_H, MATCHER_ROWS = 18, 3

TEXT = (0x40, 0x40, 0x40)
DIM = (0x7E, 0x7E, 0x7E)
OK = (0x3C, 0x7A, 0x20)
ACCENT = (0x00, 0xD5, 0xFF)
INFO = (0x1F, 0x6F, 0xB2)
PANEL = (0xC6, 0xC6, 0xC6)
SUB_PANEL = (0xBD, 0xBD, 0xBD)
BORDER = (0x00, 0x00, 0x00)
BEVEL_LIGHT = (0xFF, 0xFF, 0xFF)
BEVEL_DARK = (0x55, 0x55, 0x55)
SLOT = (0x8B, 0x8B, 0x8B)
SLOT_DARK = (0x37, 0x37, 0x37)
FACE = (0xA8, 0xA8, 0xA8)
FACE_ON = (0x8F, 0xC7, 0xE8)

OUT = Path(__file__).resolve().parent.parent / "docs"
FONT_PATH = r"C:\Windows\Fonts\msyh.ttc"


def font(size: int):
    return ImageFont.truetype(FONT_PATH, size * SCALE)


FONT = font(8)
SMALL = font(7)


def rect(d, x, y, w, h, colour):
    d.rectangle([x * SCALE, y * SCALE, (x + w) * SCALE - 1, (y + h) * SCALE - 1], fill=colour)


def text(d, s, x, y, colour, f=None):
    d.text((x * SCALE, y * SCALE), s, font=f or FONT, fill=colour)


def slot(d, x, y, size=16):
    rect(d, x, y, size, size, SLOT)
    rect(d, x, y, size, 1, SLOT_DARK)
    rect(d, x, y, 1, size, SLOT_DARK)
    rect(d, x, y + size - 1, size, 1, BEVEL_LIGHT)
    rect(d, x + size - 1, y, 1, size, BEVEL_LIGHT)


def sunken(d, x, y, w, h):
    rect(d, x, y, w, 1, SLOT_DARK)
    rect(d, x, y, 1, h, SLOT_DARK)
    rect(d, x, y + h - 1, w, 1, BEVEL_LIGHT)
    rect(d, x + w - 1, y, 1, h, BEVEL_LIGHT)


def chip(d, x, y, label, highlighted, bar=None):
    rect(d, x, y, 13, 14, FACE_ON if highlighted else FACE)
    rect(d, x, y, 13, 1, BEVEL_LIGHT)
    rect(d, x, y, 1, 14, BEVEL_LIGHT)
    rect(d, x, y + 13, 13, 1, BEVEL_DARK)
    rect(d, x + 12, y, 1, 14, BEVEL_DARK)
    if highlighted and bar:
        rect(d, x + 1, y + 12, 11, 1, bar)
    text(d, label, x + 3, y + 3, TEXT if highlighted else DIM, SMALL)


def button(d, x, y, w, h, label):
    rect(d, x, y, w, h, (0x6C, 0x6C, 0x6C))
    rect(d, x, y, w, 1, (0x8B, 0x8B, 0x8B))
    rect(d, x, y, 1, h, (0x8B, 0x8B, 0x8B))
    rect(d, x, y + h - 1, w, 1, (0x37, 0x37, 0x37))
    rect(d, x + w - 1, y, 1, h, (0x37, 0x37, 0x37))
    tw = d.textlength(label, font=FONT)
    text(d, label, x + (w * SCALE - tw) / 2 / SCALE, y + (h - 9) / 2, (0xFF, 0xFF, 0xFF))


def build(help_open: bool) -> Image.Image:
    img = Image.new("RGB", (W * SCALE, H * SCALE), PANEL)
    d = ImageDraw.Draw(img)

    rect(d, 0, 0, W, H, BORDER)
    rect(d, 1, 1, W - 2, H - 2, PANEL)
    rect(d, 1, 1, W - 2, 1, BEVEL_LIGHT)
    rect(d, 1, 2, 1, H - 3, BEVEL_LIGHT)
    rect(d, 1, H - 2, W - 2, 1, BEVEL_DARK)
    rect(d, W - 2, 2, 1, H - 3, BEVEL_DARK)

    slot(d, 6, 4)
    rect(d, 9, 7, 10, 10, (0xE8, 0xB4, 0x6A))
    text(d, "通配样板", 26, 6, TEXT)
    text(d, "in 1 / out 1 / excl 0", 128, 6, DIM, SMALL)

    for i, label in enumerate(("原料", "产物", "排除")):
        button(d, 8 + i * (TAB_W + 2), TAB_Y, TAB_W, TAB_H, label)
    rect(d, 8, TAB_Y + TAB_H + 1, TAB_W, 2, ACCENT)

    rect(d, ROW_X, CONTENT_Y - 2, ROW_W, ROWS * ROW_H + 2, SUB_PANEL)
    sunken(d, ROW_X, CONTENT_Y - 2, ROW_W, ROWS * ROW_H + 2)
    rows = [
        ("铜锭", "1", True, False, "A", False),
        ("ingot*", "1", True, False, "A", False),
    ]
    for i, (name, count, consume, optional, group, nbt) in enumerate(rows if not help_open else []):
        row_y = CONTENT_Y + i * ROW_H
        if i == 0:
            rect(d, ROW_X, row_y, ROW_W, ROW_H - 1, (0xE0, 0xE0, 0xE0))
        slot(d, ROW_X + 2, row_y + 2)
        if i == 0:
            rect(d, ROW_X + 5, row_y + 5, 10, 10, (0xE8, 0x8A, 0x5A))
        else:
            # a wildcard row cycles through the items it matches, with a kind badge in the corner
            rect(d, ROW_X + 5, row_y + 5, 10, 10, (0xD8, 0xD8, 0xD8))
            rect(d, ROW_X + 9, row_y + 8, 7, 8, (0x00, 0x00, 0x00))
            text(d, "*", ROW_X + 10, row_y + 9, ACCENT, SMALL)
        text(d, name, ROW_X + 21, row_y + 7, TEXT if i == 0 else INFO)
        chip(d, ROW_X + IN_MINUS_X, row_y + 3, "-", False)
        chip(d, ROW_X + IN_PLUS_X, row_y + 3, "+", False)
        text(d, count, ROW_X + IN_MINUS_X + 19, row_y + 7, TEXT)
        chip(d, ROW_X + 126, row_y + 3, "C", consume, OK)
        chip(d, ROW_X + 140, row_y + 3, "O", optional, (0xB2, 0x6A, 0x00))
        chip(d, ROW_X + 154, row_y + 3, group, bool(group))
        chip(d, ROW_X + 168, row_y + 3, "N", nbt, INFO)
        chip(d, ROW_X + 182, row_y + 3, "X", False)

    if help_open:
        x, y = ROW_X, CONTENT_Y - 2
        width = ROW_W
        height = (STATUS_Y + 12) - (CONTENT_Y - 2)
        rect(d, x, y, width, height, PANEL)
        sunken(d, x, y, width, height)
        text(d, "图例：", x + 6, y + 4, TEXT)
        lines = [
            "C 消耗 —— 开：从网络扣除；关：不消耗",
            "O 可选 —— 这条原料可以不放",
            "A 组 —— 同组绑定同一个材料",
            "N NBT —— 附加 NBT 条件",
            "X 删除 —— 移除这一行",
            "数量：点 - / +，点数字可直接输入",
            "鼠标停在字母上也会提示；点任意处关闭",
        ]
        line = y + 16
        for entry in lines:
            muted = entry.startswith(("数量", "鼠标"))
            text(d, entry, x + 6, line, DIM if muted else TEXT, SMALL)
            line += 11
    else:
        text(d, "将生成 1 条，排除 0 条", 8, STATUS_Y, OK)
        text(d, "1x 铜锭 -> 1x 青铜板", 118, STATUS_Y, DIM, SMALL)

    for x, w, label in ((8, 36, "+"), (46, 40, "编码"), (88, 34, "选项"), (124, 30, "复制"),
                        (156, 30, "粘贴"), (188, 20, "?")):
        button(d, x, BUTTON_Y, w, 18, label)

    text(d, "物品栏", INV_X, INV_LABEL_Y, DIM)
    for row in range(3):
        for col in range(9):
            slot(d, INV_X + col * 18, INV_ROW_Y + row * 18)
    for col in range(9):
        slot(d, INV_X + col * 18, HOTBAR_Y)
    rect(d, INV_X + 3, INV_ROW_Y + 3, 10, 10, (0xE8, 0x8A, 0x5A))
    rect(d, INV_X + 21, INV_ROW_Y + 3, 10, 10, (0x8A, 0x8A, 0x8A))
    rect(d, INV_X + 4 * 18 + 3, HOTBAR_Y + 3, 10, 10, (0x6A, 0x9A, 0xE8))
    return img


def build_matcher(help_unused: bool = False) -> Image.Image:
    """The matcher overlay: kind buttons, search row and three roomy result rows."""
    img = Image.new("RGB", (W * SCALE, H * SCALE), PANEL)
    d = ImageDraw.Draw(img)

    rect(d, 0, 0, W, H, BORDER)
    rect(d, 1, 1, W - 2, H - 2, PANEL)
    rect(d, 1, 1, W - 2, 1, BEVEL_LIGHT)
    rect(d, 1, 2, 1, H - 3, BEVEL_LIGHT)
    rect(d, 1, H - 2, W - 2, 1, BEVEL_DARK)
    rect(d, W - 2, 2, 1, H - 3, BEVEL_DARK)

    slot(d, 6, 4)
    rect(d, 9, 7, 10, 10, (0xE8, 0xB4, 0x6A))
    text(d, "通配样板", 26, 6, TEXT)
    for i, label in enumerate(("原料", "产物", "排除")):
        button(d, 8 + i * (TAB_W + 2), TAB_Y, TAB_W, TAB_H, label)
    rect(d, 8, TAB_Y + TAB_H + 1, TAB_W, 2, ACCENT)

    # dim + matcher panel
    rect(d, 4, CONTENT_Y - 4, W - 8, BUTTON_Y - CONTENT_Y, (0x8C, 0x8C, 0x8C))

    search_y = CONTENT_Y + 2
    # collapsed search bar: one click expands it into a text field
    button(d, ROW_X, search_y, ROW_W - 108, 14, "搜索物品 / #标签 / @模组")
    button(d, ROW_X + ROW_W - 104, search_y, 32, 14, "NBT")
    button(d, ROW_X + ROW_W - 68, search_y, 32, 14, "确定")
    button(d, ROW_X + ROW_W - 34, search_y, 32, 14, "取消")
    kind_y = CONTENT_Y + 18
    for i, label in enumerate(("物品", "#标签", "族通配")):
        button(d, ROW_X + i * 50, kind_y, 48, 14, label)
    # the kind in effect is underlined, like the active tab
    rect(d, ROW_X, kind_y + 15, 48, 2, ACCENT)

    list_y = CONTENT_Y + 34
    height = MATCHER_ROWS * MATCHER_ROW_H + 2
    rect(d, ROW_X, list_y, ROW_W, height, PANEL)
    sunken(d, ROW_X, list_y, ROW_W, height)
    rows = [("铁锭", "minecraft:iron_ingot"), ("铜锭", "minecraft:copper_ingot"),
            ("锡锭", "testmod:tin_ingot")]
    for i, (title, subtitle) in enumerate(rows):
        row_y = list_y + 1 + i * MATCHER_ROW_H
        if i == 1:
            rect(d, ROW_X + 1, row_y, ROW_W - 2, MATCHER_ROW_H - 1, FACE_ON)
        slot(d, ROW_X + 3, row_y + 1)
        rect(d, ROW_X + 6, row_y + 4, 10, 10, (0xD8, 0xD8, 0xD8))
        text(d, title, ROW_X + 24, row_y + 5, TEXT if i == 1 else DIM)
        text(d, subtitle[:13], ROW_X + 104, row_y + 5, DIM, SMALL)
    info = "2/64"
    text(d, info, ROW_X + ROW_W - 24, list_y + height - MATCHER_ROW_H + 2, DIM, SMALL)

    for x, w, label in ((8, 36, "+"), (46, 40, "编码"), (88, 34, "选项"), (124, 30, "复制"),
                        (156, 30, "粘贴"), (188, 20, "?")):
        button(d, x, BUTTON_Y, w, 18, label)
    text(d, "物品栏", INV_X, INV_LABEL_Y, DIM)
    for row in range(3):
        for col in range(9):
            slot(d, INV_X + col * 18, INV_ROW_Y + row * 18)
    for col in range(9):
        slot(d, INV_X + col * 18, HOTBAR_Y)
    return img


if __name__ == "__main__":
    OUT.mkdir(parents=True, exist_ok=True)
    build(False).save(OUT / "preview-editor.png")
    build(True).save(OUT / "preview-help.png")
    build_matcher().save(OUT / "preview-matcher.png")
    print("wrote preview-editor.png, preview-help.png, preview-matcher.png to", OUT)
