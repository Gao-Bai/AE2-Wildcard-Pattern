package com.ae2wildcard.encoder.client;

import com.ae2wildcard.encoder.WildcardEncoderMod;

import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;

/**
 * Binds the rule editor screen to its menu type. Forge's patched {@code MenuScreens.register} is public
 * in 1.20.1, so the classic client-setup route works.
 */
@Mod.EventBusSubscriber(modid = WildcardEncoderMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    private ClientSetup() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        event.enqueueWork(() -> MenuScreens.register(WildcardEncoderMod.WILDCARD_PATTERN_MENU.get(),
                WildcardPatternScreen::new));
    }
}
