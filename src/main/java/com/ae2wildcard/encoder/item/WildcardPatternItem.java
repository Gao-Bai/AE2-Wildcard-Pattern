package com.ae2wildcard.encoder.item;

import java.util.List;

import com.ae2wildcard.encoder.WildcardEncoderMod;
import com.ae2wildcard.encoder.craft.PatternInstantiator;
import com.ae2wildcard.encoder.craft.PatternText;
import com.ae2wildcard.encoder.menu.WildcardPatternMenu;
import com.ae2wildcard.encoder.rule.WildcardRules;
import com.ae2wildcard.encoder.rule.WildcardRules.Input;
import com.ae2wildcard.encoder.rule.WildcardRules.Output;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

/**
 * The wildcard pattern itself: a plain mod item (deliberately <b>not</b> an AE2 encoded pattern), so
 * AE2's own terminal can neither create nor edit it. Right-clicking opens the rule editor; the runtime
 * side (our pattern provider) turns the saved rules into AE2 patterns in memory.
 */
public class WildcardPatternItem extends Item {

    public WildcardPatternItem() {
        super(new Item.Properties().stacksTo(1).rarity(Rarity.UNCOMMON));
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (windowId, inventory, p) -> WildcardPatternMenu.forHeldItem(windowId, inventory, p, hand),
                    stack.getHoverName());
            serverPlayer.openMenu(provider);
            if (serverPlayer.containerMenu instanceof WildcardPatternMenu menu) {
                menu.sync(serverPlayer);
            }
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        WildcardRules rules = WildcardItemData.rules(stack);
        tooltip.add(Component.translatable("item.wildcardencoder.wildcard_pattern.tip").withStyle(ChatFormatting.GRAY));

        if (!rules.isComplete()) {
            tooltip.add(Component.translatable("wildcardencoder.tooltip.incomplete").withStyle(ChatFormatting.RED));
            return;
        }

        tooltip.add(Component.translatable("wildcardencoder.tooltip.inputs").withStyle(ChatFormatting.AQUA));
        for (Input input : rules.inputs()) {
            tooltip.add(Component.literal("  " + PatternText.plainInput(input)).withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("wildcardencoder.tooltip.outputs").withStyle(ChatFormatting.GOLD));
        for (Output output : rules.outputs()) {
            tooltip.add(Component.literal("  " + PatternText.plainOutput(output)).withStyle(ChatFormatting.GRAY));
        }
        if (!rules.excludes().isEmpty()) {
            tooltip.add(Component.translatable("wildcardencoder.tooltip.excludes", rules.excludes().size())
                    .withStyle(ChatFormatting.RED));
        }

        tooltip.add(Component.literal("Wildcard Encoder v" + WildcardEncoderMod.version())
                .withStyle(ChatFormatting.DARK_GRAY));

        PatternInstantiator.Result result = PatternInstantiator.instantiate(rules);
        if (result.ok()) {
            tooltip.add(Component.translatable("wildcardencoder.tooltip.instances", result.patterns().size(),
                    result.excluded()).withStyle(ChatFormatting.DARK_GREEN));
        } else {
            tooltip.add(result.error().component().copy().withStyle(ChatFormatting.RED));
        }
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        // a configured wildcard pattern glows so it stands out in the inventory
        return WildcardItemData.hasRules(stack) && WildcardItemData.rules(stack).isComplete();
    }

    @Override
    public Component getName(ItemStack stack) {
        WildcardRules rules = WildcardItemData.rules(stack);
        if (!rules.isComplete()) {
            return super.getName(stack);
        }
        return Component.translatable("item.wildcardencoder.wildcard_pattern.named",
                super.getName(stack), PatternText.plainSummary(rules));
    }
}
