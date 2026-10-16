# MC QQ Relay (AstrBot 主动出站版)

> NeoForge 1.21.1 模组 + AstrBot 插件  
> 解决“AstrBot 本机没有公网/不能开放端口”的问题：**由 AstrBot 主动连接 MC 服务器，MC 服务器只需要开放一个入站端口**。

## 架构

```
本地 Windows（无公网、无需开放端口）
  NapCat ──ws──> AstrBot
                    │
                    │ 插件主动出站：
                    │   HTTPS 长轮询 GET  /v1/poll
                    │   HTTPS 签名 POST /v1/send
                    ▼
远程 MC 服务器（有公网 IP/端口映射）
  mc-qq-relay 模组监听内网端口（如 25571）
  公网端口可以不同：AstrBot 的 server_base_url 填 公网IP:公网端口 即可
```

- MC→QQ：模组把玩家聊天/系统消息放入队列，AstrBot 插件长轮询拉取后用 `context.send_message()` 发到 QQ 群。
- QQ→MC：AstrBot 插件收到群消息后签名 POST 到模组，模组在主线程广播到游戏聊天。

## 组件

| 文件 | 说明 |
|---|---|
| `src/main/java/.../RelayServer.java` | 模组内嵌 HTTPS + HMAC 鉴权服务器 |
| `src/main/java/.../McQqRelay.java` | 聊天/玩家事件接入 |
| `astrbot-plugin/main.py` | AstrBot 插件：出站长轮询 + 群消息转发 |
| `config/mcqq.json` | 模组配置（服务器根目录） |

## 安全设计

1. **TLS + 自签名证书固定（防中间人）**
   - 远端模组启动 HTTPS，使用手动生成的 PKCS12 keystore。
   - AstrBot 通过 `server_cert_path` 固定该证书 PEM；不是浏览器 CA 也没关系，只有持有相同证书的服务端才能建立连接。
   - 可选配置 `server_cert_sha256` 做人工核对；插件当前以 `server_cert_path` 固定证书为准。

2. **HMAC-SHA256 请求签名**
   - 每个请求带 `X-McQq-Timestamp`、`X-McQq-Nonce`、`X-McQq-Signature`。
   - 签名内容：`timestamp\nnonce\nMETHOD\npath\nsha256(body)`，使用 `sharedSecret` 计算。
   - 服务端校验时间戳 ±5 分钟、nonce 不重复，防重放/篡改。
   - `sharedSecret` 只出现在配置文件里，不随请求明文传输。

3. **最小开放面**
   - 只需要开放 MC 服务器上的 HTTPS 端口；AstrBot 本地完全不需要公网或端口映射。
   - 公网端口与内网端口不一致时，只是 AstrBot 的 URL 填公网端口，模组绑定内网端口。

## 快速部署

### 1. 生成自签名证书（在 MC 服务器侧）

```bash
# 假如公网 IP 是 1.2.3.4，域名是 mc.example.com，按需写 SAN
keytool -genkeypair \
  -alias relay \
  -keyalg RSA -keysize 3072 -validity 3650 \
  -dname "CN=mcqq-relay" \
  -ext "SAN=IP:1.2.3.4,DNS:mc.example.com" \
  -storetype PKCS12 \
  -keystore relay.p12 \
  -storepass <你的强密码>

# 导出 PEM 证书给 AstrBot 使用
keytool -exportcert -rfc \
  -alias relay -keystore relay.p12 -storepass <你的强密码> \
  -file relay_cert.pem

# 查询证书指纹（可选填写到插件配置做二次校验）
keytool -list -v -keystore relay.p12 -storepass <你的强密码> | grep SHA256
```

把 `relay.p12` 放到 MC 服务器根目录（与 `server.properties` 同级），把 `relay_cert.pem` 手动复制给 AstrBot 本机。

### 2. 模组配置 `config/mcqq.json`

