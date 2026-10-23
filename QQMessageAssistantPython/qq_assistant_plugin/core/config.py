from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    spring_base_url: str
    spring_timeout: float
    dify_base_url: str
    dify_api_key: str
    dify_timeout: float
    dify_file_workflow_key: str
    file_confirm_threshold: float
    bot_uuid: str
    bot_qq: int
    group_prefix: str
    fact_auto_threshold: float
    delay_base_ms: int
    delay_per_char_ms: int
    delay_jitter_ms: int
    delay_max_ms: int
    intercept_refresh_s: int
    intercept_scope: str          # group | all | off


def load_settings(plugin) -> Settings:
    c = plugin.get_config()
    return Settings(
        spring_base_url=str(c.get("spring_base_url", "http://localhost:8080")).rstrip("/"),
        spring_timeout=float(c.get("spring_timeout", 5)),
        dify_base_url=str(c.get("dify_base_url", "http://localhost/v1")).rstrip("/"),
        dify_api_key=str(c.get("dify_api_key", "app-9dVmcLyV3dcA9tdvd4HjyBMO")),
        dify_timeout=float(c.get("dify_timeout", 60)),
        dify_file_workflow_key=str(c.get("dify_file_workflow_key", "")),
        file_confirm_threshold=float(c.get("file_confirm_threshold", 0.85)),
        bot_uuid=str(c.get("bot", "")),
        bot_qq=int(c.get("bot_qq", 0)),
        group_prefix=str(c.get("group_prefix", "!")),
        fact_auto_threshold=float(c.get("fact_auto_threshold", 0.85)),
        delay_base_ms=int(c.get("delay_base_ms", 1500)),
        delay_per_char_ms=int(c.get("delay_per_char_ms", 150)),
        delay_jitter_ms=int(c.get("delay_jitter_ms", 1000)),
        delay_max_ms=int(c.get("delay_max_ms", 15000)),
        intercept_refresh_s=int(c.get("intercept_refresh_s", 60)),
        intercept_scope=str(c.get("intercept_scope", "group")),
    )