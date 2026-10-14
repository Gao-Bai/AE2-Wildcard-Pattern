"""Generates the MC百科 (mcmod.cn) image set.

These are *drawn* illustrations, not screenshots: the sandbox cannot launch Minecraft. They use the
same AE2 palette and pixel-art style as the in-game screens, so they look consistent with the real UI.
Replace them with real screenshots whenever you have them.

Run:  python tools/generate_mcmod_images.py
Out:  mcmod/images/*.png
"""

import shutil
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "mcmod" / "images"
DOCS = ROOT / "docs"

FONT_REGULAR = r"C:\Windows\Fonts\msyh.ttc"
FONT_BOLD = r"C:\Windows\Fonts\msyhbd.ttc"

PANEL = (0xC6, 0xC6, 0xC6)
PANEL_DARK = (0xBD, 0xBD, 0xBD)
BORDER = (0x00, 0x00, 0x00)
LIGHT = (0xFF, 0xFF, 0xFF)
DARK = (0x55, 0x55, 0x55)
SLOT = (0x8B, 0x8B, 0x8B)
SLOT_DARK = (0x37, 0x37, 0x37)
TEXT = (0x40, 0x40, 0x40)
MUTED = (0x7E, 0x7E, 0x7E)
ACCENT = (0x00, 0xD5, 0xFF)
INFO = (0x1F, 0x6F, 0xB2)
OK = (0x3C, 0x7A, 0x20)
BAD = (0xCE, 0x24, 0x01)
GOLD = (0xE8, 0xB4, 0x6A)
COPPER = (0xE8, 0x8A, 0x5A)
IRON = (0xD8, 0xD8, 0xD8)
TIN = (0xC9, 0xD2, 0xDA)


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    path = FONT_BOLD if bold and Path(FONT_BOLD).exists() else FONT_REGULAR
    return ImageFont.truetype(path, size)


def bevel(d, x, y, w, h, fill=PANEL, raised=True):
    d.rectangle([x, y, x + w - 1, y + h - 1], fill=fill)
    top = LIGHT if raised else SLOT_DARK
    bottom = DARK if raised else LIGHT
    d.line([x, y, x + w - 1, y], fill=top)
    d.line([x, y, x, y + h - 1], fill=top)
    d.line([x, y + h - 1, x + w - 1, y + h - 1], fill=bottom)
    d.line([x + w - 1, y, x + w - 1, y + h - 1], fill=bottom)


def slot(d, x, y, size=48):
    bevel(d, x, y, size, size, SLOT, raised=False)


def card(d, x, y, w, h, fill=PANEL):
    d.rectangle([x - 2, y - 2, x + w + 1, y + h + 1], fill=BORDER)
    bevel(d, x, y, w, h, fill)


def arrow(d, x, y, length, colour=INFO, width=4):
    d.line([x, y, x + length, y], fill=colour, width=width)
    d.polygon([(x + length, y - 10), (x + length, y + 10), (x + length + 16, y)], fill=colour)


