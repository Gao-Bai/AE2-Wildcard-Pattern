package com.ae2wildcard.encoder.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.function.Consumer;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.Msg;
import com.ae2wildcard.encoder.craft.PatternText;
import com.ae2wildcard.encoder.craft.TagIndex;
import com.ae2wildcard.encoder.craft.VariantResolver;
import com.ae2wildcard.encoder.craft.WildcardAliases;
import com.ae2wildcard.encoder.menu.WildcardPatternMenu;
import com.ae2wildcard.encoder.network.RuleEditPacket;
import com.ae2wildcard.encoder.network.RuleSyncPacket;
import com.ae2wildcard.encoder.network.WildcardNetwork;
import com.ae2wildcard.encoder.rule.RuleCodec;
import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Exclude;
import com.ae2wildcard.encoder.rule.WildcardRules.ExcludeKind;
import com.ae2wildcard.encoder.rule.WildcardRules.Input;
import com.ae2wildcard.encoder.rule.WildcardRules.Match;
import com.ae2wildcard.encoder.rule.WildcardRules.MatchKind;
import com.ae2wildcard.encoder.rule.WildcardRules.Options;
import com.ae2wildcard.encoder.rule.WildcardRules.Output;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

import org.lwjgl.glfw.GLFW;

/**
 * The three tab rule editor (原料 / 产物 / 排除).
 *
 * <p>Pure view + editor: every change goes to the server as a {@link RuleEditPacket} and the
 * authoritative state comes back as a {@link RuleSyncPacket}. The client never decides what is stored.
 */
public class WildcardPatternScreen extends AbstractContainerScreen<WildcardPatternMenu> {

    private static final int W = 220;
    private static final int H = 236;

    private static final int TAB_INPUT = 0;
    private static final int TAB_OUTPUT = 1;
    private static final int TAB_EXCLUDE = 2;

    private static final int TAB_Y = 16;
    private static final int TAB_H = 15;
    private static final int TAB_W = 68;

    private static final int CONTENT_Y = 34;
    private static final int ROW_H = 20;
    private static final int ROWS = 4;
    private static final int ROW_X = 8;
    private static final int ROW_W = 204;

    // count controls, row relative x (drawing and hit testing must not drift apart)
    private static final int IN_MINUS_X = 72;
    private static final int IN_PLUS_X = 112;
    private static final int OUT_MINUS_X = 96;
    private static final int OUT_PLUS_X = 140;
    private static final int COUNT_BUTTON = 14;

    /** Matcher overlay: kind buttons / search row / results, all inside the content area. */
    private static final int MATCHER_SEARCH_Y = CONTENT_Y + 2;
    private static final int MATCHER_KIND_Y = CONTENT_Y + 18;
    private static final int MATCHER_LIST_Y = CONTENT_Y + 34;
    private static final int MATCHER_ROW_H = 18;
    private static final int MATCHER_ROWS = 3;

    /** Wildcard rows cycle through the items they match. */
    private static final long PREVIEW_SWITCH_MS = 900L;
    private static final int PREVIEW_LIMIT = 32;

    private static final int STATUS_Y = 116;
    private static final int BUTTON_Y = 127;
    private static final int BUTTON_H = 18;
    /** Y of the "Inventory" label; the slots themselves live in {@link WildcardPatternMenu}. */
    private static final int INV_LABEL_Y = 149;

    // Colours follow AE2 15's own UI: the container palette used by its GUI textures (panel #C6C6C6,
    // slots #8B8B8B with #373737 / #FFFFFF bevels, black outline) plus the text colours from
    // assets/ae2/screens/common/palette.json.
    private static final int COLOR_TEXT = 0xFF404040;         // DEFAULT_TEXT_COLOR
    private static final int COLOR_DIM = 0xFF7E7E7E;          // MUTED_TEXT_COLOR
    private static final int COLOR_OK = 0xFF3C7A20;
    private static final int COLOR_BAD = 0xFFCE2401;          // ERROR
    private static final int COLOR_ACCENT = 0xFF00D5FF;       // SELECTION_COLOR
    private static final int COLOR_INFO = 0xFF1F6FB2;
    private static final int COLOR_ROW = 0x00000000;
    private static final int COLOR_ROW_HOVER = 0x33FFFFFF;
    private static final int COLOR_PANEL = 0xFFC6C6C6;
    private static final int COLOR_SUB_PANEL = 0xFFBDBDBD;
    private static final int COLOR_OVERLAY = 0xB0000000;
    private static final int COLOR_BORDER = 0xFF000000;
    private static final int COLOR_BEVEL_LIGHT = 0xFFFFFFFF;
    private static final int COLOR_BEVEL_DARK = 0xFF555555;
    private static final int COLOR_SLOT = 0xFF8B8B8B;
    private static final int COLOR_SLOT_DARK = 0xFF373737;
    private static final int COLOR_FACE = 0xFFA8A8A8;
    private static final int COLOR_FACE_ON = 0xFF8FC7E8;

    private enum Overlay {
        NONE, MATCHER, TEXT, CHOOSER, HELP
    }

    private enum MatcherTarget {
        INPUT, OUTPUT, EXCLUDE_ITEM, EXCLUDE_OUTPUT
    }

    // ---- server mirrored state
    private WildcardRules rules = WildcardRules.EMPTY;
    private int revision = -1;
    private int previewCount;
    private int excludedCount;
    private boolean truncated;
    private boolean draftMode;
    private Msg serverError;
    private List<Msg> serverWarnings = List.of();
    private List<String> samples = List.of();

    // ---- view state
    private int tab = TAB_INPUT;
    private int scroll;
    /** Transient feedback line (e.g. "added minecraft:iron_ingot"). */
    private String hint = "";

    private final Map<Match, List<ItemStack>> previewCache = new HashMap<>();
    private int previewRevision = -1;

    /** Matcher overlay: the search bar is collapsed until clicked, and widgets are rebuilt lazily. */
    private boolean searchExpanded;
    private String searchQuery = "";
    private boolean matcherWidgetsDirty;
    /**
     * True when the current click already added a rule. A plain click over the list fires
     * {@code mouseClicked} and then {@code mouseReleased} at the same spot, which used to add the same
     * item twice; the release only adds when the press did not.
     */
    private boolean clickConsumed;

    // ---- overlay state
    private Overlay overlay = Overlay.NONE;
    private MatcherTarget matcherTarget = MatcherTarget.INPUT;
    private int editIndex = -1;
    private MatchKind editorKind = MatchKind.ITEM;
    private List<ItemSearch.Entry> results = List.of();
    private int selected;
    private CompoundTag pendingNbt;
    private String pendingPairInput = "";

    private String textTitleKey = "";
    private String textInitial = "";
    private Consumer<String> textCommit;
    private final List<String> chooserLabels = new ArrayList<>();
    private final List<Runnable> chooserActions = new ArrayList<>();

    private final List<GuiEventListener> overlayWidgets = new ArrayList<>();
    private final List<Button> actionButtons = new ArrayList<>();
    private EditBox searchField;
    private EditBox textField;

    public WildcardPatternScreen(WildcardPatternMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = W;
        this.imageHeight = H;
        this.draftMode = menu.isDraft();
    }

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void init() {
        super.init();
        overlayWidgets.clear();
        actionButtons.clear();

        for (int i = 0; i < 3; i++) {
            final int index = i;
            addRenderableWidget(Button.builder(tabLabel(i), b -> switchTab(index))
                    .bounds(leftPos + 8 + i * (TAB_W + 2), topPos + TAB_Y, TAB_W, TAB_H).build());
        }
        rebuildActionButtons();

        switch (overlay) {
            case MATCHER -> buildMatcherWidgets();
            case TEXT -> buildTextWidgets();
            case CHOOSER -> buildChooserWidgets();
            default -> {
            }
        }
    }

    private Component tabLabel(int index) {
        return Component.translatable(switch (index) {
            case TAB_OUTPUT -> "wildcardencoder.gui.tab.outputs";
            case TAB_EXCLUDE -> "wildcardencoder.gui.tab.excludes";
            default -> "wildcardencoder.gui.tab.inputs";
        });
    }

    private void switchTab(int index) {
        if (tab == index) {
            return;
        }
        closeOverlay();
        tab = index;
        scroll = 0;
        hint = "";
        rebuildActionButtons();
    }

