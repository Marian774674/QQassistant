import httpx


class DifyClient:
    def __init__(self, base_url: str, api_key: str, timeout: float):
        self._api_key = (api_key or "").strip()
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            headers={"Authorization": f"Bearer {self._api_key}"}
        )

    async def close(self):
        await self._client.aclose()

    @staticmethod
    def _raise_for_dify(response: httpx.Response, path: str):
        if response.status_code >= 400:
            # raise_for_status 会吞掉 Dify 的 code/message（如 not_chat_app），
            # 这里把响应体带进错误，QQ 里能直接看到根因。
            body = (response.text or "").strip()
            raise RuntimeError(f"Dify {path} HTTP {response.status_code}: {body[:500]}")

    async def app_info(self, api_key: str) -> dict:
        """读取 App 元信息（name/mode），用于启动自检配置是否用错应用。"""
        key = (api_key or "").strip()
        if not key:
            return {}
        response = await self._client.get("/info", headers={"Authorization": f"Bearer {key}"})
        self._raise_for_dify(response, "/info")
        return response.json()

    async def chat(self, query: str, user: str, conversation_id=None, inputs=None):
        if not self._api_key:
            raise RuntimeError("未配置 Dify 聊天应用 API Key（dify_api_key）")
        payload = {
            "inputs": inputs or {},
            "query": query,
            "response_mode": "blocking",
            "user": user,
            # 不要每条消息都让 Dify 生成会话名：既浪费调用，也会让日志难对照
            "auto_generate_name": False,
        }
        if conversation_id:
            payload["conversation_id"] = conversation_id
        response = await self._client.post("/chat-messages", json=payload)
        self._raise_for_dify(response, "/chat-messages")
        return response.json()

    async def run_workflow(self, workflow_api_key: str, inputs: dict, user: str):
        """文件工作流（独立 App Key）：structure/facts/request → fills/missing/confidence。"""
        key = (workflow_api_key or "").strip()
        if not key:
            # 空 key 会拼出 "Bearer "，httpx 只报 Illegal header value，极难定位
            raise RuntimeError("未配置 Dify 文件工作流 API Key（dify_file_workflow_key）")
        response = await self._client.post(
            "/workflows/run",
            json={"inputs": inputs, "response_mode": "blocking", "user": user},
            headers={"Authorization": f"Bearer {key}"},
        )
        self._raise_for_dify(response, "/workflows/run")
        return response.json()
