package com.mcqq.bridge.network;

import com.mcqq.bridge.McQqRelay;
import com.mcqq.bridge.bridge.RelayServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

public final class ServerPayloadHandler {
    private ServerPayloadHandler() {}

    public static void handle(McImagePayload payload, IPayloadContext context) {
        ServerPlayer player = (ServerPlayer) context.player();
        String name = player.getGameProfile().getName();
        String token = RelayServer.enqueueImageMessage(name, payload.data(), payload.fileName());
        if (token != null) {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("[MCQQ] 图片已发送到 QQ"), false);
            // 图片直链由 AstrBot 在成功转发到 QQ 后回传（避免自签名 HTTPS 证书导致客户端加载失败）。
            // 这里只广播文字，不直接生成 CICode。
            String message = "[MCQQ] " + name + " 发送了一张图片到 QQ";
            player.server.getPlayerList().broadcastSystemMessage(
                    net.minecraft.network.chat.Component.literal(message), false);
        } else {
            player.displayClientMessage(net.minecraft.network.chat.Component.literal("[MCQQ] 图片发送失败（过大或服务器繁忙）"), false);
        }
    }
}
