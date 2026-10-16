package com.mcqq.bridge.network;

import com.mcqq.bridge.McQqRelay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record McTextPayload(String text) implements CustomPacketPayload {
    public static final Type<McTextPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McQqRelay.MODID, "text_upload"));

    public static final StreamCodec<FriendlyByteBuf, McTextPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public McTextPayload decode(FriendlyByteBuf buf) {
            return new McTextPayload(buf.readUtf(32767));
        }

        @Override
        public void encode(FriendlyByteBuf buf, McTextPayload payload) {
            buf.writeUtf(payload.text(), 32767);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
