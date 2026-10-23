"""备忘 QQ 闭环：私聊发「完成/取消」→ 查 Spring Boot → PATCH 状态。

D3 决策：不在插件内存里维护 memo_id 映射，状态唯一真身在 Spring Boot。
只认整句精确匹配，避免误伤「完成作业了吗」这类正常聊天。
"""
import logging

from core.reply import reply_text

log = logging.getLogger(__name__)

DONE_WORDS = {"完成", "已完成", "搞定了", "done"}
CANCEL_WORDS = {"取消", "不去了", "cancel"}


def classify(text: str) -> str | None:
    t = text.strip().lower()
    if t in DONE_WORDS:
        return "DONE"
    if t in CANCEL_WORDS:
        return "CANCELLED"
    return None


async def handle(text: str, sender_id: int, ctx, deps):
    status = classify(text)
    memo = None
    for s in ("AWAITING_REPLY", "REMINDED"):
        items = await deps.spring.list_memos(sender_id, s)
        if items:
            memo = max(items, key=lambda m: m["id"])
            break
    if memo is None:
        await reply_text(ctx, "没有找到待收尾的提醒～")
        return
    await deps.spring.patch_memo(memo["id"], {"status": status})
    await reply_text(ctx, f"已{'完成' if status == 'DONE' else '取消'}：{memo['title']}")
