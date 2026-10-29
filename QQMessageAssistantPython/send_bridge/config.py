import os
from dataclasses import dataclass
from pathlib import Path

# 幂等状态默认放在包内 data/ 的绝对路径：跟启动目录无关，换目录启动也不会丢幂等
_PACKAGE_DIR = Path(__file__).resolve().parent
DEFAULT_STATE_FILE = str(_PACKAGE_DIR / "data" / "sent.json")


def _env_bool(name: str, default: bool = False) -> bool:
    raw = os.getenv(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "y", "on"}


@dataclass(frozen=True)
class Settings:
    host: str = os.getenv("BRIDGE_HOST", "127.0.0.1")
    port: int = int(os.getenv("BRIDGE_PORT", "5301"))

    langbot_base: str = os.getenv("LANGBOT_BASE", "http://localhost:5300").rstrip("/")
    langbot_bot_uuid: str = os.getenv("LANGBOT_BOT_UUID", "")
    langbot_api_key: str = os.getenv("LANGBOT_API_KEY", "")

    napcat_base: str = os.getenv("NAPCAT_BASE", "http://localhost:3000").rstrip("/")
    napcat_token: str = os.getenv("NAPCAT_TOKEN", "")

    spring_base: str = os.getenv("SPRING_BASE", "http://localhost:8080").rstrip("/")

    delay_base_ms: int = int(os.getenv("DELAY_BASE_MS", "1500"))
    delay_per_char_ms: int = int(os.getenv("DELAY_PER_CHAR_MS", "150"))
    delay_jitter_ms: int = int(os.getenv("DELAY_JITTER_MS", "1000"))
    delay_max_ms: int = int(os.getenv("DELAY_MAX_MS", "15000"))
    timeout: float = float(os.getenv("HTTP_TIMEOUT", "10"))

    # LangBot 官方 send_message 是否接受 File 组件；实测不支持时保持 false，直连 NapCat
    file_via_langbot: bool = _env_bool("FILE_VIA_LANGBOT", False)

    state_file: str = os.getenv("STATE_FILE", DEFAULT_STATE_FILE)
    idempotency_ttl: int = int(os.getenv("IDEMPOTENCY_TTL", "86400"))


settings = Settings()