    private void rebuildActionButtons() {
        for (Button button : actionButtons) {
            removeWidget(button);
        }
        actionButtons.clear();

        int y = topPos + BUTTON_Y;
        if (draftMode) {
            addAction("+", 36, leftPos + 8, y, this::openAddOverlay);
            addAction(Component.translatable("wildcardencoder.gui.encode").getString(), 40, leftPos + 46, y,
                    () -> send(WildcardPatternMenu.ENCODE, -1, new CompoundTag()));
            addAction(Component.translatable("wildcardencoder.gui.options").getString(), 34, leftPos + 88, y,
                    this::openOptionsChooser);
            addAction(Component.translatable("wildcardencoder.gui.copy").getString(), 30, leftPos + 124, y,
                    this::copyRules);
            addAction(Component.translatable("wildcardencoder.gui.paste").getString(), 30, leftPos + 156, y,
                    this::pasteRules);
            addAction("?", 20, leftPos + 188, y, () -> openOverlay(Overlay.HELP));
        } else {
            addAction("+", 40, leftPos + 8, y, this::openAddOverlay);
            addAction(Component.translatable("wildcardencoder.gui.options").getString(), 48, leftPos + 50, y,
                    this::openOptionsChooser);
            addAction(Component.translatable("wildcardencoder.gui.copy").getString(), 36, leftPos + 100, y,
                    this::copyRules);
            addAction(Component.translatable("wildcardencoder.gui.paste").getString(), 36, leftPos + 138, y,
                    this::pasteRules);
            addAction("?", 20, leftPos + 176, y, () -> openOverlay(Overlay.HELP));
        }
    }

    private void addAction(String label, int width, int x, int y, Runnable action) {
        Button button = Button.builder(Component.literal(label), b -> action.run())
                .bounds(x, y, width, BUTTON_H).build();
        addRenderableWidget(button);
        actionButtons.add(button);
    }

    private <T extends GuiEventListener & Renderable & NarratableEntry> void addOverlayWidget(T widget) {
        overlayWidgets.add(widget);
        addRenderableWidget(widget);
    }

    public void applySync(RuleSyncPacket packet) {
        if (packet.revision() < revision) {
            return;
        }
        this.rules = packet.rules();
        this.revision = packet.revision();
        this.previewCount = packet.previewCount();
        this.excludedCount = packet.excludedCount();
        this.truncated = packet.truncated();
        this.serverError = packet.error();
        this.serverWarnings = packet.warnings() == null ? List.of() : packet.warnings();
        this.hint = "";
        this.samples = packet.samples();
        if (packet.draftMode() != draftMode) {
            this.draftMode = packet.draftMode();
            rebuildActionButtons();
        }
        clampScroll();
    }

    private void clampScroll() {
        scroll = Math.max(0, Math.min(scroll, Math.max(0, rowCount() - ROWS)));
    }

    private int rowCount() {
        return switch (tab) {
            case TAB_OUTPUT -> rules.outputs().size();
            case TAB_EXCLUDE -> rules.excludes().size();
            default -> rules.inputs().size();
        };
    }

    // ------------------------------------------------------------------ rendering

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;

        // AE2 / vanilla container frame: black outline, light panel, 1px bevel
        graphics.fill(x, y, x + W, y + H, COLOR_BORDER);
        graphics.fill(x + 1, y + 1, x + W - 1, y + H - 1, COLOR_PANEL);
        graphics.fill(x + 1, y + 1, x + W - 1, y + 2, COLOR_BEVEL_LIGHT);
        graphics.fill(x + 1, y + 2, x + 2, y + H - 1, COLOR_BEVEL_LIGHT);
        graphics.fill(x + 1, y + H - 2, x + W - 1, y + H - 1, COLOR_BEVEL_DARK);
        graphics.fill(x + W - 2, y + 2, x + W - 1, y + H - 1, COLOR_BEVEL_DARK);

        graphics.renderItem(headerIcon(), x + 6, y + 4);
        draw(graphics, title.getString(), x + 26, y + 6, COLOR_TEXT);
        int summaryMax = W - 16 - 26 - font.width(title.getString()) - 8;
        if (summaryMax > 24) {
            String summary = font.plainSubstrByWidth(PatternText.plainSummary(rules), summaryMax);
            draw(graphics, summary, x + W - 8 - font.width(summary), y + 6, COLOR_DIM);
        }

        graphics.fill(x + ROW_X, y + CONTENT_Y - 2, x + ROW_X + ROW_W, y + CONTENT_Y + ROWS * ROW_H,
                COLOR_SUB_PANEL);
        sunken(graphics, x + ROW_X, y + CONTENT_Y - 2, ROW_W, ROWS * ROW_H + 2);
        if (overlay == Overlay.NONE) {
            // an open overlay owns the list area: row item icons sit on the item layer (z 150) and
            // would otherwise bleed through whatever is drawn on top of them
            drawRows(graphics, mouseX, mouseY);
        }

        // player inventory: the slot backgrounds (the items on top are drawn by the vanilla slot loop)
        for (Slot slot : menu.slots) {
            drawSlot(graphics, x + slot.x, y + slot.y);
        }

        String status = Component.translatable("wildcardencoder.gui.status", previewCount, excludedCount).getString();
        if (rowCount() > ROWS) {
            status = status + "  " + (scroll + 1) + "/" + rowCount();
        }
        if (serverError != null) {
            drawClipped(graphics, serverError.plain(), x + 8, y + STATUS_Y, COLOR_BAD, W - 16);
        } else if (!hint.isEmpty()) {
            drawClipped(graphics, hint, x + 8, y + STATUS_Y, COLOR_INFO, W - 16);
        } else {
            draw(graphics, status, x + 8, y + STATUS_Y, truncated ? COLOR_INFO : COLOR_OK);
            String tail = !serverWarnings.isEmpty() ? serverWarnings.get(0).plain()
                    : (samples.isEmpty() ? "" : samples.get(0));
            int tailX = x + 10 + font.width(status);
            int tailMax = x + W - 8 - tailX;
            if (!tail.isEmpty() && tailMax > 30) {
                drawClipped(graphics, tail, tailX, y + STATUS_Y,
                        serverWarnings.isEmpty() ? COLOR_DIM : COLOR_INFO, tailMax);
            }
        }

        draw(graphics, Component.translatable("container.inventory").getString(), x + 8, y + INV_LABEL_Y, COLOR_DIM);

