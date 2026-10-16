"""MC 服务器 <-> QQ 群消息互通（AstrBot 主动出站连接，无需本机开放端口）。

架构：
  AstrBot 插件 --出站 HTTPS/HMAC 长轮询--> MC 服务器上的 mc-qq-relay 模组
  这样 AstrBot 不需要公网/端口，MC 服务器只需要开放一个可映射的 HTTPS 端口。
"""

import asyncio
import hashlib
import hmac
import json
import os
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import ssl
import tempfile
import threading
import time
import urllib.error
import urllib.request
import uuid

from astrbot import logger
from astrbot.api.event import AstrMessageEvent, filter
from astrbot.api.star import Context, Star
from astrbot.core.message.components import Image
from astrbot.core.message.message_event_result import MessageChain
from astrbot.core.platform.message_session import MessageSession
from astrbot.core.platform.message_type import MessageType


class _LocalImageHandler(BaseHTTPRequestHandler):
    images = {}
    lock = threading.Lock()
    max_entries = 64

    def do_GET(self):
        token = self.path.split("/")[-1]
        with self.lock:
            item = self.images.get(token)
        if not item:
            self.send_response(404)
            self.end_headers()
            return
        data, ext = item
        self.send_response(200)
        self.send_header("Content-Type", "image/png" if ext != ".gif" else "image/gif")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        try:
            self.wfile.write(data)
        except OSError:
            pass

    def log_message(self, *args):
        pass

