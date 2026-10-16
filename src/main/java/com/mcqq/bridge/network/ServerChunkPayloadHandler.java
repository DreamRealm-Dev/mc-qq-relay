package com.mcqq.bridge.network;

import com.mcqq.bridge.bridge.RelayServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServerChunkPayloadHandler {
    private static final long ASSEMBLY_TTL_MS = 60_000L;
    private static final int MAX_IMAGE_BYTES = 10 * 1024 * 1024;
    private static final Map<String, Assembly> ASSEMBLIES = new ConcurrentHashMap<>();

    private ServerChunkPayloadHandler() {}

    public static void handle(McImageChunkPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        if (payload.totalSize() <= 0 || payload.totalSize() > MAX_IMAGE_BYTES) {
            return;
        }
        if (payload.chunkCount() <= 0 || payload.chunkCount() > 64) {
            return;
        }
        if (payload.chunkIndex() < 0 || payload.chunkIndex() >= payload.chunkCount()) {
            return;
        }
        if (payload.data() == null || payload.data().length == 0) {
            return;
        }

        String key = player.getStringUUID() + ":" + payload.transferId();
        Assembly assembly = ASSEMBLIES.computeIfAbsent(key, k -> new Assembly(
                payload.fileName(), payload.totalSize(), payload.chunkCount()));
        if (!assembly.fileName.equals(payload.fileName())
                || assembly.totalSize != payload.totalSize()
                || assembly.chunkCount != payload.chunkCount()) {
            ASSEMBLIES.remove(key);
            return;
        }

        byte[] completeData = null;
        synchronized (assembly) {
            assembly.chunks[payload.chunkIndex()] = payload.data();
            assembly.updatedAt = System.currentTimeMillis();
            if (isComplete(assembly)) {
                ASSEMBLIES.remove(key);
                completeData = assemble(assembly);
            }
        }
        cleanup();

        if (completeData != null) {
            String token = RelayServer.enqueueImageMessage(
                    player.getGameProfile().getName(), completeData, assembly.fileName);
            if (token != null) {
                player.displayClientMessage(
                        Component.literal("[MCQQ] 图片已发送到 QQ"), false);
                String message = "[MCQQ] " + player.getGameProfile().getName() + " 发送了一张图片到 QQ";
                player.server.getPlayerList().broadcastSystemMessage(
                        Component.literal(message), false);
            } else {
                player.displayClientMessage(
                        Component.literal("[MCQQ] 图片发送失败（过大或服务器繁忙）"), false);
            }
        }
    }

    private static boolean isComplete(Assembly assembly) {
        for (byte[] chunk : assembly.chunks) {
            if (chunk == null) {
                return false;
            }
        }
        return true;
    }

    private static byte[] assemble(Assembly assembly) {
        try {
            byte[] data = new byte[assembly.totalSize];
            int pos = 0;
            for (byte[] chunk : assembly.chunks) {
                if (chunk == null || pos + chunk.length > data.length) {
                    return null;
                }
                System.arraycopy(chunk, 0, data, pos, chunk.length);
                pos += chunk.length;
            }
            return pos == data.length ? data : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static void cleanup() {
        long cutoff = System.currentTimeMillis() - ASSEMBLY_TTL_MS;
        ASSEMBLIES.entrySet().removeIf(e -> e.getValue().updatedAt < cutoff);
    }

    private static class Assembly {
        final String fileName;
        final int totalSize;
        final int chunkCount;
        final byte[][] chunks;
        volatile long updatedAt;

        Assembly(String fileName, int totalSize, int chunkCount) {
            this.fileName = fileName == null ? "" : fileName;
            this.totalSize = totalSize;
            this.chunkCount = chunkCount;
            this.chunks = new byte[chunkCount][];
            this.updatedAt = System.currentTimeMillis();
        }
    }
}
