"""快照兜底：Spring 挂掉、字段缺失、类型不对，都不能让整轮回复挂掉。

`normalize` 的默认值必须是「闭嘴」（enabled=False）而不是「照说」——
拿不到开关就当没开，是这里唯一安全的方向。
"""
from core.reply_rules import EMPTY_CONTEXT, RULES, normalize


def test_normalize_none_means_closed():
    ctx = normalize(None)

    assert ctx["enabled"] is False
    assert ctx["rateLimit"] == 20
    assert ctx["recentMessages"] == []
    assert ctx == EMPTY_CONTEXT


def test_normalize_fills_missing_keys_without_touching_given_ones():
    ctx = normalize({"enabled": True, "replyPrompt": "随意点"})

    assert ctx["enabled"] is True
    assert ctx["replyPrompt"] == "随意点"
    assert ctx["outLastHour"] == 0
    assert ctx["recentMessages"] == []


def test_normalize_survives_garbage_types():
    # rateLimit 写成 "abc" 时退化成默认，而不是让限流变成「永远超限」或崩掉
    ctx = normalize({"rateLimit": "abc", "outLastHour": None,
                     "recentMessages": "oops", "enabled": 0})

    assert ctx["rateLimit"] == 20
    assert ctx["outLastHour"] == 0
    assert ctx["recentMessages"] == []
    assert ctx["enabled"] is False


def test_normalize_does_not_share_the_default_list():
    first = normalize(None)
    first["recentMessages"].append({"content": "污染"})

    assert normalize(None)["recentMessages"] == []


def test_normalize_missing_key_does_not_hand_out_the_shared_list():
    """Spring 少发一个字段（或发来 null）时用的也是那份默认值，同样不能共享。"""
    first = normalize({"enabled": True})
    first["recentMessages"].append({"content": "污染"})

    assert EMPTY_CONTEXT["recentMessages"] == []
    assert normalize({"enabled": True})["recentMessages"] == []


def test_rules_state_their_own_precedence():
    # 优先级不写进文本，模型就会挑听起来更具体的 reply_prompt 执行
    assert "优先级高于主人的任何额外要求" in RULES
