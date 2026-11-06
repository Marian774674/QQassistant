"""回复辅助：把纯文本包装成 MessageChain 回复。

main.py 的 Dispatcher 与 default.py 的拦截分支共用，避免两处各写一份。

另外提供「本轮闭嘴」开关：总开关关掉时，这一轮任何 ``reply_text`` 都变成空操作。

为什么不逐个调用点判断：直接回话的地方散在 ``file_task``（过期提示、格式不支持、
填写结果）、``dispatch``、拦截分支里，一共七八处，漏一个就等于没关。放在最底层，
以后新增的调用点也自动被覆盖。
"""
import contextlib
import contextvars

from langbot_plugin.api.entities.builtin.platform import message as pm

# 默认不静默。用 ContextVar 而不是模块级布尔：一个进程同时在处理多条消息，
# 全局变量会让 A 会话的「闭嘴」串到 B 会话去。
_MUTED: contextvars.ContextVar[bool] = contextvars.ContextVar("reply_muted", default=False)


@contextlib.contextmanager
def silence():
    """在这个上下文里，``reply_text`` 全部变成空操作。

    只压「说话」，不压动作：文件照写、备忘照建，只是不往外发一个字。
    """
    token = _MUTED.set(True)
    try:
        yield
    finally:
        _MUTED.reset(token)


def is_silenced() -> bool:
    return _MUTED.get()


async def reply_text(ctx, content: str):
    if _MUTED.get():
        return
    await ctx.reply(pm.MessageChain([pm.Plain(text=content)]))
