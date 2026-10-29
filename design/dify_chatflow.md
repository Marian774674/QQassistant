# Dify Chatflow 搭建指南（精确到节点）

> 配套契约：[`agent_contract.md`](./agent_contract.md)。
> 目标：Dify 负责语义、对话状态、文件任务判断；插件只做机械执行。
> 适用：Dify 1.17.x，应用类型 **Chatflow（advanced-chat）**。
>
> 本文按“照着点”写：节点名、位置、输入、输出、连线、条件、变量赋值、代码、提示词全部给出。
> 画布坐标是参考值，按列从左到右排即可，不必精确到像素。

---

## 0. 最终效果

1. 用户先说“我等下发个表，把姓名学号填上” → `WAIT_FILE`，要求存进会话变量。
2. 用户发文件 → 自动用存下的要求填，不再问“要我填什么”。
3. 用户先发文件 → `ASK`“要我填什么”，后续文本继续填。
4. 文本 + 文件同一条消息 → `CHECK_REQUEST` 会把文件消息自带的文本也算进要求，直接填。
5. 普通聊天 / 备忘 / 事实 / 备忘闭环 → 走 `CHAT_DECISION`。
6. 所有分支最后汇入一个 `ANSWER`，输出纯 JSON，插件负责执行。

---

## 1. 先建变量（节点之前必须做）

### 1.1 Start 节点变量（全部 String）

在 Start 节点「输入字段」里逐个添加，顺序建议如下：

| 变量名 | 说明 | 示例 |
|---|---|---|
| `event_type` | `text` / `file` | `text` |
| `query` | 用户文本；文件事件可带同条消息文本 | `把姓名填上` |
| `facts` | 画像事实 JSON 字符串 | `{"姓名":"张三"}` |
| `style_prompt` | 风格画像 | `语气自然，简短` |
| `chat_type` | `private` / `group` | `private` |
| `chat_id` | 群号；私聊空串 | `` |
| `sender_id` | QQ 号 | `1001` |
| `current_time` | ISO 时间 | `2026-10-26T20:30:00` |
| `file_task_ref` | 文件任务引用 | `ft_ab12cd34` |
| `file_name` | 原始文件名 | `test.xlsx` |
| `file_kind` | `xlsx/docx/pdf/other` | `xlsx` |
| `structure` | 文件结构 JSON 字符串 | `{"kind":"xlsx",...}` |
| `pending_request_hint` | 插件侧备份的待填要求 | `把姓名和学号填上` |
| `last_task_ref` | 上一轮文件任务 | `ft_ab12cd34` |
| `open_memos` | 未终结备忘 JSON 数组（`max_length` 20000） | `[{"id":12,"title":"班会",...}]` |
| `event_id` | 写操作幂等键（`max_length` 64） | `m3f9a0…` |

> 插件发送未声明的变量会被 Dify 判 400。**先把这 16 个变量建全，再升级插件。**

### 1.3 环境变量（Environment Variables）

控制台 →「环境变量」页签，两个都要建（`OP_HTTP` 节点引用它们，见 §12）：

| 变量名 | 类型 | 值 | 说明 |
|---|---|---|---|
| `SPRING_BASE_URL` | 字符串 | `http://host.docker.internal:8080` | Dify 在 Docker 里时用 host.docker.internal；同机直跑用 `http://localhost:8080` |
| `AGENT_OPS_TOKEN` | **Secret** | 与 Spring 的 `AGENT_OPS_TOKEN` 环境变量一致 | 不随 DSL 导出：**导入后必须在 UI 里重填**，否则 `/api/agent/*` 一律 503 |

### 1.2 会话变量（Conversation Variables）

右侧「变量」→「会话变量」，逐个添加：

| 变量名 | 类型 | 默认值 | 作用 |
|---|---|---|---|
| `pending_file_request` | String | `""` | 用户先说的填写要求 |
| `pending_file_task_ref` | String | `""` | 已收到文件、等待补充的 task_ref |
| `pending_file_expires_at` | Number | `0.1` | 过期时间（epoch 秒）；**默认值必须是小数**，写成 `0` 会被 Dify 存成 `value_type: integer` 并让所有请求 400，见 §6.1 |
| `awaiting` | String | `none` | `none/file_request/file_input` |
| `last_action` | String | `""` | 调试 |
| `last_task_ref` | String | `""` | 上一轮任务 |

> 所有状态更新都用「变量赋值」节点，不要让 LLM 直接改变量。

---

## 2. 节点总表（含参考位置）

画布从左到右 9 列，文件分支在上、文本分支在下。

