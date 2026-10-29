"""Dify 图的离线体检。

图是在 Dify 控制台里搭的，真正的验收只能靠导入 + 真机；
但「聚合器顺序」和「代码节点的兜底」这两类错误一旦上线就是静默失败
（写成功了却回 SILENT、后端拒绝了却说没连上），所以在这里钉死。
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
    nodes = {n["id"]: n for n in g["nodes"]}
    return g, nodes, doc


def _main(nodes, node_id):
    """把代码节点里嵌的 Python 拿出来直接执行。"""
    ns = {}
    exec(nodes[node_id]["data"]["code"], ns)
    return ns["main"]


def test_op_result_precedes_clean_decision_in_aggregator(graph):
    _, nodes, _ = graph
    order = [".".join(v) for v in nodes["merge_decision"]["data"]["variables"]]
    # op 分支时 clean_decision 输出空串；聚合器取第一个非空，顺序错了就回 SILENT
    assert order.index("read_op_result.decision") < order.index("clean_decision.decision")
    assert order.index("op_failed.decision") < order.index("clean_decision.decision")


def test_op_branch_is_wired(graph):
    g, nodes, _ = graph
    edges = {(e["source"], e["sourceHandle"]): e["target"] for e in g["edges"]}
    assert edges[("clean_decision", "source")] == "is_op"
    assert edges[("is_op", "true")] == "op_http"
    assert edges[("is_op", "false")] == "merge_decision"
    assert edges[("op_http", "source")] == "read_op_result"
    assert edges[("op_http", "fail-branch")] == "op_failed"
    assert edges[("read_op_result", "source")] == "merge_decision"
    assert edges[("op_failed", "source")] == "merge_decision"


def test_http_node_carries_token_from_env_not_literal(graph):
    _, nodes, doc = graph
    data = nodes["op_http"]["data"]
    assert data["method"] == "post"
    assert data["url"].endswith("/api/agent/ops")
    assert data["error_strategy"] == "fail-branch"
    # JSON body 只能有一个条目（Dify 的硬限制），所以整包 body 由代码节点序列化
    assert len(data["body"]["data"]) == 1
    assert data["body"]["data"][0]["value"] == "{{#clean_decision.op_body#}}"
    assert "{{#env.AGENT_OPS_TOKEN#}}" in data["headers"]
    # token 必须以环境变量引用出现，secret 不随 DSL 落地
    env = {v["name"]: v for v in doc["workflow"]["environment_variables"]}
    assert env["AGENT_OPS_TOKEN"]["value_type"] == "secret"
    assert env["AGENT_OPS_TOKEN"]["value"] == ""


def test_http_node_data_has_fields_the_runtime_requires(graph):
    """Dify 的 HttpRequestNodeData 是严格 pydantic 模型：少一个字段，整条消息 400。

    踩过一次：手写的 DSL 漏了 `authorization`，运行时报
    「HttpRequestNodeData.authorization Field required」，QQ 侧直接没回复。
    """
    _, nodes, _ = graph
    data = nodes["op_http"]["data"]
    for key in (
        "title",
        "type",
        "method",
        "url",
        "authorization",
        "headers",
        "params",
        "body",
        "timeout",
        "variables",
    ):
        assert key in data, f"OP_HTTP 缺少 {key}，Dify 会拒绝整张图"
    assert data["type"] == "http-request"
    assert data["authorization"]["type"] in {"no-auth", "api-key"}
    assert data["params"] == ""
    assert {"max_connect_timeout", "max_read_timeout", "max_write_timeout"} <= set(
        data["timeout"]
    )


def test_start_declares_open_memos_and_event_id(graph):
    _, nodes, _ = graph
    names = {v["variable"]: v for v in nodes["start"]["data"]["variables"]}
    assert names["open_memos"]["max_length"] == 20000
    assert names["event_id"]["max_length"] == 64
    assert "open_memos" in nodes["chat_decision"]["data"]["prompt_template"][1]["text"]


def test_memo_op_body_takes_identity_from_start(graph):
    _, nodes, _ = graph
    main = _main(nodes, "clean_decision")
    text = json.dumps({"action": "MEMO_OP", "op": "memo.update", "memoId": "12",
                       "content": "明天下午2点 教学楼 S111",
                       "senderId": "999", "eventId": "forged"})

    out = main(text, sender_id="8801", chat_id="555", event_id="evt-1")

    assert out["is_op"] == "true"
    body = json.loads(out["op_body"])
    assert body["senderId"] == "8801"      # 模型改不了归属
    assert body["eventId"] == "evt-1"      # 模型改不了幂等键
    assert body["chatId"] == "555"
    assert body["memoId"] == "12"
    assert body["status"] == ""            # 没给的字段留空串，让 Spring 认为「未提供」


def test_legacy_memo_and_garbage_still_parse(graph):
    _, nodes, _ = graph
    main = _main(nodes, "clean_decision")

    memo = main(json.dumps({"action": "MEMO",
                            "memo": {"content": "周五交实验报告"}}),
                "1", "", "evt")
    assert memo["is_op"] == "false"
    decision = json.loads(memo["decision"])
    assert decision["memo"]["importance"] == 3

    garbage = main("不是 JSON", "1", "", "evt")
    assert json.loads(garbage["decision"])["action"] == "SILENT"


def test_memo_op_without_event_id_does_not_write(graph):
    _, nodes, _ = graph
    main = _main(nodes, "clean_decision")
    out = main(json.dumps({"action": "MEMO_OP", "op": "memo.close", "status": "DONE"}),
               "1", "", "")
    assert out["is_op"] == "false"
    assert json.loads(out["decision"])["action"] == "SEND"


def test_illegal_op_is_not_forwarded(graph):
    _, nodes, _ = graph
    main = _main(nodes, "clean_decision")
    out = main(json.dumps({"action": "MEMO_OP", "op": "memo.delete", "memoId": "12"}),
               "1", "", "evt")
    assert out["is_op"] == "false"
    assert json.loads(out["decision"])["action"] == "SILENT"


def test_read_op_result_passes_business_message_verbatim(graph):
    _, nodes, _ = graph
    main = _main(nodes, "read_op_result")

    ok = main(200, json.dumps({"code": 0,
                               "message": "改好了：班会 · 10-31 16:00",
                               "data": {"memoId": 12, "replayed": False}}))
    assert json.loads(ok["decision"])["text"] == "改好了：班会 · 10-31 16:00"

    rejected = main(200, json.dumps({"code": 400, "message": "备忘录不存在", "data": None}))
    assert json.loads(rejected["decision"])["text"] == "备忘录不存在"


def test_read_op_result_is_total(graph):
    _, nodes, _ = graph
    main = _main(nodes, "read_op_result")
    for args in ((500, "upstream error"), (200, "<html>502 Bad Gateway</html>"),
                 (0, ""), (None, None)):
        decision = json.loads(main(*args)["decision"])
        assert decision["action"] == "SEND"
        assert "没办成" in decision["text"]


def test_replayed_result_says_it_did_not_run_again(graph):
    _, nodes, _ = graph
    main = _main(nodes, "read_op_result")
    out = main(200, json.dumps({"code": 0, "message": "已取消：班会",
                                "data": {"memoId": 12, "replayed": True}}))
    assert "没有重复执行" in json.loads(out["decision"])["text"]


def test_op_failed_never_echoes_the_raw_error(graph):
    _, nodes, _ = graph
    data = nodes["op_failed"]["data"]
    assert data["variables"] == []          # 根本没接 error_message，想回显都没得回显
    decision = json.loads(_main(nodes, "op_failed")()["decision"])
    assert decision["action"] == "SEND"
    assert "http" not in decision["text"].lower()
