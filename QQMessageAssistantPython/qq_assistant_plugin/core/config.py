from dataclasses import dataclass


def _text(value, default=""):
    return str(value if value is not None else default or "").strip()


def _number(value, default, cast):
    if value is None or str(value).strip() == "":
        return default
    try:
        return cast(value)
    except (TypeError, ValueError):
        return default


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
    napcat_api_url: str = ""     # NapCat HTTP API（文件下载/回传必需，留空则禁用）
    napcat_token: str = ""
    data_dir: str = ""           # 插件数据根目录（留空=用户目录，避免写只读插件包）


def scoped_config(plugin) -> dict:
    """SDK 的 get_config()：invocation 作用域内的 config 快照。"""
    try:
        return dict(plugin.get_config() or {})
    except Exception:
        return {}


def host_config(plugin) -> dict:
    """运行器直接赋值给 plugin.config 的配置（controller: plugin_instance.config = ...）。"""
    return dict(getattr(plugin, "_legacy_config", None) or {})


def read_plugin_config(plugin) -> tuple[dict, str]:
    """返回 (配置字典, 来源告警说明)。

    坑（实机 0.2.14 踩过）：initialize() 执行时 SDK 已把 invocation 作用域绑成
    worker 容器的 plugin_config，而 dedicated worker 注册时那个值是 {}；
    BasePlugin.get_config() 会优先返回这个空快照，把运行器刚赋给 _legacy_config
    的真配置完全遮住 —— 结果所有配置项都退化成代码里的默认值
    （dify_file_workflow_key 为空 → 文件任务必失败；napcat/spring 地址看似正常，
    只是因为默认值刚好对）。所以这里以 _legacy_config 为准。
    """
    host = host_config(plugin)
    scoped = scoped_config(plugin)
    if host and len(host) >= len(scoped):
        return host, "host"
    if scoped:
        return scoped, "scoped"
    return {}, "empty"


def load_settings(plugin) -> Settings:
    c, _ = read_plugin_config(plugin)
    # 配置面板里未填的可选项可能是空串/None；不能让它把插件初始化直接炸掉
    return Settings(
        spring_base_url=_text(c.get("spring_base_url"), "http://localhost:8080").rstrip("/"),
        spring_timeout=_number(c.get("spring_timeout"), 5.0, float),
        dify_base_url=_text(c.get("dify_base_url"), "http://localhost/v1").rstrip("/"),
        # 不在代码里留任何默认 key；缺失时由 DifyClient 报可读错误
        dify_api_key=_text(c.get("dify_api_key")),
        dify_timeout=_number(c.get("dify_timeout"), 60.0, float),
        dify_file_workflow_key=_text(c.get("dify_file_workflow_key")),
        file_confirm_threshold=_number(c.get("file_confirm_threshold"), 0.85, float),
        bot_uuid=_text(c.get("bot")),
        bot_qq=_number(c.get("bot_qq"), 0, int),
        group_prefix=_text(c.get("group_prefix"), "!") or "!",
        fact_auto_threshold=_number(c.get("fact_auto_threshold"), 0.85, float),
        delay_base_ms=_number(c.get("delay_base_ms"), 1500, int),
        delay_per_char_ms=_number(c.get("delay_per_char_ms"), 150, int),
        delay_jitter_ms=_number(c.get("delay_jitter_ms"), 1000, int),
        delay_max_ms=_number(c.get("delay_max_ms"), 15000, int),
        intercept_refresh_s=_number(c.get("intercept_refresh_s"), 60, int),
        intercept_scope=_text(c.get("intercept_scope"), "group") or "group",
        napcat_api_url=_text(c.get("napcat_api_url"), "http://127.0.0.1:3000").rstrip("/"),
        napcat_token=_text(c.get("napcat_token")),
        data_dir=_text(c.get("data_dir")),
    )