package com.mcqq.bridge.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.mcqq.bridge.McQqRelay;
import com.mcqq.bridge.config.ModConfig;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.MinecraftServer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class RelayServer {
    private static final Gson GSON = new GsonBuilder().create();
    private static HttpServer server;
    private static ExecutorService executor;
    private static final LinkedBlockingQueue<OutboundMessage> OUTBOX = new LinkedBlockingQueue<>();
    private static final Map<String, Long> NONCES = new ConcurrentHashMap<>();
    private static final long MAX_NONCE_ENTRIES = 8192;
    private static final Map<String, TempImage> IMAGES = new ConcurrentHashMap<>();
    private static final long IMAGE_TTL_MS = 120_000L;
    private static final int MAX_IMAGES = 64;
    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;
    private static final int LONG_MESSAGE_THRESHOLD = 200;
    private static final int LONG_MESSAGE_PREVIEW = 80;
    private static final long LONG_MESSAGE_TTL_MS = 10 * 60 * 1000L;
    private static final int MAX_LONG_MESSAGES = 64;
    private static final Map<String, LongMessage> LONG_MESSAGES = new ConcurrentHashMap<>();

    private RelayServer() {}

    public static void start() {
        stop();
        if (!ModConfig.validServerSecret()) {
            McQqRelay.LOGGER.error("[McQqRelay] sharedSecret is too short (min 16 chars). Refusing to start insecure bridge.");
            return;
        }

        String host = ModConfig.serverHost == null || ModConfig.serverHost.isBlank() ? "0.0.0.0" : ModConfig.serverHost;
        int port = ModConfig.serverPort;
        InetSocketAddress addr = new InetSocketAddress(host, port);

        try {
            if (ModConfig.tlsEnabled) {
                if (!ModConfig.requireTls) {
                    McQqRelay.LOGGER.warn("[McQqRelay] TLS is enabled but requireTls is false; continuing as TLS server.");
                }
                SSLContext ssl = loadSslContext();
                HttpsServer https = HttpsServer.create(addr, 0);
                https.setHttpsConfigurator(new HttpsConfigurator(ssl));
                https.createContext("/v1/poll", RelayServer::handlePoll);
                https.createContext("/v1/send", RelayServer::handleSend);
                https.createContext("/v1/ping", RelayServer::handlePing);
                https.createContext("/v1/image/", RelayServer::handleImage);
                executor = Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "mcqq-relay-http");
                    t.setDaemon(true);
                    return t;
                });
                https.setExecutor(executor);
                https.start();
                server = https;
                McQqRelay.LOGGER.info("[McQqRelay] HTTPS relay listening on {}:{}. AstrBot should connect to https://<public-ip-or-domain>:<public-port>",
                        host, port);
            } else {
                if (ModConfig.requireTls) {
                    McQqRelay.LOGGER.error("[McQqRelay] requireTls=true but tlsEnabled=false. Refusing to start insecure bridge.");
                    return;
                }
                McQqRelay.LOGGER.warn("[McQqRelay] TLS is DISABLED. This is insecure and should only be used on a trusted LAN.");
                HttpServer plain = HttpServer.create(addr, 0);
                plain.createContext("/v1/poll", RelayServer::handlePoll);
                plain.createContext("/v1/send", RelayServer::handleSend);
                plain.createContext("/v1/ping", RelayServer::handlePing);
                plain.createContext("/v1/image/", RelayServer::handleImage);
                executor = Executors.newCachedThreadPool(r -> {
                    Thread t = new Thread(r, "mcqq-relay-plain");
                    t.setDaemon(true);
                    return t;
                });
                plain.setExecutor(executor);
                plain.start();
                server = plain;
                McQqRelay.LOGGER.info("[McQqRelay] HTTP relay listening on {}:{} (INSECURE)", host, port);
            }
        } catch (Exception e) {
            McQqRelay.LOGGER.error("[McQqRelay] Failed to start relay server: {}", e.toString());
        }
    }

    public static void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        NONCES.clear();
        OUTBOX.clear();
        IMAGES.clear();
    }

    public static void enqueuePlayerMessage(String playerName, String message) {
        if (!ModConfig.broadcastMcToQq || !ModConfig.broadcastPlayerMcToQq) {
            return;
        }
        if (playerName == null || playerName.isBlank() || message == null || message.isBlank()) {
            return;
        }
        OUTBOX.offer(new OutboundMessage(playerName, message.trim(), ModConfig.mcPrefix));
        McQqRelay.LOGGER.debug("[McQqRelay] queued player message from {}", playerName);
    }

    public static void enqueueSystemMessage(String text) {
        if (!ModConfig.broadcastMcToQq || !ModConfig.broadcastSystemMcToQq) {
            return;
        }
        if (text == null || text.isBlank()) {
            return;
        }
        OUTBOX.offer(new OutboundMessage(ModConfig.systemName, text.trim(), ModConfig.mcPrefix));
        McQqRelay.LOGGER.debug("[McQqRelay] queued system message");
    }

    public static void enqueueTest(String text) {
        OUTBOX.offer(new OutboundMessage("测试", text == null ? "test" : text, ModConfig.mcPrefix));
    }

    public static String enqueueImageMessage(String playerName, byte[] imageData, String fileName) {
        if (!ModConfig.broadcastMcToQq || !ModConfig.broadcastPlayerMcToQq) {
            return null;
        }
        String token = storeImage(imageData, fileName);
        if (token == null) {
            return null;
        }
        OUTBOX.offer(new OutboundMessage(playerName, "[图片]", ModConfig.mcPrefix, token, fileName));
        McQqRelay.LOGGER.info("[McQqRelay] queued image message from {} token={} size={}", playerName, token, imageData.length);
        return token;
    }

    private static String storeImage(byte[] imageData, String fileName) {
        if (imageData == null || imageData.length == 0 || imageData.length > MAX_IMAGE_BYTES) {
            return null;
        }
        cleanupImages();
        if (IMAGES.size() >= MAX_IMAGES) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, TempImage> entry : IMAGES.entrySet()) {
                if (entry.getValue().createdAt < oldestTime) {
                    oldestTime = entry.getValue().createdAt;
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) {
                IMAGES.remove(oldest);
            }
        }
        String token = UUID.randomUUID().toString().replace("-", "");
        IMAGES.put(token, new TempImage(imageData, fileName, System.currentTimeMillis()));
        return token;
    }

    private static void cleanupImages() {
        long cutoff = System.currentTimeMillis() - IMAGE_TTL_MS;
        IMAGES.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }

    public static String storeLongMessage(String text) {
        cleanupLongMessages();
        if (LONG_MESSAGES.size() >= MAX_LONG_MESSAGES) {
            String oldest = null;
            long oldestTime = Long.MAX_VALUE;
            for (Map.Entry<String, LongMessage> entry : LONG_MESSAGES.entrySet()) {
                if (entry.getValue().createdAt < oldestTime) {
                    oldestTime = entry.getValue().createdAt;
                    oldest = entry.getKey();
                }
            }
            if (oldest != null) {
                LONG_MESSAGES.remove(oldest);
            }
        }
        String id = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        LONG_MESSAGES.put(id, new LongMessage(text, System.currentTimeMillis()));
        return id;
    }

    public static String getLongMessage(String id) {
        if (id == null) {
            return null;
        }
        LongMessage msg = LONG_MESSAGES.get(id);
        return msg == null ? null : msg.text;
    }

    private static void cleanupLongMessages() {
        long cutoff = System.currentTimeMillis() - LONG_MESSAGE_TTL_MS;
        LONG_MESSAGES.entrySet().removeIf(e -> e.getValue().createdAt < cutoff);
    }

    public static boolean hasServer() {
        return server != null;
    }

    private static SSLContext loadSslContext() throws Exception {
        Path path = Path.of(ModConfig.keystorePath);
        if (!Files.exists(path)) {
            throw new IllegalStateException("TLS keystore not found: " + path.toAbsolutePath()
                    + ". Generate it with keytool (see README) or set requireTls=false for LAN only.");
        }
        char[] password = ModConfig.keystorePassword == null ? new char[0] : ModConfig.keystorePassword.toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(kmf.getKeyManagers(), null, null);
        return ssl;
    }

    private static void handlePoll(HttpExchange exchange) throws IOException {
        try {
            String requestPath = exchange.getRequestURI().toString();
            if (!authenticate(exchange, "GET", requestPath)) {
                respond(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }
            int timeout = parseTimeout(exchange.getRequestURI().getQuery());
            List<Map<String, String>> messages = takeMessages(timeout);
            String body = GSON.toJson(Map.of("messages", messages));
            respond(exchange, 200, body);
        } catch (Exception e) {
            McQqRelay.LOGGER.error("[McQqRelay] poll error: {}", e.toString());
            respond(exchange, 500, "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    private static void handleSend(HttpExchange exchange) throws IOException {
        try {
            byte[] body = exchange.getRequestBody().readAllBytes();
            if (!authenticate(exchange, "POST", "/v1/send", body)) {
                respond(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }
            String json = new String(body, StandardCharsets.UTF_8);
            IncomingMessage in = GSON.fromJson(json, IncomingMessage.class);
            if (in == null || in.message == null || in.message.isBlank()) {
                respond(exchange, 400, "{\"ok\":false,\"error\":\"bad_message\"}");
                return;
            }
            if (!ModConfig.broadcastQqToMc) {
                respond(exchange, 200, "{\"ok\":true,\"skipped\":\"broadcastQqToMc=false\"}");
                return;
            }
            String name = in.name == null || in.name.isBlank() ? "QQ" : in.name.trim();
            String text = in.message.trim();
            String imageUrl = in.imageUrl == null ? "" : in.imageUrl.trim();

            MinecraftServer ms = ServerLifecycleHooks.getCurrentServer();
            if (ms == null) {
                respond(exchange, 503, "{\"ok\":false,\"error\":\"no_server\"}");
                return;
            }
            final String fName = name;
            final String fText = text;
            final String fImageUrl = imageUrl;
            ms.execute(() -> {
                if (fImageUrl.isEmpty()) {
                    if (fText.length() > LONG_MESSAGE_THRESHOLD) {
                        String id = storeLongMessage(fText);
                        String preview = fText.substring(0, Math.min(fText.length(), LONG_MESSAGE_PREVIEW));
                        MutableComponent comp = Component.literal(ModConfig.qqPrefix + " ").withStyle(ChatFormatting.DARK_AQUA);
                        comp.append(Component.literal(fName).withStyle(ChatFormatting.AQUA));
                        comp.append(Component.literal(": "));
                        comp.append(Component.literal(preview + "…… ").withStyle(ChatFormatting.GRAY));
                        Style clickStyle = Style.EMPTY
                                .withColor(ChatFormatting.GREEN)
                                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/mcqq show " + id));
                        comp.append(Component.literal("[点击展开]").withStyle(clickStyle));
                        ms.getPlayerList().broadcastSystemMessage(comp, false);
                    } else {
                        Component comp = ChatImageSupport.buildQqComponent(fName, fText);
                        ms.getPlayerList().broadcastSystemMessage(comp, false);
                    }
                } else {
                    String safeUrl = fImageUrl.replace(",", "%2C").replace("]", "%5D");
                    String ci = "[[CICode,url=" + safeUrl + ",name=QQ image]]";
                    String message = "[MCQQ] " + fName + " 发送了一张图片到 QQ " + ci;
                    ms.getPlayerList().broadcastSystemMessage(
                            net.minecraft.network.chat.Component.literal(message), false);
                }
            });
            respond(exchange, 200, "{\"ok\":true}");
        } catch (Exception e) {
            McQqRelay.LOGGER.error("[McQqRelay] send error: {}", e.toString());
            respond(exchange, 500, "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    private static void handleImage(HttpExchange exchange) throws IOException {
        try {
            String requestPath = exchange.getRequestURI().getPath();
            // 图片 token 是随机令牌，允许 MC 客户端直接通过公网地址加载；
            // AstrBot 使用带签名的请求访问也同样兼容；读取不会删除图片。
            if (!requestPath.startsWith("/v1/image/")) {
                respond(exchange, 400, "{\"ok\":false,\"error\":\"bad_path\"}");
                return;
            }
            String token = requestPath.substring("/v1/image/".length());
            // 不删除图片：AstrBot 转发到 QQ 和 MC 客户端显示需要同时读取同一个 token。
            // 图片仍会在 IMAGE_TTL_MS 后由 cleanupImages() 自动清理。
            TempImage img = IMAGES.get(token);
            if (img == null) {
                respond(exchange, 404, "{\"ok\":false,\"error\":\"not_found\"}");
                return;
            }
            byte[] bytes = img.data;
            exchange.getResponseHeaders().set("Content-Type", contentType(img.fileName));
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        } catch (Exception e) {
            McQqRelay.LOGGER.error("[McQqRelay] image error: {}", e.toString());
            respond(exchange, 500, "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    private static String contentType(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lower = fileName.toLowerCase();
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".bmp")) return "image/bmp";
        return "application/octet-stream";
    }

    private static void handlePing(HttpExchange exchange) throws IOException {
        try {
            if (!authenticate(exchange, "GET", "/v1/ping")) {
                respond(exchange, 401, "{\"ok\":false,\"error\":\"unauthorized\"}");
                return;
            }
            respond(exchange, 200, "{\"ok\":true,\"time\":" + System.currentTimeMillis() + "}");
        } catch (Exception e) {
            McQqRelay.LOGGER.error("[McQqRelay] ping error: {}", e.toString());
            respond(exchange, 500, "{\"ok\":false,\"error\":\"internal\"}");
        }
    }

    private static List<Map<String, String>> takeMessages(int timeoutSeconds) throws InterruptedException {
        List<Map<String, String>> result = new ArrayList<>();
        if (OUTBOX.isEmpty()) {
            OutboundMessage first = OUTBOX.poll(Math.max(1, timeoutSeconds), TimeUnit.SECONDS);
            if (first != null) {
                result.add(first.toJsonMap());
            }
        }
        List<OutboundMessage> raw = new ArrayList<>();
        OUTBOX.drainTo(raw, 50);
        for (OutboundMessage m : raw) {
            result.add(m.toJsonMap());
        }
        return result;
    }

    private static int parseTimeout(String query) {
        if (query == null || query.isBlank()) {
            return Math.max(1, ModConfig.pollTimeout);
        }
        for (String part : query.split("&")) {
            if (part.startsWith("timeout=")) {
                try {
                    return Math.max(1, Math.min(120, Integer.parseInt(part.substring(8))));
                } catch (NumberFormatException ignored) {}
            }
        }
        return Math.max(1, ModConfig.pollTimeout);
    }

    private static boolean authenticate(HttpExchange exchange, String method, String path) throws Exception {
        return authenticate(exchange, method, path, new byte[0]);
    }

    private static boolean authenticate(HttpExchange exchange, String method, String path, byte[] body) throws Exception {
        long ts;
        try {
            ts = Long.parseLong(exchange.getRequestHeaders().getFirst("X-McQq-Timestamp"));
        } catch (Exception ex) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (Math.abs(now - ts) > ModConfig.timeSkewMs) {
            return false;
        }
        String nonce = exchange.getRequestHeaders().getFirst("X-McQq-Nonce");
        String signature = exchange.getRequestHeaders().getFirst("X-McQq-Signature");
        if (nonce == null || nonce.length() < 8 || signature == null) {
            return false;
        }
        String expected = sign(ModConfig.sharedSecret, ts, nonce, method, path, body);
        if (!constantTimeEquals(expected, signature)) {
            return false;
        }
        Long inserted = NONCES.putIfAbsent(nonce, now);
        if (inserted != null) {
            return false;
        }
        cleanupNonces(now);
        return true;
    }

    private static String sign(String secret, long ts, String nonce, String method, String path, byte[] body) throws Exception {
        String data = ts + "\n" + nonce + "\n" + method + "\n" + path + "\n" + sha256Hex(body);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static void cleanupNonces(long now) {
        if (NONCES.size() > MAX_NONCE_ENTRIES) {
            long cutoff = now - ModConfig.timeSkewMs;
            NONCES.entrySet().removeIf(e -> e.getValue() < cutoff);
        }
    }

    private static void respond(HttpExchange exchange, int code, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.sendResponseHeaders(code, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    public static class OutboundMessage {
        public String name;
        public String message;
        public String prefix;
        public String imageToken;
        public String imageName;

        public OutboundMessage(String name, String message, String prefix) {
            this(name, message, prefix, null, null);
        }

        public OutboundMessage(String name, String message, String prefix, String imageToken, String imageName) {
            this.name = name;
            this.message = message;
            this.prefix = prefix;
            this.imageToken = imageToken;
            this.imageName = imageName;
        }

        public Map<String, String> toJsonMap() {
            Map<String, String> map = new java.util.LinkedHashMap<>();
            map.put("name", name);
            map.put("message", message);
            map.put("prefix", prefix);
            if (imageToken != null) map.put("image_token", imageToken);
            if (imageName != null) map.put("image_name", imageName);
            return map;
        }
    }

    private static class LongMessage {
        final String text;
        final long createdAt;

        LongMessage(String text, long createdAt) {
            this.text = text;
            this.createdAt = createdAt;
        }
    }

    private static class TempImage {
        final byte[] data;
        final String fileName;
        final long createdAt;

        TempImage(byte[] data, String fileName, long createdAt) {
            this.data = data;
            this.fileName = fileName;
            this.createdAt = createdAt;
        }
    }

    @SuppressWarnings("unused")
    public static class IncomingMessage {
        public String name;
        public String message;
        @SerializedName(value="image_url", alternate={"imageUrl"})
        public String imageUrl;
    }
}