class McQqRelayPlugin(Star):
    """将 QQ 群消息实时转发到 MC，并从 MC 长轮询玩家聊天到 QQ 群。"""

    def __init__(self, context: Context, config=None):
        super().__init__(context)
        self.config = config or {}
        self.group_id = str(self.config.get("group_id", ""))
        self.platform_id = str(self.config.get("platform_id", "napcat"))
        self.server_base_url = str(self.config.get("server_base_url", "")).rstrip("/")
        self.auth_token = str(self.config.get("auth_token", ""))
        self.server_cert_path = str(self.config.get("server_cert_path", ""))
        self.server_cert_sha256 = str(self.config.get("server_cert_sha256", "")).lower()
        self.mc_prefix = str(self.config.get("mc_prefix", "[MC]"))
        self.poll_timeout = int(self.config.get("poll_timeout", 25))
        self.poll_retry = float(self.config.get("poll_retry", 5.0))
        self.exclude_self = bool(self.config.get("exclude_self", True))
        self.exclude_qq_official_bot = bool(self.config.get("exclude_qq_official_bot", True))
        self.exclude_user_ids = [
            str(x) for x in (self.config.get("exclude_user_ids", []) or [])
        ]
        self.exclude_official_bot_ids = [
            str(x) for x in (self.config.get("exclude_official_bot_ids", ["2854196306", "2854196310"]) or [])
        ]
        self.image_transcription_enabled = bool(self.config.get("image_transcription_enabled", False))
        self.chatimage_enabled = bool(self.config.get("chatimage_enabled", False))
        self.chatimage_width = int(self.config.get("chatimage_width", 320))
        self.chatimage_height = int(self.config.get("chatimage_height", 240))
        self.chatimage_info = str(self.config.get("chatimage_info", "QQ image"))

        self._local_image_server = None
        self._local_images = {}
        self._local_image_lock = threading.Lock()
        self._start_local_image_server()

        self._poll_task = None
        self._stopping = False
        self._ssl_context = self._build_ssl_context()

    async def initialize(self) -> None:
        self._stopping = False
        if self.group_id and self.auth_token and self.server_base_url:
            self._poll_task = asyncio.create_task(self._poll_loop())
            self.logger.info("[mcqq] poll task started: %s", self.server_base_url)
        else:
            self.logger.warning("[mcqq] poll not started: group_id/auth_token/server_base_url must all be set")

    async def terminate(self) -> None:
        self._stopping = True
        self._stop_local_image_server()
        if self._poll_task:
            self._poll_task.cancel()
            try:
                await self._poll_task
            except (asyncio.CancelledError, Exception):
                pass
            self._poll_task = None

    # ------------------------------------------------------------------
    # QQ -> MC
    # ------------------------------------------------------------------
    @filter.event_message_type(filter.EventMessageType.GROUP_MESSAGE)
    async def on_group_message(self, event: AstrMessageEvent):
        if not self.group_id:
            return
        if str(event.message_obj.group_id) != self.group_id:
            return
        if self.exclude_self and str(event.get_self_id()) and str(event.get_sender_id()) == str(event.get_self_id()):
            return
        if self.exclude_qq_official_bot and str(event.get_sender_id()) in self.exclude_official_bot_ids:
            return
        if str(event.get_sender_id()) in self.exclude_user_ids:
            return

        name = event.get_sender_name() or event.get_sender_id()
        text = self._extract_text_with_images(event)
        if not text:
            return

        try:
            await asyncio.to_thread(self._post_to_mc, payload={"name": name, "message": text})
        except Exception as e:
            self.logger.error("[mcqq] 转发到 MC 失败: %s", e)

        # 转发后阻止 LLM 自动回复
        if self.config.get("disable_llm_reply", True):
            event.call_llm = False

    def _extract_text_with_images(self, event: AstrMessageEvent) -> str:
        has_image = any(isinstance(comp, Image) for comp in event.get_messages())
        if not has_image:
            # 纯文本消息直接用 AstrBot 的 message_str，避免部分平台重复 Plain 段导致文字翻倍。
            return (event.message_str or "").strip()

        parts = []
        for comp in event.get_messages():
            if isinstance(comp, Image):
                has_image = True
                if self.chatimage_enabled:
                    parts.append(self._chatimage_code(comp))
                else:
                    parts.append("[图片]")
            elif hasattr(comp, "text"):
                parts.append(str(comp.text))
            else:
                # 其他组件（如 @、表情）以事件自带的文本为准，避免丢失。
                pass
        if not parts:
            parts.append((event.message_str or "").strip())
        text = " ".join(parts).strip()
        if has_image and self.image_transcription_enabled:
            # 可选：如果未来接入“聊天图片”等 OCR/视觉模型，可以在这里把 [图片] 替换为转录文本。
            # 这里保留占位说明；未接入时仍然会转发 [图片] 以及同条文本。
            pass
        return text

    def _chatimage_code(self, comp) -> str:
        """把 QQ 图片直链包装成新版 ChatImage (kitUIN) 识别的 [[CICode,url=...]] 代码。"""
        url = getattr(comp, "url", "") or getattr(comp, "file", "") or ""
        url = str(url).strip()
        if not url:
            return "[图片]"
        if url.startswith("http://") or url.startswith("https://"):
            pass
        elif url.startswith("base64://") or url.startswith("data:"):
            return "[图片]"
        else:
            # NapCat 有时只给本地缓存路径；用本地 HTTP 服务转成 http://127.0.0.1 直链，
            # 避免 ChatImage 误以为要“从服务端加载”而卡住。
            served = self._serve_local_image(url)
            if not served:
                return "[图片]"
            url = served
        # 新版 ChatImageCode 使用逗号分隔参数，简单转义避免 URL/说明里的逗号和右括号破坏格式。
        safe_url = url.replace(",", "%2C").replace("]", "%5D")
        safe_info = (self.chatimage_info or "QQ image").replace(",", "，").replace("]", "」")
        return f"[[CICode,url={safe_url},name={safe_info}]]"

    def _start_local_image_server(self):
        try:
            server = ThreadingHTTPServer(("127.0.0.1", 0), _LocalImageHandler)
            self._local_image_server = server
            threading.Thread(target=server.serve_forever, daemon=True).start()
            self.logger.info("[mcqq] local image server started on port %d", server.server_address[1])
        except Exception as e:
            self.logger.warning("[mcqq] local image server failed: %s", e)

    def _stop_local_image_server(self):
        if self._local_image_server is not None:
            try:
                self._local_image_server.shutdown()
                self._local_image_server.server_close()
            except Exception:
                pass
            self._local_image_server = None

    def _serve_local_image(self, source: str) -> str | None:
        if not source:
            return None
        if source.startswith("http://") or source.startswith("https://"):
            return source
        path = None
        try:
            if source.startswith("file://"):
                path = Path(source.replace("file:///", "", 1) if source.startswith("file:///") else source.replace("file://", "", 1))
            else:
                path = Path(source)
            if not path.exists():
                return None
            data = path.read_bytes()
        except Exception:
            return None
        if not data or len(data) > 10 * 1024 * 1024:
            return None
        token = uuid.uuid4().hex
        ext = path.suffix.lower() if path.suffix else ".png"
        with self._local_image_lock:
            self._local_images[token] = (data, ext)
            if len(self._local_images) > 64:
                oldest = next(iter(self._local_images))
                self._local_images.pop(oldest, None)
            _LocalImageHandler.images[token] = (data, ext)
            _LocalImageHandler.lock.acquire()
            try:
                if len(_LocalImageHandler.images) > _LocalImageHandler.max_entries:
                    oldest = next(iter(_LocalImageHandler.images))
                    _LocalImageHandler.images.pop(oldest, None)
            finally:
                _LocalImageHandler.lock.release()
        port = self._local_image_server.server_address[1] if self._local_image_server else 0
        if port == 0:
            return None
        return f"http://127.0.0.1:{port}/img/{token}"

    def _build_ssl_context(self) -> ssl.SSLContext | None:
        if not self.server_cert_path:
            return None
        if not os.path.exists(self.server_cert_path):
            self.logger.warning("[mcqq] server_cert_path not found: %s", self.server_cert_path)
            return None
        ctx = ssl.create_default_context(cafile=self.server_cert_path)
        # 手动交换的自签名证书通常没有匹配 IP 的 SAN，关闭主机名校验，
        # 通过固定证书文件保证不会被中间人替换；server_cert_sha256 仅用于人工核对。
        ctx.check_hostname = False
        return ctx

    def _post_to_mc(self, payload: dict):
        """把 QQ 群消息签名 POST 到 MC 模组。"""
        return self._signed_request("POST", "/v1/send", payload=payload, timeout=10)

    # ------------------------------------------------------------------
    # MC -> QQ long poll
    # ------------------------------------------------------------------
    async def _poll_loop(self):
        while not self._stopping:
            delay = 0.3
            try:
                data = await asyncio.to_thread(self._poll_once)
                messages = (data or {}).get("messages") or []
                for m in messages:
                    await self._send_to_qq(m)
            except asyncio.CancelledError:
                break
            except urllib.error.HTTPError as e:
                self.logger.error("[mcqq] poll HTTP error: %s %s", e.code, e.reason)
                delay = self.poll_retry
            except Exception as e:
                self.logger.error("[mcqq] poll error: %s", e)
                delay = self.poll_retry
            if not self._stopping:
                await asyncio.sleep(delay)

    def _poll_once(self):
        if not self.server_base_url or not self.auth_token:
            return {"messages": []}
        path = "/v1/poll?timeout=" + str(max(1, self.poll_timeout))
        return self._signed_request("GET", path, timeout=self.poll_timeout + 15)

    async def _send_to_qq(self, m: dict):
        name = str(m.get("name", "MC"))
        message = str(m.get("message", ""))
        prefix = str(m.get("prefix", self.mc_prefix) or self.mc_prefix)
        image_token = str(m.get("image_token", "") or "").strip()
        session = MessageSession(self.platform_id, MessageType.GROUP_MESSAGE, self.group_id)

        if image_token:
            temp_path = None
            try:
                raw = await asyncio.to_thread(
                    self._signed_request_raw, "GET", f"/v1/image/{image_token}", None, 30
                )
                if not raw:
                    self.logger.warning("[mcqq] image download empty: %s", image_token)
                    return
                suffix = self._guess_image_ext(str(m.get("image_name", "")))
                fd, temp_path = tempfile.mkstemp(prefix="mcqq_img_", suffix=suffix)
                with os.fdopen(fd, "wb") as f:
                    f.write(raw)

                # 优先直接走 NapCat 发送，顺便用 get_msg 拿 QQ CDN 直链，
                # 避免 MC 客户端加载自签名 HTTPS 图片超时。
                sent, qq_url = await self._try_send_image_get_url(prefix, name, message, temp_path)
                if not sent:
                    chain = MessageChain().message(f"{prefix} {name}: {message}")
                    chain.file_image(temp_path)
                    ok = await self.context.send_message(session, chain)
                    if not ok:
                        self.logger.warning("[mcqq] send_message to QQ failed: platform %s not found", self.platform_id)
                    else:
                        sent = True

                if sent:
                    display_url = qq_url
                    if not display_url and self.server_base_url and image_token:
                        display_url = f"{self.server_base_url}/v1/image/{image_token}"
                    if display_url:
                        await self._broadcast_image_back_to_mc(name, display_url)
            except Exception as e:
                self.logger.error("[mcqq] send image to QQ failed: %s", e)
            finally:
                if temp_path:
                    try:
                        os.remove(temp_path)
                    except OSError:
                        pass
            return

        if not message:
            return
        chain = MessageChain().message(f"{prefix} {name}: {message}")
        try:
            ok = await self.context.send_message(session, chain)
            if not ok:
                self.logger.warning("[mcqq] send_message to QQ failed: platform %s not found", self.platform_id)
        except Exception as e:
            self.logger.error("[mcqq] send to QQ failed: %s", e)

    @staticmethod
    def _guess_image_ext(file_name: str) -> str:
        lower = (file_name or "").lower()
        for ext in (".png", ".jpg", ".jpeg", ".gif", ".webp", ".bmp"):
            if lower.endswith(ext):
                return ext
        return ".png"

    async def _broadcast_image_back_to_mc(self, name: str, display_url: str) -> None:
        """图片成功发到 QQ 后，把可加载的图片直链回传到 MC，让游戏内也能看到图片。"""
        if not self.chatimage_enabled:
            return
        if not display_url:
            return
        try:
            await asyncio.to_thread(
                self._post_to_mc,
                {"name": name, "message": "图片已发送到 QQ", "imageUrl": display_url},
            )
            self.logger.info("[mcqq] image display url broadcast: %s", display_url)
        except Exception as e:
            self.logger.warning("[mcqq] broadcast image display url failed: %s", e)

    async def _try_send_image_get_url(self, prefix: str, name: str, message: str, temp_path: str) -> tuple[bool, str | None]:
        """直接通过 NapCat 发送图片到群，并尝试从 get_msg 提取 QQ CDN 图片直链。

        返回 (是否发送成功, QQ 图片直链或 None)。
        """
        bot = self._get_napcat_bot()
        if bot is None or not hasattr(bot, "call_action"):
            return False, None
        try:
            text = f"{prefix} {name}: {message}".strip()
            resp = await bot.call_action("send_group_msg", group_id=int(self.group_id), message=[
                {"type": "text", "data": {"text": text}},
                {"type": "image", "data": {"file": temp_path}},
            ])
            msg_id = (resp or {}).get("message_id")
            if not msg_id:
                return True, None
            msg = await bot.call_action("get_msg", message_id=msg_id)
            url = self._extract_image_url_from_msg(msg)
            if not url:
                # get_msg 有时不直接给 url，但会给缓存 file；用这个 file 调 get_image 拿 CDN 直链。
                file_ref = self._extract_image_file_from_msg(msg)
                if file_ref:
                    ret = await bot.call_action("get_image", file=file_ref)
                    if isinstance(ret, dict):
                        url = str(ret.get("url") or "").strip()
                        if not (url.startswith("http://") or url.startswith("https://")):
                            url = None
            return True, url
        except Exception as e:
            self.logger.warning("[mcqq] direct send/get_url failed: %s", e)
            return False, None

    @staticmethod
    def _extract_image_url_from_msg(msg) -> str | None:
        if not isinstance(msg, dict):
            return None
        for seg in msg.get("message") or []:
            if not isinstance(seg, dict):
                continue
            if seg.get("type") != "image":
                continue
            data = seg.get("data") or {}
            url = str(data.get("url") or "").strip()
            if url.startswith("http://") or url.startswith("https://"):
                return url
        return None

    @staticmethod
    def _extract_image_file_from_msg(msg) -> str | None:
        if not isinstance(msg, dict):
            return None
        for seg in msg.get("message") or []:
            if not isinstance(seg, dict):
                continue
            if seg.get("type") != "image":
                continue
            data = seg.get("data") or {}
            file_ref = str(data.get("file") or "").strip()
            if file_ref:
                return file_ref
        return None

    def _get_napcat_bot(self):
        """从 AstrBot 平台管理器里找到当前 NapCat 的 CQHttp 客户端。"""
        try:
            for platform in self.context.platform_manager.platform_insts:
                if platform.meta().id == self.platform_id:
                    get_client = getattr(platform, "get_client", None)
                    if get_client:
                        return get_client()
        except Exception:
            pass
        return None

    # ------------------------------------------------------------------
    # Signed HTTP helper
    # ------------------------------------------------------------------
    def _signed_request_raw(self, method: str, path: str, payload: dict | None = None, timeout: int = 20) -> bytes:
        if not self.auth_token:
            raise RuntimeError("auth_token not set")
        body = b""
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

        ts = int(time.time() * 1000)
        nonce = uuid.uuid4().hex
        body_hash = hashlib.sha256(body).hexdigest()
        sig_data = f"{ts}\n{nonce}\n{method}\n{path}\n{body_hash}".encode("utf-8")
        signature = hmac.new(self.auth_token.encode("utf-8"), sig_data, hashlib.sha256).hexdigest()

        url = self.server_base_url + path
        headers = {
            "X-McQq-Timestamp": str(ts),
            "X-McQq-Nonce": nonce,
            "X-McQq-Signature": signature,
            "User-Agent": "astrbot-mcqq-relay/1.1.0",
            "Content-Type": "application/json; charset=utf-8",
        }
        data = body if payload is not None else None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=timeout, context=self._ssl_context) as resp:
            return resp.read()

    def _signed_request(self, method: str, path: str, payload: dict | None = None, timeout: int = 20):
        if not self.auth_token:
            raise RuntimeError("auth_token not set")
        body = b""
        if payload is not None:
            body = json.dumps(payload, ensure_ascii=False).encode("utf-8")

        ts = int(time.time() * 1000)
        nonce = uuid.uuid4().hex
        body_hash = hashlib.sha256(body).hexdigest()
        sig_data = f"{ts}\n{nonce}\n{method}\n{path}\n{body_hash}".encode("utf-8")
        signature = hmac.new(self.auth_token.encode("utf-8"), sig_data, hashlib.sha256).hexdigest()

        url = self.server_base_url + path
        headers = {
            "X-McQq-Timestamp": str(ts),
            "X-McQq-Nonce": nonce,
            "X-McQq-Signature": signature,
            "User-Agent": "astrbot-mcqq-relay/1.1.0",
            "Content-Type": "application/json; charset=utf-8",
        }
        if payload is not None:
            data = body
        else:
            data = None
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=timeout, context=self._ssl_context) as resp:
            raw = resp.read()
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))
