"""文件任务持久化：任务 JSON 与工作文件全部落可写数据目录。

为什么不用宿主 plugin storage：LangBot 的 API 绑定在 invocation 生命周期上
（handler 返回后 active=False，任何调用报 "Plugin invocation has ended"），
且文件任务本来就需要工作目录，落盘可直接查看、重启可续。

为什么不用插件目录：LangBot 把插件解压到只读 artifact，写 __file__ 附近会
PermissionError。

数据目录按以下顺序取第一个**可写**的（见 _candidate_roots）：
1. QQ_ASSISTANT_DATA_DIR 环境变量
2. data_dir 配置（configure 传入）
3. <LangBot data>/qq_assistant/data  ← 由 LANGBOT_PLUGIN_FILE_STORAGE_DIR 反推
4. %LOCALAPPDATA%/qq_assistant/data（Linux 为 $XDG_DATA_HOME 或 ~/.local/share）
5. 系统临时目录

第 3 条最关键：LangBot 的 Windows 插件 worker 用白名单 env 启动
（worker_launcher._WINDOWS_WORKER_ENV_ALLOWLIST 只有 COMSPEC/PATH/PATHEXT/
SYSTEMROOT/WINDIR），**没有 USERPROFILE/LOCALAPPDATA/TEMP**，Path.home() 会抛
RuntimeError("Could not determine home directory.")，插件因此连初始化都过不去。
运行器唯一传进来的路径线索是 LANGBOT_PLUGIN_FILE_STORAGE_DIR。

文件约定：
- <data>/files/<taskRef>/task.json   任务全量 JSON
- <data>/files/_active/<sender>.txt  该用户当前 NEED_INPUT 的 taskRef（空串=无）
"""
import hashlib
import json
import os
import time
import uuid
from datetime import datetime
from pathlib import Path


def _safe_path(raw: str) -> Path:
    """expanduser 在没有 USERPROFILE/HOME 的进程里会抛 RuntimeError，不能让它冒泡。"""
    try:
        return Path(raw).expanduser()
    except (RuntimeError, OSError, KeyError):
        return Path(raw)


def _home() -> Path | None:
    for name in ("USERPROFILE", "HOME"):
        value = os.environ.get(name, "").strip()
        if value:
            return _safe_path(value)
    try:
        return Path.home()
    except (RuntimeError, OSError, KeyError):
        return None


def _langbot_data_root() -> Path | None:
    """从运行器给的 rpc-transfer 目录反推 LangBot 数据根目录。

    形如 <data>/plugin-runtime/installations/<id>/rpc-transfer → <data>。
    installations/<id> 会随重装被删，所以要往上走到 plugin-runtime 的父目录。
    """
    raw = os.environ.get("LANGBOT_PLUGIN_FILE_STORAGE_DIR", "").strip()
    if not raw:
        return None
    path = _safe_path(raw)
    for parent in path.parents:
        if parent.name == "plugin-runtime":
            return parent.parent
    return path.parent


def _candidate_roots() -> list[Path]:
    """按可靠性排序的候选根目录，落盘位置统一为 <root>/files。"""
    roots: list[Path] = []
    env_dir = os.environ.get("QQ_ASSISTANT_DATA_DIR", "").strip()
    if env_dir:
        roots.append(_safe_path(env_dir))
    langbot_root = _langbot_data_root()
    if langbot_root is not None:
        roots.append(langbot_root / "qq_assistant" / "data")
    local = os.environ.get("LOCALAPPDATA", "").strip()
    if local:
        roots.append(_safe_path(local) / "qq_assistant" / "data")
    xdg = os.environ.get("XDG_DATA_HOME", "").strip()
    if xdg:
        roots.append(_safe_path(xdg) / "qq_assistant" / "data")
    home = _home()
    if home is not None:
        if os.name == "nt":
            roots.append(home / "qq_assistant" / "data")
        else:
            roots.append(home / ".local" / "share" / "qq_assistant" / "data")
    try:
        import tempfile
        roots.append(_safe_path(tempfile.gettempdir()) / "qq_assistant" / "data")
    except Exception:
        pass
    # 最后兜底：插件包目录（只读时会在 _ensure 里失败并被跳过）
    roots.append(_safe_path(str(Path(__file__).resolve().parent.parent / "data")))
    return roots


