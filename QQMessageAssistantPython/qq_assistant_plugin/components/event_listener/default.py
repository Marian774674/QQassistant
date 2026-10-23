# 唯一事件监听器：接管 PersonMessageReceived / GroupMessageReceived。
# D1 决策：prevent_default + prevent_postorder 掐断 LangBot 内置管线，插件自己编排。
# 主流程顺序即优先级，勿调整（原因见各步注释）。
from __future__ import annotations

import asyncio
import logging

from langbot_plugin.api.definition.components.common.event_listener import EventListener
from langbot_plugin.api.entities import context, events

from core import closed_loop, file_store, file_task
from core.dispatch import parse_answer
from core.reply import reply_text
from core.rules import task_key
from core.text import extract_text, has_file, human_delay_seconds, is_at_bot

log = logging.getLogger(__name__)


class DefaultEventListener(EventListener):

    def __init__(self):
        super().__init__()
        # 注意：handler 装饰器是实例方法，只能在 __init__ 里注册，不能写在类体
        self.handler(events.PersonMessageReceived)(self._on_person)
        self.handler(events.GroupMessageReceived)(self._on_group)

    async def _on_person(self, ctx: context.EventContext):
        await self._handle(ctx, is_group=False)

    async def _on_group(self, ctx: context.EventContext):
        await self._handle(ctx, is_group=True)

    async def _handle(self, ctx: context.EventContext, is_group: bool):
        deps = self.plugin          # 单例容器，见 main.py
        settings = deps.settings

        event = ctx.event
        # 只接管 QQ 平台（sender/launcher 为数字 ID）；
        # WebChat 等非 QQ 来源（如 websocket_xxx）不 prevent_default，放行给内置管线
        try:
            sender_id = int(event.sender_id)
            chat_id = int(event.launcher_id) if is_group else None
        except (TypeError, ValueError):
            log.info("non-QQ sender %r, leave to default pipeline", event.sender_id)
            return

        # 0. 掐断内置管线（两连击，缺一不可）
        ctx.prevent_default()
        ctx.prevent_postorder()

        chain = event.message_chain
        text = extract_text(chain)

        # 1. IN 日志：同步入队，永不阻塞、永不抛错（排查依据，最先落）
        deps.log_queue.enqueue({
            "senderId": sender_id, "chatId": chat_id, "direction": "IN",
            "msgType": "file" if has_file(chain) else "text", "content": text,
        })

        # 2. 文件消息 → 文件任务状态机（Phase 3-2）异步处理，立即返回
        if has_file(chain):
            asyncio.create_task(file_task.handle(ctx, event, is_group, deps))
            return

        if not text:
            return

        # 2.5 私聊纯文本：若存在 NEED_INPUT 文件任务，优先收集补充信息，
        #     整段交给 Dify 对齐 missing，插件不做语义解析
        if not is_group:
            active = await file_store.load_active_input(deps, sender_id)
            if active and await file_task.provide_input(active, text, ctx, deps):
                return

        # 3. 私聊「完成/取消」闭环：不进 Dify，直接查 Spring 收尾
        if not is_group and closed_loop.classify(text):
            try:
                await closed_loop.handle(text, sender_id, ctx, deps)
            except Exception as e:
                log.exception("closed loop failed: %s", e)
            return

        # 4. 触发判定：群聊只响应 @机器人 或配置前缀
        if is_group:
            triggered = is_at_bot(chain, settings.bot_qq) \
                or text.startswith(settings.group_prefix)
        else:
            triggered = True

        # 5. 前置拦截。注意在 triggered 退出【之前】执行——
        #    群里发收集通知通常不会 @机器人，不 @ 也要能拦
        rule = None
        if settings.intercept_scope == "all" \
                or (settings.intercept_scope == "group" and is_group):
            rule = deps.rule_cache.match(text)
        if rule:
            await self._intercept(ctx, deps, rule, sender_id, chat_id)
            return

        if not triggered:
            return

        # 6. 画像 → Dify → 机械分发。整段兜底：任何异常只记日志，不回复
        #    （宁可不回，也不发半截/错的内容；用户可在草稿中心/日志排查）
        query = text.removeprefix(settings.group_prefix) if is_group else text
        try:
            profile = await deps.spring.profile(sender_id) or {}
            inputs = {
                "facts": profile.get("facts", {}),
                "style_prompt": profile.get("stylePrompt", ""),
            }
            result = await deps.dify.chat(query, user=str(sender_id), inputs=inputs)
            data = parse_answer(result.get("answer", ""))
            sent = await deps.dispatcher.handle(data, ctx, sender_id, chat_id)
            if sent:
                deps.log_queue.enqueue({
                    "senderId": sender_id, "chatId": chat_id, "direction": "OUT",
                    "msgType": "text", "content": sent,
                })
        except Exception as e:
            log.exception("handle message failed: %s", e)

    async def _intercept(self, ctx, deps, rule, sender_id, chat_id):
        """命中拦截规则：防重检查 → 拟人延迟回复 → mark → OUT 日志。

        发送动作绝不重试；check/mark 是幂等接口，失败只记日志。
        """
        template = (rule.get("replyTemplate") or "").strip()
        if not template:
            log.warning("rule %s has empty replyTemplate, skip", rule.get("name"))
            return
        try:
            # 私聊场景 chat_id 为 None，防重键退化为 sender 维度
            scope_id = chat_id if chat_id is not None else sender_id
            key = task_key(scope_id, rule["name"])
            if await deps.spring.check_replied(scope_id, rule["name"], key):
                return
            await asyncio.sleep(human_delay_seconds(template, deps.settings))
            await reply_text(ctx, template)
            await deps.spring.mark_replied(scope_id, rule["name"], key)
            deps.log_queue.enqueue({
                "senderId": sender_id, "chatId": chat_id, "direction": "OUT",
                "msgType": "text", "content": template,
            })
        except Exception as e:
            log.warning("intercept reply failed (rule=%s): %s", rule.get("name"), e)
