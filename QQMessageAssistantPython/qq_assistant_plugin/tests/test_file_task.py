"""文件任务状态机测试：真实 xlsx + respx 假下载/假 Dify 工作流。

钉三件事（计划 Task 4 Step 3）：
1. 产出文件真的被写入且内容正确
2. 状态流转序列精确为 PENDING→PROCESSING→SENT
3. 每次流转都 sync 到 Spring（次数与状态数一致）
"""
from types import SimpleNamespace
from unittest.mock import AsyncMock, patch

import httpx
import pytest
import respx
from langbot_plugin.api.entities.builtin.platform import message as pm
from openpyxl import Workbook, load_workbook

from core import file_store
from core.config import Settings
from core.file_task import handle, provide_input
from core.dify_client import DifyClient

FILE_URL = "http://files.test/in.xlsx"
WORKFLOW_URL = "http://dify/v1/workflows/run"


class FakePlugin:
    """插件替身：内存版 storage + 记录 send_message，属性对齐真 deps。"""

    def __init__(self, settings):
        self.store = {}
        self.sent = []
        self.settings = settings
        self.spring = AsyncMock()
        self.spring.profile = AsyncMock(return_value={"facts": {"姓名": "张三"}})
        self.dify = DifyClient("http://dify/v1", "chat-key", 10)

    async def set_plugin_storage(self, key, value):
        self.store[key] = value

    async def get_plugin_storage(self, key):
        return self.store.get(key, b"")

    async def send_message(self, bot_uuid, target_type, target_id, chain):
        self.sent.append((target_type, target_id, chain))

    async def call_platform_api(self, bot_uuid, action, params=None):
        return {}


class FakeCtx:
    def __init__(self, chain):
        self.event = SimpleNamespace(sender_id="1001", launcher_id=None,
                                     message_chain=chain)

    async def get_bot_uuid(self):
        return "bot-u"


@pytest.fixture
def settings(tmp_path, monkeypatch):
    # 把工作目录挪到 tmp_path，测试不污染 data/files
    monkeypatch.setattr(file_store, "BASE", tmp_path / "files")
    return Settings(
        spring_base_url="http://sb", spring_timeout=5,
        dify_base_url="http://dify/v1", dify_api_key="k", dify_timeout=10,
        dify_file_workflow_key="wf-key", file_confirm_threshold=0.85,
        bot_uuid="u", bot_qq=12345, group_prefix="!",
        fact_auto_threshold=0.85,
        delay_base_ms=0, delay_per_char_ms=0, delay_jitter_ms=0, delay_max_ms=0,
        intercept_refresh_s=60, intercept_scope="group",
    )


@pytest.fixture
def plugin(settings):
    return FakePlugin(settings)


def make_xlsx(tmp_path):
    wb = Workbook()
    ws = wb.active
    ws.title = "Sheet1"
    ws["A1"] = "姓名"
    src = tmp_path / "src.xlsx"
    wb.save(src)
    return src.read_bytes()


def file_chain():
    return pm.MessageChain([
        pm.Plain(text="帮我把表填了"),
        pm.File(type="File", id="f1", name="名单.xlsx", size=100, url=FILE_URL),
    ])


def workflow_response(outputs):
    return httpx.Response(200, json={"data": {"outputs": outputs}})


@pytest.mark.asyncio
@respx.mock
async def test_happy_path_pending_processing_sent(plugin, tmp_path):
    respx.get(FILE_URL).mock(return_value=httpx.Response(200, content=make_xlsx(tmp_path)))
    respx.post(WORKFLOW_URL).mock(return_value=workflow_response({
        "fills": '[{"sheet":"Sheet1","ref":"B1","value":"张三"}]',  # 字符串形态！
        "missing": "[]",
        "confidence": 0.95,
    }))
    ctx = FakeCtx(file_chain())

    with patch("core.file_task.reply_text", new=AsyncMock()):
        await handle(ctx, ctx.event, is_group=False, deps=plugin)

    # 状态序列精确
    statuses = [c.args[0]["status"] for c in plugin.spring.sync_file_task.await_args_list]
    assert statuses == ["PENDING", "PROCESSING", "SENT"]

    # 产物文件真的写了、内容对
    task_ref = plugin.spring.sync_file_task.await_args_list[0].args[0]["taskRef"]
    out = file_store.task_dir(task_ref) / "input_filled.xlsx"
    assert out.exists()
    assert load_workbook(out)["Sheet1"]["B1"].value == "张三"

    # 高置信走 send_message 回传，没进草稿
    assert len(plugin.sent) == 1
    plugin.spring.create_draft.assert_not_awaited()


@pytest.mark.asyncio
@respx.mock
async def test_need_input_then_provide_completes(plugin, tmp_path):
    respx.get(FILE_URL).mock(return_value=httpx.Response(200, content=make_xlsx(tmp_path)))
    # 两次工作流调用：第一次缺学号，第二次补全后给 fills
    respx.post(WORKFLOW_URL).mock(side_effect=[
        workflow_response({"fills": "[]", "missing": '["学号"]', "confidence": 0.9}),
        workflow_response({"fills": '[{"sheet":"Sheet1","ref":"B2","value":"2023001"}]',
                           "missing": [], "confidence": 0.95}),
    ])
    ctx = FakeCtx(file_chain())

    with patch("core.file_task.reply_text", new=AsyncMock()) as rt:
        await handle(ctx, ctx.event, is_group=False, deps=plugin)
        # 反问文案 + active_input 登记
        assert any("还缺：学号" in c.args[1] for c in rt.await_args_list)
        assert await file_store.load_active_input(plugin, 1001) is not None

        statuses = [c.args[0]["status"] for c in plugin.spring.sync_file_task.await_args_list]
        assert statuses == ["PENDING", "PROCESSING", "NEED_INPUT"]

    active = await file_store.load_active_input(plugin, 1001)
    with patch("core.file_task.reply_text", new=AsyncMock()):
        done = await provide_input(active, "学号 2023001", ctx, plugin)

    assert done is True
    assert await file_store.load_active_input(plugin, 1001) is None
    last = plugin.spring.sync_file_task.await_args_list[-1].args[0]["status"]
    assert last == "SENT"


@pytest.mark.asyncio
@respx.mock
async def test_unwritable_format_fails_fast(plugin):
    # pdf 不进入抽取/决策，直接 FAILED + 明确文案
    pdf_chain = pm.MessageChain([
        pm.File(type="File", id="f2", name="材料.pdf", size=10, url=FILE_URL)])
    respx.get(FILE_URL).mock(return_value=httpx.Response(200, content=b"%PDF-fake"))
    ctx = FakeCtx(pdf_chain)

    with patch("core.file_task.reply_text", new=AsyncMock()) as rt:
        await handle(ctx, ctx.event, is_group=False, deps=plugin)

    assert plugin.spring.sync_file_task.await_args_list[-1].args[0]["status"] == "FAILED"
    assert any("只能读不能改" in c.args[1] for c in rt.await_args_list)
    # 没有调工作流（respx 未注册 workflows 路由，调了会炸）
