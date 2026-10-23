# Phase 3-2：文件任务（读取 → Dify 决策 → 回写 → 回传）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** QQ 收到文件后，插件下载 → 机械抽取出结构 → 交给 Dify 决策「填什么」→ 按 `fills` 回写 → 回传；缺字段时在 QQ 里反问补全。**文件 I/O 全部在插件内，Dify 不碰二进制。**

**Architecture:** 任务状态机宿主在插件；Spring Boot 的 `file_task` 只是只读镜像，每次状态变化 `POST /api/file-tasks/sync`。LLM 决策与机械执行严格分离：Dify 只返回 `fills` / `missing` / `confidence`，插件不认识任何业务字段。

**Tech Stack:** openpyxl / python-docx / pypdf / httpx / asyncio。

## Global Constraints

- **只支持写回 xlsx、docx**；pdf 只读（抽文本给 Dify 做摘要/填表建议），收到 pdf 时明确回复「暂不支持回写」。
- 用户上传的文件与产物全部落在插件工作目录 `data/files/<taskRef>/` 下，**不覆盖原文件**。
- 任何状态变化都要 sync 到 Spring Boot；sync 失败只记日志，不影响任务。
- 任务必须异步执行（`asyncio.create_task`），事件 handler 立即返回。
- 一个用户同时只能有 **1 个** 处于 `NEED_INPUT` 的任务；新文件到达时若已存在，先取消旧任务。

---

## 一、状态机

```
PENDING ──► PROCESSING ──► NEED_INPUT ──► PROCESSING (补信息后重跑)
                │                              │
                ├──► AWAITING_CONFIRM (低置信) ─┘
                └──► SENT (高置信直接回传)
                └──► FAILED (异常 / 不支持)
                └──► CANCELLED (用户取消或新任务顶替)
```

| 状态 | 触发 | 通知用户 |
|---|---|---|
| `PENDING` | 收到文件、建任务 | 「收到，正在处理…」 |
| `PROCESSING` | 开始抽取/调用 Dify | 无 |
| `NEED_INPUT` | `missing` 非空 | 「还缺：专业、学号，直接回复我即可」 |
| `AWAITING_CONFIRM` | 低置信 | 「填好了，去草稿中心确认一下」 |
| `SENT` | 已回传 | 无（文件本身就是回复） |
| `FAILED` | 异常 | 「处理失败：<原因>」 |
| `CANCELLED` | 用户取消 / 被新任务顶替 | 视情况 |

---

## 二、文件结构（在 Phase 3-1 基础上新增）

```
qq_assistant_plugin/
├── core/
│   ├── file_store.py          # taskRef、目录、任务 JSON 持久化
│   ├── extractors/
│   │   ├── __init__.py        # extract(path) 分派
│   │   ├── xlsx.py
│   │   ├── docx.py
│   │   └── pdf.py
│   ├── writers/
│   │   ├── __init__.py
│   │   ├── xlsx.py            # apply_fills
│   │   └── docx.py
│   └── file_task.py           # 状态机编排
└── tests/
    ├── test_extract_xlsx.py
    ├── test_extract_docx.py
    ├── test_writer_xlsx.py
    └── test_file_task.py
```

---

## Task 1: 任务存储与目录

**Files:**
- Create: `core/file_store.py`

**Interfaces:**
- Produces:
  - `new_task_ref() -> str`（`ft_` + 8 位 hex）
  - `task_dir(task_ref) -> Path`（`data/files/<taskRef>/`，自动 mkdir）
  - `save_task(plugin, task: dict)` / `load_task(plugin, task_ref) -> dict | None`
  - `active_input_key(sender_id) -> str`

**持久化：** 用 `plugin.set_plugin_storage(key, json.dumps(task).encode())` / `get_plugin_storage`。key 约定：
- `file_task:{taskRef}`
- `file_input:{sender_id}` → 当前 `NEED_INPUT` 的 taskRef

- [ ] **Step 1: 实现**

