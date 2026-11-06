import logging
import httpx

log = logging.getLogger(__name__)


class SpringClient:
    def __init__(self, base_url: str, timeout: float = 5.0, agent_token: str = ""):
        # 登录上线后业务接口要会话；插件没有浏览器会话，带共享 token 走 agent 通道
        headers = {"X-Agent-Token": agent_token} if (agent_token or "").strip() else None
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout, headers=headers)

    async def close(self):
        await self._client.aclose()

    async def _request(self, method: str, path: str, **kwargs):
        response = await self._client.request(method, path, **kwargs)
        response.raise_for_status()
        body = response.json()
        if body.get("code") != 0:
            raise RuntimeError(f"spring boot {path}: {body.get('message')}")
        return body.get("data")

    async def log_messages(self, items):
        return await self._request("POST", "/api/message-logs", json=items)

    async def check_noise(self, text, msg_type="text"):
        """单条噪音判定。规则只在 Spring 侧存一份，插件不缓存，网页改完立刻生效。

        拦截规则下线后，前置过滤不再拉规则表；判定失败由调用方按「非噪音」放行。
        """
        return await self._request("POST", "/api/agent/noise-filter/check",
                                   json={"text": text, "msgType": msg_type})

    async def profile(self, sender_id):
        return await self._request("GET", f"/api/profile/{sender_id}")

    async def resolve_conversation(self, bot_uuid, chat_type, chat_id, sender_id):
        params = {
            "botUuid": bot_uuid,
            "chatType": chat_type,
            "senderId": sender_id,
        }
        if chat_id:
            params["chatId"] = chat_id
        data = await self._request("GET", "/api/conversations/resolve", params=params)
        return (data or {}).get("conversationId")

    async def bind_conversation(self, bot_uuid, chat_type, chat_id, sender_id, conversation_id):
        return await self._request("POST", "/api/conversations/bind", json={
            "botUuid": bot_uuid,
            "chatType": chat_type,
            "chatId": chat_id or 0,
            "senderId": sender_id,
            "conversationId": conversation_id,
        })

    async def reset_conversation(self, bot_uuid, chat_type, chat_id, sender_id):
        return await self._request("POST", "/api/conversations/reset", json={
            "botUuid": bot_uuid,
            "chatType": chat_type,
            "chatId": chat_id or 0,
            "senderId": sender_id,
        })

    async def create_draft(self, type_, sender_id, chat_id, title, payload):
        return await self._request("POST", "/api/drafts",
                                   json={
                                       "type": type_,
                                       "senderId": sender_id,
                                       "chatId": chat_id,
                                       "title": title,
                                       "payload": payload
                                   })

    async def create_memo(self, body):
        return await self._request("POST", "/api/memos", json=body)

    async def patch_memo(self, memo_id, body):
        return await self._request("PATCH", f"/api/memos/{memo_id}", json=body)

    async def list_memos(self, sender_id, status=None):
        params = {"senderId": sender_id}
        if status:
            params["status"] = status
        return await self._request("GET", "/api/memos", params=params)

    async def list_open_memos(self, sender_id, limit=20):
        """未终结备忘的紧凑摘要，喂给 Dify 让它能指认「改哪一条」。"""
        return await self._request("GET", "/api/memos/open",
                                   params={"senderId": sender_id, "limit": limit})

    async def reply_context(self, chat_id, kind, limit=15):
        """自动回复的决策快照：开关 / 限流 / 子提示词 / 最近消息，一次取齐。

        拆成多次请求会让快照之间互相不一致（刚发完一条，限流窗口已经变了）。
        """
        return await self._request("GET", "/api/chat-settings/reply-context",
                                   params={"chatId": chat_id, "kind": kind, "limit": limit})

    async def upsert_fact(self, sender_id, fact_key, fact_value, confidence):
        return await self._request("PUT", "/api/profile/facts",
                                   json={
                                       "senderId": sender_id,
                                       "factKey": fact_key,
                                       "factValue": fact_value,
                                       "confidence": confidence
                                   })

    async def sync_file_task(self, body):
        return await self._request("POST", "/api/file-tasks/sync", json=body)

    async def napcat_targets(self, limit=50):
        """该补哪些会话、各自补到哪一条。补拉本身由插件直连 NapCat 做。"""
        return await self._request("GET", "/api/agent/napcat/targets",
                                   params={"limit": limit})

    async def advance_napcat_cursor(self, chat_type, chat_id, last_message_seq):
        """推进游标。后端是 GREATEST 语义，只会前进，重复调用安全。"""
        return await self._request("POST", "/api/agent/napcat/cursor",
                                   json={
                                       "chatType": chat_type,
                                       "chatId": chat_id,
                                       "lastMessageSeq": last_message_seq,
                                   })

    async def record_token_usage(self, body):
        """记一笔模型用量。账本是旁路边，失败由调用方吞掉，绝不回压消息流程。"""
        return await self._request("POST", "/api/agent/token-usage", json=body)