| # | 节点名 | 类型（Dify 面板） | X | Y | 作用 |
|---|---|---|---|---|---|
| 1 | `START` | 开始 | 80 | 480 | 用户输入 |
| 2 | `CLOCK` | 代码执行 | 320 | 480 | 算 now / 过期时间 |
| 3 | `IS_FILE` | 条件分支 | 560 | 480 | 文件事件分流 |
| 4 | `CHECK_REQUEST` | 代码执行 | 800 | 160 | 算本次要填的要求 |
| 5 | `HAS_REQUEST` | 条件分支 | 1040 | 160 | 有要求吗 |
| 6 | `FILE_FILL` | 工具 | 1280 | 60 | 文件分支调填写工作流 |
| 7 | `CHECK_MISSING` | 代码执行 | 1520 | 60 | 生成 ASK 或 FILE_FILL |
| 8 | `HAS_MISSING` | 条件分支 | 1760 | 60 | 缺字段吗 |
| 9 | `KEEP_FILE_INPUT` | 变量赋值 | 2000 | 20 | 缺字段：保持等待 |
| 10 | `CLEAR_AFTER_FILL` | 变量赋值 | 2000 | 140 | 填完：清状态 |
| 11 | `ASK_NO_REQUEST` | 代码执行 | 1280 | 260 | “要我填什么” |
| 12 | `SET_FILE_INPUT` | 变量赋值 | 1520 | 260 | 文件先到：记 task_ref |
| 13 | `IS_AWAITING` | 条件分支 | 800 | 720 | 是否在等补充信息 |
| 14 | `IS_CANCEL` | 条件分支 | 1040 | 600 | 用户是否放弃 |
| 15 | `CANCEL_REPLY` | 代码执行 | 1280 | 540 | 生成“不填了” |
| 16 | `CLEAR_CANCEL` | 变量赋值 | 1520 | 540 | 清状态 |
| 17 | `BUILD_REQUEST` | 代码执行 | 1040 | 720 | 合并 pending + 本轮文本 |
| 18 | `FILE_FILL_TEXT` | 工具 | 1280 | 720 | 文本补充调填写工作流 |
| 19 | `CHECK_MISSING_TEXT` | 代码执行 | 1520 | 720 | 同 #7 |
| 20 | `HAS_MISSING_TEXT` | 条件分支 | 1760 | 720 | 同 #8 |
| 21 | `KEEP_FILE_INPUT_TEXT` | 变量赋值 | 2000 | 660 | 缺字段：保持 |
| 22 | `CLEAR_AFTER_FILL_TEXT` | 变量赋值 | 2000 | 780 | 填完：清状态 |
| 23 | `TEXT_INTENT` | 问题分类器 | 1040 | 920 | 文件要求 vs 其他 |
| 24 | `WAIT_FILE_REPLY` | 代码执行 | 1280 | 880 | 生成 WAIT_FILE |
| 25 | `SET_FILE_REQUEST` | 变量赋值 | 1520 | 880 | 存 pending 要求 |
| 26 | `CHAT_DECISION` | LLM | 1280 | 1040 | 聊天/备忘/事实 |
| 27 | `CLEAN_DECISION` | 代码执行 | 1520 | 1040 | 清围栏、兜底 SILENT、产出 `decision/is_op/op_body` |
| 28 | `IS_OP` | 条件分支 | 1800 | 1040 | 这条消息是不是写操作 |
| 29 | `OP_HTTP` | HTTP 请求 | 2080 | 960 | 调 Spring `/api/agent/ops`（见 §12） |
| 30 | `READ_OP_RESULT` | 代码执行 | 2360 | 900 | 把 Result 信封翻成 SEND |
| 31 | `OP_FAILED` | 代码执行 | 2360 | 1180 | 失败分支的固定道歉 |
| 32 | `MERGE_DECISION` | 变量聚合器 | 2760 | 540 | 合并所有分支（顺序有语义，见 §12.4） |
| 33 | `ANSWER` | 直接回复 | 3000 | 540 | 输出最终 JSON |

> 如果画布放不下，把 X 方向拉长即可；关键是**列顺序**和**连线**不要错。

---

## 3. 连线表（从 → 到，端口）

| 从 | 端口/条件 | 到 |
|---|---|---|
| `START` | 默认 | `CLOCK` |
| `CLOCK` | 默认 | `IS_FILE` |
| `IS_FILE` | `IF`（event_type = file） | `CHECK_REQUEST` |
| `IS_FILE` | `ELSE` | `IS_AWAITING` |
| `CHECK_REQUEST` | 默认 | `HAS_REQUEST` |
| `HAS_REQUEST` | `IF`（has_request = true） | `FILE_FILL` |
| `HAS_REQUEST` | `ELSE` | `ASK_NO_REQUEST` |
| `FILE_FILL` | 默认 | `CHECK_MISSING` |
| `CHECK_MISSING` | 默认 | `HAS_MISSING` |
| `HAS_MISSING` | `IF`（has_missing = true） | `KEEP_FILE_INPUT` |
| `HAS_MISSING` | `ELSE` | `CLEAR_AFTER_FILL` |
| `ASK_NO_REQUEST` | 默认 | `SET_FILE_INPUT` |
| `IS_AWAITING` | `IF`（awaiting=file_input 且 task_ref 非空） | `IS_CANCEL` |
| `IS_AWAITING` | `ELSE` | `TEXT_INTENT` |
| `IS_CANCEL` | `IF`（query 含取消词） | `CANCEL_REPLY` |
| `IS_CANCEL` | `ELSE` | `BUILD_REQUEST` |
| `CANCEL_REPLY` | 默认 | `CLEAR_CANCEL` |
| `BUILD_REQUEST` | 默认 | `FILE_FILL_TEXT` |
| `FILE_FILL_TEXT` | 默认 | `CHECK_MISSING_TEXT` |
| `CHECK_MISSING_TEXT` | 默认 | `HAS_MISSING_TEXT` |
| `HAS_MISSING_TEXT` | `IF`（has_missing = true） | `KEEP_FILE_INPUT_TEXT` |
| `HAS_MISSING_TEXT` | `ELSE` | `CLEAR_AFTER_FILL_TEXT` |
| `TEXT_INTENT` | `file_request` | `WAIT_FILE_REPLY` |
| `TEXT_INTENT` | `other` | `CHAT_DECISION` |
| `WAIT_FILE_REPLY` | 默认 | `SET_FILE_REQUEST` |
| `CHAT_DECISION` | 默认 | `CLEAN_DECISION` |
| `KEEP_FILE_INPUT` | 默认 | `MERGE_DECISION` |
| `CLEAR_AFTER_FILL` | 默认 | `MERGE_DECISION` |
| `SET_FILE_INPUT` | 默认 | `MERGE_DECISION` |
| `CLEAR_CANCEL` | 默认 | `MERGE_DECISION` |
| `KEEP_FILE_INPUT_TEXT` | 默认 | `MERGE_DECISION` |
| `CLEAR_AFTER_FILL_TEXT` | 默认 | `MERGE_DECISION` |
| `SET_FILE_REQUEST` | 默认 | `MERGE_DECISION` |
| `CLEAN_DECISION` | 默认 | `IS_OP` |
| `IS_OP` | `IF`（is_op = true） | `OP_HTTP` |
| `IS_OP` | `ELSE` | `MERGE_DECISION` |
| `OP_HTTP` | 成功 | `READ_OP_RESULT` |
| `OP_HTTP` | 失败分支 | `OP_FAILED` |
| `READ_OP_RESULT` | 默认 | `MERGE_DECISION` |
| `OP_FAILED` | 默认 | `MERGE_DECISION` |
| `MERGE_DECISION` | 默认 | `ANSWER` |

> 如果 Dify 版本没有「变量聚合器」：删掉 `MERGE_DECISION`，在每个分支末端各放一个「直接回复」，内容引用该分支的 `decision`。其余不变。

