import random
import time
from langbot_plugin.api.entities.builtin.platform import message as pm


def extract_text(chain) -> str:
    """拼出可读正文。@ 也要留下：光看 Plain，有人真的 @ 你就只剩半句话。"""
    parts = []
    for comp in chain:
        if isinstance(comp, pm.Plain):
            parts.append(comp.text or "")
        elif isinstance(comp, pm.AtAll):
            parts.append("@全体成员")
        elif isinstance(comp, pm.At):
            # display 在部分适配器上是空的，退回目标 id，至少还看得出「@了谁」
            parts.append("@" + (comp.display or str(comp.target)))
    return "".join(parts).strip()


def has_file(chain) -> bool:
    return any(isinstance(comp, pm.File) for comp in chain)


def is_at_bot(chain, bot_qq: int) -> bool:
    return any(isinstance(comp, pm.At) and str(comp.target) == str(bot_qq) for comp in chain)


def human_delay_seconds(text: str, settings) -> float:
    ms = (settings.delay_base_ms
          + len(text) * settings.delay_per_char_ms
          + random.randint(0, settings.delay_jitter_ms))
    return min(ms, settings.delay_max_ms) / 1000.0

def _first_attr(obj, names):
    """依次取第一个非空字符串属性；任何一层缺失都返回空串，绝不抛错。"""
    for name in names:
        value = getattr(obj, name, None)
        if value:
            return str(value)[:128]
    return ""


def peer_names(event):
    """返回 (发送者昵称, 群名)；私聊时群名为空串。

    LangBot 各版本字段名不一致（sender.name / sender_name / sender.nickname），
    所以逐层探测而不是写死；日志里这两个名字只是给人看的，取不到就留空。
    """
    sender = getattr(event, "sender", None) or getattr(event, "sender_obj", None)
    sender_name = ""
    if sender is not None:
        sender_name = _first_attr(sender, ("name", "nickname", "card"))
    if not sender_name:
        sender_name = _first_attr(event, ("sender_name", "sender_nickname"))

    group = getattr(event, "group", None)
    chat_name = ""
    if group is not None:
        chat_name = _first_attr(group, ("name", "group_name"))
    if not chat_name:
        chat_name = _first_attr(event, ("group_name", "chat_name"))
    return sender_name, chat_name
