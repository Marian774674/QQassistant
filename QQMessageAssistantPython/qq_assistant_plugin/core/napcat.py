"""NapCat HTTP API 的最小客户端：只做掉线补拉要用的三个 action。

为什么不复用 `file_task._napcat_call`：那个是给文件下载/回传用的，
超时 30 秒、失败就抛、每次调用都新建连接，一次补拉要翻几十页，
用它会把整个补拉拖成分钟级。这里单独一套，超时短、失败可预期。

为什么不走 LangBot 的平台 API：OneBot11 适配器的白名单里没有历史消息接口
（PLATFORM_API_MAP），拿历史只能直连 NapCat——这也是「补拉放插件」的原因。
"""
from __future__ import annotations

import logging

import httpx

log = logging.getLogger(__name__)


class NapcatClient:
    """NapCat（OneBot 11 HTTP）的最小封装。

    未配置 `napcat_api_url` 时 `configured` 为 False，调用方据此整体跳过补拉：
    没有 NapCat 就没有历史消息，也没有「在线」可言。
    """

    def __init__(self, base_url: str = "", token: str = "", timeout: float = 10.0):
        self._base = (base_url or "").rstrip("/")
        self._token = (token or "").strip()
        self._timeout = timeout

    @property
    def configured(self) -> bool:
        return bool(self._base)

    async def call(self, action: str, params: dict | None = None):
        """打一个 NapCat action，返回 data 段。

        `status == "failed"` 也算失败：OneBot 的约定是 HTTP 200 + 业务失败，
        只看状态码会把「群号不存在」当成「这个群没有历史消息」，然后静默跳过。
        """
        if not self._base:
            raise RuntimeError("napcat_api_url 未配置")
        headers = {"Authorization": f"Bearer {self._token}"} if self._token else {}
        # trust_env=False：NapCat 永远是本机服务，系统代理（Clash 等）会把
        # 127.0.0.1 的请求也接走，表现成 502 Bad Gateway，看起来像 NapCat 挂了
        async with httpx.AsyncClient(timeout=self._timeout, trust_env=False) as client:
            resp = await client.post(f"{self._base}/{action}", json=params or {},
                                     headers=headers)
            if resp.status_code >= 400:
                detail = (resp.text or "").strip()[:200] or "<空响应体>"
                if resp.status_code in (401, 403):
                    why = "token 未配置" if not self._token else "token 与服务端不一致"
                else:
                    why = "服务端拒绝了该 action"
                raise RuntimeError(f"napcat {action} HTTP {resp.status_code}（{why}）：{detail}")
            payload = resp.json()
        if str(payload.get("status", "ok")).lower() == "failed":
            raise RuntimeError(f"napcat {action} failed: {payload.get('message') or payload}")
        return payload.get("data") or {}

    async def online(self) -> bool:
        """OneBot 服务是否在线。

        LangBot 只暴露消息事件，没有「机器人上线」事件，所以只能轮询这里。
        `online` / `good` 是标准字段，但各版本 NapCat 与兼容实现不一定带：
        字段缺失时只要 `get_status` 正常返回了非空 data 就当在线。否则
        「字段不叫这个名」会让 `_was_online` 永远停在 False、补拉永久停摆，
        比偶尔误判一次在线难查得多。
        """
        data = await self.call("get_status")
        if not isinstance(data, dict) or not data:
            return False
        if "online" in data:
            return bool(data.get("online"))
        if "good" in data:
            return bool(data.get("good"))
        return True

    async def account_online(self) -> bool:
        """QQ 账号是否真的登录着，而不只是 OneBot 服务还活着。

        这是「登录过期」和「服务重启」两类故障的分界线：NapCat 掉登录时
        进程和 HTTP 服务都还在，`get_status` 可能照常报在线，但 QQ 已经不推
        消息给 LangBot 了。`get_login_info` 拿不到有效 `user_id` 才算真掉登录。

        单独探它还有个好处：账号态恢复是比 `get_status.online` 灵敏得多的
        补拉触发信号，重登后不用等低频兜底那一轮。
        """
        data = await self.call("get_login_info")
        uid = (data or {}).get("user_id")
        try:
            return int(uid or 0) > 0
        except (TypeError, ValueError):
            return False

    async def group_history(self, group_id: int, count: int, message_seq: int = 0) -> list:
        """群历史消息。

        `message_seq=0` 取最新一页；传上一页里最小的 seq 就往更早翻。
        页内是倒序（最新在前），排序交给调用方。
        """
        data = await self.call("get_group_msg_history", {
            "group_id": int(group_id),
            "count": int(count),
            "message_seq": int(message_seq),
        })
        return (data or {}).get("messages") or []

    async def friend_history(self, user_id: int, count: int, message_seq: int = 0) -> list:
        """私聊历史消息。语义与 `group_history` 一致。"""
        data = await self.call("get_friend_msg_history", {
            "user_id": int(user_id),
            "count": int(count),
            "message_seq": int(message_seq),
        })
        return (data or {}).get("messages") or []
