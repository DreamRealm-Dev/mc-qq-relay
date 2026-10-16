package com.mcqq.bridge.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.annotations.SerializedName;
import com.mcqq.bridge.McQqRelay;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public class ModConfig {
    // Bridge server (the MC server side; AstrBot connects outbound to this)
    public static String serverHost = "0.0.0.0";
    public static int serverPort = 25571;
    public static boolean tlsEnabled = true;
    public static boolean requireTls = true;
    public static String keystorePath = "relay.p12";
    public static String keystorePassword = "";
    public static String sharedSecret = "";

    // Security
    public static long timeSkewMs = 5 * 60 * 1000L;

    // Routing / formatting
    public static String qqPrefix = "[QQ]";
    public static String mcPrefix = "[MC]";
    public static String systemName = "系统";
    public static boolean broadcastPlayerMcToQq = true;
    public static boolean broadcastSystemMcToQq = false;
    public static boolean broadcastQqToMc = true;
    public static boolean broadcastMcToQq = true;
    public static boolean chatimageEnabled = false;
    public static String publicBaseUrl = "";

    // Poll tuning
    public static int pollTimeout = 25;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static Path configPath;

    public static void load() {
        configPath = Path.of("config", "mcqq.json");
        if (Files.exists(configPath)) {
            try {
                String json = Files.readString(configPath, StandardCharsets.UTF_8);
                Data data = GSON.fromJson(json, Data.class);
                if (data != null) {
                    if (data.serverHost != null) serverHost = data.serverHost;
                    if (data.serverPort != null && data.serverPort > 0) {
                        serverPort = data.serverPort;
                    } else if (data.listenPort != null && data.listenPort > 0) {
                        // Upgrade from old schema.
                        serverPort = data.listenPort;
                    }
                    if (data.tlsEnabled != null) tlsEnabled = data.tlsEnabled;
                    if (data.requireTls != null) requireTls = data.requireTls;
                    if (data.keystorePath != null) keystorePath = data.keystorePath;
                    if (data.keystorePassword != null) keystorePassword = data.keystorePassword;
                    if (data.sharedSecret == null && data.webhookToken != null && !data.webhookToken.isEmpty()) {
                        // Upgrade from the old config schema.
                        sharedSecret = data.webhookToken;
                    } else if (data.sharedSecret != null) {
                        sharedSecret = data.sharedSecret;
                    }
                    if (data.timeSkewMs != null && data.timeSkewMs > 0) timeSkewMs = data.timeSkewMs;
                    if (data.qqPrefix != null) qqPrefix = data.qqPrefix;
                    if (data.mcPrefix != null) mcPrefix = data.mcPrefix;
                    if (data.systemName != null) systemName = data.systemName;
                    if (data.broadcastPlayerMcToQq != null) broadcastPlayerMcToQq = data.broadcastPlayerMcToQq;
                    if (data.broadcastSystemMcToQq != null) broadcastSystemMcToQq = data.broadcastSystemMcToQq;
                    if (data.broadcastQqToMc != null) broadcastQqToMc = data.broadcastQqToMc;
                    if (data.broadcastMcToQq != null) broadcastMcToQq = data.broadcastMcToQq;
                    if (data.chatimageEnabled != null) chatimageEnabled = data.chatimageEnabled;
                    if (data.publicBaseUrl != null) publicBaseUrl = data.publicBaseUrl;
                    if (data.pollTimeout != null && data.pollTimeout > 0) pollTimeout = data.pollTimeout;
                }
            } catch (Exception e) {
                McQqRelay.LOGGER.error("[McQqRelay] Failed to load config: {}", e.toString());
            }
        } else {
            save();
        }
        McQqRelay.LOGGER.info("[McQqRelay] Config loaded (without secrets). tls={} requireTls={} port={}",
                tlsEnabled, requireTls, serverPort);
    }

    public static void save() {
        try {
            if (configPath == null) {
                configPath = Path.of("config", "mcqq.json");
            }
            Files.createDirectories(configPath.getParent());
            Files.writeString(configPath, GSON.toJson(data()), StandardCharsets.UTF_8);
        } catch (IOException e) {
            McQqRelay.LOGGER.error("[McQqRelay] Failed to save config: {}", e.toString());
        }
    }

    public static boolean validServerSecret() {
        return sharedSecret != null && sharedSecret.length() >= 16;
    }

    private static Data data() {
        Data d = new Data();
        d.serverHost = serverHost;
        d.serverPort = serverPort;
        d.tlsEnabled = tlsEnabled;
        d.requireTls = requireTls;
        d.keystorePath = keystorePath;
        d.keystorePassword = keystorePassword;
        d.sharedSecret = sharedSecret;
        d.timeSkewMs = timeSkewMs;
        d.qqPrefix = qqPrefix;
        d.mcPrefix = mcPrefix;
        d.systemName = systemName;
        d.broadcastPlayerMcToQq = broadcastPlayerMcToQq;
        d.broadcastSystemMcToQq = broadcastSystemMcToQq;
        d.broadcastQqToMc = broadcastQqToMc;
        d.broadcastMcToQq = broadcastMcToQq;
        d.chatimageEnabled = chatimageEnabled;
        d.publicBaseUrl = publicBaseUrl;
        d.pollTimeout = pollTimeout;
        return d;
    }

    @SuppressWarnings("unused")
    public static class Data {
        // New schema
        @SerializedName(value="server_host", alternate={"serverHost"})
        public String serverHost;
        @SerializedName(value="server_port", alternate={"serverPort"})
        public Integer serverPort;
        @SerializedName(value="tls_enabled", alternate={"tlsEnabled"})
        public Boolean tlsEnabled;
        @SerializedName(value="require_tls", alternate={"requireTls"})
        public Boolean requireTls;
        @SerializedName(value="keystore_path", alternate={"keystorePath"})
        public String keystorePath;
        @SerializedName(value="keystore_password", alternate={"keystorePassword"})
        public String keystorePassword;
        @SerializedName(value="shared_secret", alternate={"sharedSecret"})
        public String sharedSecret;
        @SerializedName(value="time_skew_ms", alternate={"timeSkewMs"})
        public Long timeSkewMs;
        @SerializedName(value="qq_prefix", alternate={"qqPrefix"})
        public String qqPrefix;
        @SerializedName(value="mc_prefix", alternate={"mcPrefix"})
        public String mcPrefix;
        @SerializedName(value="system_name", alternate={"systemName"})
        public String systemName;
        @SerializedName(value="broadcast_player_mc_to_qq", alternate={"broadcastPlayerMcToQq"})
        public Boolean broadcastPlayerMcToQq;
        @SerializedName(value="broadcast_system_mc_to_qq", alternate={"broadcastSystemMcToQq"})
        public Boolean broadcastSystemMcToQq;
        @SerializedName(value="broadcast_qq_to_mc", alternate={"broadcastQqToMc"})
        public Boolean broadcastQqToMc;
        @SerializedName(value="broadcast_mc_to_qq", alternate={"broadcastMcToQq"})
        public Boolean broadcastMcToQq;
        @SerializedName(value="chatimage_enabled", alternate={"chatimageEnabled"})
        public Boolean chatimageEnabled;
        @SerializedName(value="poll_timeout", alternate={"pollTimeout"})
        public Integer pollTimeout;
        @SerializedName(value="public_base_url", alternate={"publicBaseUrl"})
        public String publicBaseUrl;

        // Backward-compatible legacy fields, only read for migration.
        @SerializedName(value="webhook_token", alternate={"webhookToken"})
        public String webhookToken;
        @SerializedName(value="listen_port", alternate={"listenPort"})
        public Integer listenPort;
    }
}