```python
import json
import uuid
from pathlib import Path

BASE = Path(__file__).resolve().parent.parent / "data" / "files"


def new_task_ref() -> str:
    return "ft_" + uuid.uuid4().hex[:8]


def task_dir(task_ref: str) -> Path:
    d = BASE / task_ref
    d.mkdir(parents=True, exist_ok=True)
    return d


async def save_task(plugin, task: dict):
    await plugin.set_plugin_storage(f"file_task:{task['taskRef']}",
                                    json.dumps(task, ensure_ascii=False).encode())


async def load_task(plugin, task_ref: str) -> dict | None:
    try:
        raw = await plugin.get_plugin_storage(f"file_task:{task_ref}")
        return json.loads(raw.decode()) if raw else None
    except Exception:
        return None


async def set_active_input(plugin, sender_id: int, task_ref: str | None):
    key = f"file_input:{sender_id}"
    if task_ref:
        await plugin.set_plugin_storage(key, task_ref.encode())
    else:
        await plugin.set_plugin_storage(key, b"")
```

- [ ] **Step 2: Commit**

```bash
git add core/file_store.py
git commit -m "feat(plugin): file task storage and workspace layout"
```

---

## Task 2: 结构抽取器

**Files:**
- Create: `core/extractors/{__init__,xlsx,docx,pdf}.py`
- Test: `tests/test_extract_xlsx.py`、`tests/test_extract_docx.py`

**统一输出（喂给 Dify 的「结构化文本」）：**

```jsonc
// xlsx
{"kind":"xlsx","sheets":[{"name":"Sheet1","maxRow":10,"maxCol":5,
  "cells":[{"ref":"A1","value":"姓名"},{"ref":"B1","value":""}],
  "merged":["A1:B1"]}]}

// docx
{"kind":"docx",
 "paragraphs":[{"index":0,"text":"入团申请书"}],
 "tables":[{"index":0,"rows":[["姓名",""],["学号",""]]}]}

// pdf
{"kind":"pdf","pages":[{"index":0,"text":"..."}]}
```

**规则：**
- 只输出**非空单元格**（值非 None 且 str 后非空）；空单元格由 Dify 按需指定 ref 填充。为了让 LLM 知道表有多大，同时给 `maxRow/maxCol`。
- 单元格值统一转字符串；日期用 `isoformat()`。
- 单个文件结构 JSON 超过 20000 字符时截断并置 `"truncated": true`（防止撑爆 Dify 输入）。

- [ ] **Step 1: xlsx 测试**

```python
from openpyxl import Workbook
from core.extractors.xlsx import extract_xlsx

def test_extract_cells_and_merges(tmp_path):
    wb = Workbook()
    ws = wb.active
    ws["A1"] = "姓名"; ws["B1"] = ""
    ws["A2"] = 100
    ws.merge_cells("A3:B3")
    path = tmp_path / "t.xlsx"; wb.save(path)

    data = extract_xlsx(path)
    cells = {c["ref"]: c["value"] for c in data["sheets"][0]["cells"]}
    assert cells == {"A1": "姓名", "A2": "100"}
    assert data["sheets"][0]["merged"] == ["A3:B3"]
    assert data["sheets"][0]["maxRow"] == 3
```

- [ ] **Step 2: 实现 xlsx**

```python
from openpyxl import load_workbook


def extract_xlsx(path) -> dict:
    wb = load_workbook(path, data_only=True)
    sheets = []
    for ws in wb.worksheets:
        cells = []
        for row in ws.iter_rows():
            for cell in row:
                if cell.value is None:
                    continue
                text = cell.value.isoformat() if hasattr(cell.value, "isoformat") else str(cell.value)
                if text.strip():
                    cells.append({"ref": cell.coordinate, "value": text})
        sheets.append({
            "name": ws.title,
            "maxRow": ws.max_row,
            "maxCol": ws.max_column,
            "cells": cells,
            "merged": [str(r) for r in ws.merged_cells.ranges],
        })
    return {"kind": "xlsx", "sheets": sheets}
```

- [ ] **Step 3: docx 测试 + 实现**

```python
from docx import Document
from core.extractors.docx import extract_docx

def test_extract_paragraphs_and_tables(tmp_path):
    doc = Document()
    doc.add_paragraph("入团申请书")
    table = doc.add_table(rows=2, cols=2)
    table.cell(0, 0).text = "姓名"
    path = tmp_path / "t.docx"; doc.save(path)

    data = extract_docx(path)
    assert data["paragraphs"][0]["text"] == "入团申请书"
    assert data["tables"][0]["rows"][0][0] == "姓名"
```

```python
from docx import Document


def extract_docx(path) -> dict:
    doc = Document(path)
    paragraphs = [{"index": i, "text": p.text} for i, p in enumerate(doc.paragraphs)]
    tables = []
    for ti, table in enumerate(doc.tables):
        rows = [[cell.text for cell in row.cells] for row in table.rows]
        tables.append({"index": ti, "rows": rows})
    return {"kind": "docx", "paragraphs": paragraphs, "tables": tables}
```

