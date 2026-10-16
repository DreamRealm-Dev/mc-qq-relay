package com.mcqq.bridge.network;

import com.mcqq.bridge.McQqRelay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record McImagePayload(String fileName, byte[] data) implements CustomPacketPayload {
    public static final Type<McImagePayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McQqRelay.MODID, "image_upload"));

    public static final StreamCodec<FriendlyByteBuf, McImagePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public McImagePayload decode(FriendlyByteBuf buf) {
            return new McImagePayload(buf.readUtf(), buf.readByteArray(10 * 1024 * 1024));
        }

        @Override
        public void encode(FriendlyByteBuf buf, McImagePayload payload) {
            buf.writeUtf(payload.fileName());
            buf.writeByteArray(payload.data());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
