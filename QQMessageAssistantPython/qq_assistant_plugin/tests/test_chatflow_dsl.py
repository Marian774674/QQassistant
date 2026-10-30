"""Chatflow 的离线体检：自动回复的三个新输入 + 信封字段的归一化。

真正的验收要靠导入 Dify；这里只钉那些**一错就静默失败**的点：
- 插件发了新输入但 Start 没声明 → Dify 直接 400，整轮没有回复。
- `send_kind` / `needs_confirm` 没被归一化 → 插件门控拿不到，开关等于不存在。
- 确认语写在 `text` 而不是 `content` → 插件 `_send` 读不到，用户什么都没收到。
"""
import json
from pathlib import Path

import pytest
import yaml

DSL = Path(__file__).resolve().parents[3] / "design" / "QQAssistant.chatflow.yml"

pytestmark = pytest.mark.skipif(not DSL.exists(), reason="chatflow DSL 不存在")


@pytest.fixture(scope="module")
def graph():
    doc = yaml.safe_load(DSL.read_text(encoding="utf-8"))
    g = doc["workflow"]["graph"]
    return doc, g, {n["id"]: n for n in g["nodes"]}


def _main(nodes, node_id):
    ns = {}
    exec(nodes[node_id]["data"]["code"], ns)
    return ns["main"]


def _clean(nodes, payload, event_id="evt-1"):
    """跑一遍 CLEAN_DECISION，返回解析后的信封。"""
    raw = json.dumps(payload, ensure_ascii=False) if not isinstance(payload, str) else payload
    out = _main(nodes, "clean_decision")(raw, "1001", "222", event_id)
    return json.loads(out["decision"]) if out["decision"] else None


# ---------- Start 变量 ----------

def test_start_declares_the_auto_reply_inputs(graph):
    """Dify 只认 Start 里声明过的变量；漏一个，那一段就永远是空输入。"""
    _, _, nodes = graph
    declared = {v["variable"]: v for v in nodes["start"]["data"]["variables"]}
    for name in ("reply_prompt", "recent_messages", "reply_rules"):
        assert name in declared, f"Start 缺少 {name}"
    # 最近消息是 JSON 数组，短了会被截断成非法 JSON
    assert declared["recent_messages"]["max_length"] >= 20000


def test_chat_prompt_uses_the_new_inputs(graph):
    """输入声明了却没人读，等于没加：提示词里必须真的引用它们。"""
    _, _, nodes = graph
    prompt = "\n".join(m["text"] for m in nodes["chat_decision"]["data"]["prompt_template"])
    assert "{{#start.recent_messages#}}" in prompt
    assert "{{#start.reply_prompt#}}" in prompt
    assert "{{#start.reply_rules#}}" in prompt


def test_chat_prompt_pins_the_send_kind_contract(graph):
    """模型不写 send_kind 就永远是 TASK——开关和限流对它完全不起作用。"""
    _, _, nodes = graph
    system = nodes["chat_decision"]["data"]["prompt_template"][0]["text"]
    assert "send_kind" in system
    assert "needs_confirm" in system
    assert "CHAT" in system and "TASK" in system


# ---------- CLEAN_DECISION 归一化 ----------

def test_missing_send_kind_is_filled_in_as_task(graph):
    """旧提示词/漏写的信封按 TASK：行为和今天一模一样，不能被门控吞掉。"""
    _, _, nodes = graph
    envelope = _clean(nodes, {"version": 1, "action": "SEND", "content": "记下了"})
    assert envelope["send_kind"] == "TASK"
    assert envelope["content"] == "记下了"


def test_chat_kind_survives_cleaning(graph):
    _, _, nodes = graph
    envelope = _clean(nodes, {"action": "SEND", "send_kind": "CHAT", "content": "在呢"})
    assert envelope["send_kind"] == "CHAT"
    assert envelope["content"] == "在呢"


def test_unknown_send_kind_falls_back_to_task(graph):
    """模型自创的枚举不能直接透传，否则门控认不出来、当成 CHAT 还是 TASK 全靠运气。"""
    _, _, nodes = graph
    assert _clean(nodes, {"action": "SEND", "send_kind": "GOSSIP", "content": "x"})["send_kind"] == "TASK"
    assert _clean(nodes, {"action": "SEND", "send_kind": " chat ", "content": "x"})["send_kind"] == "CHAT"


def test_needs_confirm_is_a_real_boolean(graph):
    """字符串 "true" 也要认：宁可多一条草稿，也不能让该确认的话直接发出去。"""
    _, _, nodes = graph
    envelope = _clean(nodes, {"action": "SEND", "send_kind": "CHAT",
                              "needs_confirm": "true", "content": "行，我三点到"})
    assert envelope["needs_confirm"] is True


def test_needs_confirm_is_dropped_on_a_task(graph):
    """TASK 是干活；把它塞进草稿箱等于备忘确认语永远发不出去。"""
    _, _, nodes = graph
    envelope = _clean(nodes, {"action": "SEND", "send_kind": "TASK",
                              "needs_confirm": True, "content": "改好了"})
    assert not envelope.get("needs_confirm")


def test_false_needs_confirm_is_not_emitted(graph):
    _, _, nodes = graph
    envelope = _clean(nodes, {"action": "SEND", "send_kind": "CHAT",
                              "needs_confirm": False, "content": "在呢"})
    assert not envelope.get("needs_confirm")


# ---------- 确认语必须写在 content 上 ----------

def test_op_success_confirmation_uses_content(graph):
    """插件 `_send` 读的是 content；写成 text 就是「后台写成功了，用户什么都没收到」。"""
    _, _, nodes = graph
    body = json.dumps({"code": 0, "message": "改好了：班会 · S111", "data": {}}, ensure_ascii=False)
    envelope = json.loads(_main(nodes, "read_op_result")(200, body)["decision"])
    assert envelope["action"] == "SEND"
    assert envelope["content"] == "改好了：班会 · S111"
    assert "text" not in envelope


def test_op_failure_apology_uses_content(graph):
    _, _, nodes = graph
    envelope = json.loads(_main(nodes, "op_failed")()["decision"])
    assert envelope["action"] == "SEND"
    assert envelope["content"]
    assert "text" not in envelope


def test_memo_op_without_event_id_uses_content(graph):
    """没有幂等键就不写，但要回一句话——这句话同样必须落在 content 上。"""
    _, _, nodes = graph
    envelope = _clean(nodes, {"action": "MEMO_OP", "op": "memo.update", "memoId": "12"},
                      event_id="")
    assert envelope["action"] == "SEND"
    assert envelope["content"]
    assert "text" not in envelope


# ---------- 聚合顺序 ----------

def test_merge_decision_reads_op_results_before_clean_decision(graph):
    """op 分支时 clean_decision 输出空串；顺序错了聚合器会拿到空值，回成 SILENT。"""
    _, _, nodes = graph
    order = [v[0] for v in nodes["merge_decision"]["data"]["variables"]]
    assert order.index("read_op_result") < order.index("clean_decision")
    assert order.index("op_failed") < order.index("clean_decision")
