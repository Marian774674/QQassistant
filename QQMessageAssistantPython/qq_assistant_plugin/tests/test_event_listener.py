"""事件监听器回归：所有消息都进 Chatflow，插件只负责传上下文和执行。

钉两件事：
1. 文本补充必须把待续文件的 structure/task_ref 一起送过去，否则 Dify 无法判断
   这句是“补充字段”还是普通聊天。
2. 文件消息必须先下载/抽取，再把 event_type=file + structure 送 Chatflow。
"""
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import pytest
from langbot_plugin.api.entities.builtin.platform import message as pm

from components.event_listener.default import DefaultEventListener
from core import conversation_store, file_store


class FakeCtx:
    def __init__(self, chain, sender_id="1001", launcher_id=None):
        self.event = SimpleNamespace(sender_id=sender_id, launcher_id=launcher_id,
                                     message_chain=chain)
        self.prevented = []

    def prevent_default(self):
        self.prevented.append("default")

    def prevent_postorder(self):
        self.prevented.append("postorder")


@pytest.fixture
def deps(tmp_path, monkeypatch):
    # 面包屑写进 tmp_path，别污染真实 data/files
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    conversation_store.clear_cache()
    d = SimpleNamespace(
        settings=SimpleNamespace(group_prefix="!", bot_qq=12345, intercept_scope="off",
                                 bot_uuid="bot-1", fact_auto_threshold=0.85),
        spring=SimpleNamespace(
            profile=AsyncMock(return_value={"facts": {}, "stylePrompt": ""}),
            sync_file_task=AsyncMock()),
        dify=SimpleNamespace(chat=AsyncMock(return_value={
            "answer": '{"action":"SILENT"}', "conversation_id": "conv-1"})),
        dispatcher=SimpleNamespace(handle=AsyncMock(return_value=None)),
    )
    d.log_queue = SimpleNamespace(enqueue=lambda payload: None)
    return d