- [ ] **Step 4: pdf（只读）**

```python
from pypdf import PdfReader


def extract_pdf(path) -> dict:
    reader = PdfReader(str(path))
    pages = [{"index": i, "text": (page.extract_text() or "")[:4000]}
             for i, page in enumerate(reader.pages)]
    return {"kind": "pdf", "pages": pages, "writable": False}
```

- [ ] **Step 5: 分派器**

```python
from pathlib import Path
from .xlsx import extract_xlsx
from .docx import extract_docx
from .pdf import extract_pdf

MAX_CHARS = 20_000

WRITABLE = {".xlsx", ".docx"}


def extract(path: Path) -> dict:
    suffix = path.suffix.lower()
    if suffix == ".xlsx":
        data = extract_xlsx(path)
    elif suffix == ".docx":
        data = extract_docx(path)
    elif suffix == ".pdf":
        data = extract_pdf(path)
    else:
        raise ValueError(f"不支持的文件类型：{suffix}")
    import json
    if len(json.dumps(data, ensure_ascii=False)) > MAX_CHARS:
        data["truncated"] = True
    return data
```

- [ ] **Step 6: 跑测试并 Commit**

```bash
../.venv/Scripts/python.exe -m pip install openpyxl python-docx pypdf
../.venv/Scripts/python.exe -m pytest tests/test_extract_xlsx.py tests/test_extract_docx.py -q
git add core/extractors tests/test_extract_xlsx.py tests/test_extract_docx.py
git commit -m "feat(plugin): generic xlsx/docx/pdf structure extractors"
```

---

## Task 3: 回写器

**Files:**
- Create: `core/writers/{__init__,xlsx,docx}.py`
- Test: `tests/test_writer_xlsx.py`

**Dify 返回的 `fills` 协议：**

```jsonc
// xlsx
[{"sheet":"Sheet1","ref":"B2","value":"张三"}, {"sheet":"Sheet1","ref":"B3","value":"2023001"}]
// docx
[{"kind":"paragraph","index":3,"value":"张三"},
 {"kind":"table","table":0,"row":1,"col":1,"value":"2023001"}]
```

- [ ] **Step 1: xlsx 写回测试**

```python
from openpyxl import Workbook, load_workbook
from core.writers.xlsx import apply_xlsx

def test_apply_fills_by_ref(tmp_path):
    wb = Workbook(); ws = wb.active; ws.title = "Sheet1"
    src = tmp_path / "in.xlsx"; wb.save(src)
    out = tmp_path / "out.xlsx"

    errors = apply_xlsx(src, out, [
        {"sheet": "Sheet1", "ref": "B2", "value": "张三"},
        {"sheet": "Nope", "ref": "A1", "value": "x"},     # 忽略并记错误
    ])

    assert load_workbook(out).active["B2"].value == "张三"
    assert len(errors) == 1
```

- [ ] **Step 2: 实现**

```python
from openpyxl import load_workbook


def apply_xlsx(src, out, fills) -> list[str]:
    wb = load_workbook(src)
    errors = []
    for fill in fills or []:
        name = fill.get("sheet")
        ref = fill.get("ref")
        if not name or not ref:
            errors.append(f"missing sheet/ref: {fill}")
            continue
        if name not in wb.sheetnames:
            errors.append(f"sheet not found: {name}")
            continue
        wb[name][ref] = fill.get("value")
    wb.save(out)
    return errors
```

- [ ] **Step 3: docx 写回**

```python
from docx import Document


def apply_docx(src, out, fills) -> list[str]:
    doc = Document(src)
    errors = []
    for fill in fills or []:
        kind = fill.get("kind")
        try:
            if kind == "paragraph":
                doc.paragraphs[int(fill["index"])].text = str(fill.get("value", ""))
            elif kind == "table":
                table = doc.tables[int(fill["table"])]
                table.cell(int(fill["row"]), int(fill["col"])).text = str(fill.get("value", ""))
            else:
                errors.append(f"unknown kind: {kind}")
        except (IndexError, KeyError, ValueError) as e:
            errors.append(f"bad fill {fill}: {e}")
    doc.save(out)
    return errors
```

> docx 的 `paragraph.text =` 会清掉段落内的 run 格式（加粗/字号）。对「填空」场景可接受；若要保格式，后续改成替换 run 文本。

