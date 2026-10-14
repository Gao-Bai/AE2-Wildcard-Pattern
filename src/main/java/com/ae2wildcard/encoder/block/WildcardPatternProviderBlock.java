package com.ae2wildcard.encoder.block;

import java.util.List;

import com.ae2wildcard.encoder.WildcardEncoderMod;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The wildcard pattern provider block.
 *
 * <p>Interaction is deliberately simple (no extra GUI in P2):
 * <ul>
 * <li>right-click holding a wildcard pattern -&gt; insert it into the provider;</li>
 * <li>sneak + right-click with an empty hand -&gt; take all patterns back;</li>
 * <li>right-click with an empty hand -&gt; report how many patterns / recipes are active.</li>
 * </ul>
 * The pattern slots are also a normal Forge item handler, so hoppers and pipes can fill them.
 */
public class WildcardPatternProviderBlock extends Block implements EntityBlock {

    public WildcardPatternProviderBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5F, 8.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new WildcardPatternProviderBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(Level level, BlockState state,
            BlockEntityType<T> type) {
        if (level.isClientSide) {
            return null;
        }
        return (BlockEntityTicker<T>) (lvl, pos, st, be) -> {
            if (be instanceof WildcardPatternProviderBlockEntity provider) {
                provider.serverTick();
            }
        };
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean isMoving) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof WildcardPatternProviderBlockEntity provider) {
            provider.dropContents();
        }
        super.onRemove(state, level, pos, newState, isMoving);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!(level.getBlockEntity(pos) instanceof WildcardPatternProviderBlockEntity provider)) {
            return super.use(state, level, pos, player, hand, hit);
        }
        if (!level.isClientSide) {
            ItemStack held = player.getItemInHand(hand);
            if (held.is(WildcardEncoderMod.WILDCARD_PATTERN.get())) {
                if (provider.insertPattern(held)) {
                    player.displayClientMessage(Component.translatable("wildcardencoder.msg.provider.inserted",
                            provider.patternCount(), provider.availablePatternCount(),
                            provider.returnCount()), true);
                } else {
                    player.displayClientMessage(Component.translatable("wildcardencoder.msg.provider.full"), true);
                }
            } else if (held.isEmpty() && player.isShiftKeyDown()) {
                List<ItemStack> ejected = provider.ejectPatterns();
                for (ItemStack stack : ejected) {
                    if (!player.getInventory().add(stack)) {
                        player.drop(stack, false);
                    }
                }
                player.displayClientMessage(
                        Component.translatable("wildcardencoder.msg.provider.ejected", ejected.size()), true);
            } else {
                player.displayClientMessage(Component.translatable("wildcardencoder.msg.provider.status",
                        provider.patternCount(), provider.availablePatternCount(),
                        provider.returnCount()), true);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
