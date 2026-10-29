import logging
import httpx

log = logging.getLogger(__name__)


class SpringClient:
    def __init__(self, base_url: str, timeout: float = 5.0):
        self._client = httpx.AsyncClient(base_url=base_url, timeout=timeout)

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

    async def list_rules(self):
        return await self._request("GET", "/api/collection-tasks/rules")

    async def check_replied(self, chat_id, task_type, task_key) -> bool:
        data = await self._request("POST", "/api/collection-tasks/check",
                                   json={
                                       "chatId": chat_id,
                                       "taskType": task_type,
                                       "taskKey": task_key
                                   })
        return bool(data["replied"])

    async def mark_replied(self, chat_id, task_type, task_key):
        await self._request("POST", "/api/collection-tasks/mark",
                            json={
                                "chatId": chat_id,
                                "taskType": task_type,
                                "taskKey": task_key
                            })

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
