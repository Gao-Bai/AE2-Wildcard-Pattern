package com.ae2wildcard.encoder.craft;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;

/**
 * A user facing message: a translation key plus positional arguments.
 *
 * <p>The rule engine runs on the server, so it must not bake English text into what it reports. It
 * produces a {@code Msg} instead, which travels to the client inside {@code RuleSyncPacket} and is
 * rendered there with the player's own language.
 *
 * <p>The keys are declared as constants here so a typo cannot silently produce a missing translation.
 */
public record Msg(String key, List<String> args) {

    // errors
    public static final String ERROR_NEED_INPUT_OUTPUT = "wildcardencoder.msg.error.need_input_output";
    public static final String ERROR_FAILED = "wildcardencoder.msg.error.failed";
    public static final String ERROR_NO_MATCH = "wildcardencoder.msg.error.no_match";
    public static final String ERROR_UNKNOWN_ITEM = "wildcardencoder.msg.error.unknown_item";
    public static final String ERROR_INVALID_SNBT = "wildcardencoder.msg.bad_snbt";
    public static final String ERROR_EDIT_FAILED = "wildcardencoder.msg.error.edit_failed";
    public static final String ERROR_NOT_HOLDING = "wildcardencoder.msg.hold_pattern";
    public static final String ERROR_CANNOT_REENCODE = "wildcardencoder.msg.error.cannot_reencode";
    public static final String ERROR_NEEDS_BLANK_PATTERN = "wildcardencoder.msg.error.needs_blank_pattern";

    // warnings
    public static final String WARN_VARIANTS_CAPPED = "wildcardencoder.msg.warn.variants_capped";
    public static final String WARN_TRUNCATED = "wildcardencoder.msg.warn.truncated";
    public static final String WARN_OPTIONAL_LIMIT = "wildcardencoder.msg.warn.optional_limit";
    public static final String WARN_GROUP_NO_COMMON = "wildcardencoder.msg.warn.group_no_common";
    public static final String WARN_ITEM_NBT = "wildcardencoder.msg.warn.item_nbt_mismatch";
    public static final String WARN_UNKNOWN_TAG = "wildcardencoder.msg.warn.unknown_tag";
    public static final String WARN_NO_MOD_ITEMS = "wildcardencoder.msg.warn.no_mod_items";
    public static final String WARN_FAMILY_NEEDS_STAR = "wildcardencoder.msg.warn.family_needs_star";
    public static final String WARN_NO_FAMILY = "wildcardencoder.msg.warn.no_family";
    public static final String WARN_PATTERN_NEEDS_WILDCARD =
            "wildcardencoder.msg.warn.pattern_needs_wildcard";
    public static final String WARN_PATTERN_NO_MATCH = "wildcardencoder.msg.warn.pattern_no_match";

    private static final int MAX_KEY = 128;
    private static final int MAX_ARG = 512;

    public static Msg of(String key, Object... args) {
        List<String> values = new ArrayList<>(args.length);
        for (Object arg : args) {
            values.add(String.valueOf(arg));
        }
        return new Msg(key, List.copyOf(values));
    }

    public Component component() {
        return Component.translatable(key, args.toArray());
    }

    /** Best effort plain text (used by commands and log lines). */
    public String plain() {
        return component().getString();
    }

    // ------------------------------------------------------------------ codecs

    public void toBuffer(FriendlyByteBuf buffer) {
        buffer.writeUtf(key, MAX_KEY);
        buffer.writeVarInt(args.size());
        for (String arg : args) {
            buffer.writeUtf(arg, MAX_ARG);
        }
    }

    public static Msg fromBuffer(FriendlyByteBuf buffer) {
        String key = buffer.readUtf(MAX_KEY);
        int count = buffer.readVarInt();
        List<String> args = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            args.add(buffer.readUtf(MAX_ARG));
        }
        return new Msg(key, List.copyOf(args));
    }

    public CompoundTag write() {
        CompoundTag tag = new CompoundTag();
        tag.putString("k", key);
        ListTag list = new ListTag();
        for (String arg : args) {
            list.add(StringTag.valueOf(arg));
        }
        tag.put("a", list);
        return tag;
    }

    public static Msg read(CompoundTag tag) {
        if (tag == null || !tag.contains("k", Tag.TAG_STRING)) {
            return null;
        }
        List<String> args = new ArrayList<>();
        for (Tag element : tag.getList("a", Tag.TAG_STRING)) {
            args.add(element.getAsString());
        }
        return new Msg(tag.getString("k"), List.copyOf(args));
    }
}
