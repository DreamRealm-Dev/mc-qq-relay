package com.mcqq.bridge.command;

import com.mcqq.bridge.bridge.RelayServer;
import com.mcqq.bridge.config.ModConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public class RelayCommand {
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("mcqq")
                .then(Commands.literal("test")
                        .executes(ctx -> {
                            String name = ctx.getSource().getDisplayName().getString();
                            RelayServer.enqueueTest("test from " + name);
                            ctx.getSource().sendSuccess(() -> Component.literal("已把测试消息放入队列，等待 AstrBot 拉取"), false);
                            return 1;
                        }))
                .then(Commands.literal("reload")
                        .requires(src -> src.hasPermission(2))
                        .executes(ctx -> {
                            ModConfig.load();
                            RelayServer.stop();
                            RelayServer.start();
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "配置已重载。relay=" + (RelayServer.hasServer() ? "已启动" : "未启动")
                                    + " | tls=" + ModConfig.tlsEnabled
                                    + " | port=" + ModConfig.serverPort), false);
                            return 1;
                        }))
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    "relay: " + (RelayServer.hasServer() ? "running" : "stopped") +
                                    " | tls: " + ModConfig.tlsEnabled +
                                    " | requireTls: " + ModConfig.requireTls +
                                    " | listen: " + ModConfig.serverHost + ":" + ModConfig.serverPort +
                                    " | secret: " + (ModConfig.sharedSecret.isEmpty() ? "未设置" : "已设置(长度" + ModConfig.sharedSecret.length() + ")")), false);
                            return 1;
                        }))
                .then(Commands.literal("show")
                        .then(Commands.argument("id", StringArgumentType.word())
                                .executes(ctx -> {
                                    String id = StringArgumentType.getString(ctx, "id");
                                    String full = RelayServer.getLongMessage(id);
                                    if (full == null) {
                                        ctx.getSource().sendFailure(Component.literal("该长消息不存在或已过期"));
                                        return 0;
                                    }
                                    if (ctx.getSource().getEntity() instanceof net.minecraft.server.level.ServerPlayer player) {
                                        player.displayClientMessage(Component.literal(full), false);
                                    } else {
                                        ctx.getSource().sendSuccess(() -> Component.literal(full), false);
                                    }
                                    return 1;
                                }))));
    }
}
