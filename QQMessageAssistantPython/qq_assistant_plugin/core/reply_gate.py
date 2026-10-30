"""出口门控：Dify 决定「说什么」，这里决定「说不说」。

语料来自 `GET /api/chat-settings/reply-context` 的快照（{@link core.reply_rules.normalize}）。
纯函数，不碰网络也不碰文件：所有判断都能在单测里一眼看完。

三条不能错的语义：

1. `TASK` 永远发。会话开关关的是「替主人说日常话」，不是「让它干活」——
   拿开关去卡备忘确认语，等于把群里的备忘功能一起关了。
2. 缺 `send_kind` 按 `TASK`。旧 Chatflow 连上来时，行为必须和今天一模一样。
3. `needs_confirm` 只对 `CHAT` 生效，且只在有内容时才建草稿。
"""
from dataclasses import dataclass, field

KINDS = ("TASK", "CHAT")
DEFAULT_RATE_LIMIT = 20


@dataclass
class GateResult:
    """门控结果。`data` 永远是新的字典——原信封还要给日志用，不能就地清空。"""

    data: dict
    send_kind: str
    #: None=照发；"off" / "rate" / "confirm"=没发，原因写进面包屑
    suppressed: str | None = None
    #: 非空表示要写一条 draft(type=REPLY)
    draft: dict | None = field(default=None)


def _silence(envelope: dict) -> dict:
    """保留动作与副作用，只把「要说的话」清掉。"""
    quiet = dict(envelope)
    quiet["content"] = ""
    return quiet


def decide(data: dict, reply_ctx: dict) -> GateResult:
    envelope = dict(data or {})
    send_kind = str(envelope.get("send_kind") or "TASK").strip().upper()
    if send_kind not in KINDS:
        send_kind = "TASK"

    if send_kind == "TASK":
        return GateResult(envelope, send_kind)

    if not reply_ctx.get("enabled"):
        return GateResult(_silence(envelope), send_kind, suppressed="off")

    limit = reply_ctx.get("rateLimit")
    used = reply_ctx.get("outLastHour")
    if (used or 0) >= (limit if isinstance(limit, int) and limit > 0 else DEFAULT_RATE_LIMIT):
        return GateResult(_silence(envelope), send_kind, suppressed="rate")

    if envelope.get("needs_confirm") is True:
        content = str(envelope.get("content") or "").strip()
        if not content:
            return GateResult(_silence(envelope), send_kind, suppressed="confirm-empty")
        return GateResult(_silence(envelope), send_kind, suppressed="confirm",
                          draft={"content": content})

    return GateResult(envelope, send_kind)
