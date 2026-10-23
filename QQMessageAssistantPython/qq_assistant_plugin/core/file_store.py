"""文件任务持久化：任务 JSON 存 LangBot 存储 API，工作文件落 data/files/<taskRef>/。

key 约定：
- file_task:{taskRef}   任务全量 JSON
- file_input:{senderId} 该用户当前 NEED_INPUT 的 taskRef（空串=无）
"""
import json
import uuid
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent / "data" / "files"


def new_task_ref() -> str:
    return "ft_" + uuid.uuid4().hex[:8]


def task_dir(task_ref: str) -> Path:
    d = BASE / task_ref
    d.mkdir(parents=True, exist_ok=True)
    return d


async def save_task(plugin, task: dict):
    await plugin.set_plugin_storage(f"file_task:{task['taskRef']}",
                                    json.dumps(task, ensure_ascii=False).encode())


async def load_task(plugin, task_ref: str) -> dict | None:
    try:
        raw = await plugin.get_plugin_storage(f"file_task:{task_ref}")
        return json.loads(raw.decode()) if raw else None
    except Exception:
        return None


async def set_active_input(plugin, sender_id: int, task_ref: str | None):
    await plugin.set_plugin_storage(f"file_input:{sender_id}", (task_ref or "").encode())


async def load_active_input(plugin, sender_id: int) -> str | None:
    try:
        raw = await plugin.get_plugin_storage(f"file_input:{sender_id}")
        ref = raw.decode() if raw else ""
        return ref or None
    except Exception:
        return None
