"""上游发送通道：文本走 LangBot 官方 API，文件优先 LangBot、回落 NapCat。"""

import asyncio
import random

import httpx


class UpstreamError(RuntimeError):
    pass


class LangBotUnsupported(UpstreamError):
    """LangBot 确定性拒绝（4xx）：确定没发出去，才允许回落 NapCat。"""


class LangBotUpstream:
    def __init__(self, settings):
        self._s = settings
        # trust_env=False：LangBot / NapCat 都是本机服务，系统代理会回 502
        self._client = httpx.AsyncClient(
            timeout=settings.timeout, trust_env=False)

    async def close(self) -> None:
        await self._client.aclose()

    @property
    def _send_url(self) -> str:
        return (f"{self._s.langbot_base}/api/v1/platform/bots/"
                f"{self._s.langbot_bot_uuid}/send_message")

    @property
    def _langbot_headers(self) -> dict:
        return {"X-API-Key": self._s.langbot_api_key}

    @staticmethod
    def _target_type(target_type: str) -> str:
        return "person" if target_type == "private" else "group"

    async def send_text(self, target_type: str, target_id: int, text: str) -> str:
        payload = {
            "target_type": self._target_type(target_type),
            "target_id": str(target_id),
            "message_chain": [{"type": "Plain", "text": text}],
        }
        resp = await self._client.post(
            self._send_url, json=payload, headers=self._langbot_headers)
        if resp.status_code >= 400:
            raise UpstreamError(
                f"LangBot send_message HTTP {resp.status_code}: {resp.text[:200]}")
        return _message_id(resp)

    async def send_file_via_langbot(self, target_type: str, target_id: int,
                                    path: str, name: str) -> str:
        payload = {
            "target_type": self._target_type(target_type),
            "target_id": str(target_id),
            "message_chain": [{"type": "File", "name": name, "url": path}],
        }
        resp = await self._client.post(
            self._send_url, json=payload, headers=self._langbot_headers)
        if resp.status_code >= 400:
            # 只有 400/404/405 能确定「没发出去」；5xx 与超时可能已送达，
            # 回落 NapCat 就会重复发文件，因此只对确定性拒绝回落。
            if resp.status_code in (400, 404, 405):
                raise LangBotUnsupported(
                    f"LangBot File HTTP {resp.status_code}: {resp.text[:200]}")
            raise UpstreamError(
                f"LangBot File HTTP {resp.status_code}: {resp.text[:200]}")
        return _message_id(resp)

    async def send_file_via_napcat(self, target_type: str, target_id: int,
                                   path: str, name: str) -> str:
        action = "upload_private_file" if target_type == "private" else "upload_group_file"
        key = "user_id" if target_type == "private" else "group_id"
        headers = {}
        if self._s.napcat_token:
            headers["Authorization"] = f"Bearer {self._s.napcat_token}"
        resp = await self._client.post(
            f"{self._s.napcat_base}/{action}",
            json={key: target_id, "file": path, "name": name},
            headers=headers,
        )
        if resp.status_code >= 400:
            raise UpstreamError(
                f"NapCat {action} HTTP {resp.status_code}: {resp.text[:200]}")
        body = resp.json()
        if str(body.get("status", "ok")).lower() == "failed":
            raise UpstreamError(f"NapCat {action} failed: {body.get('message') or body}")
        data = body.get("data") or {}
        return str(data.get("message_id") or data.get("messageId") or "")


def _message_id(resp: httpx.Response) -> str:
    try:
        body = resp.json()
    except Exception:
        return ""
    return str(body.get("message_id") or body.get("messageId") or "")


def delay_ms(text: str, requested: int | None, s) -> int:
    if requested is not None:
        return max(0, int(requested))
    ms = s.delay_base_ms + len(text) * s.delay_per_char_ms \
        + random.randint(0, s.delay_jitter_ms)
    return min(ms, s.delay_max_ms)


async def human_pause(text: str, requested: int | None, s, target_type: str) -> None:
    # 拟人延迟只在私聊：群聊本来就是异步阅读，延迟只会拖慢调度
    if target_type != "private":
        return
    await asyncio.sleep(delay_ms(text, requested, s) / 1000.0)
