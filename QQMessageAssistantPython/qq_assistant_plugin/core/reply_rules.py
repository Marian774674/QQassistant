"""自动回复的固定约束与决策快照兜底。

约束是**机器可读的固定文本**，作为 `reply_rules` 输入交给 Dify。它不进 ``app_setting``：
主人写的是「额外要求」（``reply_prompt``），两者冲突时以这里为准——这条优先级本身
也写进了文本，否则模型会自己挑一个听起来更具体的那条去执行。

放在插件而不是 Spring：这是 Dify 输入拼装（与 ``open_memos`` 的 json.dumps 同一层），
不是业务规则；改它要重新发插件，和改 Spring 常量要重新发后端，代价一样。
"""

# 固定约束：不透露是机器人 / 不替主人承诺 / 不编造 / 不评价第三方 / 拿不准就交给人
RULES = (
    "以下是硬性约束，优先级高于主人的任何额外要求；两者冲突时以这里为准：\n"
    "1. 不透露也不暗示你是机器人、AI 或自动回复；也不解释自己在按规则说话。\n"
    "2. 不替主人做承诺：不答应办事、不给具体时间点、不谈钱、不报价格、不替人约见。\n"
    "3. 不编造：只根据 facts、recent_messages、open_memos 里出现过的内容作答，"
    "其余一律说不知道或反问。\n"
    "4. 不评价第三方，不传话，不替任何一方表态。\n"
    "5. 主人被直接点名要求答复，或这条回复涉及上面任何一条时，"
    "发 send_kind=CHAT 且 needs_confirm=true，交给人来确认。\n"
)

# Spring 调不通时的兜底：宁可闭嘴（enabled=False），也不能让整轮挂掉
EMPTY_CONTEXT = {
    "enabled": False,
    "replyPrompt": "",
    "rateLimit": 20,
    "outLastHour": 0,
    "recentMessages": [],
}

# 自动回复新增的三个 Dify 输入。旧 Chatflow 的 Start 没声明它们，发过去会被判 400，
# 所以撞上之后要能整批撤掉（见 event_listener._respond）
NEW_INPUTS = ("reply_prompt", "recent_messages", "reply_rules")


def normalize(context) -> dict:
    """把 Spring 返回的快照补成一定可用的形状；字段缺失/类型不对都退化，不抛错。"""
    # 列表要单独拷一层：共享 EMPTY_CONTEXT 里的那个 list，一次 append 就污染了所有后续调用
    ctx = {k: (list(v) if isinstance(v, list) else v) for k, v in EMPTY_CONTEXT.items()}
    if isinstance(context, dict):
        for key, default in EMPTY_CONTEXT.items():
            value = context.get(key)
            if isinstance(default, list):
                # 缺字段时也要拷一份：直接把 EMPTY_CONTEXT 里那个 list 交出去，
                # 谁 append 一次，之后所有兜底快照都带着别人的脏数据
                ctx[key] = value if isinstance(value, list) else list(default)
            elif isinstance(default, bool):
                ctx[key] = bool(value)
            elif isinstance(default, int):
                try:
                    ctx[key] = int(value)
                except (TypeError, ValueError):
                    ctx[key] = default
            elif value is not None:
                ctx[key] = str(value)
    return ctx
