# QQ 消息智能助手 LangBot 插件入口。
# 职责：创建并持有全部单例（配置/客户端/队列/规则缓存/分发器），
# 组件（EventListener）通过 self.plugin.<attr> 取用。
from __future__ import annotations

import asyncio
import importlib.util
import logging
import os

import httpx

from langbot_plugin.api.definition.plugin import BasePlugin

from core import file_store
from core.config import load_settings, read_plugin_config
from core.dify_client import DifyClient
from core.dispatch import Dispatcher
from core.logging_queue import MessageLogQueue
from core.reply import reply_text
from core.rules import RuleCache
from core.spring_client import SpringClient

log = logging.getLogger(__name__)


class qqassistantplugin(BasePlugin):

    async def initialize(self) -> None:
        # 由运行器在 config 注入后调用（controller: plugin_instance.config 先于 initialize）
        await super().initialize()
        # 诊断：
        # 注意不能直接用 self.get_config()：initialize() 期间它返回的是 invocation
        # 作用域里的空 config（dedicated worker 注册容器 plugin_config={}），
        # 会把运行器刚赋值给 _legacy_config 的真配置遮掉，
        # 导致所有配置退化成默认值（dify key 为空 → 文件任务必失败）。
        raw_cfg, cfg_source = read_plugin_config(self)
        cfg_summary = {k: ("<set>" if str(v).strip() else "<empty>")
                       for k, v in sorted(raw_cfg.items())}
        log.info("plugin config source=%s keys=%s", cfg_source, cfg_summary)
        self.settings = load_settings(self)
        # 插件包目录在 LangBot 里通常是只读 artifact；数据统一写可写目录。
        # 注意：worker 的 env 白名单不含 USERPROFILE/LOCALAPPDATA，Path.home() 会抛
        # RuntimeError（不是 OSError），所以这里必须捕 Exception，否则整个插件
        # 初始化失败、安装卡在「启动并刷新组件」。
        try:
            file_store.configure(self.settings.data_dir)
        except Exception as e:
            log.error("data_dir 不可写 %r: %r，回退到自动探测目录", self.settings.data_dir, e)
            file_store.configure("")
        file_store.debug(f"plugin initialize: data_dir={file_store.BASE}")
        file_store.debug(f"CONFIG source={cfg_source} keys={cfg_summary}")
        log.info("数据目录: %s（worker env: home=%r localappdata=%r storage=%r proxy=%r/%r）",
                 file_store.BASE,
                 os.environ.get("USERPROFILE"),
                 os.environ.get("LOCALAPPDATA"),
                 os.environ.get("LANGBOT_PLUGIN_FILE_STORAGE_DIR"),
                 os.environ.get("HTTP_PROXY") or os.environ.get("http_proxy"),
                 os.environ.get("NO_PROXY") or os.environ.get("no_proxy"))
        self.spring = SpringClient(self.settings.spring_base_url,
                                   self.settings.spring_timeout)
        self.dify = DifyClient(self.settings.dify_base_url,
                               self.settings.dify_api_key,
                               self.settings.dify_timeout)

        self.log_queue = MessageLogQueue(self.spring)
        self.log_queue.start()

        self.rule_cache = RuleCache(self.spring)
        # 不能 await：Spring Boot 没启动时会阻塞最多 spring_timeout 秒，
        # LangBot 的插件初始化可能因此超时，整个插件收不到任何事件。
        self._initial_rule_task = asyncio.create_task(self.rule_cache.refresh())

        self.dispatcher = Dispatcher(self.spring, self.settings, reply_text)

        for module in ("openpyxl", "docx", "pypdf"):
            if importlib.util.find_spec(module) is None:
                log.warning("缺少文件依赖 %s：文件任务会在处理时失败，请检查插件 requirements", module)

        self._rule_task = asyncio.create_task(self._refresh_rules_loop())
        log.info("QQ assistant plugin initialized: spring=%s dify=%s scope=%s napcat=%s difyWf=%s data=%s",
                 self.settings.spring_base_url,
                 self.settings.dify_base_url,
                 self.settings.intercept_scope,
                 self.settings.napcat_api_url or "<未配置，文件下载不可用>",
                 "已配置（可选，Chatflow 内部调用）"
                 if (self.settings.dify_file_workflow_key or "").strip()
                 else "<未配置（新架构由 Chatflow 内部调用，可留空）>",
                 file_store.BASE)
        if not self.settings.dify_api_key:
            log.warning("dify_api_key 为空：普通聊天会失败（文件工作流不受影响）")
        # 同一个 Key 不可能既是 chat 应用又是 workflow 应用；实机就踩过：
        # 两处填了同一个 workflow Key → /chat-messages 报 not_chat_app，QQ 静默不回复
        if (self.settings.dify_api_key
                and self.settings.dify_api_key == self.settings.dify_file_workflow_key):
            log.warning("dify_api_key 与 dify_file_workflow_key 完全相同："
                        "聊天会报 not_chat_app（需单独建一个聊天助手应用）")
            file_store.debug("CONFIG dify_api_key == dify_file_workflow_key（聊天应用未配置）")
        # 启动自检：只发 httpx 请求，不碰插件 API，不需要 invocation
        self._napcat_check_task = asyncio.create_task(self._check_napcat())
        self._dify_check_task = asyncio.create_task(self._check_dify())

    async def _check_napcat(self) -> None:
        """启动时验一次 NapCat HTTP（下载/回传文件唯一通道），结果直接打日志。"""
        base = self.settings.napcat_api_url
        if not base:
            log.warning("NapCat HTTP 未配置：文件任务无法下载/回传附件")
            return
        headers = {}
        if self.settings.napcat_token:
            headers["Authorization"] = f"Bearer {self.settings.napcat_token}"
        log.info("NapCat 鉴权: token=%s",
                 f"已设置({len(self.settings.napcat_token)}位)"
                 if self.settings.napcat_token else "空（NapCat 开了鉴权就会 403）")
        try:
            # trust_env=False：NapCat 是本机服务，不能被系统代理接管
            # （Clash 等会让 127.0.0.1 请求经代理，表现成 502 Bad Gateway）
            async with httpx.AsyncClient(timeout=5, trust_env=False) as client:
                resp = await client.post(f"{base}/get_login_info",
                                         json={}, headers=headers)
                resp.raise_for_status()
                data = (resp.json() or {}).get("data") or {}
            log.info("NapCat HTTP OK: %s (self_id=%s)", base, data.get("user_id"))
            file_store.debug(f"NAPCAT OK {base} self_id={data.get('user_id')}")
        except httpx.HTTPStatusError as e:
            log.warning("NapCat HTTP 拒绝 %s × %s（服务端返回: %s）",
                        base, e.response.status_code, (e.response.text or "")[:200])
            file_store.debug(f"NAPCAT {e.response.status_code} {base} "
                             f"{(e.response.text or '')[:200]}")
        except Exception as e:
            log.warning("NapCat HTTP 不可用 %s: %r（检查端口/地址/代理；否则文件任务必失败）",
                        base, e)
            file_store.debug(f"NAPCAT 不可用 {base}: {e!r}")

    async def _check_dify(self) -> None:
        """启动时确认聊天 Key 指向正确的 App 类型，避免上线后才发现 400。

        文件工作流现在由 Chatflow 的 Tool 节点内部调用，插件不再直接使用
        dify_file_workflow_key；该配置仅保留兼容，留空不影响文件任务。
        """
        checks = (
            ("dify_api_key", self.settings.dify_api_key, {"chat", "advanced-chat", "agent-chat"}),
        )
        for name, key, expected in checks:
            if not key:
                log.warning("Dify 自检：%s 为空", name)
                file_store.debug(f"DIFY 自检 {name} 为空")
                continue
            try:
                info = await self.dify.app_info(key)
                mode = info.get("mode") or "<unknown>"
                if mode not in expected:
                    log.warning("Dify 自检：%s 指向的 App「%s」mode=%s，期望 %s",
                                name, info.get("name"), mode, "/".join(sorted(expected)))
                    file_store.debug(
                        f"DIFY 自检 {name} App「{info.get('name')}」mode={mode}，"
                        f"期望 {'/'.join(sorted(expected))}")
                else:
                    log.info("Dify 自检：%s OK（%s / %s）", name, info.get("name"), mode)
                    file_store.debug(f"DIFY 自检 {name} OK（{info.get('name')} / {mode}）")
            except Exception as e:
                log.warning("Dify 自检：%s 调用 /info 失败：%s", name, e)
                file_store.debug(f"DIFY 自检 {name} /info 失败：{e}")

    async def _refresh_rules_loop(self) -> None:
        # RuleCache.refresh 内部已吞异常，循环不会因单次拉取失败而终止
        while True:
            await asyncio.sleep(self.settings.intercept_refresh_s)
            await self.rule_cache.refresh()

    async def terminate(self) -> None:
        # 注意：当前 SDK 版本（langbot_plugin 0.7.x）没有 async 关闭钩子，
        # 本方法不会被自动调用；保留它以备升级或手动清理。
        # 日志队列每 1s flush，进程被杀最多丢 1 秒日志，可接受。
        self._rule_task.cancel()
        self._initial_rule_task.cancel()
        self._napcat_check_task.cancel()
        self._dify_check_task.cancel()
        await self.log_queue.stop()
        await self.spring.close()
        await self.dify.close()
