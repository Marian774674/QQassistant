"""NapCat 掉线补拉。

**边界**：NapCat 掉线期间 QQ 根本没把消息推给它，任何下游 MQ 都变不出来。
MQ 只能保证「已到达 NapCat / LangBot 的消息」在消费端重启或变慢时不丢——
那是另一个问题，当前单机单消费者没出现过。真正能补的是
**NapCat 重新上线后向 QQ 回查历史**，所以这里只做这一件事。

**为什么不进 Dify、不触发自动回复**：补回来的是几小时前的消息，
拿它们去跑语义决策只会得到一堆过时的回复，而且一次掉线可能就是几百条。
这些消息只写 `message_log`；整理、夜间归纳、向量索引都从 `message_log` 读，
它们会自然吃到补回来的内容——不需要在这里再接一遍。

**为什么放插件**：红线要求 Spring 不直连 NapCat，而插件本来就有
`napcat_api_url` 与鉴权配置；Spring 只负责「补哪些会话」和「补到哪了」。

**为什么不能只靠「离线 → 在线」边沿**：登录过期时 NapCat 进程和 HTTP 服务都还在，
`get_status` 可能一直报在线；掉登录到重登也可能落在两次 10s 探活之间被采样漏掉；
LangBot 与 NapCat 之间的 WS 断开时 NapCat 更是完全正常。这三种情况边沿都不出现，
补拉就永不执行——正是「NapCat 重新登录后，中间消息还是没被消费」的根因。
所以边沿只当「立即补」的加速信号，真正的保底是低频周期补拉。补拉本身幂等
（`dedupeKey = msg:<message_id>` + 游标 GREATEST），重复跑只多几次本机 HTTP。
"""
from __future__ import annotations

import asyncio
import logging
import time

from core import file_store

log = logging.getLogger(__name__)

# 硬上限（design/design_overview.md §5.4）：单次最多 50 个会话、每会话最多 200 条、最多翻 5 页。
# 不是为了省资源，是为了不打爆 QQ 接口——短时间密集拉历史很容易触发风控。
MAX_TARGETS = 50
PAGE_SIZE = 40
MAX_PAGES = 5
MAX_MESSAGES = 200
REQUEST_INTERVAL_S = 1.0

# 只给「人看的内容」留个占位：实时路径里图片/语音的正文是空的，
# 补回来再留空，整理时会被噪音过滤当成「纯标点/空串」丢掉。
_SEGMENT_TEXT = {
    "face": "[表情]",
    "mface": "[表情]",
    "record": "[语音]",
    "video": "[视频]",
    "forward": "[聊天记录]",
    "json": "[卡片消息]",
    "markdown": "[卡片消息]",
}


def _seq(message: dict) -> int:
    """NapCat 的 message_seq。翻页、排序、推进游标全靠它，不是时间戳。

    同一秒里可能有好几条消息，时间戳既不能排序也不能做「大于游标」的截断。
    """
    for key in ("message_seq", "real_seq"):
        value = message.get(key)
        if value is None:
            continue
        try:
            return int(value)
        except (TypeError, ValueError):
            continue
    return 0


def _message_id(message: dict) -> str:
    for key in ("message_id", "real_id", "id"):
        value = message.get(key)
        if value not in (None, ""):
            return str(value)
    return ""


def _sender(message: dict) -> dict:
    sender = message.get("sender")
    return sender if isinstance(sender, dict) else {}


def _sender_id(message: dict) -> int | None:
    sender = _sender(message)
    for key in ("user_id", "uin", "qq"):
        value = sender.get(key)
        if value is None:
            continue
        try:
            return int(value)
        except (TypeError, ValueError):
            continue
    return None


def _sender_name(message: dict) -> str:
    sender = _sender(message)
    for key in ("card", "nickname", "name"):
        value = sender.get(key)
        if value:
            return str(value)[:64]
    return ""


def _occurred_at(message: dict) -> int | None:
    value = message.get("time")
    try:
        return int(value) if value is not None else None
    except (TypeError, ValueError):
        return None


