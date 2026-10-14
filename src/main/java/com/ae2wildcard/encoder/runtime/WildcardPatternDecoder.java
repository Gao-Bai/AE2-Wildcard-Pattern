package com.ae2wildcard.encoder.runtime;

import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.ResolvedPattern;

import appeng.api.crafting.IPatternDetails;
import appeng.api.crafting.IPatternDetailsDecoder;
import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.stacks.AEItemKey;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Registers the wildcard pattern with AE2 through its <b>public</b> decoder API
 * ({@link PatternDetailsHelper#registerDecoder}), so no mixin is needed.
 *
 * <p>Two cases:
 * <ul>
 * <li>a <b>resolved definition key</b> (our item + {@code we_res}) decodes into exactly that one
 * recipe - this is what makes crafting jobs survive a world reload;</li>
 * <li>a plain wildcard pattern item decodes to {@code null}, because one item expands to many recipes
 * and must never be mistaken for a single recipe (that expansion is the job of our own provider).</li>
 * </ul>
 */
public final class WildcardPatternDecoder implements IPatternDetailsDecoder {

    public static final WildcardPatternDecoder INSTANCE = new WildcardPatternDecoder();

    private WildcardPatternDecoder() {
    }

    public static void register() {
        PatternDetailsHelper.registerDecoder(INSTANCE);
    }

    @Override
    public boolean isEncodedPattern(ItemStack stack) {
        return !stack.isEmpty() && stack.is(WildcardEncoderMod.WILDCARD_PATTERN.get());
    }

    @Override
    public IPatternDetails decodePattern(AEItemKey what, Level level) {
        if (what == null || what.getItem() != WildcardEncoderMod.WILDCARD_PATTERN.get()) {
            return null;
        }
        ResolvedPattern pattern = ResolvedPatternKey.read(what.getTag());
        return pattern == null ? null : new WildcardPatternDetails(pattern);
    }

    @Override
    public IPatternDetails decodePattern(ItemStack stack, Level level, boolean autoRecovery) {
        if (stack.isEmpty() || !stack.is(WildcardEncoderMod.WILDCARD_PATTERN.get())) {
            return null;
        }
        ResolvedPattern pattern = ResolvedPatternKey.read(stack.getTag());
        return pattern == null ? null : new WildcardPatternDetails(pattern);
    }
}
