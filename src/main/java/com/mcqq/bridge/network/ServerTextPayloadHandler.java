package com.mcqq.bridge.network;

import com.mcqq.bridge.bridge.RelayServer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerTextPayloadHandler {
    private ServerTextPayloadHandler() {}

    public static void handle(McTextPayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        String text = payload.text();
        if (text == null || text.isBlank()) {
            return;
        }
        // 去掉首尾空白，保留内部换行/长文本，绕过原版聊天输入框的长度限制。
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            return;
        }
        RelayServer.enqueuePlayerMessage(player.getGameProfile().getName(), trimmed);
        player.displayClientMessage(Component.literal("[MCQQ] 文本已发送到 QQ"), false);
    }
}
