package com.mimicenzymes.litematicafiller.network;

import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Local stand-in for Carpet UAS Addition's held-shulker open payload
 * (carpet-uas-addition:shulker_box_open). The real payload carries no data,
 * so this unit record is wire-compatible; it is only registered when the
 * Carpet UAS client mod itself is absent so the mod can still talk to a
 * server that runs the server-side extension.
 */
public record CarpetUasOpenPayload() implements CustomPacketPayload {
    public static final CarpetUasOpenPayload INSTANCE = new CarpetUasOpenPayload();
    public static final CustomPacketPayload.Type<CarpetUasOpenPayload> ID =
            new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("carpet-uas-addition", "shulker_box_open"));
    public static final StreamCodec<net.minecraft.network.RegistryFriendlyByteBuf, CarpetUasOpenPayload> CODEC =
            StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return ID;
    }
}