def _ensure(path: Path) -> Path:
    """建目录并真实写一次，确认可写；不可写抛异常供调用方跳过。"""
    path.mkdir(parents=True, exist_ok=True)
    probe = path / ".writable"
    probe.write_text("ok", encoding="utf-8")
    probe.unlink(missing_ok=True)
    return path.resolve()


def default_base() -> Path:
    """选第一个可写目录；全不可写时返回第一个候选（让后续写盘报出真实错误）。"""
    roots = _candidate_roots()
    for root in roots:
        try:
            return _ensure(root / "files")
        except Exception:
            continue
    return roots[0] / "files"


BASE = default_base()


def configure(data_dir: str = "") -> Path:
    """由 main.initialize 调用；data_dir 为空时用 default_base()。"""
    global BASE
    BASE = _ensure(_safe_path(data_dir) / "files") if data_dir else default_base()
    return BASE


def debug(msg: str) -> None:
    """落盘面包屑。路径在可写目录，LangBot 日志看不到时靠它定位。"""
    try:
        BASE.mkdir(parents=True, exist_ok=True)
        ts = datetime.now().strftime("%H:%M:%S.%f")[:-3]
        with open(BASE / "_debug.log", "a", encoding="utf-8") as f:
            f.write(f"[{ts}] {msg}\n")
    except Exception:
        pass


def new_task_ref() -> str:
    return "ft_" + uuid.uuid4().hex[:8]


def task_dir(task_ref: str) -> Path:
    d = BASE / task_ref
    d.mkdir(parents=True, exist_ok=True)
    return d


def _active_path(sender_id: int) -> Path:
    return BASE / "_active" / f"{sender_id}.txt"


async def save_task(deps, task: dict):
    path = task_dir(task["taskRef"]) / "task.json"
    path.write_text(json.dumps(task, ensure_ascii=False), encoding="utf-8")


async def load_task(deps, task_ref: str) -> dict | None:
    try:
        raw = (BASE / task_ref / "task.json").read_text(encoding="utf-8")
        return json.loads(raw) if raw else None
    except Exception:
        return None


async def set_active_input(deps, sender_id: int, task_ref: str | None):
    path = _active_path(sender_id)
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(task_ref or "", encoding="utf-8")


async def load_active_input(deps, sender_id: int) -> str | None:
    try:
        ref = _active_path(sender_id).read_text(encoding="utf-8").strip()
        return ref or None
    except Exception:
        return None


# ---------- 待填要求备份 ----------
# Dify 会话变量是唯一真身；这里只是本地备份，用于 Dify 会话被重置/重建后
# 仍能把「用户先前说的填写要求」通过 pending_request_hint 带过去。

def _pending_path(conversation_key: str) -> Path:
    # conversation_key 含 | 和数字，Windows 可用，但统一哈希避免长度/字符问题
    digest = hashlib.sha1((conversation_key or "").encode("utf-8")).hexdigest()
    return BASE / "_pending" / f"{digest}.json"


async def save_pending(deps, conversation_key: str, request: str,
                       ttl_seconds: int = 900, task_ref: str = ""):
    path = _pending_path(conversation_key)
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "request": (request or "").strip(),
        "taskRef": task_ref or "",
        "savedAt": time.time(),
        "expiresAt": time.time() + max(int(ttl_seconds or 0), 60),
    }
    path.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")


async def load_pending(deps, conversation_key: str) -> dict | None:
    path = _pending_path(conversation_key)
    try:
        payload = json.loads(path.read_text(encoding="utf-8"))
    except Exception:
        return None
    if not isinstance(payload, dict):
        return None
    expires = float(payload.get("expiresAt") or 0)
    if expires and expires < time.time():
        path.unlink(missing_ok=True)
        return None
    return payload


async def clear_pending(deps, conversation_key: str):
    _pending_path(conversation_key).unlink(missing_ok=True)
