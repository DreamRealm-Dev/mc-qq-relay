package com.mcqq.bridge;

import com.mcqq.bridge.bridge.RelayServer;
import com.mcqq.bridge.network.McImageChunkPayload;
import com.mcqq.bridge.network.McImagePayload;
import com.mcqq.bridge.network.McTextPayload;
import com.mcqq.bridge.network.ServerChunkPayloadHandler;
import com.mcqq.bridge.network.ServerPayloadHandler;
import com.mcqq.bridge.network.ServerTextPayloadHandler;
import com.mcqq.bridge.command.RelayCommand;
import com.mcqq.bridge.config.ModConfig;
import com.mojang.brigadier.ParseResults;
import com.mojang.logging.LogUtils;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import java.util.regex.Pattern;
import org.slf4j.Logger;

@Mod(McQqRelay.MODID)
public class McQqRelay {
    public static final String MODID = "mcqq";
    public static final Logger LOGGER = LogUtils.getLogger();
    private static final Pattern LOCAL_CI_PATTERN = Pattern.compile("\\[\\[CICode,url=file:///.*?\\]\\]");

    public McQqRelay(IEventBus modEventBus) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerPayloads);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(final FMLCommonSetupEvent event) {}

    private void registerPayloads(final RegisterPayloadHandlersEvent event) {
        var registrar = event.registrar("1");
        registrar.playToServer(McImagePayload.TYPE, McImagePayload.STREAM_CODEC, ServerPayloadHandler::handle);
        registrar.playToServer(McImageChunkPayload.TYPE, McImageChunkPayload.STREAM_CODEC, ServerChunkPayloadHandler::handle);
        registrar.playToServer(McTextPayload.TYPE, McTextPayload.STREAM_CODEC, ServerTextPayloadHandler::handle);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        ModConfig.load();
        RelayServer.start();
        LOGGER.info("[McQqRelay] relay server state: enabled={}, tls={}, requireTls={}, listen={}:{}",
                RelayServer.hasServer(), ModConfig.tlsEnabled, ModConfig.requireTls,
                ModConfig.serverHost, ModConfig.serverPort);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        RelayServer.stop();
        LOGGER.info("[McQqRelay] relay stopped.");
    }

    @SubscribeEvent
    public void onChat(ServerChatEvent event) {
        if (event.isCanceled()) {
            return;
        }
        ServerPlayer player = event.getPlayer();
        String name = player.getGameProfile().getName();
        String msg = event.getRawText();
        if (msg == null || msg.isEmpty()) {
            return;
        }
        // 本地 file:/// CICode 只有发送者自己的客户端能访问，转发到 QQ 只会变成无意义的本地路径。
        // 这里替换成占位文本，避免把 C:\Users\... 这类路径发到 QQ 群。
        msg = LOCAL_CI_PATTERN.matcher(msg).replaceAll("[本地图片]");
        RelayServer.enqueuePlayerMessage(name, msg);
    }

    @SubscribeEvent
    public void onPlayerJoin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() == null || event.getEntity().getGameProfile() == null) {
            return;
        }
        RelayServer.enqueueSystemMessage(event.getEntity().getGameProfile().getName() + " 加入了游戏");
    }

    @SubscribeEvent
    public void onPlayerLeave(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() == null || event.getEntity().getGameProfile() == null) {
            return;
        }
        RelayServer.enqueueSystemMessage(event.getEntity().getGameProfile().getName() + " 离开了游戏");
    }

    @SubscribeEvent
    public void onCommand(CommandEvent event) {
        if (!ModConfig.broadcastSystemMcToQq) {
            return;
        }
        ParseResults<CommandSourceStack> parse = event.getParseResults();
        if (parse == null || parse.getReader() == null) {
            return;
        }
        String cmd = parse.getReader().getString();
        if (cmd == null) {
            return;
        }
        String trimmed = cmd.trim();
        String lower = trimmed.toLowerCase();
        // 把 /say 这类服务端“系统公告”也作为可选的系统消息转发。
        if (lower.startsWith("say ") || lower.equals("say")
                || lower.startsWith("/say ") || lower.equals("/say")) {
            int space = trimmed.indexOf(' ');
            if (space >= 0 && space + 1 < trimmed.length()) {
                RelayServer.enqueueSystemMessage(trimmed.substring(space + 1));
            } else if (trimmed.equalsIgnoreCase("/say") || trimmed.equalsIgnoreCase("say")) {
                RelayServer.enqueueSystemMessage("");
            }
        }
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        RelayCommand.register(event.getDispatcher());
    }
}
