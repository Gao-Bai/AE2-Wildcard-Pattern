package com.ae2wildcard.encoder.menu;

import java.util.ArrayList;
import java.util.List;

import com.ae2wildcard.encoder.WildcardConfig;
import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.Msg;
import com.ae2wildcard.encoder.craft.PatternInstantiator;
import com.ae2wildcard.encoder.craft.PatternText;
import com.ae2wildcard.encoder.item.WildcardItemData;
import com.ae2wildcard.encoder.network.RuleSyncPacket;
import com.ae2wildcard.encoder.network.WildcardNetwork;
import com.ae2wildcard.encoder.rule.RuleCodec;
import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Options;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * The rule editor container. Two modes share everything:
 * <ul>
 * <li>{@link #MODE_HELD_ITEM} - editing the wildcard pattern the player is holding (right-click the item);</li>
 * <li>{@link #MODE_DRAFT} - the wildcard pattern encoder block, which creates a <b>single</b> wildcard
 * pattern from the draft rules.</li>
 * </ul>
 *
 * The server side is authoritative: every edit is applied here, written into the item's NBT (held item
 * mode) and mirrored back to the client with a {@link RuleSyncPacket}.
 */
public class WildcardPatternMenu extends AbstractContainerMenu {

    public static final byte MODE_HELD_ITEM = 0;
    public static final byte MODE_DRAFT = 1;

    /** Player inventory layout, shared with the screen (see WildcardPatternScreen). */
    public static final int INV_X = 8;
    public static final int INV_ROW_Y = 160;
    public static final int HOTBAR_Y = 216;
    public static final int INV_ROWS = 3;

    // edit actions (also used by RuleEditPacket)
    public static final int ADD_INPUT = 1;
    public static final int SET_INPUT = 2;
    public static final int REMOVE_INPUT = 3;
    public static final int ADD_OUTPUT = 4;
    public static final int SET_OUTPUT = 5;
    public static final int REMOVE_OUTPUT = 6;
    public static final int ADD_EXCLUDE = 7;
    public static final int SET_EXCLUDE = 8;
    public static final int REMOVE_EXCLUDE = 9;
    public static final int SET_OPTIONS = 10;
    public static final int CLEAR = 11;
    public static final int IMPORT_SNBT = 12;
    public static final int ENCODE = 13;

    private static final int SAMPLE_LIMIT = 6;

    private final byte mode;
    private final InteractionHand hand;
    private final Inventory playerInventory;

    private WildcardRules rules;
    private int revision;
    private int previewCount;
    private int excludedCount;
    private boolean truncated;
    private Msg error;
    private List<Msg> warnings = List.of();
    private List<String> samples = List.of();

    /**
     * Client side constructor, used by the registered menu type when the open-screen packet arrives.
     * Everything else (mode, hand, rules) is delivered by the first {@link RuleSyncPacket}, so the
     * menu open packet carries no extra data at all.
     */
    public WildcardPatternMenu(int windowId, Inventory playerInventory) {
        this(windowId, playerInventory, MODE_HELD_ITEM, InteractionHand.MAIN_HAND, WildcardRules.EMPTY);
    }

    private WildcardPatternMenu(int windowId, Inventory playerInventory, byte mode, InteractionHand hand,
            WildcardRules rules) {
        super(WildcardEncoderMod.WILDCARD_PATTERN_MENU.get(), windowId);
        this.playerInventory = playerInventory;
        this.mode = mode;
        this.hand = hand;
        this.rules = rules == null ? WildcardRules.EMPTY : rules;
        addPlayerInventory(playerInventory);
        recompute();
    }

    /** The player inventory is shown so items can be dragged straight into the rule list. */
    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < INV_ROWS; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, 9 + column + row * 9, INV_X + column * 18, INV_ROW_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, INV_X + column * 18, HOTBAR_Y));
        }
    }

    /** Editing the wildcard pattern the player is holding; the current rules are loaded right away. */
    public static WildcardPatternMenu forHeldItem(int windowId, Inventory playerInventory, Player player,
            InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        return new WildcardPatternMenu(windowId, playerInventory, MODE_HELD_ITEM, hand,
                WildcardItemData.rules(stack));
    }

    /** The encoder block: a fresh draft that is only written into an item when the player encodes it. */
    public static WildcardPatternMenu forDraft(int windowId, Inventory playerInventory) {
        return new WildcardPatternMenu(windowId, playerInventory, MODE_DRAFT, InteractionHand.MAIN_HAND,
                WildcardRules.EMPTY);
    }

    // ------------------------------------------------------------------ accessors

    public byte mode() {
        return mode;
    }

    public boolean isDraft() {
        return mode == MODE_DRAFT;
    }

    public WildcardRules rules() {
        return rules;
    }

    public int revision() {
        return revision;
    }

    public int previewCount() {
        return previewCount;
    }

    public int excludedCount() {
        return excludedCount;
    }

    public boolean truncated() {
        return truncated;
    }

    public Msg error() {
        return error;
    }

    public List<String> samples() {
        return samples;
    }

    // ------------------------------------------------------------------ container

    @Override
    public boolean stillValid(Player player) {
        if (mode == MODE_DRAFT) {
            return true;
        }
        return player.getItemInHand(hand).is(WildcardEncoderMod.WILDCARD_PATTERN.get());
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // only player inventory slots exist: shift-click moves between the inventory and the hotbar
        Slot slot = this.slots.get(index);
        if (slot == null || !slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        boolean inMain = index < INV_ROWS * 9;
        if (inMain) {
            if (!moveItemStackTo(stack, INV_ROWS * 9, this.slots.size(), false)) {
                return ItemStack.EMPTY;
            }
        } else if (!moveItemStackTo(stack, 0, INV_ROWS * 9, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    // ------------------------------------------------------------------ edits

    /** Applies one edit coming from the client. Server thread only. */
    public void applyEdit(ServerPlayer player, int action, int index, CompoundTag payload) {
        if (action == ENCODE) {
            encode(player);
            return;
        }
        WildcardRules updated = rules;
        try {
            CompoundTag data = payload == null ? new CompoundTag() : payload;
            switch (action) {
                case ADD_INPUT -> updated = rules.withInputs(append(rules.inputs(), RuleCodec.readInput(data)));
                case SET_INPUT -> updated = rules.withInputs(replace(rules.inputs(), index, RuleCodec.readInput(data)));
                case REMOVE_INPUT -> updated = rules.withInputs(remove(rules.inputs(), index));
                case ADD_OUTPUT -> updated = rules.withOutputs(append(rules.outputs(), RuleCodec.readOutput(data)));
                case SET_OUTPUT -> updated = rules.withOutputs(replace(rules.outputs(), index, RuleCodec.readOutput(data)));
                case REMOVE_OUTPUT -> updated = rules.withOutputs(remove(rules.outputs(), index));
                case ADD_EXCLUDE -> updated = rules.withExcludes(append(rules.excludes(), RuleCodec.readExclude(data)));
                case SET_EXCLUDE -> updated = rules.withExcludes(replace(rules.excludes(), index, RuleCodec.readExclude(data)));
                case REMOVE_EXCLUDE -> updated = rules.withExcludes(remove(rules.excludes(), index));
                case SET_OPTIONS -> updated = rules.withOptions(new Options(
                        !data.contains("bind") || data.getBoolean("bind"),
                        data.contains("maxI") ? data.getInt("maxI") : rules.options().maxInstances(),
                        data.contains("maxOpt") ? data.getInt("maxOpt") : rules.options().maxOptionalExpansion()));
                case CLEAR -> updated = WildcardRules.EMPTY;
                case IMPORT_SNBT -> {
                    WildcardRules imported = RuleCodec.fromSnbt(data.getString("snbt"));
                    if (imported == null) {
                        error = Msg.of(Msg.ERROR_INVALID_SNBT);
                        sync(player);
                        return;
                    }
                    updated = imported;
                }
                default -> {
                    return;
                }
            }
        } catch (RuntimeException e) {
            error = Msg.of(Msg.ERROR_EDIT_FAILED, e.toString());
            sync(player);
            return;
        }

        if (!store(player, updated)) {
            sync(player);
            return;
        }
        revision++;
        error = null;
        recompute();
        sync(player);
    }

    private boolean store(ServerPlayer player, WildcardRules updated) {
        if (mode == MODE_HELD_ITEM) {
            ItemStack stack = player.getItemInHand(hand);
            if (!stack.is(WildcardEncoderMod.WILDCARD_PATTERN.get())) {
                error = Msg.of(Msg.ERROR_NOT_HOLDING);
                return false;
            }
            WildcardItemData.setRules(stack, updated);
        }
        rules = updated;
        return true;
    }

    /** Creates one wildcard pattern from the draft rules (encoder block only). */
    private void encode(ServerPlayer player) {
        if (mode != MODE_DRAFT) {
            error = Msg.of(Msg.ERROR_CANNOT_REENCODE);
            sync(player);
            return;
        }
        if (!rules.isComplete()) {
            error = Msg.of(Msg.ERROR_NEED_INPUT_OUTPUT);
            sync(player);
            return;
        }
        // two independent switches: requiring a blank pattern and consuming it
        if (!player.getAbilities().instabuild) {
            boolean requires = WildcardConfig.encodeRequiresBlankPattern();
            boolean consumes = WildcardConfig.encodeConsumesBlankPattern();
            if (requires || consumes) {
                boolean has = hasBlankPattern(player);
                if (!has && requires) {
                    error = Msg.of(Msg.ERROR_NEEDS_BLANK_PATTERN);
                    sync(player);
                    return;
                }
                if (has && consumes) {
                    consumeBlankPattern(player);
                }
            }
        }
        ItemStack created = new ItemStack(WildcardEncoderMod.WILDCARD_PATTERN.get());
        WildcardItemData.setRules(created, rules);
        if (!player.getInventory().add(created)) {
            player.drop(created, false);
        }
        error = null;
        player.displayClientMessage(Component.translatable("wildcardencoder.msg.encoded",
                PatternText.summary(rules)), false);
        sync(player);
    }

    private static boolean hasBlankPattern(ServerPlayer player) {
        Item blank = blankPattern();
        if (blank == Items.AIR) {
            return false;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            if (player.getInventory().getItem(slot).is(blank)) {
                return true;
            }
        }
        return false;
    }

    private static boolean consumeBlankPattern(ServerPlayer player) {
        Item blank = blankPattern();
        if (blank == Items.AIR) {
            return false;
        }
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(blank)) {
                stack.shrink(1);
                player.getInventory().setChanged();
                return true;
            }
        }
        return false;
    }

    private static Item blankPattern() {
        Item item = ForgeRegistries.ITEMS.getValue(new ResourceLocation("ae2", "blank_pattern"));
        return item == null ? Items.AIR : item;
    }

    // ------------------------------------------------------------------ preview/sync

    private void recompute() {
        PatternInstantiator.Result result = PatternInstantiator.instantiate(rules);
        previewCount = result.patterns().size();
        excludedCount = result.excluded();
        truncated = result.truncated();
        error = result.error();
        warnings = result.warnings();
        List<String> lines = new ArrayList<>(SAMPLE_LIMIT);
        for (var pattern : result.patterns()) {
            if (lines.size() >= SAMPLE_LIMIT) {
                break;
            }
            lines.add(PatternText.plain(pattern));
        }
        samples = List.copyOf(lines);
    }

    public void sync(ServerPlayer player) {
        WildcardNetwork.CHANNEL.send(net.minecraftforge.network.PacketDistributor.PLAYER.with(() -> player),
                new RuleSyncPacket(revision, mode == MODE_DRAFT, rules, previewCount, excludedCount, truncated,
                        error, warnings, samples));
    }

    // ------------------------------------------------------------------ list helpers

    private static <T> List<T> append(List<T> list, T value) {
        List<T> copy = new ArrayList<>(list);
        copy.add(value);
        return copy;
    }

    private static <T> List<T> replace(List<T> list, int index, T value) {
        List<T> copy = new ArrayList<>(list);
        if (index >= 0 && index < copy.size()) {
            copy.set(index, value);
        }
        return copy;
    }

    private static <T> List<T> remove(List<T> list, int index) {
        List<T> copy = new ArrayList<>(list);
        if (index >= 0 && index < copy.size()) {
            copy.remove(index);
        }
        return copy;
    }
}