- [ ] **Step 4: 跑测试并 Commit**

```bash
../.venv/Scripts/python.exe -m pytest tests/test_writer_xlsx.py -q
git add core/writers tests/test_writer_xlsx.py
git commit -m "feat(plugin): mechanical xlsx/docx fill writers"
```

---

## Task 4: 下载文件与状态机编排

**Files:**
- Create: `core/file_task.py`
- Test: `tests/test_file_task.py`

**下载：** 事件里拿到 `File` 组件后：

```python
file_comp = next(c for c in chain if isinstance(c, pm.File))
# 先确认 SDK 字段（不同版本可能是 file_id / url / name）：
log.info("file component: %s", file_comp.model_dump())

file_id = getattr(file_comp, "file_id", None) or getattr(file_comp, "id", None)
name = getattr(file_comp, "name", None) or getattr(file_comp, "file_name", None) or "upload.bin"

url = getattr(file_comp, "url", None)
if not url and file_id:
    info = await plugin.call_platform_api(bot_uuid, "get_file_url", {"file_id": file_id})
    url = info.get("url") or info.get("file")
```

> **Task 4 第 0 步必须做**：上传一个文件并打印 `model_dump()`，把真实字段名补进本文件。`get_file_url` 是 LangBot 文档列出的平台 action，已确认可用。

然后 `httpx.get(url)` 下载到 `task_dir/input<ext>`。

**Dify 文件工作流：**

```
POST {dify_base}/workflows/run
Authorization: Bearer <FILE_WORKFLOW_KEY>
{
  "inputs": {
    "structure": "<结构 JSON 字符串>",
    "facts": "<画像 facts JSON>",
    "request": "用户原话/要求"
  },
  "response_mode": "blocking",
  "user": "<sender_id>"
}
→ { "data": { "outputs": { "fills": "[...]", "missing": "[...]", "confidence": 0.9, "needs_confirm": false } } }
```

`outputs` 里的 `fills`/`missing` 可能是字符串，统一 `json.loads`，失败置空并记错误。

**编排（伪代码，按此顺序实现）：**

```python
async def handle(ctx, event, is_group, deps):
    sender_id = int(event.sender_id)
    chat_id = int(event.launcher_id) if is_group else None
    task_ref = new_task_ref()
    task = {"taskRef": task_ref, "senderId": sender_id, "chatId": chat_id,
            "status": "PENDING", "input": {}, "provided": {}, "fills": []}
    await sync(deps, task)
    await reply_text(ctx, "收到，正在处理…")

    try:
        path = await download(event, task_ref, deps)          # 失败 → FAILED
        task["input"] = {"path": str(path), "name": path.name}
        task["status"] = "PROCESSING"; await sync(deps, task)

        if path.suffix.lower() not in WRITABLE:
            task["status"] = "FAILED"
            task["detail"] = {"reason": "pdf 暂不支持回写"}
            await sync(deps, task)
            await reply_text(ctx, "这个格式我只能读不能改，换成 xlsx/docx 吧")
            return

        structure = extract(path)
        facts = (await deps.spring.profile(sender_id)).get("facts", {})
        decision = await call_file_workflow(deps, structure, facts, task.get("request", ""))
        task["fills"] = decision.get("fills", [])
        task["missing"] = decision.get("missing", [])
        task["confidence"] = decision.get("confidence", 0)

        if task["missing"]:
            task["status"] = "NEED_INPUT"
            await set_active_input(deps.plugin, sender_id, task_ref)
            await sync(deps, task)
            await reply_text(ctx, "还缺：" + "、".join(task["missing"]) + "，直接回复我即可")
            return

        await finish(task, deps, ctx)

    except Exception as e:
        log.exception("file task %s failed", task_ref)
        task["status"] = "FAILED"; task["detail"] = {"reason": str(e)}
        await sync(deps, task)
        await reply_text(ctx, f"处理失败：{e}")
```

`finish()`：

```python
out = task_dir(task_ref) / f"{Path(task['input']['path']).stem}_filled{Path(task['input']['path']).suffix}"
errors = apply(out, fills)          # 按后缀分派
task["output"] = {"path": str(out), "name": out.name, "errors": errors}

low_confidence = task.get("needs_confirm") or task.get("confidence", 1.0) < deps.settings.file_confirm_threshold

if low_confidence:
    await deps.spring.create_draft("FILE", sender_id, chat_id, "文件待确认",
        {"taskRef": task_ref, "fileName": out.name, "fileRef": f"local://{out}"})
    task["status"] = "AWAITING_CONFIRM"
    await reply_text(ctx, "填好了，去草稿中心确认后发送")
else:
    await send_file(deps, task, bot_uuid)
    task["status"] = "SENT"
await sync(deps, task)
```

