"""「记忆与整理」Workflow 的离线体检。

真正的验收要靠导入 Dify + 真机跑一轮；这里钉死三件一错就是静默失败的事：
接线错（跑不到 LLM）、Start 变量名和 Spring 发的不一致（Dify 直接 400）、
解析节点把没有证据的条目放过去（编造的证据会落进数据库）。
"""
import json
from pathlib import Path

import pytest
import yaml

DSL = Path(__file__).resolve().parents[3] / "design" / "QQAssistant-memory-workflow.yml"

pytestmark = pytest.mark.skipif(not DSL.exists(), reason="memory workflow DSL 不存在")


@pytest.fixture(scope="module")
def graph():
    doc = yaml.safe_load(DSL.read_text(encoding="utf-8"))
    g = doc["workflow"]["graph"]
    return doc, g, {n["id"]: n for n in g["nodes"]}


def _main(nodes, node_id):
    ns = {}
    exec(nodes[node_id]["data"]["code"], ns)
    return ns["main"]


def test_is_a_workflow_app(graph):
    doc, _, _ = graph
    assert doc["app"]["mode"] == "workflow"
    assert doc["app"]["name"] == "记忆与整理"


def test_digest_branch_is_wired(graph):
    _, g, _ = graph
    edges = {(e["source"], e["sourceHandle"]): e["target"] for e in g["edges"]}
    assert edges[("start", "source")] == "is_digest"
    assert edges[("is_digest", "true")] == "digest_llm"
    assert edges[("is_digest", "false")] == "empty_items"
    assert edges[("digest_llm", "source")] == "parse_digest"
    assert edges[("parse_digest", "source")] == "end_digest"
    assert edges[("empty_items", "source")] == "end_other"


def test_start_declares_exactly_the_inputs_spring_sends(graph):
    _, _, nodes = graph
    declared = {v["variable"] for v in nodes["start"]["data"]["variables"]}
    # 与 DifyWorkflowClient.run(task, {chat_name, messages, profile, focus}) 必须一致
    assert declared == {"task", "chat_name", "messages", "profile", "focus"}


def test_prompt_pins_the_evidence_contract(graph):
    _, _, nodes = graph
    system = nodes["digest_llm"]["data"]["prompt_template"][0]["text"]
    assert "source_msg_ids" in system
    assert "[@我]" in system
    assert "不少于 4" in system or "不低于 4" in system
    assert "[]" in system


def test_parse_digest_drops_items_without_evidence(graph):
    _, _, nodes = graph
    raw = """
```json
[{"summary":"班主任提醒明天开班会","importance":4,"keywords":"班会","mentions_me":false,"source_msg_ids":[1]},
 {"summary":"没有证据的一条","importance":5,"keywords":"","mentions_me":false,"source_msg_ids":[]},
 {"summary":"","importance":3,"source_msg_ids":[2]}]
```
"""
    items = json.loads(_main(nodes, "parse_digest")(raw)["items"])
    assert [i["summary"] for i in items] == ["班主任提醒明天开班会"]


def test_parse_digest_returns_empty_on_garbage(graph):
    _, _, nodes = graph
    main = _main(nodes, "parse_digest")
    assert main("模型开始胡说八道了")["items"] == "[]"
    assert main("")["items"] == "[]"


def test_placeholder_branch_returns_empty_list(graph):
    _, _, nodes = graph
    assert _main(nodes, "empty_items")()["items"] == "[]"


def test_end_nodes_expose_unique_output_names(graph):
    """Dify 要求各结束节点的输出变量名全局唯一，否则「发布」直接被清单挡住。

    两个结束节点都叫 items，用户导入后才在检查清单里看到红叉——这条测试就是替那个红叉把关。
    """
    _, _, nodes = graph
    names = [
        out["variable"]
        for node in nodes.values()
        if node["data"].get("type") == "end"
        for out in node["data"].get("outputs", [])
    ]
    assert len(names) == len(set(names)), f"结束节点输出变量重名：{names}"