```json
{
  "server_host": "0.0.0.0",
  "server_port": 25571,
  "tls_enabled": true,
  "require_tls": true,
  "keystore_path": "relay.p12",
  "keystore_password": "你的keystore密码",
  "shared_secret": "至少16位的随机共享密钥",
  "time_skew_ms": 300000,
  "qq_prefix": "[QQ]",
  "mc_prefix": "[MC]",
  "system_name": "系统",
  "broadcast_player_mc_to_qq": true,
  "broadcast_system_mc_to_qq": false,
  "broadcast_qq_to_mc": true,
  "broadcast_mc_to_qq": true,
  "poll_timeout": 25
}
```

- `server_port` 是模组在服务器内网绑定的端口。
- AstrBot 的 `server_base_url` 填 `https://公网IP:公网映射端口`。
- 若没有条件做 TLS，可以设 `tls_enabled=false`、`require_tls=false`，但会有完整中间人风险，不建议公网使用。

### 3. AstrBot 插件配置（WebUI → 插件 → mc_qq_relay）

```text
server_base_url        https://你的公网IP:公网端口
auth_token             与 shared_secret 完全一致
server_cert_path       H:\astrbot\relay_cert.pem
server_cert_sha256     可选，证书 SHA-256 指纹
group_id               要互通的 QQ 群号
platform_id            napcat（与 AstrBot 平台适配器 ID 一致）
mc_prefix              [MC]
poll_timeout           25
poll_retry             5.0
exclude_self           true
exclude_qq_official_bot true
exclude_official_bot_ids ["2854196306","2854196310"]
exclude_user_ids       []
image_transcription_enabled false
chatimage_enabled      false
chatimage_width        320
chatimage_height       240
chatimage_info         QQ image
disable_llm_reply      true
```

### 4. 构建与安装

```bash
# 需要 JDK 21
set JAVA_HOME=<你的 JDK 21 路径>
gradlew.bat build
```

产物：`build/libs/mc-qq-relay-1.2.4.jar`，放入服务器 `mods/`，重启后把 `config/mcqq.json` 和 `relay.p12`、`relay_cert.pem` 放好。

### 5. 验证

- 服务器内执行 `/mcqq status`：应看到 `relay: running`、`tls: true`、`listen: 0.0.0.0:25571`。
- 执行 `/mcqq test`：AstrBot 插件会在一两个长轮询周期内把测试消息发到 QQ 群。
- QQ 群里发消息，游戏内应显示 `[QQ] 昵称: 内容`；游戏内玩家聊天应显示在 QQ 群。

## 可配置行为

- **默认仅玩家消息**：`broadcast_system_mc_to_qq=false` 时只转发 `ServerChatEvent` 玩家聊天。
- **系统消息**：设为 `true` 后会附带转发玩家进服/退服消息，以及服务端 `/say` 系统公告。
- **QQ 官方机器人**：插件默认排除自身上报和 `exclude_official_bot_ids` 中的官方机器人账号，可自行增删。
- **聊天图片/图片转录**：
  - 未开启 ChatImages 时，QQ 图片会转发为 `[图片]` 占位。
  - 若服务器和客户端都安装 `chatimage-2.1.0.jar`，可把 AstrBot 插件 `chatimage_enabled` 设为 `true`，同时把模组 `config/mcqq.json` 的 `chatimage_enabled` 设为 `true`。QQ 图片会提取直链并包装成 ChatImages 的 `CI{...}` 代码，游戏内可悬浮/点击查看图片。
  - 如果服务端没有 ChatImages，模组会自动把 `CI{...}` 替换回 `[图片]`，不会显示乱码。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/v1/poll?timeout=25` | AstrBot 长轮询拉取 MC→QQ 消息队列 |
| POST | `/v1/send` | AstrBot 转发 QQ 群消息给 MC |
| GET | `/v1/ping` | 连通性/鉴权自检 |

所有请求都必须带 HMAC 签名头。
