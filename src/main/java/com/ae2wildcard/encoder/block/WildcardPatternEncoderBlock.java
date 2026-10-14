package com.ae2wildcard.encoder.block;

import com.ae2wildcard.encoder.menu.WildcardPatternMenu;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;

/**
 * The wildcard pattern encoder: a workbench for the <b>rule set</b>. It opens the three tab editor and,
 * when the player presses "encode", produces exactly <b>one</b> wildcard pattern item - it never
 * enumerates combinations and never creates a batch of concrete AE2 patterns.
 */
public class WildcardPatternEncoderBlock extends Block {

    public WildcardPatternEncoderBlock() {
        super(BlockBehaviour.Properties.of()
                .mapColor(MapColor.METAL)
                .strength(3.5F, 8.0F)
                .sound(SoundType.METAL)
                .requiresCorrectToolForDrops());
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand,
            BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (windowId, inventory, p) -> WildcardPatternMenu.forDraft(windowId, inventory),
                    Component.translatable("block.wildcardencoder.wildcard_pattern_encoder"));
            serverPlayer.openMenu(provider);
            if (serverPlayer.containerMenu instanceof WildcardPatternMenu menu) {
                menu.sync(serverPlayer);
            }
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }
}
