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


def _flag(value, default: bool) -> bool:
    """布尔开关。不能用 `bool(value)`：配置面板传下来的 "false" 是真值。"""
    if value is None or str(value).strip() == "":
        return default
    return str(value).strip().lower() not in ("false", "0", "no", "off")


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
    # 前置噪音判定走 Spring /api/agent/noise-filter/check，插件不再缓存拦截规则
    napcat_api_url: str = ""     # NapCat HTTP API（文件下载/回传必需，留空则禁用）
    napcat_token: str = ""
    spring_agent_token: str = ""  # 调 Spring 业务接口的 X-Agent-Token，与 AGENT_OPS_TOKEN 相同
    data_dir: str = ""           # 插件数据根目录（留空=用户目录，避免写只读插件包）
    # 掉线补拉：NapCat 掉线期间 QQ 根本没推消息给它，重新上线后只能靠回查历史补。
    # 关掉后插件完全不碰历史接口（不影响文件下载/回传）。
    napcat_backfill_enabled: bool = True
    napcat_backfill_interval_s: int = 10   # 轮询 get_status / get_login_info 的间隔（本机 HTTP，几乎零成本）
    napcat_backfill_start_delay_s: int = 10  # 启动后先等一会，给 Spring / NapCat 就绪的时间
    # 兜底补拉间隔：即使探活边沿一直不出现（登录过期 / WS 断开被漏采样），
    # 也会周期性回查一次历史。补拉幂等，重复跑只多几次本机 HTTP。
    napcat_backfill_sweep_s: int = 300
    # Dify 的 /chat-messages 不回传模型名，账本里这一列只能由部署者自己标；
    # 留空就显示「未标注」，不猜一个写进去。
    dify_chat_model: str = ""
    token_usage_enabled: bool = True  # 关掉后插件完全不上报用量（Spring 侧记账不受影响）


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
        napcat_api_url=_text(c.get("napcat_api_url"), "http://127.0.0.1:3000").rstrip("/"),
        napcat_token=_text(c.get("napcat_token")),
        spring_agent_token=_text(c.get("spring_agent_token")),
        data_dir=_text(c.get("data_dir")),
        napcat_backfill_enabled=_flag(c.get("napcat_backfill_enabled"), True),
        napcat_backfill_interval_s=_number(c.get("napcat_backfill_interval_s"), 10, int),
        napcat_backfill_start_delay_s=_number(c.get("napcat_backfill_start_delay_s"), 10, int),
        napcat_backfill_sweep_s=_number(c.get("napcat_backfill_sweep_s"), 300, int),
        dify_chat_model=_text(c.get("dify_chat_model")),
        token_usage_enabled=_flag(c.get("token_usage_enabled"), True),
    )