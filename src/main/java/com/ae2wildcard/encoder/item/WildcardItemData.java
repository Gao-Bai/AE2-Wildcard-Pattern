package com.ae2wildcard.encoder.item;

import com.ae2wildcard.encoder.rule.RuleCodec;
import com.ae2wildcard.encoder.rule.WildcardRules;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * The single place that knows where the rule data lives inside the wildcard pattern's NBT.
 *
 * <pre>
 * { "we":  { we_ver:1, in:[...], out:[...], ex:[...], opt:{...} } }   rules (this class)
 * { "we_res": { ... } }                                              resolved recipe key (runtime)
 * </pre>
 */
public final class WildcardItemData {

    /** Sub tag holding the rule set. */
    public static final String RULES_KEY = "we";

    private WildcardItemData() {
    }

    public static CompoundTag rulesTag(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(RULES_KEY, CompoundTag.TAG_COMPOUND)) {
            return null;
        }
        return tag.getCompound(RULES_KEY);
    }

    public static WildcardRules rules(ItemStack stack) {
        return RuleCodec.read(rulesTag(stack));
    }

    public static void setRules(ItemStack stack, WildcardRules rules) {
        stack.getOrCreateTag().put(RULES_KEY, RuleCodec.write(rules));
    }

    public static boolean hasRules(ItemStack stack) {
        return RuleCodec.looksLikeRules(rulesTag(stack));
    }
}