def pattern_icon(d, x, y, size=48, accent=GOLD):
    """The wildcard pattern item: an amber rule card with an asterisk."""
    d.rectangle([x, y, x + size - 1, y + size - 1], fill=(0x3A, 0x2E, 0x1E))
    d.rectangle([x + 2, y + 2, x + size - 3, y + size - 3], fill=(0x2C, 0x23, 0x17))
    d.rectangle([x, y, x + size - 1, y + 1], fill=(0x7E, 0x63, 0x38))
    d.rectangle([x, y + size - 2, x + size - 1, y + size - 1], fill=(0x1A, 0x14, 0x0D))
    c = size // 2
    arm = size // 3
    for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (-1, -1)):
        d.line([x + c - dx * arm, y + c - dy * arm, x + c + dx * arm, y + c + dy * arm],
               fill=accent, width=max(2, size // 14))
    d.rectangle([x + c - 2, y + c - 2, x + c + 2, y + c + 2], fill=(0xFF, 0xF6, 0xDC))


def item_icon(d, x, y, colour, size=48, label=None, small=None):
    slot(d, x, y, size)
    pad = size // 4
    d.rectangle([x + pad, y + pad, x + size - pad - 1, y + size - pad - 1], fill=colour)
    d.rectangle([x + pad, y + pad, x + size - pad - 1, y + pad + 1], fill=LIGHT)
    if label:
        f = small or font(max(11, size // 4))
        w = d.textlength(label, font=f)
        d.text((x + size + 10, y + (size - f.size) / 2), label, font=f, fill=TEXT)


def centred(d, text, x, y, w, f, colour=TEXT):
    width = d.textlength(text, font=f)
    d.text((x + (w - width) / 2, y), text, font=f, fill=colour)


# --------------------------------------------------------------------------- cover
def cover() -> Image.Image:
    W, H = 1200, 600
    img = Image.new("RGB", (W, H), PANEL)
    d = ImageDraw.Draw(img)

    d.rectangle([0, 0, W - 1, H - 1], fill=PANEL)
    d.rectangle([0, 0, W - 1, H - 1], outline=BORDER, width=4)
    d.line([4, 4, W - 5, 4], fill=LIGHT, width=3)
    d.line([4, 4, 4, H - 5], fill=LIGHT, width=3)
    d.line([4, H - 5, W - 5, H - 5], fill=DARK, width=3)
    d.line([W - 5, 4, W - 5, H - 5], fill=DARK, width=3)

    pattern_icon(d, 60, 52, 96)
    d.text((180, 56), "通配样板", font=font(64, True), fill=TEXT)
    d.text((184, 132), "AE2 Wildcard Pattern", font=font(28), fill=MUTED)

    d.line([60, 208, W - 60, 208], fill=DARK, width=2)
    d.line([60, 210, W - 60, 210], fill=LIGHT, width=2)

    d.text((60, 240), "一张样板，通配所有材料", font=font(52, True), fill=TEXT)
    d.text((60, 312), "ingot* → plate*　自动识别所有已注册金属，按材料一对一配对",
           font=font(26), fill=INFO)

    # before / after mini demo
    demo_y = 382
    d.text((60, demo_y - 34), "不再需要为每种材料各编一张样板：", font=font(22), fill=MUTED)

    card(d, 60, demo_y, 300, 76)
    slot(d, 74, demo_y + 14, 48)
    d.text((138, demo_y + 20), "ingot*", font=font(26, True), fill=INFO)
    d.text((138, demo_y + 48), "族通配（一张样板）", font=font(17), fill=MUTED)

    arrow(d, 388, demo_y + 38, 70)
    d.text((372, demo_y - 2), "运行时展开", font=font(18), fill=MUTED)

    for i, (name, colour) in enumerate((("铁板", IRON), ("铜板", COPPER), ("锡板", TIN))):
        item_icon(d, 500 + i * 150, demo_y + 14, colour, 48, name)
    d.text((500, demo_y + 72), "……所有同时有锭和板的金属", font=font(17), fill=MUTED)

    bevel(d, 960, 470, 190, 74)
    centred(d, "Minecraft 1.20.1", 960, 480, 190, font(20, True), TEXT)
    centred(d, "Forge 47 · AE2 15", 960, 506, 190, font(18), MUTED)
    centred(d, "v2.2.0 · MIT", 960, 530, 190, font(16), INFO)
    return img


# --------------------------------------------------------------------------- flow
def flow() -> Image.Image:
    W, H = 1200, 420
    img = Image.new("RGB", (W, H), PANEL)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], outline=BORDER, width=4)
    d.line([4, 4, W - 5, 4], fill=LIGHT, width=3)
    d.line([4, 4, 4, H - 5], fill=LIGHT, width=3)
    d.line([4, H - 5, W - 5, H - 5], fill=DARK, width=3)
    d.line([W - 5, 4, W - 5, H - 5], fill=DARK, width=3)

    centred(d, "工作流程：规则 → 运行时实例化 → 送回网络", 0, 26, W, font(30, True), TEXT)

    boxes = [
        ("通配样板物品", ["一套规则存在物品 NBT", "原料 / 产物 / 排除"]),
        ("通配样板编码器", ["只产出「一张」样板", "右键 = 三标签页编辑器"]),
        ("通配样板供应器", ["接进 ME 网络，占 1 频道", "按规则惰性实例化配方"]),
        ("相邻机器", ["输入被推入机器", "成品自动弹出回供应器"]),
    ]
    x = 40
    for title, lines in boxes:
        card(d, x, 96, 240, 118)
        centred(d, title, x, 112, 240, font(22, True), TEXT)
        for i, line in enumerate(lines):
            centred(d, line, x, 148 + i * 26, 240, font(17), MUTED)
        if x + 240 < 1160:
            arrow(d, x + 252, 155, 20)
        x += 292

    # return path
    d.line([40, 268, 1080, 268], fill=OK, width=3)
    d.line([1080, 268, 1080, 250], fill=OK, width=3)
    d.polygon([(1074, 250), (1086, 250), (1080, 236)], fill=OK)
    card(d, 300, 292, 600, 92, (0xDD, 0xE6, 0xD8))
    centred(d, "产物自动回流：供应器的「返回槽」", 300, 306, 600, font(22, True), OK)
    centred(d, "机器开自动弹出 → 产物进返回槽 → 每刻注入 ME 网络（终端里立刻能搜到）",
             300, 340, 600, font(18), TEXT)

    d.text((40, 344), "与 AE2 原版样板供应器一致的行为", font=font(17), fill=MUTED)
    return img


# --------------------------------------------------------------------------- matchers
def matchers() -> Image.Image:
    W, H = 1200, 470
    img = Image.new("RGB", (W, H), PANEL)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], outline=BORDER, width=4)
    d.line([4, 4, W - 5, 4], fill=LIGHT, width=3)
    d.line([4, 4, 4, H - 5], fill=LIGHT, width=3)
    d.line([4, H - 5, W - 5, H - 5], fill=DARK, width=3)
    d.line([W - 5, 4, W - 5, H - 5], fill=DARK, width=3)

    centred(d, "四种匹配器", 0, 24, W, font(30, True), TEXT)

    kinds = [
        ("物品", "copper_ingot", "匹配一个具体物品", "可再加 NBT 条件"),
        ("#标签", "#forge:ingots", "匹配标签下所有物品", "也就是矿物辞典"),
        ("族通配", "ingot*", "匹配一整族材料", "如 ingot*、plate*"),
        ("@模组", "@gtceu", "匹配某模组所有物品", "默认关闭"),
    ]
    for i, (name, example, note, note2) in enumerate(kinds):
        x = 40 + i * 292
        card(d, x, 78, 260, 132)
        d.text((x + 16, 92), name, font=font(24, True), fill=INFO)
        d.text((x + 16, 128), example, font=font(20), fill=TEXT)
        d.text((x + 16, 160), note, font=font(16), fill=MUTED)
        d.text((x + 16, 182), note2, font=font(15), fill=MUTED)

    d.line([40, 232, 1160, 232], fill=DARK, width=2)
    d.line([40, 234, 1160, 234], fill=LIGHT, width=2)

    centred(d, "「组」：让原料和产物按材料一对一配对", 0, 250, W, font(26, True), TEXT)

    # example table: every label has its own space, nothing is drawn over the icons
    card(d, 60, 296, 1080, 150)
    d.text((80, 312), "规则示例", font=font(19, True), fill=TEXT)
    d.text((80, 344), "ingot* 与 plate* 都设成 A 组：", font=font(17), fill=MUTED)
    d.text((80, 372), "→ 每种金属一对一配对", font=font(17), fill=OK)
    d.text((80, 404), "煤不设组（组显示 - ）：→ 与金属互不影响", font=font(17), fill=MUTED)

    for i, (name, colour) in enumerate((("铜", COPPER), ("铁", IRON), ("锡", TIN))):
        x = 480 + i * 230
        d.text((x + 20, 304), name, font=font(18, True), fill=TEXT)
        item_icon(d, x, 330, colour, 40)
        arrow(d, x + 48, 350, 24, INFO, 3)
        item_icon(d, x + 82, 330, (0xB9, 0xC4, 0xCC), 40)
        d.text((x + 4, 380), f"{name}锭 → {name}板", font=font(16), fill=OK)
    d.text((480, 412), "同组的原料与产物永远取同一个材料；不同组各自独立组合（有上限保护）",
           font=font(16), fill=MUTED)
    return img