@pytest.mark.asyncio
async def test_text_without_active_task_falls_through(deps):
    """没有待续文件任务时，普通文本正常进 Chatflow。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["event_type"] == "text"
    assert inputs["file_task_ref"] == ""
    assert inputs["structure"] == ""
    assert deps.dify.chat.await_args.args[0] == "你好"


@pytest.mark.asyncio
async def test_text_with_active_file_task_passes_structure(deps):
    """“姓名张三”这类补充必须带 structure，Dify 才能直接填。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="姓名张三")]))
    task = {"taskRef": "ft_abc", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx", "sheets": []},
            "originalName": "名单.xlsx"}

    with patch("core.file_store.load_active_input", AsyncMock(return_value="ft_abc")), \
            patch("core.file_store.load_task", AsyncMock(return_value=task)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["event_type"] == "text"
    assert inputs["file_task_ref"] == "ft_abc"
    assert json.loads(inputs["structure"])["kind"] == "xlsx"
    assert inputs["file_name"] == "名单.xlsx"


@pytest.mark.asyncio
async def test_text_does_not_leak_task_from_another_chat(deps):
    """群聊 A 的文件任务不能被群聊 B 的文本带走。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="!姓名张三")]), launcher_id="222")
    task = {"taskRef": "ft_abc", "senderId": 1001, "chatId": 111,
            "structure": {"kind": "xlsx"}, "originalName": "a.xlsx"}

    with patch("core.file_store.load_active_input", AsyncMock(return_value="ft_abc")), \
            patch("core.file_store.load_task", AsyncMock(return_value=task)):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=True)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["file_task_ref"] == ""
    assert inputs["structure"] == ""


@pytest.mark.asyncio
async def test_file_message_ingests_then_sends_file_inputs(deps):
    """文件消息：先 ingest，再把 event_type=file + structure 交给 Chatflow。"""
    chain = pm.MessageChain([
        pm.Plain(text="把姓名填上"),
        pm.File(type="File", id="f1", name="名单.xlsx", size=1, url=""),
    ])
    ctx = FakeCtx(chain)
    task = {"taskRef": "ft_1", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx", "sheets": []},
            "originalName": "名单.xlsx"}

    with patch("core.file_task.ingest", AsyncMock(return_value=task)) as ingest:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    ingest.assert_awaited_once()
    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["event_type"] == "file"
    assert inputs["file_task_ref"] == "ft_1"
    assert inputs["file_kind"] == "xlsx"
    assert deps.dify.chat.await_args.args[0] == "把姓名填上"


@pytest.mark.asyncio
async def test_pending_hint_is_forwarded(deps):
    """Dify 会话重建后，本地备份的填写要求仍要带过去。"""
    chain = pm.MessageChain([pm.File(type="File", id="f1", name="a.xlsx", size=1, url="")])
    ctx = FakeCtx(chain)
    task = {"taskRef": "ft_1", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx"}, "originalName": "a.xlsx"}

    with patch("core.file_task.ingest", AsyncMock(return_value=task)), \
            patch("core.file_store.load_pending",
                  AsyncMock(return_value={"request": "把姓名填上", "taskRef": "ft_0"})):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    inputs = deps.dify.chat.await_args.kwargs["inputs"]
    assert inputs["pending_request_hint"] == "把姓名填上"
    assert inputs["last_task_ref"] == "ft_0"


@pytest.mark.asyncio
async def test_file_task_mirror_gets_conversation_and_pending_request(deps):
    """文件任务镜像要带上 conversation_id 和待填要求，Vue 才能按会话看任务。"""
    chain = pm.MessageChain([pm.File(type="File", id="f1", name="a.xlsx", size=1, url="")])
    ctx = FakeCtx(chain)
    task = {"taskRef": "ft_1", "senderId": 1001, "chatId": None,
            "structure": {"kind": "xlsx"}, "originalName": "a.xlsx"}

    with patch("core.file_task.ingest", AsyncMock(return_value=task)), \
            patch("core.file_store.load_pending",
                  AsyncMock(return_value={"request": "把姓名填上", "taskRef": "ft_0"})):
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    body = deps.spring.sync_file_task.await_args.args[0]
    assert body["taskRef"] == "ft_1"
    assert body["conversationId"] == "conv-1"
    assert body["pendingRequest"] == "把姓名填上"


@pytest.mark.asyncio
async def test_conversation_id_is_passed_to_dify(deps):
    """同一会话的第二条消息必须带上上一条返回的 conversation_id。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))
    deps.dify.chat.return_value = {
        "answer": '{"action":"SILENT"}', "conversation_id": "conv-old"}

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve",
                  AsyncMock(return_value="conv-old")) as resolve, \
            patch("core.conversation_store.bind", AsyncMock()) as bind:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    resolve.assert_awaited_once_with(deps, "bot-1", "private", None, 1001)
    assert deps.dify.chat.await_args.kwargs["conversation_id"] == "conv-old"
    assert deps.dify.chat.await_args.kwargs["user"] == "private:1001"
    bind.assert_not_awaited()


@pytest.mark.asyncio
async def test_new_conversation_is_bound(deps):
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve", AsyncMock(return_value=None)), \
            patch("core.conversation_store.bind", AsyncMock()) as bind:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    assert deps.dify.chat.await_args.kwargs["conversation_id"] is None
    bind.assert_awaited_once_with(deps, "bot-1", "private", None, 1001, "conv-1")


@pytest.mark.asyncio
async def test_missing_conversation_resets_and_retries(deps):
    """Dify 侧会话不存在时，不能把 400 直接吞成“无回复”。"""
    ctx = FakeCtx(pm.MessageChain([pm.Plain(text="你好")]))
    deps.dify.chat = AsyncMock(side_effect=[
        RuntimeError('Dify /chat-messages HTTP 400: '
                     '{"message":"Conversation Not Exists."}'),
        {"answer": '{"action":"SILENT"}', "conversation_id": "conv-new"},
    ])

    with patch("core.file_store.load_active_input", AsyncMock(return_value=None)), \
            patch("core.conversation_store.resolve", AsyncMock(return_value="conv-old")), \
            patch("core.conversation_store.reset", AsyncMock()) as reset, \
            patch("core.conversation_store.bind", AsyncMock()) as bind:
        await DefaultEventListener._handle(SimpleNamespace(plugin=deps), ctx, is_group=False)

    reset.assert_awaited_once_with(deps, "bot-1", "private", None, 1001)
    assert deps.dify.chat.await_count == 2
    assert "conversation_id" not in deps.dify.chat.await_args_list[1].kwargs
    bind.assert_awaited_once_with(deps, "bot-1", "private", None, 1001, "conv-new")
