"""回复辅助：把纯文本包装成 MessageChain 回复。

main.py 的 Dispatcher 与 default.py 的拦截分支共用，避免两处各写一份。
"""
from langbot_plugin.api.entities.builtin.platform import message as pm


async def reply_text(ctx, content: str):
    await ctx.reply(pm.MessageChain([pm.Plain(text=content)]))