---

## 4. 条件分支的精确条件

### 4.1 `IS_FILE`

- 变量：`Start / event_type`
- 操作符：`是`（等于）
- 值：`file`
- `IF` → `CHECK_REQUEST`；`ELSE` → `IS_AWAITING`

### 4.2 `HAS_REQUEST`

- 变量：`CHECK_REQUEST / has_request`
- 操作符：`是`
- 值：`true`
- `IF` → `FILE_FILL`；`ELSE` → `ASK_NO_REQUEST`

### 4.3 `HAS_MISSING` / `HAS_MISSING_TEXT`

- 变量：`CHECK_MISSING / has_missing`（文本分支选 `CHECK_MISSING_TEXT / has_missing`）
- 操作符：`是`
- 值：`true`
- `IF` → `KEEP_*`；`ELSE` → `CLEAR_*`

### 4.4 `IS_AWAITING`

选择「且」（AND），两个条件：

1. 变量 `会话变量 / awaiting`，操作符 `是`，值 `file_input`
2. 变量 `会话变量 / pending_file_task_ref`，操作符 `不为空`

- `IF` → `IS_CANCEL`；`ELSE` → `TEXT_INTENT`

### 4.5 `IS_CANCEL`

选择「或」（OR），四个条件：

1. `Start / query` `包含` `算了`
2. `Start / query` `包含` `取消`
3. `Start / query` `包含` `不填了`
4. `Start / query` `包含` `不用了`

- `IF` → `CANCEL_REPLY`；`ELSE` → `BUILD_REQUEST`

### 4.6 `IS_OP`

- 变量：`CLEAN_DECISION / is_op`
- 操作符：`是`，值 `true`（字符串，不是布尔——代码节点输出的是 `"true"` / `"false"`）
- `IF` → `OP_HTTP`；`ELSE` → `MERGE_DECISION`

---

## 5. 变量赋值节点（逐个照填）

> 在「变量赋值」节点里，每个变量选择操作「覆盖/设置」；常量用「固定值」。

### 5.1 `KEEP_FILE_INPUT`（缺字段，继续等）

| 目标变量 | 值 |
|---|---|
| `conversation.awaiting` | 固定值 `file_input` |
| `conversation.pending_file_task_ref` | `Start / file_task_ref` |
| `conversation.pending_file_expires_at` | `CLOCK / next_expires` |
| `conversation.last_action` | 固定值 `ASK` |

> 不要动 `pending_file_request`，保留用户原来提的要求。

### 5.2 `CLEAR_AFTER_FILL`（填完）

| 目标变量 | 值 |
|---|---|
| `conversation.pending_file_request` | 固定值 空字符串 |
| `conversation.pending_file_task_ref` | 固定值 空字符串 |
| `conversation.pending_file_expires_at` | 固定值 `0` |
| `conversation.awaiting` | 固定值 `none` |
| `conversation.last_action` | 固定值 `FILE_FILL` |
| `conversation.last_task_ref` | `Start / file_task_ref` |

### 5.3 `SET_FILE_INPUT`（文件先到）

| 目标变量 | 值 |
|---|---|
| `conversation.pending_file_request` | 固定值 空字符串 |
| `conversation.pending_file_task_ref` | `Start / file_task_ref` |
| `conversation.pending_file_expires_at` | `CLOCK / next_expires` |
| `conversation.awaiting` | 固定值 `file_input` |
| `conversation.last_action` | 固定值 `ASK` |

### 5.4 `CLEAR_CANCEL`（用户放弃）

| 目标变量 | 值 |
|---|---|
| `conversation.pending_file_request` | 固定值 空字符串 |
| `conversation.pending_file_task_ref` | 固定值 空字符串 |
| `conversation.pending_file_expires_at` | 固定值 `0` |
| `conversation.awaiting` | 固定值 `none` |
| `conversation.last_action` | 固定值 `SEND` |

### 5.5 `KEEP_FILE_INPUT_TEXT`（文本补充仍缺字段）

| 目标变量 | 值 |
|---|---|
| `conversation.awaiting` | 固定值 `file_input` |
| `conversation.pending_file_expires_at` | `CLOCK / next_expires` |
| `conversation.last_action` | 固定值 `ASK` |

> 不要动 `pending_file_request` 和 `pending_file_task_ref`。

### 5.6 `CLEAR_AFTER_FILL_TEXT`（文本补充填完）

| 目标变量 | 值 |
|---|---|
| `conversation.last_task_ref` | `conversation.pending_file_task_ref` |
| `conversation.pending_file_request` | 固定值 空字符串 |
| `conversation.pending_file_task_ref` | 固定值 空字符串 |
| `conversation.pending_file_expires_at` | 固定值 `0` |
| `conversation.awaiting` | 固定值 `none` |
| `conversation.last_action` | 固定值 `FILE_FILL` |

> `last_task_ref` 一定放在第一行，先取值再清空 `pending_file_task_ref`。

### 5.7 `SET_FILE_REQUEST`（用户只提要求、还没发文件）

| 目标变量 | 值 |
|---|---|
| `conversation.pending_file_request` | `Start / query` |
| `conversation.pending_file_task_ref` | 固定值 空字符串 |
| `conversation.pending_file_expires_at` | `CLOCK / next_expires` |
| `conversation.awaiting` | 固定值 `file_request` |
| `conversation.last_action` | 固定值 `WAIT_FILE` |

---

## 6. 代码节点（直接粘贴）

### 6.1 `CLOCK`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `current_time` | String | `Start / current_time` |

输出：

| 变量 | 类型 |
|---|---|
| `now_epoch` | Number |
| `next_expires` | Number |

```python
import datetime

def main(current_time: str) -> dict:
    # 返回 float 即可：会话变量 pending_file_expires_at 的默认值是 0.1，
    # Dify 据此判成 number；默认值写成 0 会变成 integer，所有请求直接 400
    try:
        now = datetime.datetime.fromisoformat(current_time).timestamp()
    except Exception:
        now = datetime.datetime.now().timestamp()
    return {
        "now_epoch": now,
        "next_expires": now + 900,
    }
```

