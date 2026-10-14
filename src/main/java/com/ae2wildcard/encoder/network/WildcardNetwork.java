package com.ae2wildcard.encoder.network;

import com.ae2wildcard.encoder.WildcardEncoderMod;

import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * Networking: rule edits up, authoritative rule snapshots down. Everything else (labels, textures)
 * is plain data.
 */
public final class WildcardNetwork {

    private static final String PROTOCOL_VERSION = "2";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(WildcardEncoderMod.MODID, "main"),
            () -> PROTOCOL_VERSION,
            PROTOCOL_VERSION::equals,
            PROTOCOL_VERSION::equals);

    private WildcardNetwork() {
    }

    public static void register() {
        int id = 0;
        CHANNEL.registerMessage(id++, RuleEditPacket.class,
                RuleEditPacket::encode, RuleEditPacket::decode, RuleEditPacket::handle);
        CHANNEL.registerMessage(id++, RuleSyncPacket.class,
                RuleSyncPacket::encode, RuleSyncPacket::decode, RuleSyncPacket::handle);
    }
}
