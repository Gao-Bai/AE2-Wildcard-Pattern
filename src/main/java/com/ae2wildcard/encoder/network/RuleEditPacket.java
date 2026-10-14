package com.ae2wildcard.encoder.network;

import java.util.function.Supplier;

import com.ae2wildcard.encoder.menu.WildcardPatternMenu;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

/**
 * Client -&gt; server: one rule edit (add/set/remove an entry, options, import, encode). The server
 * validates everything, writes the canonical data and answers with a {@link RuleSyncPacket}.
 */
public record RuleEditPacket(int action, int index, CompoundTag payload) {

    private static final int MAX_SNBT = 32767;

    public static void encode(RuleEditPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.action);
        buffer.writeVarInt(packet.index);
        buffer.writeNbt(packet.payload);
    }

    public static RuleEditPacket decode(FriendlyByteBuf buffer) {
        return new RuleEditPacket(buffer.readVarInt(), buffer.readVarInt(), buffer.readNbt());
    }

    public static void handle(RuleEditPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            ServerPlayer player = context.getSender();
            if (player == null) {
                return;
            }
            if (!(player.containerMenu instanceof WildcardPatternMenu menu)) {
                return;
            }
            CompoundTag payload = packet.payload;
            if (payload != null && payload.contains("snbt") && payload.getString("snbt").length() > MAX_SNBT) {
                return;
            }
            menu.applyEdit(player, packet.action, packet.index, payload);
        });
        context.setPacketHandled(true);
    }
}