> **`pending_file_expires_at` 的默认值必须是小数（`0.1`）。** Dify 的 Number 变量在 UI 上
> 不分整数/小数，后端按**默认值**推断：默认 `0` → 存成 `value_type: integer`，而会话变量工厂
> 不认 `integer`，初始化变量池时就抛 `not supported value type integer`，`/chat-messages`
> 直接 400 `invalid_param`——**任何消息都回不了**，且在执行任何节点之前就失败。
> 默认 `0.1` → `value_type: number`，一切正常。导出 DSL 里可直接核对这一行。

### 6.2 `CHECK_REQUEST`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `pending_file_request` | String | 会话变量 |
| `pending_request_hint` | String | `Start / pending_request_hint` |
| `query` | String | `Start / query` |
| `pending_file_expires_at` | Number | 会话变量 |
| `now_epoch` | Number | `CLOCK / now_epoch` |

输出：

| 变量 | 类型 |
|---|---|
| `has_request` | String |
| `request` | String |

```python
def main(pending_file_request: str, pending_request_hint: str,
         query: str, pending_file_expires_at: float,
         now_epoch: float) -> dict:
    pending = (pending_file_request or "").strip()
    hint = (pending_request_hint or "").strip()
    inline = (query or "").strip()

    try:
        expires = float(pending_file_expires_at or 0)
    except (TypeError, ValueError):
        expires = 0

    valid = bool(pending or hint) and (expires == 0 or expires > float(now_epoch or 0))
    base = (pending or hint) if valid else ""
    parts = [p for p in (base, inline) if p]
    request = "\n".join(parts).strip()

    return {
        "has_request": "true" if request else "false",
        "request": request,
    }
```

### 6.3 `CHECK_MISSING` / `CHECK_MISSING_TEXT`

两个节点代码完全一样，只是输入来源不同。

输入（文件分支）：

| 变量 | 类型 | 来源 |
|---|---|---|
| `task_ref` | String | `Start / file_task_ref` |
| `fills` | Array[Object] | `FILE_FILL / fills` |
| `missing` | Array[String] | `FILE_FILL / missing` |
| `confidence` | Number | `FILE_FILL / confidence` |
| `needs_confirm` | Boolean | `FILE_FILL / needs_confirm` |
| `content` | String | 固定值 空字符串 |

输入（文本分支）：

| 变量 | 类型 | 来源 |
|---|---|---|
| `task_ref` | String | 会话变量 `pending_file_task_ref` |
| `fills` | Array[Object] | `FILE_FILL_TEXT / fills` |
| `missing` | Array[String] | `FILE_FILL_TEXT / missing` |
| `confidence` | Number | `FILE_FILL_TEXT / confidence` |
| `needs_confirm` | Boolean | `FILE_FILL_TEXT / needs_confirm` |
| `content` | String | 固定值 空字符串 |

输出：

| 变量 | 类型 |
|---|---|
| `decision` | String |
| `has_missing` | String |

```python
import json


def load(value, default):
    if isinstance(value, (list, dict)):
        return value
    try:
        return json.loads(value) if value else default
    except Exception:
        return default


def main(task_ref: str, fills, missing, confidence,
         needs_confirm, content: str) -> dict:
    fill_list = load(fills, [])
    missing_list = load(missing, [])
    if not isinstance(fill_list, list):
        fill_list = []
    if not isinstance(missing_list, list):
        missing_list = []

    try:
        conf = float(confidence)
    except (TypeError, ValueError):
        conf = 0.0

    if isinstance(needs_confirm, str):
        confirm = needs_confirm.lower() == "true"
    else:
        confirm = bool(needs_confirm)

    if missing_list:
        decision = {
            "version": 1,
            "action": "ASK",
            "content": (content or "").strip()
                       or ("还差这些信息：" + "、".join(str(x) for x in missing_list)),
            "ask": {
                "missing": [str(x) for x in missing_list],
                "task_ref": task_ref or "",
            },
        }
    else:
        decision = {
            "version": 1,
            "action": "FILE_FILL",
            "content": (content or "").strip() or "填好了。",
            "file": {
                "task_ref": task_ref or "",
                "fills": fill_list,
                "missing": [],
                "confidence": conf,
                "needs_confirm": confirm,
            },
        }

    return {
        "decision": json.dumps(decision, ensure_ascii=False),
        "has_missing": "true" if missing_list else "false",
    }
```

> 如果 Tool 节点的输出类型只能是 String，把 `fills`/`missing` 的输入类型改成 String，
> 上面的 `load()` 已经兼容 JSON 字符串。

### 6.4 `ASK_NO_REQUEST`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `task_ref` | String | `Start / file_task_ref` |

输出：`decision` String。

```python
import json

def main(task_ref: str) -> dict:
    decision = {
        "version": 1,
        "action": "ASK",
        "content": "收到文件了，要我填什么？",
        "ask": {"missing": [], "task_ref": task_ref or ""},
    }
    return {"decision": json.dumps(decision, ensure_ascii=False)}
```

### 6.5 `BUILD_REQUEST`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `query` | String | `Start / query` |
| `hint` | String | `Start / pending_request_hint` |
| `pending` | String | 会话变量 `pending_file_request` |

输出：`request` String。

```python
def main(query: str, hint: str, pending: str) -> dict:
    base = (pending or hint or "").strip()
    query = (query or "").strip()
    if base and query:
        return {"request": base + "\n补充：" + query}
    return {"request": query or base}
```

### 6.6 `CANCEL_REPLY`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `query` | String | `Start / query` |

输出：`decision` String。

```python
import json

def main(query: str) -> dict:
    decision = {
        "version": 1,
        "action": "SEND",
        "content": "好的，这份表先不填了。",
    }
    return {"decision": json.dumps(decision, ensure_ascii=False)}
```

### 6.7 `WAIT_FILE_REPLY`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `query` | String | `Start / query` |

输出：`decision` String。

```python
import json

def main(query: str) -> dict:
    decision = {
        "version": 1,
        "action": "WAIT_FILE",
        "content": "发过来吧，我按你说的填。",
        "wait": {"request": (query or "").strip(), "ttl_seconds": 900},
    }
    return {"decision": json.dumps(decision, ensure_ascii=False)}
```

### 6.8 `CLEAN_DECISION`

输入：

