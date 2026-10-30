"""出口门控：Dify 说「说什么」，插件按会话开关与限流决定「说不说」。

三条不能错的语义：
1. `TASK` 永远发——开关与限流只约束替主人闲聊，不能连备忘确认语一起吞掉。
2. 缺 `send_kind` 按 `TASK`——旧 Chatflow 连上来时行为必须和今天一样。
3. `needs_confirm` 只对 `CHAT` 生效——不然一条备忘确认语会被塞进草稿箱。
"""
from core.reply_gate import decide

ON = {"enabled": True, "rateLimit": 20, "outLastHour": 0}
OFF = {"enabled": False, "rateLimit": 20, "outLastHour": 0}
FULL = {"enabled": True, "rateLimit": 20, "outLastHour": 20}


def test_task_is_sent_even_when_the_switch_is_off():
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