def _segments(message: dict) -> list:
    raw = message.get("message")
    if isinstance(raw, list):
        return raw
    return []


def _render(message: dict) -> tuple[str, str]:
    """返回 (正文, msgType)。

    msgType 用 `file` / `image` / `text`：`MessageNoiseFilter` 只对 `text` 做噪音判断，
    非 text 一律保留——补回来的图片和文件不该因为「正文里没字」被当成噪音。
    """
    parts: list[str] = []
    has_file = False
    has_image = False
    for segment in _segments(message):
        if not isinstance(segment, dict):
            continue
        kind = segment.get("type")
        data = segment.get("data") or {}
        if kind == "text":
            parts.append(str(data.get("text") or ""))
        elif kind == "at":
            qq = str(data.get("qq") or "")
            parts.append("@全体成员" if qq == "all" else "@" + qq)
        elif kind == "file":
            has_file = True
            name = str(data.get("name") or data.get("file") or "").strip()
            parts.append(f"[文件] {name}".strip())
        elif kind == "image":
            has_image = True
            parts.append("[图片]")
        elif kind in _SEGMENT_TEXT:
            parts.append(_SEGMENT_TEXT[kind])
    content = "".join(parts).strip()
    if not content:
        # 有些适配器只给 raw_message（CQ 码串）；宁可留一串原始文本，也别留空行
        content = str(message.get("raw_message") or "").strip()
    msg_type = "file" if has_file else ("image" if has_image else "text")
    return content, msg_type