| 变量 | 类型 | 来源 |
|---|---|---|
| `text` | String | `CHAT_DECISION / text` |

输出：`decision` String。

```python
import json

ALLOWED = {"SEND", "DRAFT", "SILENT", "MEMO", "FACT", "MEMO_CLOSE"}
CATEGORIES = {"学业", "社交", "健康", "财务", "日常", "其他"}


def _clean_memo(memo):
    """模型偶尔写错枚举/越界分数；这里兜底，否则 Spring 侧 400 会把整条备忘丢掉。"""
    if not isinstance(memo, dict):
        memo = {}
    out = {}
    title = str(memo.get("title") or "").strip()
    content = str(memo.get("content") or "").strip()
    due = str(memo.get("dueTime") or "").strip()
    out["title"] = title or (content[:40] or "备忘")
    if content:
        out["content"] = content
    if due:
        out["dueTime"] = due
    try:
        importance = int(memo.get("importance"))
    except (TypeError, ValueError):
        importance = 3
    out["importance"] = max(1, min(5, importance))
    category = str(memo.get("category") or "").strip()
    out["category"] = category if category in CATEGORIES else "其他"
    return out


def main(text: str) -> dict:
    raw = (text or "").strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        if raw.startswith("json"):
            raw = raw[4:].lstrip()

    try:
        data = json.loads(raw)
    except Exception:
        data = None

    if not isinstance(data, dict) or data.get("action") not in ALLOWED:
        decision = {"version": 1, "action": "SILENT"}
    else:
        data.setdefault("version", 1)
        if data.get("action") == "MEMO":
            data["memo"] = _clean_memo(data.get("memo"))
        decision = data

    return {"decision": json.dumps(decision, ensure_ascii=False)}
```

> 为什么要这段兜底：`category` 在 Spring 侧是强校验枚举，模型按「生活/工作」输出会直接 400，
> 插件此时既拿不到备忘录、也发不出确认语。Dify 侧修正 + 插件侧 `sanitize_memo` 双重保险。

---

## 7. 提示词

### 7.1 `TEXT_INTENT`（问题分类器）

输入变量：`Start / query`。

类别 1：

- 名称：`file_request`
- 描述：
  ```
  用户在提出填写/修改/整理文件的要求，且当前没有发文件。
  包括：“我等下发个表，你帮我填姓名学号”、“这个 Excel 帮我补一下专业”、
  “能不能帮我填表”、“发你一个 Word，按模板填一下”。
  只是问“能不能填表”也算。
  ```

类别 2：

- 名称：`other`
- 描述：
  ```
  普通聊天、备忘、事实、纠错、对已发文件的补充信息、其他。
  已经在补充字段（例如“姓名张三”）不算 file_request。
  ```

### 7.2 `CHAT_DECISION`（LLM）

- 模型：选一个中文对话稳定的模型。
- Memory：**开启**，窗口 10。
- 输出变量：`text`（默认）。

**System Prompt：**

```text
你是 QQ 消息助手的大脑。你只输出一个 JSON 对象，不输出代码块、不输出解释。

你要在以下动作中选择一个：
- SEND：直接回复用户
- DRAFT：生成需要人工确认后才能发出的回复草稿
- SILENT：不需要回复（群聊闲聊、误触发、纯表情）
- MEMO：创建备忘录
- FACT：记录用户稳定事实
- MEMO_CLOSE：用户表示某件备忘已完成/已取消

输出格式：
{"version":1,"action":"...","content":"...","draft":{...},"memo":{...},"fact":{...},"close":{...}}

规则：
1. 普通聊天 → SEND，content 像真人在 QQ 上说话：简短、自然、1–3 句。
   群聊不要用 Markdown、不要长篇大论。
2. 正式回复（给老师/领导/客户）→ DRAFT：
   {"type":"REPLY","title":"...","payload":{"content":"..."}}
   content 可以留空，让用户去草稿中心确认。
3. 需要记住的事 → MEMO。不只是“提醒我”：凡是带时间/截止/邀约/需要后续行动的通知，
   用户自己说的事、别人转达的事都算，例如“周五交实验报告”“明天下午开班会”“下周三面试”。
   {"title":"...","content":"...","importance":1-5,
    "category":"学业|社交|健康|财务|日常|其他","dueTime":"YYYY-MM-DDTHH:mm:ss"}
   - importance 必须按下面标准打分，不要一律给 3：
     5=有明确时间且不做后果严重（考试/面试/答辩/截止缴费）
     4=有明确时间且重要但可调整（重要邀约、班委通知）
     3=普通邀约或一般截止（班会、聚餐）
     2=低价值待办，可做可不做（帮带东西）
     1=纯信息无行动项（仅存档）
   - category 只能取：学业、社交、健康、财务、日常、其他（不要输出“生活/工作”）
   - dueTime 必须结合「当前时间」把“明天下午三点”这类相对时间换算成
     YYYY-MM-DDTHH:mm:ss；**确实没有时间就省略整个 dueTime 字段**，不要编造日期。
     没有 dueTime 就不会产生提醒。
   - content 只写一句确认语，例如“记下了，周五前提醒你。”
   - 纯闲聊、没有时间也没有后续行动的话，不要输出 MEMO。
4. 用户陈述稳定个人信息（姓名、学号、专业、常用地址、偏好）→ FACT：
   {"factKey":"学号","factValue":"2023001","confidence":0.0-1.0}
   只有用户明确说出的才记；推断的 confidence < 0.85。
5. 用户说“那个做完了/取消了” → MEMO_CLOSE：
   {"memo_id":123,"status":"DONE"} 或 {"status":"CANCELLED"}
   不知道 memo_id 就省略。
6. 文件填写由专门流程处理，你不要输出 WAIT_FILE/ASK/FILE_FILL。
7. 不确定用户是否在跟你说话 → SILENT。
8. 不要编造事实、日期、承诺；不知道就说不知道。
9. 没有的字段直接省略，不要输出 null。

示例：
用户：明天下午三点提醒我交实验报告
→ {"version":1,"action":"MEMO","content":"记下了，明天下午三点提醒你。",
   "memo":{"title":"交实验报告","content":"明天下午三点前提交",
           "importance":4,"category":"学业","dueTime":"2026-10-27T15:00:00"}}

用户：我叫张三，学号2023001
→ {"version":1,"action":"FACT","content":"",
   "fact":{"factKey":"姓名","factValue":"张三","confidence":0.98}}

用户：帮我写一段给导师的请假说明
→ {"version":1,"action":"DRAFT","content":"",
   "draft":{"type":"REPLY","title":"请假说明",
            "payload":{"content":"老师您好，我因……"}}}

用户：哈哈哈
→ {"version":1,"action":"SILENT"}
```

