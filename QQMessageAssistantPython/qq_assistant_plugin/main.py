# QQ 消息智能助手 LangBot 插件入口。
# 职责：创建并持有全部单例（配置/客户端/队列/规则缓存/分发器），
# 组件（EventListener）通过 self.plugin.<attr> 取用。
from __future__ import annotations

import asyncio
import logging

from langbot_plugin.api.definition.plugin import BasePlugin

from core.config import load_settings
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
        self.settings = load_settings(self)
        self.spring = SpringClient(self.settings.spring_base_url,
                                   self.settings.spring_timeout)
        self.dify = DifyClient(self.settings.dify_base_url,
                               self.settings.dify_api_key,
                               self.settings.dify_timeout)

        self.log_queue = MessageLogQueue(self.spring)
        self.log_queue.start()

        self.rule_cache = RuleCache(self.spring)
        await self.rule_cache.refresh()

        self.dispatcher = Dispatcher(self.spring, self.settings, reply_text)

        self._rule_task = asyncio.create_task(self._refresh_rules_loop())
        log.info("QQ assistant plugin initialized: spring=%s dify=%s scope=%s",
                 self.settings.spring_base_url,
                 self.settings.dify_base_url,
                 self.settings.intercept_scope)

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
        await self.log_queue.stop()
        await self.spring.close()
        await self.dify.close()
