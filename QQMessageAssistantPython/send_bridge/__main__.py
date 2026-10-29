"""`python -m send_bridge`：按配置起 uvicorn。"""

import uvicorn

from .config import settings

if __name__ == "__main__":
    uvicorn.run("send_bridge.app:app", host=settings.host, port=settings.port)