**User Prompt：**

```text
当前时间：{{#Start.current_time#}}
会话类型：{{#Start.chat_type#}}
用户画像事实：{{#Start.facts#}}
风格要求：{{#Start.style_prompt#}}
用户消息：{{#Start.query#}}
```

> 插入变量时用 Dify 的变量选择器，不要手打 `{{#...#}}`，否则节点引用可能不生效。

---

## 8. 文件工作流（Workflow-as-Tool）

单独建一个 **Workflow** 应用（不是 Chatflow），发布后在 Chatflow 的 Tool 节点里选它。

### 8.1 Start

| 变量 | 类型 |
|---|---|
| `structure` | String |
| `facts` | String |
| `request` | String |

### 8.2 LLM 节点

**System Prompt：**

```text
你是表单填写器。根据 structure、facts 和 request，决定每个空该填什么。

只输出一个 JSON 对象，不要 Markdown、不要解释：
{"fills":[],"missing":[],"confidence":0.0,"needs_confirm":false}

规则：
1. 只填 request 明确要求、且 facts 或 structure 能确定的字段；不要猜测。
2. missing 列出 request 要求但 facts 中缺失的字段名（用表头/标签原文）。
3. xlsx 的 fills 格式：{"sheet":"Sheet1","ref":"B2","value":"张三"}
   docx 的 paragraph 格式：{"kind":"paragraph","index":3,"value":"张三"}
   docx 的 table 格式：{"kind":"table","table":0,"row":1,"col":1,"value":"2023001"}
4. ref/index/row/col 必须来自 structure 中真实存在的坐标；拿不准就不填。
5. confidence 取 0–1；任何字段靠推断时 ≤0.6，并置 needs_confirm=true。
6. 不要改动无关单元格，不要新增行/列，不要改格式。
7. request 为空、或与文件无关时，返回：
   {"fills":[],"missing":[],"confidence":0,"needs_confirm":true}
8. 用户要求“保留原格式/只填空”时，只输出 fills，不要描述文件内容。
```

**User Prompt：**

```text
structure:
{{#Start.structure#}}

facts:
{{#Start.facts#}}

request:
{{#Start.request#}}
```

### 8.3 Code 节点 `PARSE_FILL`

输入：`text`（String，来自 LLM）。
输出（类型要选对）：

| 变量 | 类型 |
|---|---|
| `fills` | Array[Object] |
| `missing` | Array[String] |
| `confidence` | Number |
| `needs_confirm` | Boolean |

```python
import json

def main(text: str) -> dict:
    raw = (text or "").strip()
    if raw.startswith("```"):
        raw = raw.strip("`")
        if raw.startswith("json"):
            raw = raw[4:].lstrip()

    try:
        data = json.loads(raw)
    except Exception:
        data = {}

    fills = data.get("fills") or []
    missing = data.get("missing") or []
    if not isinstance(fills, list):
        fills = []
    if not isinstance(missing, list):
        missing = []

    try:
        confidence = float(data.get("confidence") or 0)
    except (TypeError, ValueError):
        confidence = 0.0

    needs_confirm = data.get("needs_confirm")
    if isinstance(needs_confirm, str):
        needs_confirm = needs_confirm.lower() == "true"
    else:
        needs_confirm = bool(needs_confirm)

    return {
        "fills": fills,
        "missing": [str(x) for x in missing],
        "confidence": confidence,
        "needs_confirm": needs_confirm,
    }