# --------------------------------------------------------------------------- recipes
def recipes() -> Image.Image:
    W, H = 1200, 440
    img = Image.new("RGB", (W, H), PANEL)
    d = ImageDraw.Draw(img)
    d.rectangle([0, 0, W - 1, H - 1], outline=BORDER, width=4)
    d.line([4, 4, W - 5, 4], fill=LIGHT, width=3)
    d.line([4, 4, 4, H - 5], fill=LIGHT, width=3)
    d.line([4, H - 5, W - 5, H - 5], fill=DARK, width=3)
    d.line([W - 5, 4, W - 5, H - 5], fill=DARK, width=3)

    centred(d, "合成表（默认值，全部可在配置里修改或关闭）", 0, 22, W, font(28, True), TEXT)

    grids = [
        ("通配样板编码器", ["Q", "G", "Q", "P", "B", "P", "Q", "G", "Q"], GOLD),
        ("通配样板供应器", ["Q", "P", "Q", "G", "B", "G", "Q", "P", "Q"], GOLD),
        ("空白通配样板", ["G", "Q", "G", "Q", "B", "Q", "G", "Q", "G"], GOLD),
    ]
    for i, (title, cells, colour) in enumerate(grids):
        x = 70 + i * 380
        centred(d, title, x, 78, 320, font(22, True), TEXT)
        for row in range(3):
            for col in range(3):
                cx = x + 74 + col * 54
                cy = 118 + row * 54
                slot(d, cx, cy, 52)
                centred(d, cells[row * 3 + col], cx, cy + 16, 52, font(22, True), TEXT)
        arrow(d, x + 250, 170, 26)
        slot(d, x + 292, 144, 52)
        if i == 2:
            pattern_icon(d, x + 295, 147, 46)
        else:
            # the two blocks, drawn as a plain cube
            d.rectangle([x + 302, 154, x + 334, 186], fill=(0x9A, 0x9A, 0x9A))
            d.rectangle([x + 302, 154, x + 334, 156], fill=LIGHT)
            d.rectangle([x + 302, 184, x + 334, 186], fill=SLOT_DARK)

    legend = [
        ("Q = ae2:quartz_glass　石英玻璃", "P = ae2:calculation_processor　运算处理器"),
        ("G = #forge:ingots/gold　任意金锭", "B = 中央格，放对应方块（样板配方放 ae2:blank_pattern）"),
    ]
    y = 320
    for left, right in legend:
        d.text((70, y), left, font=font(18), fill=MUTED)
        d.text((620, y), right, font=font(18), fill=MUTED)
        y += 26

    card(d, 70, 380, 1060, 46)
    centred(d, "配置里写 9 项材料（物品 / #标签 / 空串），按行读；可改材料、改产量，或把 enabled 设为 false 直接关掉",
             70, 394, 1060, font(18), INFO)
    return img


def main() -> None:
    OUT.mkdir(parents=True, exist_ok=True)
    for name, image in (("01-cover.png", cover()), ("02-flow.png", flow()),
                        ("03-matchers.png", matchers()), ("04-recipes.png", recipes())):
        image.save(OUT / name)
        print("wrote", OUT / name, image.size)
    for source, target in (("preview-editor.png", "05-ui-editor.png"),
                           ("preview-help.png", "06-ui-help.png"),
                           ("preview-matcher.png", "07-ui-matcher.png")):
        src = DOCS / source
        if src.exists():
            shutil.copyfile(src, OUT / target)
            print("copied", target)
    print("done ->", OUT)


if __name__ == "__main__":
    main()