class BackfillService:
    """轮询「服务可达 + 账号已登录」，边沿立即补，外加低频周期兜底补拉。"""

    def __init__(self, spring, napcat, settings, sleep=None, clock=None):
        self._spring = spring
        self._napcat = napcat
        self._settings = settings
        self._sleep = sleep or asyncio.sleep
        self._clock = clock or time.monotonic
        self._task = None
        # None = 还没探过。启动后第一次探到就绪也要补：
        # 插件重启时 NapCat 可能一直在跑，那段时间的消息一条都没进来。
        self._was_online = None
        # 上次真正跑过补拉的时刻；None 表示从没跑过，首轮必补。
        self._last_pull_at = None

    @property
    def enabled(self) -> bool:
        return (bool(getattr(self._settings, "napcat_backfill_enabled", True))
                and self._napcat.configured)

    def start(self) -> None:
        if not self.enabled:
            log.info("NapCat 补拉未启用（开关关掉，或 napcat_api_url 为空）")
            file_store.debug("BACKFILL disabled")
            return
        self._task = asyncio.create_task(self._run())
        log.info("NapCat 补拉已启动：每 %ss 探一次状态，每 %ss 兜底补拉一次",
                 self._interval, self._sweep_interval)

    async def stop(self) -> None:
        if self._task:
            self._task.cancel()
            self._task = None

    async def _run(self) -> None:
        # 先等一会：插件刚起来时 Spring 和 NapCat 往往都还没就绪，
        # 立刻探一次只会拿到「离线」，白白浪费掉「启动时补拉」这个时机
        await self._sleep(self._start_delay)
        while True:
            try:
                await self.tick()
            except asyncio.CancelledError:
                raise
            except Exception as e:
                log.warning("napcat backfill tick failed: %s", e)
                file_store.debug(f"BACKFILL tick failed: {e!r}")
            await self._sleep(self._interval)

    async def tick(self) -> bool:
        """探一次状态；返回本轮是否跑了补拉。

        探活**失败**必须当成「未就绪」，不能直接抛给上层：NapCat 重启期间
        HTTP 服务是关的，异常正是掉线信号。抛出去的话 `_was_online` 会一直
        停在 True，回来后 `online and not True` 恒为 False——永远不触发补拉，
        这是上一版「重启后补拉没反应」的原因。

        但边沿不能是唯一触发条件（见模块头）：登录过期、采样漏掉的重登、
        LangBot WS 断开，都不会产生 offline→online 边沿。所以只要持续就绪
        超过 `_sweep_interval`，就无条件再补一轮。
        """
        try:
            online = await self._napcat.online()
            logged_in = await self._napcat.account_online()
        except asyncio.CancelledError:
            raise
        except Exception as e:
            if self._was_online is not False:
                log.warning("NapCat 探活失败，按离线处理（恢复后补拉）：%s", e)
                file_store.debug(f"BACKFILL probe failed -> offline: {e!r}")
            self._was_online = False
            return False

        if not (online and logged_in):
            if self._was_online is not False:
                log.info("NapCat 未就绪（服务在线=%s，账号已登录=%s），暂停补拉",
                         bool(online), bool(logged_in))
                file_store.debug(
                    f"BACKFILL not ready online={bool(online)} login={bool(logged_in)}")
            self._was_online = False
            return False

        now = self._clock()
        edge = not self._was_online
        sweep_due = (self._last_pull_at is None
                     or now - self._last_pull_at >= self._sweep_interval)
        self._was_online = True
        if not (edge or sweep_due):
            return False

        reason = "上线/登录恢复" if edge else "周期兜底"
        log.info("NapCat 就绪（%s），开始补拉历史消息", reason)
        file_store.debug(f"BACKFILL start reason={reason}")
        try:
            await self.run_once()
        finally:
            # 失败也要压住重试风暴：Spring 挂了就等下一个周期再说，
            # 不能每 10s（探活间隔）打一轮 50 会话的历史接口。
            self._last_pull_at = now
        return True

    async def run_once(self) -> dict:
        """对全部目标补拉一轮；返回 {targets, saved} 便于日志与测试断言。"""
        try:
            targets = await self._spring.napcat_targets(MAX_TARGETS) or []
        except Exception as e:
            # 拿不到目标就别硬撑：说明 Spring 不可用，下一轮再说
            log.warning("fetch backfill targets failed: %s", e)
            file_store.debug(f"BACKFILL targets failed: {e!r}")
            return {"targets": 0, "saved": 0}

        summary = {"targets": len(targets), "saved": 0}
        for index, target in enumerate(targets):
            try:
                summary["saved"] += await self._backfill_target(target)
            except asyncio.CancelledError:
                raise
            except Exception as e:
                # 单会话失败只记日志：一个群的历史接口报错，不能把其它群一起拖住
                log.warning("backfill %s:%s failed: %s",
                            target.get("chatType"), target.get("chatId"), e)
                file_store.debug(
                    f"BACKFILL {target.get('chatType')}:{target.get('chatId')} failed: {e!r}")
            # 限速 1 req/s：补拉是后台任务，不能因为翻页把实时消息挤住
            if index < len(targets) - 1:
                await self._sleep(REQUEST_INTERVAL_S)
        log.info("补拉完成：%d 个会话，写入 %d 条", summary["targets"], summary["saved"])
        file_store.debug(f"BACKFILL done {summary}")
        return summary

    async def _backfill_target(self, target: dict) -> int:
        chat_type = str(target.get("chatType") or "").upper()
        chat_id = target.get("chatId")
        if chat_type not in ("GROUP", "PRIVATE") or chat_id is None:
            return 0
        chat_id = int(chat_id)
        cursor = int(target.get("lastMessageSeq") or 0)

        collected: list[dict] = []
        seen: set[str] = set()
        page_seq = 0
        for page in range(MAX_PAGES):
            messages = await self._history(chat_type, chat_id, page_seq)
            if not messages:
                break
            oldest = None
            for message in messages:
                if not isinstance(message, dict) or self._skip(message):
                    continue
                seq = _seq(message)
                if seq > 0:
                    oldest = seq if oldest is None else min(oldest, seq)
                if seq and seq <= cursor:
                    continue
                message_id = _message_id(message)
                if message_id:
                    if message_id in seen:
                        continue
                    seen.add(message_id)
                collected.append(message)
            if oldest is None or oldest <= cursor:
                break           # 已经翻到游标之前，收工
            if page > 0 and oldest >= page_seq:
                break           # 没有往前推进：再翻就是同一页，别打接口
            if len(collected) >= MAX_MESSAGES:
                break
            page_seq = oldest
            await self._sleep(REQUEST_INTERVAL_S)

        if not collected:
            return 0
        collected.sort(key=_seq)
        collected = collected[:MAX_MESSAGES]

        items = [item for item in
                 (self._item(m, chat_type, chat_id, target.get("chatName"))
                  for m in collected)
                 if item]
        if not items:
            return 0

        # 整页写成功才推进游标：写一半就推进，没写进去的那些消息永远不会再补。
        # 这里不走 log_queue（它是异步批量、失败只重试不报错），
        # 必须同步拿到「确实写进去了」这个结论。
        await self._spring.log_messages(items)
        max_seq = max(_seq(m) for m in collected)
        if max_seq > cursor:
            await self._spring.advance_napcat_cursor(chat_type, chat_id, max_seq)
        file_store.debug(
            f"BACKFILL {chat_type}:{chat_id} cursor {cursor}->{max_seq} "
            f"items={len(items)} pages={page + 1}")
        return len(items)

    async def _history(self, chat_type: str, chat_id: int, page_seq: int) -> list:
        if chat_type == "GROUP":
            return await self._napcat.group_history(chat_id, PAGE_SIZE, page_seq)
        return await self._napcat.friend_history(chat_id, PAGE_SIZE, page_seq)

    def _skip(self, message: dict) -> bool:
        """跳过不是「对方说的话」的消息。

        机器人自己的消息也会出现在历史里（NapCat 把会话里所有消息都给出来）。
        当成 IN 写进去，检索就会把机器人说过的话当成对方说的证据，
        整理也会把机器人的回复算进「今天群里聊了什么」——这正是
        `selectBySenders` 只取 IN 想避免的那类错误。
        """
        post_type = message.get("post_type")
        if post_type and post_type != "message":
            return True
        sender_id = _sender_id(message)
        return sender_id is not None and sender_id == self._bot_qq

    @property
    def _bot_qq(self) -> int:
        try:
            return int(getattr(self._settings, "bot_qq", 0) or 0)
        except (TypeError, ValueError):
            return 0

    @staticmethod
    def _item(message: dict, chat_type: str, chat_id: int, chat_name) -> dict | None:
        sender_id = _sender_id(message)
        if sender_id is None:
            return None
        message_id = _message_id(message)
        content, msg_type = _render(message)
        item = {
            "senderId": sender_id,
            "senderName": _sender_name(message),
            "chatId": chat_id if chat_type == "GROUP" else None,
            "direction": "IN",
            "msgType": msg_type,
            "content": content,
            # 实时与补拉共用同一个去重键，靠 message_log.uk_dedupe 吞掉重叠：
            # 补拉期间到达的实时消息与补拉页重叠是正常现象
            "dedupeKey": "msg:" + (message_id or f"{chat_type}:{chat_id}:{_seq(message)}"),
            "messageId": message_id or None,
        }
        if chat_type == "GROUP" and chat_name:
            item["chatName"] = chat_name
        occurred = _occurred_at(message)
        if occurred:
            item["createdAtEpochSeconds"] = occurred
        return item

    @property
    def _interval(self) -> int:
        return max(5, self._number("napcat_backfill_interval_s", 10))

    @property
    def _sweep_interval(self) -> int:
        """周期兜底补拉间隔。下限 30s：配 0 会让每轮探活都全量扫历史，容易触发风控。"""
        return max(30, self._number("napcat_backfill_sweep_s", 300))

    @property
    def _start_delay(self) -> int:
        return max(0, self._number("napcat_backfill_start_delay_s", 10))

    def _number(self, name: str, default: int) -> int:
        try:
            return int(getattr(self._settings, name, default))
        except (TypeError, ValueError):
            return default
