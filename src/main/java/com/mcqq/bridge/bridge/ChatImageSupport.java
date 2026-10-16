package com.mcqq.bridge.bridge;

import com.mcqq.bridge.McQqRelay;
import com.mcqq.bridge.config.ModConfig;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Optional integration with "chat image" / ChatImages mod.
 *
 * Uses reflection so mc-qq-relay still works when ChatImages is not installed.
 * If ChatImages is not present, CI{...} codes are replaced with [图片] instead of
 * showing raw text to players.
 */
final class ChatImageSupport {
    private static final Pattern CI_PATTERN = Pattern.compile("CI\\{.*?\\}");

    private static boolean availabilityChecked;
    private static boolean available;
    private static Object showImageAction;
    private static Object viewImageAction;

    private ChatImageSupport() {}

    static boolean isAvailable() {
        if (!availabilityChecked) {
            availabilityChecked = true;
            try {
                Class<?> actions = Class.forName("org.eu.hanana.reimu.chatimage.core.Actions");
                showImageAction = actions.getMethod("getShowImage").invoke(null);
                viewImageAction = actions.getMethod("getViewImage").invoke(null);
                available = true;
            } catch (Throwable t) {
                available = false;
                McQqRelay.LOGGER.debug("[McQqRelay] ChatImages not available: {}", t.toString());
            }
        }
        return available;
    }

    static Component buildQqComponent(String name, String text) {
        MutableComponent root = Component.literal("");
        root.append(Component.literal(ModConfig.qqPrefix + " ").withStyle(ChatFormatting.DARK_AQUA));
        root.append(Component.literal(name).withStyle(ChatFormatting.AQUA));
        root.append(Component.literal(": "));
        appendText(root, text);
        return root;
    }

    private static void appendText(MutableComponent root, String text) {
        Matcher m = CI_PATTERN.matcher(text);
        int last = 0;
        boolean useChatImage = ModConfig.chatimageEnabled && isAvailable();
        while (m.find()) {
            if (m.start() > last) {
                root.append(Component.literal(text.substring(last, m.start())));
            }
            String code = m.group();
            if (useChatImage) {
                root.append(buildImageComponent(code));
            } else {
                root.append(Component.literal("[图片]"));
            }
            last = m.end();
        }
        if (last < text.length()) {
            root.append(Component.literal(text.substring(last)));
        }
    }

    @SuppressWarnings("unchecked")
    private static Component buildImageComponent(String ciCode) {
        try {
            if (showImageAction == null || viewImageAction == null) {
                return Component.literal("[图片]");
            }
            HoverEvent hover = new HoverEvent(
                    (HoverEvent.Action<Component>) showImageAction,
                    Component.literal(ciCode));
            ClickEvent click = new ClickEvent((ClickEvent.Action) viewImageAction, ciCode);
            Style style = Style.EMPTY.withHoverEvent(hover).withClickEvent(click);
            return Component.literal("[图片]").withStyle(style);
        } catch (Throwable t) {
            McQqRelay.LOGGER.warn("[McQqRelay] Failed to build ChatImages component: {}", t.toString());
            return Component.literal("[图片]");
        }
    }
}
