"""出口门控：Dify 说「说什么」，插件按总开关、会话开关与限流决定「说不说」。

四条不能错的语义：
1. 总开关（globalEnabled）关掉 = 彻底闭嘴，连 `TASK` 一起停；只清 content，动作照跑。
2. 只是会话开关 / 限流关掉时，`TASK` 永远发——那关的是「替主人闲聊」，不是「让它干活」。
3. 缺 `send_kind` 按 `TASK`——旧 Chatflow 连上来时行为必须和今天一样。
4. `needs_confirm` 只对 `CHAT` 生效——不然一条备忘确认语会被塞进草稿箱。
"""
from core.reply_gate import decide

ON = {"enabled": True, "rateLimit": 20, "outLastHour": 0}
OFF = {"enabled": False, "rateLimit": 20, "outLastHour": 0}
FULL = {"enabled": True, "rateLimit": 20, "outLastHour": 20}
# 总开关关掉：enabled 也是 False（它是三层相乘），但区别在于 TASK 也不许发
MUTE = {"enabled": False, "globalEnabled": False, "rateLimit": 20, "outLastHour": 0}


def test_total_mute_silences_task_too():
    """总开关是「彻底一个字都不说」，备忘确认语也不例外。"""
    result = decide({"action": "SEND", "send_kind": "TASK", "content": "记下了：交实验报告"}, MUTE)

    assert result.data["content"] == ""
    assert result.data["action"] == "SEND"   # 动作保留，备忘照建
    assert result.suppressed == "mute"
    assert result.send_kind == "TASK"


def test_total_mute_silences_chat_as_mute_not_off():
    """两种闭嘴要能分开：日志里看不出是哪一层关的，排查会很痛苦。"""
    result = decide({"action": "SEND", "send_kind": "CHAT", "content": "在呢"}, MUTE)

    assert result.data["content"] == ""
    assert result.suppressed == "mute"


def test_total_mute_wins_even_if_enabled_is_true():
    """防御性：正常不会出现，但总开关的优先级必须最高。"""
    result = decide({"action": "SEND", "send_kind": "TASK", "content": "改好了"},
                    {"enabled": True, "globalEnabled": False, "rateLimit": 20, "outLastHour": 0})

    assert result.data["content"] == ""
    assert result.suppressed == "mute"


def test_task_is_sent_when_only_the_session_switch_is_off():
    """会话开关关的是「替这个群说日常话」，备忘确认语仍然要发。"""
    result = decide({"action": "SEND", "send_kind": "TASK", "content": "改好了：班会"}, OFF)

    assert result.data["content"] == "改好了：班会"
    assert result.suppressed is None
    assert result.send_kind == "TASK"


def test_task_is_not_affected_by_the_rate_limit():
    result = decide({"action": "SEND", "send_kind": "TASK", "content": "记下了"}, FULL)

    assert result.data["content"] == "记下了"
    assert result.suppressed is None


def test_chat_is_suppressed_when_the_switch_is_off():
    result = decide({"action": "SEND", "send_kind": "CHAT", "content": "哈哈可以啊"}, OFF)

    assert result.data["content"] == ""
    assert result.data["action"] == "SEND"   # 动作保留，只是不说话
    assert result.suppressed == "off"


def test_chat_is_suppressed_when_over_the_rate_limit():
    result = decide({"action": "SEND", "send_kind": "CHAT", "content": "在呢"}, FULL)

    assert result.data["content"] == ""
    assert result.suppressed == "rate"


def test_chat_is_sent_when_enabled_and_under_the_limit():
    result = decide({"action": "SEND", "send_kind": "CHAT", "content": "在呢"}, ON)

    assert result.data["content"] == "在呢"
    assert result.suppressed is None


def test_missing_send_kind_defaults_to_task():
    result = decide({"action": "SEND", "content": "好的"}, OFF)

    assert result.send_kind == "TASK"
    assert result.data["content"] == "好的"


def test_unknown_send_kind_defaults_to_task():
    result = decide({"action": "SEND", "send_kind": "GOSSIP", "content": "好的"}, OFF)

    assert result.send_kind == "TASK"
    assert result.data["content"] == "好的"


def test_needs_confirm_turns_a_chat_reply_into_a_draft():
    result = decide({"action": "SEND", "send_kind": "CHAT", "needs_confirm": True,
                     "content": "行，明天下午三点我过去"}, ON)

    assert result.data["content"] == ""
    assert result.suppressed == "confirm"
    assert result.draft == {"content": "行，明天下午三点我过去"}


def test_needs_confirm_on_a_task_is_ignored():
    result = decide({"action": "SEND", "send_kind": "TASK", "needs_confirm": True,
                     "content": "记下了：交实验报告"}, ON)

    assert result.data["content"] == "记下了：交实验报告"
    assert result.draft is None


def test_needs_confirm_with_empty_content_makes_no_draft():
    result = decide({"action": "SEND", "send_kind": "CHAT", "needs_confirm": True,
                     "content": "   "}, ON)

    assert result.draft is None
    assert result.data["content"] == ""


def test_suppressed_chat_does_not_mutate_the_original_envelope():
    original = {"action": "SEND", "send_kind": "CHAT", "content": "哈哈"}

    decide(original, OFF)

    # 改写后返回新字典；原字典还要给日志用，别被就地清空
    assert original["content"] == "哈哈"