        if (overlay != Overlay.NONE) {
            graphics.fill(x + 4, y + CONTENT_Y - 4, x + W - 4, y + BUTTON_Y - 4, COLOR_OVERLAY);
        }
    }

    private ItemStack headerIcon() {
        if (!draftMode && minecraft != null && minecraft.player != null) {
            ItemStack held = minecraft.player.getMainHandItem();
            if (!held.isEmpty()) {
                return held;
            }
        }
        return new ItemStack(WildcardEncoderMod.WILDCARD_PATTERN.get());
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // everything is drawn in renderBg so the layout stays in one place
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // rebuilding widgets from inside a button callback would mutate the list being iterated
        if (matcherWidgetsDirty) {
            matcherWidgetsDirty = false;
            if (overlay == Overlay.MATCHER) {
                rebuildMatcherWidgets();
            }
        }
        super.render(graphics, mouseX, mouseY, partialTick);

        int tabX = leftPos + 8 + tab * (TAB_W + 2);
        graphics.fill(tabX, topPos + TAB_Y + TAB_H, tabX + TAB_W, topPos + TAB_Y + TAB_H + 2, COLOR_ACCENT);

        if (overlay == Overlay.MATCHER) {
            drawMatcherList(graphics);
            drawActiveKind(graphics);
        }
        if (overlay == Overlay.HELP) {
            drawHelp(graphics);
        }
        if (overlay == Overlay.MATCHER) {
            Component widgetTip = matcherWidgetTooltip(mouseX, mouseY);
            if (widgetTip != null) {
                graphics.renderTooltip(this.font, widgetTip, mouseX, mouseY);
            }
        }
        if (overlay == Overlay.NONE) {
            int hoveredRow = rowAt(mouseX, mouseY);
            Component tip = toggleTooltip(hoveredRow, mouseX);
            if (tip == null) {
                tip = rowTooltip(hoveredRow);
            }
            if (tip == null && hoveredRow < 0 && isOverList(mouseX, mouseY)) {
                tip = Component.translatable("wildcardencoder.gui.hint.drag");
            }
            if (tip != null) {
                graphics.renderTooltip(this.font, tip, mouseX, mouseY);
            }
        }
    }

    /** Cyan bar under the kind button that is in effect, like the active tab. */
    private void drawActiveKind(GuiGraphics graphics) {
        int index = kindIndex(kindForQuery(searchQuery));
        if (index < 0) {
            return;
        }
        int x = leftPos + ROW_X + index * 50;
        graphics.fill(x, topPos + MATCHER_KIND_Y + 14, x + 48, topPos + MATCHER_KIND_Y + 16, COLOR_ACCENT);
    }

    /** Tooltips for the search bar and the kind buttons, so both are self explanatory. */
    private Component matcherWidgetTooltip(double mouseX, double mouseY) {
        int x = leftPos + ROW_X;
        if (inside(mouseX, mouseY, x, topPos + MATCHER_SEARCH_Y, ROW_W - 108, 14)) {
            return Component.translatable("wildcardencoder.gui.search.tip");
        }
        MatchKind kind = kindForQuery(searchQuery);
        for (MatchKind candidate : MatchKind.values()) {
            int index = kindIndex(candidate);
            if (index < 0 || !inside(mouseX, mouseY, x + index * 50, topPos + MATCHER_KIND_Y, 48, 14)) {
                continue;
            }
            return Component.translatable("wildcardencoder.gui.kind.tip."
                    + candidate.name().toLowerCase(Locale.ROOT),
                    Component.translatable("wildcardencoder.gui.kind." + candidate.name().toLowerCase(Locale.ROOT)));
        }
        return null;
    }

    private static boolean inside(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
    }

    private void openOverlay(Overlay target) {
        closeOverlay();
        overlay = target;
        rebuildActionButtons();
        switch (target) {
            case MATCHER -> buildMatcherWidgets();
            case TEXT -> buildTextWidgets();
            case CHOOSER -> buildChooserWidgets();
            default -> {
            }
        }
    }

    /** Explains C / O / A / N / X in place, so the row letters do not have to be guessed. */
    private void drawHelp(GuiGraphics graphics) {
        graphics.pose().pushPose();
        // above the item layer (z 150), otherwise item icons would show through this panel
        graphics.pose().translate(0.0F, 0.0F, 300.0F);
        int x = leftPos + ROW_X;
        int y = topPos + CONTENT_Y - 2;
        int width = ROW_W;
        int height = (STATUS_Y + 12) - (CONTENT_Y - 2);
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL);
        sunken(graphics, x, y, width, height);
        int line = y + 4;
        draw(graphics, tr("wildcardencoder.gui.help.title"), x + 6, line, COLOR_TEXT);
        line += 12;
        for (String key : new String[] { "C", "O", "A", "N", "X", "count", "tip" }) {
            boolean muted = key.equals("tip") || key.equals("count");
            for (var wrapped : font.split(Component.translatable("wildcardencoder.gui.help." + key), width - 14)) {
                graphics.drawString(font, wrapped, x + 6, line, muted ? COLOR_DIM : COLOR_TEXT, false);
                line += 10;
            }
            line += 1;
        }
        graphics.pose().popPose();
    }

    private void drawRows(GuiGraphics graphics, int mouseX, int mouseY) {
        int count = rowCount();
        if (count == 0) {
            draw(graphics, Component.translatable("wildcardencoder.gui.empty").getString(),
                    leftPos + ROW_X + 4, topPos + CONTENT_Y + 4, COLOR_DIM);
        } else {
            for (int line = 0; line < ROWS; line++) {
                int index = scroll + line;
                if (index >= count) {
                    break;
                }
                int rowY = topPos + CONTENT_Y + line * ROW_H;
                boolean hovered = overlay == Overlay.NONE && rowAt(mouseX, mouseY) == index;
                graphics.fill(leftPos + ROW_X, rowY, leftPos + ROW_X + ROW_W, rowY + ROW_H - 1,
                        hovered ? COLOR_ROW_HOVER : COLOR_ROW);
                switch (tab) {
                    case TAB_OUTPUT -> drawOutputRow(graphics, index, rowY);
                    case TAB_EXCLUDE -> drawExcludeRow(graphics, index, rowY);
                    default -> drawInputRow(graphics, index, rowY);
                }
            }
        }
    }

    private void drawInputRow(GuiGraphics graphics, int index, int rowY) {
        Input input = rules.inputs().get(index);
        drawMatchIcon(graphics, input.match(), leftPos + ROW_X + 2, rowY + 2);
        drawClipped(graphics, matchLabel(input.match()), leftPos + ROW_X + 21, rowY + 7,
                input.match().isWildcard() ? COLOR_INFO : COLOR_TEXT, 51);
        drawCount(graphics, input.count(), leftPos + ROW_X + IN_MINUS_X, leftPos + ROW_X + IN_PLUS_X, rowY);
        drawToggle(graphics, "C", leftPos + ROW_X + 126, rowY + 3, input.consume(), COLOR_OK);
        drawToggle(graphics, "O", leftPos + ROW_X + 140, rowY + 3, input.optional(), 0xFFB26A00);
        drawGroup(graphics, leftPos + ROW_X + 154, rowY + 3, input.group());
        drawToggle(graphics, "N", leftPos + ROW_X + 168, rowY + 3, input.match().hasNbt(), COLOR_INFO);
        drawToggle(graphics, "X", leftPos + ROW_X + 182, rowY + 3, false, COLOR_BAD);
    }

    private void drawOutputRow(GuiGraphics graphics, int index, int rowY) {
        Output output = rules.outputs().get(index);
        drawMatchIcon(graphics, output.match(), leftPos + ROW_X + 2, rowY + 2);
        drawClipped(graphics, matchLabel(output.match()), leftPos + ROW_X + 21, rowY + 7,
                output.match().isWildcard() ? COLOR_INFO : COLOR_TEXT, 75);
        drawCount(graphics, output.count(), leftPos + ROW_X + OUT_MINUS_X, leftPos + ROW_X + OUT_PLUS_X, rowY);
        drawGroup(graphics, leftPos + ROW_X + 154, rowY + 3, output.group());
        drawToggle(graphics, "N", leftPos + ROW_X + 168, rowY + 3, output.match().hasNbt(), COLOR_INFO);
        drawToggle(graphics, "X", leftPos + ROW_X + 182, rowY + 3, false, COLOR_BAD);
    }

    private void drawExcludeRow(GuiGraphics graphics, int index, int rowY) {
        Exclude exclude = rules.excludes().get(index);
        drawClipped(graphics, exclude.kind().name().toLowerCase(Locale.ROOT), leftPos + ROW_X + 3, rowY + 7,
                COLOR_DIM, 40);
        drawClipped(graphics, exclude.describe(), leftPos + ROW_X + 45, rowY + 7, COLOR_BAD, ROW_W - 70);
        drawToggle(graphics, "X", leftPos + ROW_X + 182, rowY + 3, false, COLOR_BAD);
    }

    private void drawMatchIcon(GuiGraphics graphics, Match match, int x, int y) {
        drawSlot(graphics, x, y);
        ItemStack icon = previewIcon(match);
        if (!icon.isEmpty()) {
            graphics.renderItem(icon, x, y);
            if (match.kind() != MatchKind.ITEM) {
                // keep the matcher kind readable while the icon cycles; item icons live on the item
                // layer (z ~150), so the badge has to be drawn above it to stay visible
                graphics.pose().pushPose();
                graphics.pose().translate(0.0F, 0.0F, 250.0F);
                graphics.fill(x + 8, y + 8, x + 16, y + 16, 0xE0101010);
                draw(graphics, matchGlyph(match), x + 10, y + 9, COLOR_ACCENT);
                graphics.pose().popPose();
            }
            return;
        }
        draw(graphics, matchGlyph(match), x + 4, y + 4,
                match.kind() == MatchKind.FAMILY ? COLOR_OK : COLOR_INFO);
    }

    private static String matchGlyph(Match match) {
        return switch (match.kind()) {
            case TAG -> "#";
            case MOD -> "@";
            case FAMILY -> "*";
            default -> "?";
        };
    }

    /**
     * The item shown in a row's icon cell. A concrete item shows itself; a wildcard matcher (tag,
     * family, mod) cycles through one representative item per variant it matches, so the row shows
     * what it really covers instead of a bare "*".
     */
    private ItemStack previewIcon(Match match) {
        if (match.kind() == MatchKind.ITEM) {
            ResourceLocation id = ResourceLocation.tryParse(
                    match.value().contains(":") ? match.value() : "minecraft:" + match.value());
            var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item == null || item == Items.AIR) {
                return ItemStack.EMPTY;
            }
            ItemStack stack = new ItemStack(item);
            if (match.hasNbt()) {
                stack.setTag(match.nbt().copy());
            }
            return stack;
        }
        List<ItemStack> icons = previewIcons(match);
        if (icons.isEmpty()) {
            return ItemStack.EMPTY;
        }
        long step = System.currentTimeMillis() / PREVIEW_SWITCH_MS;
        return icons.get((int) Math.floorMod(step, (long) icons.size()));
    }

    /** Resolving a family matcher walks every item tag, so the result is cached per matcher. */
    private List<ItemStack> previewIcons(Match match) {
        int revision = TagIndex.revision();
        if (revision != previewRevision) {
            previewRevision = revision;
            previewCache.clear();
        }
        return previewCache.computeIfAbsent(match, key -> {
            VariantResolver.Result resolved = VariantResolver.resolve(key);
            List<String> variants = new ArrayList<>(resolved.variants().keySet());
            variants.sort(String::compareTo); // stable, so the icons cycle predictably
            List<ItemStack> out = new ArrayList<>();
            for (String variant : variants) {
                var candidates = resolved.variants().get(variant);
                if (candidates != null && !candidates.isEmpty()) {
                    out.add(new ItemStack(candidates.get(0)));
                }
                if (out.size() >= PREVIEW_LIMIT) {
                    break;
                }
            }
            return List.copyOf(out);
        });
    }

    private void drawToggle(GuiGraphics graphics, String label, int x, int y, boolean on, int onColor) {
        smallButton(graphics, x, y, on);
        if (on) {
            graphics.fill(x + 1, y + 12, x + 12, y + 13, onColor);
        }
        draw(graphics, label, x + (13 - font.width(label)) / 2, y + 3, on ? COLOR_TEXT : COLOR_DIM);
    }

    private void drawGroup(GuiGraphics graphics, int x, int y, String group) {
        String label = group.isEmpty() ? "-" : group;
        smallButton(graphics, x, y, !group.isEmpty());
        draw(graphics, label, x + (13 - font.width(label)) / 2, y + 3,
                group.isEmpty() ? COLOR_DIM : COLOR_TEXT);
    }

    /** Classic beveled face used by the row toggles. */
    private void smallButton(GuiGraphics graphics, int x, int y, boolean highlighted) {
        graphics.fill(x, y, x + 13, y + 14, highlighted ? COLOR_FACE_ON : COLOR_FACE);
        graphics.fill(x, y, x + 13, y + 1, COLOR_BEVEL_LIGHT);
        graphics.fill(x, y, x + 1, y + 14, COLOR_BEVEL_LIGHT);
        graphics.fill(x, y + 13, x + 13, y + 14, COLOR_BEVEL_DARK);
        graphics.fill(x + 12, y, x + 13, y + 14, COLOR_BEVEL_DARK);
    }

    /** Vanilla style slot square (the same bevels the container textures use). */
    private void drawSlot(GuiGraphics graphics, int x, int y) {
        graphics.fill(x, y, x + 16, y + 16, COLOR_SLOT);
        graphics.fill(x, y, x + 16, y + 1, COLOR_SLOT_DARK);
        graphics.fill(x, y, x + 1, y + 16, COLOR_SLOT_DARK);
        graphics.fill(x, y + 15, x + 16, y + 16, COLOR_BEVEL_LIGHT);
        graphics.fill(x + 15, y, x + 16, y + 16, COLOR_BEVEL_LIGHT);
    }

    /** Sunken field border (dark top/left, light bottom/right). */
    private void sunken(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + 1, COLOR_SLOT_DARK);
        graphics.fill(x, y, x + 1, y + height, COLOR_SLOT_DARK);
        graphics.fill(x, y + height - 1, x + width, y + height, COLOR_BEVEL_LIGHT);
        graphics.fill(x + width - 1, y, x + width, y + height, COLOR_BEVEL_LIGHT);
    }

    /**
     * {@code [-] 4 [+]} as two real buttons with the number in between. The glyphs sit exactly on the
     * hit zones used by {@link #handleRowClick}, so clicking what you see always does what it says.
     */
    private void drawCount(GuiGraphics graphics, long count, int minusX, int plusX, int rowY) {
        smallButton(graphics, minusX, rowY + 3, false);
        draw(graphics, "-", minusX + 5, rowY + 6, COLOR_TEXT);
        smallButton(graphics, plusX, rowY + 3, false);
        draw(graphics, "+", plusX + 4, rowY + 6, COLOR_TEXT);
        String text = formatCount(count);
        int gapStart = minusX + COUNT_BUTTON;
        int gapWidth = plusX - gapStart;
        draw(graphics, text, gapStart + Math.max(0, (gapWidth - font.width(text)) / 2), rowY + 6, COLOR_TEXT);
    }

    /** At most four characters ("64", "9k", "999k", "1M"), so it always fits between - and +. */
    private static String formatCount(long count) {
        if (count >= 1_000_000L) {
            return Math.round(count / 1_000_000.0) + "M";
        }
        if (count >= 1_000L) {
            return Math.round(count / 1_000.0) + "k";
        }
        return Long.toString(count);
    }

    private void draw(GuiGraphics graphics, String text, int x, int y, int color) {
        graphics.drawString(font, text, x, y, color, false);
    }

    private void drawClipped(GuiGraphics graphics, String text, int x, int y, int color, int maxWidth) {
        if (maxWidth <= 0 || text.isEmpty()) {
            return;
        }
        graphics.drawString(font, font.plainSubstrByWidth(text, maxWidth), x, y, color, false);
    }

    private void drawMatcherList(GuiGraphics graphics) {
        int x = leftPos + ROW_X;
        int y = topPos + MATCHER_LIST_Y;
        int height = MATCHER_ROWS * MATCHER_ROW_H + 2;
        graphics.fill(x, y, x + ROW_W, y + height, COLOR_PANEL);
        sunken(graphics, x, y, ROW_W, height);

        int first = firstVisibleResult();
        for (int i = 0; i < MATCHER_ROWS; i++) {
            int index = first + i;
            if (index < 0 || index >= results.size()) {
                continue;
            }
            int rowY = y + 1 + i * MATCHER_ROW_H;
            ItemSearch.Entry entry = results.get(index);
            boolean active = index == selected;
            if (active) {
                graphics.fill(x + 1, rowY, x + ROW_W - 1, rowY + MATCHER_ROW_H - 1, COLOR_FACE_ON);
            }
            if (entry.hasIcon()) {
                drawSlot(graphics, x + 3, rowY + 1);
                graphics.renderItem(new ItemStack(entry.item()), x + 3, rowY + 1);
            } else {
                drawSlot(graphics, x + 3, rowY + 1);
                draw(graphics, "*", x + 8, rowY + 5, COLOR_INFO);
            }
            draw(graphics, entry.title(), x + 24, rowY + 5, active ? COLOR_TEXT : COLOR_DIM);
            // leave room for the "n/total" indicator in the bottom right corner
            drawClipped(graphics, entry.subtitle(), x + 104, rowY + 5, COLOR_DIM, ROW_W - 132);
        }
        if (results.isEmpty()) {
            draw(graphics, Component.translatable("wildcardencoder.gui.noresult").getString(),
                    x + 4, y + 5, COLOR_DIM);
        } else if (results.size() > MATCHER_ROWS) {
            String info = (selected + 1) + "/" + results.size();
            draw(graphics, info, x + ROW_W - font.width(info) - 4,
                    y + height - MATCHER_ROW_H + 2, COLOR_DIM);
        }
    }

    /** First result shown in the list, so the selected entry stays visible while scrolling. */
    private int firstVisibleResult() {
        return Math.max(0, Math.min(selected - 1, Math.max(0, results.size() - MATCHER_ROWS)));
    }

    private String matcherTargetLabel() {
        return Component.translatable(switch (matcherTarget) {
            case OUTPUT -> "wildcardencoder.gui.matcher.output";
            case EXCLUDE_ITEM, EXCLUDE_OUTPUT -> "wildcardencoder.gui.matcher.exclude";
            default -> "wildcardencoder.gui.matcher.input";
        }).getString();
    }

    // ------------------------------------------------------------------ row interaction

    private int rowAt(double mouseX, double mouseY) {
        if (mouseX < leftPos + ROW_X || mouseX > leftPos + ROW_X + ROW_W) {
            return -1;
        }
        int rel = (int) (mouseY - (topPos + CONTENT_Y));
        if (rel < 0) {
            return -1;
        }
        int line = rel / ROW_H;
        if (line >= ROWS) {
            return -1;
        }
        int index = scroll + line;
        return index < rowCount() ? index : -1;
    }

    /** Item matches show their localised name; wildcard matches show the rule text. */
    private String matchLabel(Match match) {
        if (match.kind() == MatchKind.ITEM) {
            ResourceLocation id = ResourceLocation.tryParse(
                    match.value().contains(":") ? match.value() : "minecraft:" + match.value());
            var item = id == null ? null : ForgeRegistries.ITEMS.getValue(id);
            if (item != null && item != Items.AIR) {
                return Component.translatable(item.getDescriptionId()).getString();
            }
        }
        return PatternText.plainMatch(match);
    }

    private Component rowTooltip(int index) {
        if (index < 0 || tab == TAB_EXCLUDE && index >= rules.excludes().size()) {
            return null;
        }
        return switch (tab) {
            case TAB_OUTPUT -> index < rules.outputs().size()
                    ? Component.literal(PatternText.plainOutput(rules.outputs().get(index)))
                    : null;
            case TAB_EXCLUDE -> index < rules.excludes().size()
                    ? Component.literal(rules.excludes().get(index).describe())
                    : null;
            default -> index < rules.inputs().size()
                    ? Component.literal(PatternText.plainInput(rules.inputs().get(index)))
                            .append(Component.literal("\n" + rules.inputs().get(index).match().value())
                                    .withStyle(ChatFormatting.GRAY))
                    : null;
        };
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        clickConsumed = false;
        if (overlay == Overlay.MATCHER && handleResultClick(mouseX, mouseY)) {
            return true;
        }
        if (overlay == Overlay.HELP) {
            closeOverlay();
            return true;
        }
        if (overlay == Overlay.NONE) {
            int row = rowAt(mouseX, mouseY);
            if (row >= 0 && handleRowClick(row, mouseX, button)) {
                return true;
            }
            // a stack on the cursor dropped inside the list is added as a rule entry
            if (button == 0 && !menu.getCarried().isEmpty() && isOverList(mouseX, mouseY)) {
                addFromStack(menu.getCarried());
                clickConsumed = true;
                return true;
            }
            // ctrl + click an inventory slot adds that item without picking it up
            if (hasControlDown() && hoveredSlot != null && hoveredSlot.hasItem()) {
                addFromStack(hoveredSlot.getItem());
                clickConsumed = true;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean alreadyAdded = clickConsumed;
        clickConsumed = false;
        // only the *release* of a drag adds, and only when the press over the list did not already add
        if (overlay == Overlay.NONE && !alreadyAdded && button == 0 && !menu.getCarried().isEmpty()
                && isOverList(mouseX, mouseY)) {
            addFromStack(menu.getCarried());
            // vanilla would clear its drag state in super.mouseReleased, which we swallow here
            this.isQuickCrafting = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** The rule list region, exposed so JEI can offer it as a ghost ingredient drop target. */
    public Rect2i listArea() {
        return new Rect2i(leftPos + ROW_X, topPos + CONTENT_Y, ROW_W, ROWS * ROW_H);
    }

    private boolean isOverList(double mouseX, double mouseY) {
        return mouseX >= leftPos + ROW_X && mouseX <= leftPos + ROW_X + ROW_W
                && mouseY >= topPos + CONTENT_Y && mouseY <= topPos + CONTENT_Y + ROWS * ROW_H;
    }

    /**
     * Adds one item as a rule entry for the active tab. Shared by drag & drop from the player
     * inventory, ctrl + click on an inventory slot and dragging an ingredient in from JEI.
     */
    public void addFromStack(ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (id == null) {
            return;
        }
        // an item that stores data in NBT (GregTech circuits, modes, ...) must keep it, otherwise
        // the rule would mean "any configuration" and resolve to a bare stack
        Match match = Match.item(id, stack.hasTag() ? stack.getTag().copy() : null);
        switch (tab) {
            case TAB_OUTPUT -> send(WildcardPatternMenu.ADD_OUTPUT, -1,
                    RuleCodec.writeOutput(new Output(match, 1, "A")));
            case TAB_EXCLUDE -> send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                    RuleCodec.writeExclude(Exclude.of(ExcludeKind.ITEM, id.toString())));
            default -> send(WildcardPatternMenu.ADD_INPUT, -1,
                    RuleCodec.writeInput(new Input(match, 1, "A", true, false)));
        }
        hint = Component.translatable(stack.hasTag() ? "wildcardencoder.gui.hint.added_nbt"
                : "wildcardencoder.gui.hint.added",
                Component.translatable(stack.getDescriptionId()).getString()).getString();
        scroll = Integer.MAX_VALUE;
        clampScroll();
    }

    /**
     * Tooltip for the element under the cursor inside a row: the C/O/A/N/X toggles and the count.
     * This is what makes the single letter buttons self explanatory.
     */
    private Component toggleTooltip(int row, double mouseX) {
        if (row < 0) {
            return null;
        }
        int relX = (int) (mouseX - (leftPos + ROW_X));
        if (relX >= 182) {
            return Component.translatable("wildcardencoder.gui.help.X");
        }
        if (relX >= 168) {
            return Component.translatable("wildcardencoder.gui.help.N");
        }
        if (relX >= 154) {
            return Component.translatable("wildcardencoder.gui.help.A");
        }
        if (tab == TAB_INPUT && relX >= 140) {
            return Component.translatable("wildcardencoder.gui.help.O");
        }
        if (tab == TAB_INPUT && relX >= 126) {
            return Component.translatable("wildcardencoder.gui.help.C");
        }
        if (tab == TAB_OUTPUT) {
            if (relX >= OUT_MINUS_X && relX < OUT_MINUS_X + COUNT_BUTTON) {
                return Component.translatable("wildcardencoder.gui.help.minus");
            }
            if (relX >= OUT_PLUS_X && relX < OUT_PLUS_X + COUNT_BUTTON) {
                return Component.translatable("wildcardencoder.gui.help.plus");
            }
            if (relX >= OUT_MINUS_X && relX < OUT_PLUS_X + COUNT_BUTTON) {
                return amountTooltip(rules.outputs().get(row).count());
            }
        } else if (tab == TAB_INPUT) {
            if (relX >= IN_MINUS_X && relX < IN_MINUS_X + COUNT_BUTTON) {
                return Component.translatable("wildcardencoder.gui.help.minus");
            }
            if (relX >= IN_PLUS_X && relX < IN_PLUS_X + COUNT_BUTTON) {
                return Component.translatable("wildcardencoder.gui.help.plus");
            }
            if (relX >= IN_MINUS_X && relX < IN_PLUS_X + COUNT_BUTTON) {
                return amountTooltip(tab == TAB_OUTPUT ? rules.outputs().get(row).count()
                        : rules.inputs().get(row).count());
            }
        }
        return null;
    }

    private static Component amountTooltip(long count) {
        return Component.translatable("wildcardencoder.gui.help.amount", formatCount(count), count);
    }

    private boolean handleResultClick(double mouseX, double mouseY) {
        int x = leftPos + ROW_X;
        int y = topPos + MATCHER_LIST_Y;
        if (mouseX < x || mouseX > x + ROW_W || mouseY < y || mouseY > y + MATCHER_ROWS * MATCHER_ROW_H + 2) {
            return false;
        }
        int row = (int) ((mouseY - (y + 1)) / MATCHER_ROW_H);
        int index = firstVisibleResult() + row;
        if (row >= 0 && row < MATCHER_ROWS && index >= 0 && index < results.size()) {
            selected = index;
            return true;
        }
        return false;
    }

    private boolean handleRowClick(int index, double mouseX, int button) {
        int relX = (int) (mouseX - (leftPos + ROW_X));
        int step = hasShiftDown() ? 10 : 1;

        switch (tab) {
            case TAB_INPUT -> {
                Input input = rules.inputs().get(index);
                if (relX >= 182) {
                    send(WildcardPatternMenu.REMOVE_INPUT, index, new CompoundTag());
                } else if (relX >= 168) {
                    openNbtEditor(input.match(), updated -> setInput(index, new Input(updated, input.count(),
                            input.group(), input.consume(), input.optional())));
                } else if (relX >= 154) {
                    setInput(index, new Input(input.match(), input.count(), nextGroup(input.group()),
                            input.consume(), input.optional()));
                } else if (relX >= 140) {
                    setInput(index, new Input(input.match(), input.count(), input.group(), input.consume(),
                            !input.optional()));
                } else if (relX >= 126) {
                    setInput(index, new Input(input.match(), input.count(), input.group(), !input.consume(),
                            input.optional()));
                } else if (relX >= IN_MINUS_X && relX < IN_MINUS_X + COUNT_BUTTON) {
                    setInput(index, new Input(input.match(), Math.max(1, input.count() - step), input.group(),
                            input.consume(), input.optional()));
                } else if (relX >= IN_PLUS_X && relX < IN_PLUS_X + COUNT_BUTTON) {
                    setInput(index, new Input(input.match(), input.count() + step, input.group(),
                            input.consume(), input.optional()));
                } else if (relX >= IN_MINUS_X && relX < IN_PLUS_X + COUNT_BUTTON) {
                    openAmountEditor(input.count(), amount -> setInput(index, new Input(input.match(), amount,
                            input.group(), input.consume(), input.optional())));
                } else {
                    openMatcherEditor(MatcherTarget.INPUT, index, input.match());
                }
                return true;
            }
            case TAB_OUTPUT -> {
                Output output = rules.outputs().get(index);
                if (relX >= 182) {
                    send(WildcardPatternMenu.REMOVE_OUTPUT, index, new CompoundTag());
                } else if (relX >= 168) {
                    openNbtEditor(output.match(), updated -> setOutput(index,
                            new Output(updated, output.count(), output.group())));
                } else if (relX >= 154) {
                    setOutput(index, new Output(output.match(), output.count(), nextGroup(output.group())));
                } else if (relX >= OUT_MINUS_X && relX < OUT_MINUS_X + COUNT_BUTTON) {
                    setOutput(index, new Output(output.match(), Math.max(1, output.count() - step),
                            output.group()));
                } else if (relX >= OUT_PLUS_X && relX < OUT_PLUS_X + COUNT_BUTTON) {
                    setOutput(index, new Output(output.match(), output.count() + step, output.group()));
                } else if (relX >= OUT_MINUS_X && relX < OUT_PLUS_X + COUNT_BUTTON) {
                    openAmountEditor(output.count(), amount -> setOutput(index,
                            new Output(output.match(), amount, output.group())));
                } else {
                    openMatcherEditor(MatcherTarget.OUTPUT, index, output.match());
                }
                return true;
            }
            case TAB_EXCLUDE -> {
                if (relX >= 182) {
                    send(WildcardPatternMenu.REMOVE_EXCLUDE, index, new CompoundTag());
                } else {
                    openExcludeEditor(rules.excludes().get(index), index);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (overlay == Overlay.MATCHER) {
            selected = clamp(selected - (int) Math.signum(delta), 0, Math.max(0, results.size() - 1));
            return true;
        }
        int max = Math.max(0, rowCount() - ROWS);
        if (overlay == Overlay.NONE && max > 0 && mouseY >= topPos + CONTENT_Y
                && mouseY <= topPos + CONTENT_Y + ROWS * ROW_H) {
            scroll = clamp(scroll - (int) Math.signum(delta), 0, max);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String nextGroup(String current) {
        return switch (current) {
            case "" -> "A";
            case "A" -> "B";
            case "B" -> "C";
            default -> "";
        };
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && overlay != Overlay.NONE) {
            if (overlay == Overlay.MATCHER && searchExpanded) {
                searchExpanded = false;
                matcherWidgetsDirty = true;
                return true;
            }
            closeOverlay();
            return true;
        }
        if (overlay != Overlay.NONE) {
            EditBox focused = overlay == Overlay.MATCHER ? searchField : textField;
            if (focused != null && focused.isFocused()) {
                if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                    confirmOverlay();
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_TAB || keyCode == GLFW.GLFW_KEY_DOWN) {
                    selected = clamp(selected + 1, 0, Math.max(0, results.size() - 1));
                    return true;
                }
                if (keyCode == GLFW.GLFW_KEY_UP) {
                    selected = clamp(selected - 1, 0, Math.max(0, results.size() - 1));
                    return true;
                }
                if (focused.keyPressed(keyCode, scanCode, modifiers) || focused.canConsumeInput()) {
                    return true;
                }
            }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ------------------------------------------------------------------ edits

    private void send(int action, int index, CompoundTag payload) {
        WildcardNetwork.CHANNEL.sendToServer(new RuleEditPacket(action, index, payload));
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private void setInput(int index, Input value) {
        send(WildcardPatternMenu.SET_INPUT, index, RuleCodec.writeInput(value));
    }

    private void setOutput(int index, Output value) {
        send(WildcardPatternMenu.SET_OUTPUT, index, RuleCodec.writeOutput(value));
    }

    private void copyRules() {
        if (minecraft != null) {
            minecraft.keyboardHandler.setClipboard(RuleCodec.toSnbt(rules));
        }
    }

    private void pasteRules() {
        if (minecraft == null) {
            return;
        }
        String text = minecraft.keyboardHandler.getClipboard();
        if (RuleCodec.fromSnbt(text) == null) {
            return;
        }
        CompoundTag payload = new CompoundTag();
        payload.putString("snbt", text);
        send(WildcardPatternMenu.IMPORT_SNBT, -1, payload);
    }

    // ------------------------------------------------------------------ overlays

    private void openAddOverlay() {
        editIndex = -1;
        pendingNbt = null;
        pendingPairInput = "";
        switch (tab) {
            case TAB_INPUT -> openMatcherEditor(MatcherTarget.INPUT, -1, Match.item(ResourceLocation.withDefaultNamespace("stone")));
            case TAB_OUTPUT -> openMatcherEditor(MatcherTarget.OUTPUT, -1, Match.item(ResourceLocation.withDefaultNamespace("stone")));
            default -> openChooser(Component.translatable("wildcardencoder.gui.choose.exclude").getString(),
                    List.of(tr("wildcardencoder.gui.exclude.kind.item"),
                            tr("wildcardencoder.gui.exclude.kind.output"),
                            tr("wildcardencoder.gui.exclude.kind.variant"),
                            tr("wildcardencoder.gui.exclude.kind.mod"),
                            tr("wildcardencoder.gui.exclude.kind.nbt"),
                            tr("wildcardencoder.gui.exclude.kind.pair")),
                    List.of(this::addExcludeItem, this::addExcludeOutput, this::addExcludeVariant,
                            this::addExcludeMod, this::addExcludeNbt, this::addExcludePair));
        }
    }

    private void openOptionsChooser() {
        Options options = rules.options();
        openChooser(Component.translatable("wildcardencoder.gui.options").getString(),
                List.of(Component.translatable("wildcardencoder.gui.opt.bind", options.bindPerGroup() ? "on" : "off").getString(),
                        Component.translatable("wildcardencoder.gui.opt.max", options.maxInstances()).getString(),
                        Component.translatable("wildcardencoder.gui.opt.optional", options.maxOptionalExpansion()).getString(),
                        Component.translatable("wildcardencoder.gui.opt.clear").getString()),
                List.of(
                        () -> setOptions(new Options(!options.bindPerGroup(), options.maxInstances(),
                                options.maxOptionalExpansion())),
                        () -> setOptions(new Options(options.bindPerGroup(), nextMax(options.maxInstances()),
                                options.maxOptionalExpansion())),
                        () -> setOptions(new Options(options.bindPerGroup(), options.maxInstances(),
                                (options.maxOptionalExpansion() + 1) % 3)),
                        () -> send(WildcardPatternMenu.CLEAR, -1, new CompoundTag())));
    }

    private static int nextMax(int current) {
        if (current < 128) {
            return 256;
        }
        return current < 1024 ? 1024 : 64;
    }

    private void setOptions(Options options) {
        CompoundTag payload = new CompoundTag();
        payload.putBoolean("bind", options.bindPerGroup());
        payload.putInt("maxI", options.maxInstances());
        payload.putInt("maxOpt", options.maxOptionalExpansion());
        send(WildcardPatternMenu.SET_OPTIONS, -1, payload);
    }

    /** Clicking the number opens a prompt instead of nudging, which is handy for large amounts. */
    private void openAmountEditor(long current, java.util.function.LongConsumer commit) {
        openTextEditor("wildcardencoder.gui.amount", Long.toString(current), text -> {
            try {
                long value = Long.parseLong(text.trim());
                if (value >= 1) {
                    commit.accept(Math.min(value, (long) WildcardConfig.maxAmount()));
                }
            } catch (NumberFormatException ignored) {
                // invalid input keeps the current amount
            }
        });
    }

    /**
     * Results for the query, restricted to the matcher kind the player picked.
     *
     * <p>The query is usually a concrete item id (editing a rule starts from its value), which matches
     * nothing in the tag or family lists. In that case the tags / families that item really belongs to
     * are offered instead - which is what you actually want after picking an item and clicking #tag or
     * the family button. If there is no item context at all, every entry of that kind is shown, so the
     * list is never empty.
     */
    private List<ItemSearch.Entry> searchResults(String query) {
        MatchKind wanted = kindForQuery(query);
        List<ItemSearch.Entry> filtered = new ArrayList<>();
        for (ItemSearch.Entry entry : ItemSearch.search(query, 64)) {
            if (entry.kind() == wanted) {
                filtered.add(entry);
            }
        }
        if (!filtered.isEmpty()) {
            return List.copyOf(filtered);
        }
        List<ItemSearch.Entry> related = relatedEntries(query, wanted);
        if (!related.isEmpty()) {
            return related;
        }
        return everythingOf(wanted);
    }

    /**
     * Everything of one matcher kind. {@code ItemSearch.search("")} only lists families, so tags and
     * items need their own source, otherwise the list could still come up empty.
     */
    private static List<ItemSearch.Entry> everythingOf(MatchKind wanted) {
        return switch (wanted) {
            case TAG -> allBoundTags();
            case MOD -> ItemSearch.search("@", 256);
            case FAMILY -> ItemSearch.search("", 256);
            default -> firstRegisteredItems();
        };
    }

    private static List<ItemSearch.Entry> allBoundTags() {
        var manager = ForgeRegistries.ITEMS.tags();
        if (manager == null) {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (var key : manager.getTagNames().toList()) {
            var tag = manager.getTag(key);
            if (tag != null && tag.isBound() && !tag.isEmpty()) {
                names.add(key.location().toString());
            }
        }
        names.sort(String::compareTo);
        List<ItemSearch.Entry> out = new ArrayList<>();
        for (String name : names) {
            if (out.size() >= 256) {
                break;
            }
            out.add(ItemSearch.Entry.tag(name));
        }
        return List.copyOf(out);
    }

    private static List<ItemSearch.Entry> firstRegisteredItems() {
        List<ItemSearch.Entry> out = new ArrayList<>();
        for (Item item : ForgeRegistries.ITEMS) {
            if (out.size() >= 256) {
                break;
            }
            ResourceLocation id = ForgeRegistries.ITEMS.getKey(item);
            if (id != null) {
                out.add(ItemSearch.Entry.item(item, id.toString()));
            }
        }
        return List.copyOf(out);
    }

    /** The tags / families a concrete item belongs to. */
    private List<ItemSearch.Entry> relatedEntries(String query, MatchKind wanted) {
        Item item = itemFromQuery(query);
        if (item == null || item == Items.AIR || wanted == MatchKind.ITEM) {
            return List.of();
        }
        List<ItemSearch.Entry> out = new ArrayList<>();
        try {
            for (var tag : item.builtInRegistryHolder().tags().toList()) {
                if (wanted == MatchKind.TAG) {
                    out.add(ItemSearch.Entry.tag(tag.location().toString()));
                } else if (wanted == MatchKind.FAMILY) {
                    out.add(ItemSearch.Entry.family(familyToken(tag.location())));
                }
                if (out.size() >= 24) {
                    break;
                }
            }
        } catch (RuntimeException ignored) {
            return List.of();
        }
        return List.copyOf(new java.util.LinkedHashSet<>(out));
    }

    private static Item itemFromQuery(String query) {
        String id = query.startsWith("#") || query.startsWith("@") ? query.substring(1) : query;
        if (id.isEmpty() || id.contains("/")) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return location == null ? null : ForgeRegistries.ITEMS.getValue(location);
    }

    /**
     * A family token for a tag: a known alias when there is one ("forge:ingots/copper" -&gt; "ingot*"),
     * otherwise a glob on the tag root.
     */
    private static String familyToken(ResourceLocation tag) {
        String path = tag.getPath();
        ResourceLocation root = path.contains("/")
                ? new ResourceLocation(tag.getNamespace(), path.substring(0, path.indexOf('/')))
                : tag;
        for (var entry : WildcardAliases.all().entrySet()) {
            if (entry.getValue().equals(tag) || entry.getValue().equals(root)) {
                return entry.getKey() + "*";
            }
        }
        return root + "/*";
    }

    /** A '#' query means tags and '@' means mods, whatever button is highlighted. */
    private MatchKind kindForQuery(String query) {
        if (query.startsWith("#")) {
            return MatchKind.TAG;
        }
        if (query.startsWith("@")) {
            return MatchKind.MOD;
        }
        return editorKind;
    }

    private int kindIndex(MatchKind kind) {
        return switch (kind) {
            case ITEM -> 0;
            case TAG -> 1;
            case FAMILY -> 2;
            case MOD -> 3;
            default -> -1;
        };
    }

    private void openMatcherEditor(MatcherTarget target, int index, Match existing) {
        closeOverlay();
        overlay = Overlay.MATCHER;
        matcherTarget = target;
        editIndex = index;
        editorKind = existing == null ? MatchKind.ITEM : existing.kind();
        pendingNbt = existing == null ? null : existing.nbt();
        selected = 0;
        String query = "";
        if (existing != null) {
            query = switch (existing.kind()) {
                case TAG -> "#" + existing.value();
                case MOD -> "@" + existing.value();
                default -> existing.value();
            };
        }
        searchQuery = query;
        searchExpanded = false;
        results = searchResults(query);
        buildMatcherWidgets();
    }

    private void buildMatcherWidgets() {
        // top row: search (a plain bar until you click it) + NBT / OK / Cancel
        if (searchExpanded) {
            searchField = new EditBox(font, leftPos + ROW_X, topPos + MATCHER_SEARCH_Y, ROW_W - 108, 14,
                    Component.translatable("wildcardencoder.gui.search"));
            searchField.setMaxLength(64);
            searchField.setValue(searchQuery);
            searchField.setResponder(value -> {
                searchQuery = value;
                selected = 0;
                results = searchResults(value);
            });
            addOverlayWidget(searchField);
            setFocused(searchField);
            searchField.setFocused(true);
        } else {
            searchField = null;
            String label = searchQuery.isEmpty() ? tr("wildcardencoder.gui.search")
                    : font.plainSubstrByWidth(searchQuery, ROW_W - 124);
            addOverlayWidget(Button.builder(Component.literal(label), b -> {
                searchExpanded = true;
                matcherWidgetsDirty = true;
            }).bounds(leftPos + ROW_X, topPos + MATCHER_SEARCH_Y, ROW_W - 108, 14).build());
        }

        addOverlayWidget(Button.builder(Component.literal(pendingNbt == null ? "NBT" : "NBT*"),
                b -> openNbtEditorForMatcher()).bounds(leftPos + ROW_X + ROW_W - 104,
                topPos + MATCHER_SEARCH_Y, 32, 14).build());
        addOverlayWidget(Button.builder(Component.translatable("wildcardencoder.gui.ok"), b -> confirmOverlay())
                .bounds(leftPos + ROW_X + ROW_W - 68, topPos + MATCHER_SEARCH_Y, 32, 14).build());
        addOverlayWidget(Button.builder(Component.translatable("wildcardencoder.gui.cancel"), b -> closeOverlay())
                .bounds(leftPos + ROW_X + ROW_W - 34, topPos + MATCHER_SEARCH_Y, 32, 14).build());

        // second row: what kind of matcher is being built
        int y = topPos + MATCHER_KIND_Y;
        List<MatchKind> kinds = new ArrayList<>(List.of(MatchKind.ITEM, MatchKind.TAG, MatchKind.FAMILY));
        if (WildcardConfig.allowModMatcher()) {
            kinds.add(MatchKind.MOD);
        }
        for (int i = 0; i < kinds.size(); i++) {
            MatchKind kind = kinds.get(i);
            addOverlayWidget(Button.builder(kindLabel(kind), b -> {
                editorKind = kind;
                selected = 0;
                results = searchResults(searchQuery);
                matcherWidgetsDirty = true;
            }).bounds(leftPos + ROW_X + i * 50, y, 48, 14).build());
        }
    }

    /** Recreates the overlay widgets after the search bar expanded or collapsed. */
    private void rebuildMatcherWidgets() {
        for (GuiEventListener widget : new ArrayList<>(overlayWidgets)) {
            removeWidget(widget);
        }
        overlayWidgets.clear();
        searchField = null;
        buildMatcherWidgets();
    }

    private String currentQuery() {
        return switch (editorKind) {
            case TAG -> "#";
            case MOD -> "@";
            default -> "";
        };
    }

    private Component kindLabel(MatchKind kind) {
        return Component.translatable("wildcardencoder.gui.kind." + kind.name().toLowerCase(Locale.ROOT));
    }

    private void openNbtEditor(Match match, Consumer<Match> commit) {
        openTextEditor("wildcardencoder.gui.nbt",
                match.hasNbt() ? match.nbt().getAsString() : "",
                text -> commit.accept(new Match(match.kind(), match.value(), parseNbt(text))));
    }

    private void openNbtEditorForMatcher() {
        ItemSearch.Entry entry = selectedEntry();
        String value = entry == null ? "" : entry.value();
        openTextEditor("wildcardencoder.gui.nbt", pendingNbt == null ? "" : pendingNbt.getAsString(),
                text -> pendingNbt = parseNbt(text));
    }

    private static CompoundTag parseNbt(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        try {
            return TagParser.parseTag(text);
        } catch (Exception e) {
            return null;
        }
    }

    private void openTextEditor(String titleKey, String initial, Consumer<String> commit) {
        closeOverlay();
        overlay = Overlay.TEXT;
        textTitleKey = titleKey;
        textInitial = initial == null ? "" : initial;
        textCommit = commit;
        buildTextWidgets();
    }

    private void buildTextWidgets() {
        textField = new EditBox(font, leftPos + ROW_X, topPos + CONTENT_Y + 38, ROW_W - 8, 16,
                Component.translatable(textTitleKey));
        textField.setMaxLength(4096);
        textField.setValue(textInitial);
        addOverlayWidget(textField);
        addOverlayWidget(Button.builder(Component.translatable("wildcardencoder.gui.ok"), b -> confirmOverlay())
                .bounds(leftPos + ROW_X, topPos + CONTENT_Y + 60, 40, 14).build());
        addOverlayWidget(Button.builder(Component.translatable("wildcardencoder.gui.cancel"), b -> closeOverlay())
                .bounds(leftPos + ROW_X + 44, topPos + CONTENT_Y + 60, 40, 14).build());
        setFocused(textField);
    }

    private void openChooser(String title, List<String> labels, List<Runnable> actions) {
        closeOverlay();
        overlay = Overlay.CHOOSER;
        chooserLabels.clear();
        chooserActions.clear();
        chooserLabels.addAll(labels);
        chooserActions.addAll(actions);
        buildChooserWidgets();
    }

    private void buildChooserWidgets() {
        int y = topPos + CONTENT_Y + 26;
        for (int i = 0; i < chooserLabels.size() && i < 6; i++) {
            Runnable action = chooserActions.get(i);
            Button button = Button.builder(Component.literal(chooserLabels.get(i)), b -> {
                closeOverlay();
                action.run();
            }).bounds(leftPos + ROW_X + (i % 2) * 104, y + (i / 2) * 20, 100, 16).build();
            addOverlayWidget(button);
        }
        addOverlayWidget(Button.builder(Component.translatable("wildcardencoder.gui.cancel"), b -> closeOverlay())
                .bounds(leftPos + ROW_X, y + 3 * 20 + 4, 40, 14).build());
    }

    private void closeOverlay() {
        for (GuiEventListener widget : overlayWidgets) {
            removeWidget(widget);
        }
        overlayWidgets.clear();
        searchField = null;
        textField = null;
        searchExpanded = false;
        searchQuery = "";
        matcherWidgetsDirty = false;
        overlay = Overlay.NONE;
    }

    private void confirmOverlay() {
        switch (overlay) {
            case MATCHER -> confirmMatcher();
            case TEXT -> {
                Consumer<String> commit = textCommit;
                String value = textField == null ? textInitial : textField.getValue();
                closeOverlay();
                if (commit != null) {
                    commit.accept(value);
                }
            }
            default -> closeOverlay();
        }
    }

    private void confirmMatcher() {
        ItemSearch.Entry entry = selectedEntry();
        String value = entry != null ? entry.value() : searchQuery.trim();
        MatchKind kind = entry != null ? entry.kind() : editorKind;
        if (value.isEmpty()) {
            closeOverlay();
            return;
        }
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        if (value.startsWith("@")) {
            value = value.substring(1);
        }
        Match match = new Match(kind, value, pendingNbt);
        int index = editIndex;
        MatcherTarget target = matcherTarget;
        closeOverlay();

        switch (target) {
            case INPUT -> {
                if (index >= 0 && index < rules.inputs().size()) {
                    Input old = rules.inputs().get(index);
                    setInput(index, new Input(match, old.count(), old.group(), old.consume(), old.optional()));
                } else {
                    send(WildcardPatternMenu.ADD_INPUT, -1, RuleCodec.writeInput(
                            new Input(match, 1, "A", true, false)));
                }
            }
            case OUTPUT -> {
                if (index >= 0 && index < rules.outputs().size()) {
                    Output old = rules.outputs().get(index);
                    setOutput(index, new Output(match, old.count(), old.group()));
                } else {
                    send(WildcardPatternMenu.ADD_OUTPUT, -1, RuleCodec.writeOutput(
                            new Output(match, 1, "A")));
                }
            }
            case EXCLUDE_ITEM -> send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                    RuleCodec.writeExclude(Exclude.of(ExcludeKind.ITEM, match.value())));
            case EXCLUDE_OUTPUT -> send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                    RuleCodec.writeExclude(Exclude.of(ExcludeKind.OUTPUT, match.describe())));
        }
    }

    private ItemSearch.Entry selectedEntry() {
        return selected >= 0 && selected < results.size() ? results.get(selected) : null;
    }

    // ------------------------------------------------------------------ exclude helpers

    private void addExcludeItem() {
        openMatcherEditor(MatcherTarget.EXCLUDE_ITEM, -1, Match.item(ResourceLocation.withDefaultNamespace("stone")));
    }

    private void addExcludeOutput() {
        openMatcherEditor(MatcherTarget.EXCLUDE_OUTPUT, -1, Match.item(ResourceLocation.withDefaultNamespace("stone")));
    }

    private void addExcludeVariant() {
        openTextEditor("wildcardencoder.gui.exclude.variant", "", text -> {
            if (!text.isBlank()) {
                send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                        RuleCodec.writeExclude(Exclude.of(ExcludeKind.VARIANT, text.trim())));
            }
        });
    }

    private void addExcludeMod() {
        openTextEditor("wildcardencoder.gui.exclude.mod", "", text -> {
            if (!text.isBlank()) {
                send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                        RuleCodec.writeExclude(Exclude.of(ExcludeKind.MOD, text.trim())));
            }
        });
    }

    private void addExcludeNbt() {
        openTextEditor("wildcardencoder.gui.exclude.nbt", "", text -> {
            CompoundTag tag = parseNbt(text);
            if (tag != null) {
                send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                        RuleCodec.writeExclude(new Exclude(ExcludeKind.NBT, "", "", tag)));
            }
        });
    }

    private void addExcludePair() {
        if (rules.inputs().isEmpty() || rules.outputs().isEmpty()) {
            return;
        }
        List<String> inputs = new ArrayList<>();
        for (Input input : rules.inputs()) {
            inputs.add(input.match().describe());
        }
        openChooser(Component.translatable("wildcardencoder.gui.exclude.pair.input").getString(), inputs,
                buildPairInputActions());
    }

    private List<Runnable> buildPairInputActions() {
        List<Runnable> actions = new ArrayList<>();
        for (Input input : rules.inputs()) {
            actions.add(() -> {
                pendingPairInput = input.match().value();
                List<String> outputs = new ArrayList<>();
                for (Output output : rules.outputs()) {
                    outputs.add(output.match().describe());
                }
                openChooser(Component.translatable("wildcardencoder.gui.exclude.pair.output").getString(), outputs,
                        buildPairOutputActions());
            });
        }
        return actions;
    }

    private List<Runnable> buildPairOutputActions() {
        List<Runnable> actions = new ArrayList<>();
        for (Output output : rules.outputs()) {
            actions.add(() -> send(WildcardPatternMenu.ADD_EXCLUDE, -1,
                    RuleCodec.writeExclude(Exclude.pair(pendingPairInput, output.match().value()))));
        }
        return actions;
    }

    private void openExcludeEditor(Exclude exclude, int index) {
        switch (exclude.kind()) {
            case ITEM -> openMatcherEditor(MatcherTarget.EXCLUDE_ITEM, -1, Match.of(MatchKind.ITEM, exclude.a()));
            case OUTPUT -> openMatcherEditor(MatcherTarget.EXCLUDE_OUTPUT, -1, Match.of(MatchKind.FAMILY, exclude.a()));
            case VARIANT -> openTextEditor("wildcardencoder.gui.exclude.variant", exclude.a(), text -> {
                if (!text.isBlank()) {
                    send(WildcardPatternMenu.SET_EXCLUDE, index,
                            RuleCodec.writeExclude(Exclude.of(ExcludeKind.VARIANT, text.trim())));
                }
            });
            case MOD -> openTextEditor("wildcardencoder.gui.exclude.mod", exclude.a(), text -> {
                if (!text.isBlank()) {
                    send(WildcardPatternMenu.SET_EXCLUDE, index,
                            RuleCodec.writeExclude(Exclude.of(ExcludeKind.MOD, text.trim())));
                }
            });
            case NBT -> openTextEditor("wildcardencoder.gui.exclude.nbt",
                    exclude.nbt() == null ? "" : exclude.nbt().getAsString(), text -> {
                        CompoundTag tag = parseNbt(text);
                        if (tag != null) {
                            send(WildcardPatternMenu.SET_EXCLUDE, index,
                                    RuleCodec.writeExclude(new Exclude(ExcludeKind.NBT, "", "", tag)));
                        }
                    });
            default -> {
                // PAIR entries are edited by removing and re-adding
            }
        }
    }
}
