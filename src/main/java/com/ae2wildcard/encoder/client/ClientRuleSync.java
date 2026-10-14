package com.ae2wildcard.encoder.client;

import com.ae2wildcard.encoder.network.RuleSyncPacket;

import net.minecraft.client.Minecraft;

/**
 * Client only entry point for {@link RuleSyncPacket}. Referenced exclusively from a client-only branch
 * so a dedicated server never loads it.
 */
public final class ClientRuleSync {

    private ClientRuleSync() {
    }

    public static void handle(RuleSyncPacket packet) {
        if (Minecraft.getInstance().screen instanceof WildcardPatternScreen screen) {
            screen.applySync(packet);
        }
    }
}
