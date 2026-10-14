package com.ae2wildcard.encoder.network;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import com.ae2wildcard.encoder.craft.Msg;
import com.ae2wildcard.encoder.rule.RuleCodec;
import com.ae2wildcard.encoder.rule.WildcardRules;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.network.NetworkEvent;

/**
 * Server -&gt; client: the authoritative rule snapshot (plus the computed preview counts). The client
 * keeps no authority at all, so a desynced or modified client can never store broken rules.
 */
public record RuleSyncPacket(int revision, boolean draftMode, WildcardRules rules, int previewCount,
        int excludedCount, boolean truncated, Msg error, List<Msg> warnings, List<String> samples) {

    private static final int MAX_SAMPLES = 16;

    public static void encode(RuleSyncPacket packet, FriendlyByteBuf buffer) {
        buffer.writeVarInt(packet.revision);
        buffer.writeBoolean(packet.draftMode);
        buffer.writeNbt(RuleCodec.write(packet.rules));
        buffer.writeVarInt(packet.previewCount);
        buffer.writeVarInt(packet.excludedCount);
        buffer.writeBoolean(packet.truncated);
        buffer.writeBoolean(packet.error != null);
        if (packet.error != null) {
            packet.error.toBuffer(buffer);
        }
        List<Msg> warnings = packet.warnings == null ? List.of() : packet.warnings;
        buffer.writeVarInt(Math.min(warnings.size(), MAX_SAMPLES));
        for (int i = 0; i < Math.min(warnings.size(), MAX_SAMPLES); i++) {
            warnings.get(i).toBuffer(buffer);
        }
        List<String> samples = packet.samples == null ? List.of() : packet.samples;
        buffer.writeVarInt(Math.min(samples.size(), MAX_SAMPLES));
        for (int i = 0; i < Math.min(samples.size(), MAX_SAMPLES); i++) {
            buffer.writeUtf(samples.get(i), 512);
        }
    }

    public static RuleSyncPacket decode(FriendlyByteBuf buffer) {
        int revision = buffer.readVarInt();
        boolean draft = buffer.readBoolean();
        WildcardRules rules = RuleCodec.read(buffer.readNbt());
        int preview = buffer.readVarInt();
        int excluded = buffer.readVarInt();
        boolean truncated = buffer.readBoolean();
        Msg error = buffer.readBoolean() ? Msg.fromBuffer(buffer) : null;
        int warningCount = buffer.readVarInt();
        List<Msg> warnings = new ArrayList<>(warningCount);
        for (int i = 0; i < warningCount; i++) {
            warnings.add(Msg.fromBuffer(buffer));
        }
        int count = buffer.readVarInt();
        List<String> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            samples.add(buffer.readUtf(512));
        }
        return new RuleSyncPacket(revision, draft, rules, preview, excluded, truncated, error, warnings, samples);
    }

    public static void handle(RuleSyncPacket packet, Supplier<NetworkEvent.Context> contextSupplier) {
        NetworkEvent.Context context = contextSupplier.get();
        context.enqueueWork(() -> {
            // Only touch client classes on the client, so the server never loads them.
            if (FMLEnvironment.dist == Dist.CLIENT) {
                com.ae2wildcard.encoder.client.ClientRuleSync.handle(packet);
            }
        });
        context.setPacketHandled(true);
    }

    /** Convenience for the empty state. */
    public static RuleSyncPacket empty(int revision) {
        return new RuleSyncPacket(revision, false, WildcardRules.EMPTY, 0, 0, false, null, List.of(), List.of());
    }
}