```

### 8.4 End 节点

输出：`fills`、`missing`、`confidence`、`needs_confirm`，类型与 8.3 一致。

> 如果 Dify 的 Workflow-as-Tool 不支持 Array[Object] 输出，把 End 输出改成 String
> （用 `json.dumps` 包一层），并把 Chatflow 里 `CHECK_MISSING` 的 `fills`/`missing`
> 输入类型改成 String；代码里的 `load()` 已兼容。

---

## 9. 你截图里已有节点怎么处理

| 你现在的节点 | 处理 |
|---|---|
| `用户输入` | 保留；按 §1.1 补齐 14 个变量 |
| `CHECK_ANY_TYPE` | 改名为 `IS_FILE`；条件按 §4.1 |
| `FILE_PENDING` | **删掉/改造**：换成 `CHECK_REQUEST`（代码）+ `HAS_REQUEST`（条件）。它不能只判断“有没有 pending”，还要合并文件消息自带的文本 |
| `意图分类2` | 改名为 `TEXT_INTENT`；类别改成 `file_request` / `other`，描述用 §7.1 |
| `FILE_FILL` | 保留为文件分支的 Tool；再复制一个给文本分支，命名 `FILE_FILL_TEXT` |
| `CHECK_MISSING` | 保留；按 §6.3 补齐输入类型和 `content` 固定空串 |
| `BUILD_WAIT_FILE` | 可保留，改名为 `WAIT_FILE_REPLY`；代码用 §6.7，输出变量叫 `decision` |

然后按 §2、§3 补齐缺少的节点和连线。

---

## 10. 搭建顺序（不要跳）

1. 建 14 个 Start 变量、6 个会话变量。
2. 建 `CLOCK`、`IS_FILE`，连 Start → CLOCK → IS_FILE。
3. 建文件分支：`CHECK_REQUEST` → `HAS_REQUEST` → `FILE_FILL` → `CHECK_MISSING` → `HAS_MISSING` → `KEEP/CLEAR`；再加 `ASK_NO_REQUEST` → `SET_FILE_INPUT`。
4. 建文本分支：`IS_AWAITING` → `IS_CANCEL` / `BUILD_REQUEST` / `FILE_FILL_TEXT` / `CHECK_MISSING_TEXT` / `HAS_MISSING_TEXT` / `KEEP_TEXT` / `CLEAR_TEXT`。
5. 建 `TEXT_INTENT` → `WAIT_FILE_REPLY` → `SET_FILE_REQUEST`；`TEXT_INTENT` → `CHAT_DECISION` → `CLEAN_DECISION`。
6. 建 `MERGE_DECISION`，把所有末端接进来；再接 `ANSWER`。
7. 单独建文件 Workflow，发布；回到 Chatflow 把两个 Tool 节点选成它。
8. 在「调试与预览」按 §11 跑一遍。

---

## 11. 手工验收清单

| # | 输入（模拟 Start 变量） | 期望 |
|---|---|---|
| 1 | `event_type=text`, `query=我要发个文件，把姓名填上` | `WAIT_FILE`，`pending_file_request` 写入 |
| 2 | 同会话 `event_type=file`, `structure=...`, `file_task_ref=ft_1` | 直接 `FILE_FILL` 或 `ASK`，不再问“填什么” |
| 3 | 新会话 `event_type=file`, `structure=...`, `file_task_ref=ft_2` | `ASK`“要我填什么”，`awaiting=file_input` |
| 4 | 同会话 `event_type=text`, `query=姓名张三`, `structure=...` | `FILE_FILL` |
| 5 | 只给姓名，要求填姓名+学号 | `ASK` 缺学号，`pending` 保留 |
| 6 | 补学号 | 直接 `FILE_FILL`，不再问姓名 |
| 7 | `query=算了，不填了` | `SEND`“不填了”，状态清空 |
| 8 | 普通聊天 | `SEND`，不破坏 pending |
| 9 | `query=记一下明天交报告` | `MEMO_OP` + `op=memo.create` → `OP_HTTP` 建备忘 |
| 10 | `query=我叫张三` | `FACT` |
| 11 | `query=那个做完了`（`open_memos` 里有 1 条） | `MEMO_OP` + `op=memo.close`，`memoId` 取自列表 |
| 11b | `query=班会改到明天下午四点`（`open_memos` 里有「班会」） | `MEMO_OP` + `op=memo.update`，同一条备忘被改，旧提醒 `CANCELLED` |
| 11c | `query=那个会改一下`（`open_memos` 里有 3 条都像） | `SEND` 反问，**库里没有任何写入** |
| 12 | 文件 + 同条文本 `query=姓名张三`，无 pending | `CHECK_REQUEST.request` 非空 → 直接 `FILE_FILL` |

---

## 12. 常见坑

| 现象 | 原因 | 处理 |
|---|---|---|
| `/chat-messages` 返回 400 `not supported value type integer`（**任何消息**都失败，且秒回） | 会话变量 `pending_file_expires_at` 默认值写成了 `0`，Dify 把它在 DSL 里存成 `value_type: integer`，初始化变量池即失败，节点一个都没跑 | 把默认值改成 `0.1`（→ `value_type: number`），导出 DSL 核对第 42 行；UI 改不动就改 DSL 后重新导入（§6.1） |
| 节点报“变量类型不匹配” | Code/Tool 输入类型和上游输出不一致 | 按 §6/§8 的表格逐个核对 |
| Code 节点报 `IndentationError: unexpected indent`（`File "<fd3>", line 1`） | 代码首行前面多了空格（从导出的 YAML 或缩进的 Markdown 里复制粘贴常见） | 打开该节点，Ctrl+A 全删后重新粘贴，**第一行必须顶格**；报错行号 1 就是线索 |
| `CHECK_REQUEST` 永远 false | `now_epoch` 没接 `CLOCK`，或 `pending` 变量选成了 Start 字段 | 重新绑定会话变量 |
| 文件分支直接 ASK | `query` 没接 Start.query，导致同条文本被丢掉 | 按 §6.2 检查输入 |
| 填完状态没清 | `CLEAR_*` 没接上，或接在 `MERGE` 后面 | 变量赋值必须在 `MERGE` 之前 |
| `TEXT_INTENT` 把“姓名张三”判成 file_request | 类别描述没写“补充字段不算” | 用 §7.1 原文 |
| Answer 输出带 ```json | LLM 没听话 | `CLEAN_DECISION` 已兜底；仍建议提示词明确“不要代码块” |
| 多分支接不进一个 Answer | 没用变量聚合器 | 用 `MERGE_DECISION`，或每分支各接一个 Answer |
| 文件先到后文本补充，Tool 收到空 structure | 插件没在后续文本轮带 structure | 插件 0.2.23+ 已修：文本轮会从本地 `task.json` 带 `structure`；若仍为空，看 `_debug.log` 里 `event=text file=-` 是否指向任务丢失 |

---

## 13. 备忘写操作分支（`MEMO_OP` → `OP_HTTP`）

> 本节是 Phase 6 引入的：Dify 不再只“提建议”，而是**自己调后端接口**去改/取消备忘。
> 边界不变：Dify 决定「改哪条、改什么」，校验/归属/状态机/提醒重算/审计/幂等仍在 Spring。
> 契约见 `agent_contract.md` §3。

### 13.1 为什么必须走 HTTP 节点

旧路径下 `MEMO` 永远 `POST /api/memos`：早上「明天下午 2 点 S109 开班会」建一条，下午
「班会取消 / 改到 S111 / 改到后天 4 点」**只会再建一条**，旧的那条到点照样提醒。
原因是 Dify 的输出里没有「改哪一条」的表达，输入里也没有「你还挂着哪些备忘」。

现在两样都补上了：`open_memos`（§1.1）给模型认路的依据，`memo.update` / `memo.close`
给它表达的手段。

### 13.2 `OP_HTTP` 节点怎么填

| 字段 | 值 |
|---|---|
| 方法 | `POST` |
| 授权 | **无**（`no-auth`）。token 走下面的 Headers，不填在授权里 |
| URL | `{{#env.SPRING_BASE_URL#}}/api/agent/ops` |
| Headers | `X-Agent-Token: {{#env.AGENT_OPS_TOKEN#}}`（自由文本一行，**别写成字面量 token**） |
| Body 类型 | `JSON` |
| Body | 只允许 **一个** 条目，值为 `{{#clean_decision.op_body#}}` |
| 超时 | 连接 5s / 读 20s / 写 20s |
| 错误策略 | **失败时走失败分支**（`fail-branch`） |
| 重试 | **关**（去重路径实测通过前不要开） |

