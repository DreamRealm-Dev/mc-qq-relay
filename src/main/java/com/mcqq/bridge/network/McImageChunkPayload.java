package com.mcqq.bridge.network;

import com.mcqq.bridge.McQqRelay;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

public record McImageChunkPayload(
        UUID transferId,
        String fileName,
        int totalSize,
        int chunkIndex,
        int chunkCount,
        byte[] data
) implements CustomPacketPayload {
    public static final int MAX_CHUNK_BYTES = 1024 * 1024;

    public static final Type<McImageChunkPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(McQqRelay.MODID, "image_chunk"));

    public static final StreamCodec<FriendlyByteBuf, McImageChunkPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public McImageChunkPayload decode(FriendlyByteBuf buf) {
            UUID transferId = buf.readUUID();
            String fileName = buf.readUtf();
            int totalSize = buf.readVarInt();
            int chunkIndex = buf.readVarInt();
            int chunkCount = buf.readVarInt();
            byte[] data = buf.readByteArray(MAX_CHUNK_BYTES);
            return new McImageChunkPayload(transferId, fileName, totalSize, chunkIndex, chunkCount, data);
        }

        @Override
        public void encode(FriendlyByteBuf buf, McImageChunkPayload payload) {
            buf.writeUUID(payload.transferId());
            buf.writeUtf(payload.fileName());
            buf.writeVarInt(payload.totalSize());
            buf.writeVarInt(payload.chunkIndex());
            buf.writeVarInt(payload.chunkCount());
            buf.writeByteArray(payload.data());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