- [ ] **Step 1: 先做字段探针（打印 `model_dump`），把真实字段名写死到下载函数**
- [ ] **Step 2: 实现 `sync()` / `call_file_workflow()` / `finish()` / `handle()`**
- [ ] **Step 3: 用 MockTransport 写 `test_file_task.py`**：给定 xlsx + 固定 Dify 输出 → 断言产出文件被写入、状态依次为 PENDING→PROCESSING→SENT，且 `sync` 调用次数正确
- [ ] **Step 4: 真机冒烟**

1. 私聊发一个空白 xlsx（A1=姓名，B1 空）→ 收到「收到」→ 若画像有姓名，直接收到填好的文件
2. 画像删掉姓名再发一次 → 收到「还缺：姓名」→ 回复「张三」→ 收到填好的文件
3. 发 pdf → 收到「只能读不能改」
4. `GET /api/file-tasks` 能看到对应 taskRef 的状态流转

- [ ] **Step 5: Commit**

```bash
git add core/file_task.py tests/test_file_task.py
git commit -m "feat(plugin): file task state machine with Dify decision and QQ follow-up"
```

---

## Task 5: 把 NEED_INPUT 回复接回主流程

**Files:**
- Modify: `components/event_listener/default.py`

在 Phase 3-1 的 `handle_message` 中，**私聊 + 纯文本**分支最前面插入：

```python
if not is_group:
    active = await file_store.load_active_input(deps.plugin, sender_id)
    if active:
        await deps.file_handler.provide_input(active, text, ctx)
        return
```

`provide_input(task_ref, text, ctx)`：

1. `task = await load_task(...)`；若不存在 → 清 key，走正常流程。
2. 把 `text` 存进 `task["provided_answer"]`（不猜字段名，整段交给 Dify，由 Dify 从 missing 里对齐）。
3. 清 `active_input` key，状态回 `PROCESSING`，用「原 structure + 用户补充 + 已提供的值」重跑 Dify 工作流。
4. 仍有 `missing` → 再次进入 `NEED_INPUT`；否则 `finish()`。

> 这样「还缺：专业」→ 用户回「计算机科学与技术」不需要插件做任何语义解析，符合「LLM 决策、Python 执行」的总原则。

- [ ] **Step 1: 实现并冒烟（覆盖上面场景 2）**
- [ ] **Step 2: Commit**

```bash
git add components/event_listener/default.py core/file_task.py
git commit -m "feat(plugin): route NEED_INPUT replies back into file tasks"
```

---

## Task 6: 文件回传

**Files:**
- Modify: `core/file_task.py`

**两条路，先验证再选：**

1. **首选**：`plugin.send_message(bot_uuid, target_type, target_id, MessageChain([File(...)]))`
   - 先 `python -c "import inspect, langbot_plugin.api.entities.builtin.platform.message as m; print(inspect.signature(m.File))"` 看字段。
   - OneBot v11 适配器支持「回复文件消息」（LangBot 平台特性表已确认），但 `File` 组件是否接受**本地绝对路径**需实测。
2. **兜底**：调 NapCat OneBot 11 的 `upload_private_file` / `upload_group_file`（`{user_id|group_id, file, name}`）。这条路径与 Phase 3-3 的桥共用代码。

> 回传统一封装成 `send_file_via_best_available()`，Phase 3-3 的桥也复用它。

- [ ] **Step 1: 实测并实现**
- [ ] **Step 2: 真机确认手机 QQ 能收到文件且文件名正确**
- [ ] **Step 3: Commit**

```bash
git add core/file_task.py core/transfer.py
git commit -m "feat(plugin): send generated file back to QQ"
```

---

## 验收清单

- [ ] xlsx：按 ref 写入，公式/合并单元格不破坏
- [ ] docx：段落与表格都能写，索引错位时返回 errors 而不是崩溃
- [ ] pdf：明确拒绝回写，不产生半成品
- [ ] 缺字段 → QQ 反问 → 用户补充 → 自动续跑，全过程无插件侧语义解析
- [ ] 低置信 → FILE 草稿 → Vue 确认后由 Phase 3-3 的桥发送
- [ ] `file_task` 镜像表状态与插件内状态一致