> Dify 的 JSON body 硬限制只能有一个条目，且不能逐字段绑变量——所以整包 body 由
> `CLEAN_DECISION` 用 `json.dumps` 序列化成 `op_body` 一个字符串塞进去，
> 顺带避免用户文本里的引号/换行把 JSON 炸开。

> **授权字段是必填的**（踩过）：`HttpRequestNodeData` 是严格 pydantic 模型，DSL 里少了
> `authorization` 不会在导入时报错，而是**每次请求**都 400——
> `{"code":"invalid_param","message":"1 validation error for HttpRequestNodeData\n authorization\n Field required"}`，
> 表现为 QQ 里完全没回复。UI 上把该节点的「授权」选成「无」即可；写入 DSL 时是
> `authorization: {type: no-auth, config: null}`。`tests/test_chatflow_dsl.py` 有断言守着。

### 13.3 三个代码节点的契约

**`CLEAN_DECISION`**（唯一解析器）：输入 `text / sender_id / chat_id / event_id`，输出
`decision / is_op / op_body`。

- `action == MEMO_OP` → 校验 `op ∈ {memo.create, memo.update, memo.close}`，
  拼出扁平字符串 body（`senderId`/`chatId`/`eventId` **只从 Start 取**），
  返回 `is_op="true"`、`decision=""`。
- `event_id` 为空 → 不写，返回一句「刚才没办成，麻烦再说一遍？」的 `SEND`
  （没有幂等键就写，重试会把改期执行两遍，用户察觉不到）。
- 非法 `op` → `SILENT`，不转发。
- 其他 action → 沿用原逻辑，`is_op="false"`。

**`READ_OP_RESULT`**（成功分支）：输入 `status_code / body`，输出 `decision`。

- 必须是**全函数**：任何输入都返回一条 `SEND`，绝不抛异常（抛了工作流断，用户收不到任何回复）。
- 非 2xx / body 不是 JSON → 「刚才没办成，后台没连上，等会儿再说一次？」。
- `code != 0` → **把 Spring 给的 `message` 原样转达**（“备忘录不存在”“没有要修改的内容”）。
  不要改写成“没连上”，否则用户会以为重试就能好。
- `code == 0` → `message`（“改好了：班会 · 10-31 16:00 · 教学楼 S111”）；
  `replayed=true` 时追加「（没有重复执行）」。
- 不回显 `body`：后端细节不该出现在 QQ 里。

**`OP_FAILED`**（失败分支）：**故意不接 `error_message`**，输出固定道歉。
代理/内网的原始报错既看不懂也不该外泄；排查线索在 Dify 运行日志的 `OP_HTTP` 节点里。

### 13.4 `MERGE_DECISION` 的顺序是有语义的

`CLEAN_DECISION` 在 `IS_OP` 之前执行，op 分支时它输出的是**空串**。
变量聚合器取「第一个非空值」，所以顺序必须是：

```
… wait_file_reply → READ_OP_RESULT → OP_FAILED → CLEAN_DECISION
```

顺序写错的表现特别隐蔽：**写库成功了，QQ 却回 SILENT**。

### 13.5 上线前必须做的两件事

1. **SSRF 预检**：`OP_HTTP` 走 Dify 的 `ssrf_proxy`，默认拦私网地址，**不配就一定失败**。
   先在 Dify 容器里 `curl -s -o /dev/null -w '%{http_code}' http://host.docker.internal:8080/api/agent/ops`
   （应为 401/503，不是连接失败）。被拦时的报错长这样：

   ```
   Access to 'http://host.docker.internal:8080/api/agent/ops' was blocked by SSRF protection
   (e.g. SSRF_PROXY_ALLOW_PRIVATE_IPS=172.21.0.0/16 to allow 172.21.0.0/16)
   ```

   修法：Dify 的 `docker/.env` 里把** Spring 解析到的私网网段**加进允许列表（是 CIDR，不是 `true`）：

   ```bash
   # 先问 api 容器：host.docker.internal 解析成什么
   docker compose -f docker/docker-compose.yaml exec api \
     python -c "import socket;print(socket.gethostbyname('host.docker.internal'))"
   # 再把这个地址（或它所在 /16）写进 .env
   SSRF_PROXY_ALLOW_PRIVATE_IPS=172.21.0.0/16
   docker compose -f docker/docker-compose.yaml up -d ssrf_proxy api worker
   ```

   允许私网等于给 Dify 的 HTTP 节点开了内网出口，只在家用/实验环境这么做；
   公网部署请改走一个只暴露 `/api/agent/ops` 的反向代理域名。
   同时确认 Windows 防火墙放行 8080。
2. **重填 `AGENT_OPS_TOKEN`**：secret 类型不随 DSL 导出，导入后值一定是空的。
   留空 ⇒ `/api/agent/*` 返回 503（fail closed，不会误开无鉴权写入口）。

### 13.6 验收（每条都要留证据）

| # | 操作 | 期望 |
|---|---|---|
| 1 | 「明天下午 2 点 S109 开班会」 | 备忘生成，`open_memos` 能看到它 |
| 2 | 「班会地点改成 S111」 | **同一条**备忘 content 更新；`GET /api/memos/{id}/history` 有 `AGENT/UPDATE`；旧值可查 |
| 3 | 「班会改到后天下午 4 点」 | 旧 `memo_reminder` 变 `CANCELLED`，新提醒按策略重建 |
| 4 | 「班会取消了」 | `status=CANCELLED`，PENDING 提醒全取消，不再推 QQ |
| 5 | 「那个会改一下」（多条候选） | 机器人反问，**库里没有任何写入** |
| 6 | 停掉后端再改期 | 回“没办成”，不新增重复备忘；Dify 运行日志里 `OP_HTTP` 是失败分支 |
| 7 | 同一条消息被重投 | `memo_change_log` 只有一行、`replayed=true`，不产生第二条备忘 |

离线可跑的部分（聚合器顺序、三个代码节点的兜底）已由
`QQMessageAssistantPython/qq_assistant_plugin/tests/test_chatflow_dsl.py` 覆盖，
改图后必须先过它。
