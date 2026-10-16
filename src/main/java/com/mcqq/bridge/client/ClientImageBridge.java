package com.mcqq.bridge.client;

import com.mcqq.bridge.McQqRelay;
import com.mcqq.bridge.network.McImageChunkPayload;
import com.mcqq.bridge.network.McImagePayload;
import com.mcqq.bridge.network.McTextPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ChatScreen;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import com.mojang.blaze3d.platform.NativeImage;
import javax.imageio.ImageIO;
import java.awt.Frame;
import java.awt.GraphicsEnvironment;
import java.awt.FileDialog;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.Arrays;
import java.util.List;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@EventBusSubscriber(modid = McQqRelay.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.GAME)
public final class ClientImageBridge {
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final int MAX_SINGLE_PACKET_BYTES = 3_500_000;
    private static final int MAX_CHUNK_BYTES = 1024 * 1024;

    private ClientImageBridge() {}

    @SubscribeEvent
    public static void onRegisterClientCommands(RegisterClientCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("mcqqimg")
                .then(Commands.literal("file")
                        .then(Commands.argument("path", StringArgumentType.greedyString())
                                .executes(ctx -> sendFile(StringArgumentType.getString(ctx, "path")))))
                .then(Commands.literal("pick")
                        .executes(ctx -> {
                            pickImage();
                            return 1;
                        }))
                .then(Commands.literal("clipboard")
                        .executes(ctx -> sendClipboard()))
        );
        dispatcher.register(Commands.literal("mcqqtext")
                .executes(ctx -> sendClipboardText()));
    }

    private static final int BUTTON_Y = 2;
    private static final int BUTTON_W = 20;
    private static final int BUTTON_H = 20;
    private static final int BTN_FILE_X = 10;
    private static final int BTN_SHOT_X = 32;
    private static final int BTN_CLIP_X = 54;
    private static final int BTN_TEXT_X = 76;

    @SubscribeEvent
    public static void onScreenRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }
        var graphics = event.getGuiGraphics();
        drawButton(graphics, BTN_FILE_X, "图");
        drawButton(graphics, BTN_SHOT_X, "截");
        drawButton(graphics, BTN_CLIP_X, "贴");
        drawButton(graphics, BTN_TEXT_X, "文");
    }

    private static void drawButton(GuiGraphics graphics, int x, String label) {
        graphics.fill(x, BUTTON_Y, x + BUTTON_W, BUTTON_Y + BUTTON_H, 0xFF000000);
        graphics.fill(x + 1, BUTTON_Y + 1, x + BUTTON_W - 1, BUTTON_Y + BUTTON_H - 1, 0xFF808080);
        graphics.drawString(Minecraft.getInstance().font, label, x + 6, BUTTON_Y + 6, 0xFFFFFFFF);
    }

    @SubscribeEvent
    public static void onMousePressed(ScreenEvent.MouseButtonPressed.Pre event) {
        if (!(event.getScreen() instanceof ChatScreen)) {
            return;
        }
        if (event.getButton() != 0) {
            return;
        }
        double x = event.getMouseX();
        double y = event.getMouseY();
        if (y < BUTTON_Y || y >= BUTTON_Y + BUTTON_H) {
            return;
        }
        if (x >= BTN_FILE_X && x < BTN_FILE_X + BUTTON_W) {
            event.setCanceled(true);
            pickImage();
        } else if (x >= BTN_SHOT_X && x < BTN_SHOT_X + BUTTON_W) {
            event.setCanceled(true);
            sendScreenshot();
        } else if (x >= BTN_CLIP_X && x < BTN_CLIP_X + BUTTON_W) {
            event.setCanceled(true);
            sendClipboard();
        } else if (x >= BTN_TEXT_X && x < BTN_TEXT_X + BUTTON_W) {
            event.setCanceled(true);
            sendClipboardText();
        }
    }

    private static void pickImage() {
        Minecraft mc = Minecraft.getInstance();
        if (GraphicsEnvironment.isHeadless()) {
            feedback("当前环境不支持文件选择器，请使用 /mcqqimg file <路径>");
            return;
        }
        try {
            Frame frame = new Frame();
            frame.setVisible(true);
            frame.setAlwaysOnTop(true);
            FileDialog dialog = new FileDialog(frame, "选择要发送到 QQ 的图片", FileDialog.LOAD);
            dialog.setFile("*.png;*.jpg;*.jpeg;*.gif;*.webp;*.bmp");
            dialog.setVisible(true);
            String dir = dialog.getDirectory();
            String file = dialog.getFile();
            frame.dispose();
            if (dir == null || file == null) {
                return;
            }
            Path path = Path.of(dir, file);
            mc.execute(() -> sendFile(path.toString()));
        } catch (Throwable t) {
            feedback("当前环境不支持文件选择器，请使用 /mcqqimg file <路径>（" + t.getMessage() + "）");
        }
    }

    private static void sendImageData(String fileName, byte[] data) {
        if (data.length <= MAX_SINGLE_PACKET_BYTES) {
            PacketDistributor.sendToServer(new McImagePayload(fileName, data));
            return;
        }
        // 超过 NEBL/原版 4MB 单包上限时，拆成 1MB 分片发送。
        UUID transferId = UUID.randomUUID();
        int chunkCount = (data.length + MAX_CHUNK_BYTES - 1) / MAX_CHUNK_BYTES;
        for (int i = 0; i < chunkCount; i++) {
            int from = i * MAX_CHUNK_BYTES;
            int to = Math.min(data.length, from + MAX_CHUNK_BYTES);
            byte[] chunk = Arrays.copyOfRange(data, from, to);
            PacketDistributor.sendToServer(new McImageChunkPayload(
                    transferId, fileName, data.length, i, chunkCount, chunk));
        }
    }

    private static void sendScreenshot() {
        try {
            NativeImage image = Screenshot.takeScreenshot(Minecraft.getInstance().getMainRenderTarget());
            byte[] data = image.asByteArray();
            image.close();
            if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
                feedback("截图生成失败或超过 10MB");
                return;
            }
            sendImageData("screenshot.png", data);
            feedback(data.length > MAX_SINGLE_PACKET_BYTES
                    ? "截图较大，已分片发送，正在转发 QQ…"
                    : "截图已发送到服务器，正在转发 QQ…");
        } catch (Throwable t) {
            feedback("截图失败: " + t.getMessage());
        }
    }

    private static int sendFile(String path) {
        try {
            Path p = Path.of(path);
            if (!Files.exists(p) || !Files.isRegularFile(p)) {
                feedback("文件不存在: " + path);
                return 0;
            }
            byte[] data = Files.readAllBytes(p);
            if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
                feedback("图片为空或超过 10MB");
                return 0;
            }
            String fileName = p.getFileName().toString();
            sendImageData(fileName, data);
            feedback(data.length > MAX_SINGLE_PACKET_BYTES
                    ? "图片较大，已分片发送，正在转发 QQ…"
                    : "图片已发送到服务器，正在转发 QQ…");
            return 1;
        } catch (Exception e) {
            feedback("发送图片失败: " + e.getMessage());
            return 0;
        }
    }

    private static int sendClipboard() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            if (contents == null) {
                feedback("剪贴板为空");
                return 0;
            }
            if (contents.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                BufferedImage image = (BufferedImage) contents.getTransferData(DataFlavor.imageFlavor);
                if (image == null) {
                    feedback("剪贴板中没有图片");
                    return 0;
                }
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                ImageIO.write(image, "png", baos);
                byte[] data = baos.toByteArray();
                if (data.length == 0 || data.length > MAX_IMAGE_BYTES) {
                    feedback("剪贴板图片为空或超过 10MB");
                    return 0;
                }
                sendImageData("clipboard.png", data);
                feedback(data.length > MAX_SINGLE_PACKET_BYTES
                        ? "剪贴板图片较大，已分片发送，正在转发 QQ…"
                        : "剪贴板图片已发送到服务器，正在转发 QQ…");
                return 1;
            }
            if (contents.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                List<?> files = (List<?>) contents.getTransferData(DataFlavor.javaFileListFlavor);
                for (Object obj : files) {
                    if (obj instanceof File file && isImageFile(file.getName())) {
                        return sendFile(file.getAbsolutePath());
                    }
                }
                feedback("剪贴板中的文件不是图片");
                return 0;
            }
            feedback("剪贴板中没有图片或图片文件");
            return 0;
        } catch (Throwable t) {
            feedback("读取剪贴板失败: " + t.getMessage());
            return 0;
        }
    }

    private static int sendClipboardText() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            Transferable contents = clipboard.getContents(null);
            if (contents == null || !contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                feedback("剪贴板中没有文本");
                return 0;
            }
            String text = String.valueOf(contents.getTransferData(DataFlavor.stringFlavor));
            if (text == null || text.isBlank()) {
                feedback("剪贴板中没有文本");
                return 0;
            }
            if (text.length() > 30000) {
                text = text.substring(0, 30000);
                feedback("剪贴板文本超过 30000 字，已截断发送");
            }
            PacketDistributor.sendToServer(new McTextPayload(text));
            feedback("剪贴板文本已发送到服务器，正在转发 QQ…");
            return 1;
        } catch (Throwable t) {
            feedback("读取剪贴板文本失败: " + t.getMessage());
            return 0;
        }
    }

    private static boolean isImageFile(String name) {
        if (name == null) return false;
        String lower = name.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")
                || lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }

    private static void feedback(String message) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.displayClientMessage(Component.literal("[MCQQ] " + message), false);
        }
    }
}
