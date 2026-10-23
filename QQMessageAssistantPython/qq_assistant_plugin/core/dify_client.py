import httpx


class DifyClient:
    def __init__(self, base_url: str, api_key: str, timeout: float):
        self._client = httpx.AsyncClient(
            base_url=base_url,
            timeout=timeout,
            headers={"Authorization": f"Bearer {api_key}"}
        )

    async def close(self):
        await self._client.aclose()

    async def chat(self, query: str, user: str, conversation_id=None, inputs=None):
        payload = {
            "inputs": inputs or {},
            "query": query,
            "response_mode": "blocking",
            "user": user
        }
        if conversation_id:
            payload["conversation_id"] = conversation_id
        response = await self._client.post("/chat-messages", json=payload)
        response.raise_for_status()
        return response.json()

    async def run_workflow(self, workflow_api_key: str, inputs: dict, user: str):
        """文件工作流（独立 App Key）：structure/facts/request → fills/missing/confidence。"""
        response = await self._client.post(
            "/workflows/run",
            json={"inputs": inputs, "response_mode": "blocking", "user": user},
            headers={"Authorization": f"Bearer {workflow_api_key}"},
        )
        response.raise_for_status()
        return response.json()
