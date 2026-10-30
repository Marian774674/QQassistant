# 「记忆与整理」Dify Workflow 契约

对应文件：`design/QQAssistant-memory-workflow.yml`（Workflow 模式，直接导入 Dify 即可）。

> **为什么不改 `QQAssistant (2).yml` 而是新建同目录文件**：Dify 一个 DSL 文件只描述一个应用，
> 而 `QQAssistant (2).yml` 是 `mode: advanced-chat` 的对话应用（走 `/v1/chat-messages`）。
> 整理任务走的是 Workflow 接口 `/v1/workflows/run`，两者不可能装进同一个 YAML。
> 新文件是**以那份导出为模板**写的：同 DSL 版本（0.7.0）、同 tongyi 插件依赖、同模型
> `qwen3.8-flash`，只把图换成 Workflow。原文件保持原样，两个应用各自导入。

## 1. 调用契约（Spring 侧：`DifyWorkflowClient`）

```
POST {dify.workflow.base-url}/workflows/run
Authorization: Bearer {dify.workflow.api-key}

{
  "inputs": {
    "task": "digest",
    "chat_name": "三班群",
    "messages": "12|[@我]|班主任|14:03|明天下午三点开班会\n13||小明|14:05|收到",
    "profile": "小明,明哥",
    "focus": "只关心实习和考试相关"
  },
  "response_mode": "blocking",
  "user": "spring"
}
```

响应取 `data.outputs.items`（**字符串**，内容是一个 JSON 数组）：

```json
[{"summary":"班主任提醒明天下午三点开班会","importance":4,"keywords":"班会,通知","mentions_me":false,"source_msg_ids":[12]}]
```

- `source_msg_ids` 必须是输入里出现过的 `message_log.id`。Spring 会逐项校验，**任何一项证据不在本次窗口内，整项丢弃**（不是截断、不是修补）。
- `importance` Spring 会夹到 1–5；`mentions_me` 按布尔解析。
- `items` 解析失败时 Spring 记日志并**不删除**该时段旧数据；只有「解析成功但没有条目」才会清空该时段。

## 2. `messages` 行格式

```
id|[@我]|昵称|HH:mm|正文
```

- 第二列是 `[@我]` 表示这条点名了用户（由 Spring 按 `app_setting.owner_aliases` 逗号分隔做**字符串包含**判断，确定性、不交给模型猜）。
- 正文压掉换行、截 200 字；一次最多送最近 **200** 条。

## 3. 图结构

```
start ──► is_digest ──true──► digest_llm(LLM) ──► parse_digest(code) ──► end_digest
                  └──false──► empty_items(code) ──► end_other
```

- `is_digest`：`task is digest`。非 digest 分支本期只返回 `items: "[]"`，留给后续「历史查询」「事实抽取」。
- `parse_digest`：剥 Markdown 围栏 → `json.loads` → 丢掉没有 `summary` 或没有 `source_msg_ids` 的条目 → 重新 `dumps`。
  它只做「形状」上的清洗，**不做事实校验**（那是 Spring 的事）。

## 4. 提示词（与 YAML 内一致）

System：

```
你是群聊记录整理器。用户（下称「我」）不在线时群里发生了很多事，你要把值得他知道的挑出来，每条一句话说清「谁说了什么」。

messages 每行一条消息，格式是：
id|[@我]|昵称|时间|内容
第二列是 [@我] 表示这条消息点名了我。

只输出一个 JSON 数组，不要 Markdown 代码块、不要任何解释：
[{"summary":"班主任提醒明天下午三点开班会","importance":4,"keywords":"班会,通知","mentions_me":false,"source_msg_ids":[12]}]

规则：
1. summary 不超过 60 字，必须写明是谁说的、说了什么；同一个人围绕同一件事连发的几条合并成一条，source_msg_ids 列出全部证据。
2. importance 取 1–5：5=要我立刻处理（时间地点变更、截止时间、点名要我回复）；4=与我直接相关或必须知道；3=一般通知；2=闲聊但有信息；1=可忽略。
3. keywords 最多 3 个，逗号分隔，不要带 #。
4. mentions_me 为 true 当且仅当这条的证据行里有 [@我]。
5. source_msg_ids 只能取输入每行开头的 id（整数数组），不许编造、不许用行号代替；拿不出证据的条目不要输出。
6. 带 [@我] 的行必须各自产出一项（同一件事可以合并），mentions_me 为 true，且 importance 不低于 4。
7. 没有值得说的内容就输出 []。「哈哈哈」「收到」「+1」这类口水话不要单独成条。
8. 不许出现输入里没有的人名、时间、事项；不确定就在 summary 里写「有人提到」，并降低 importance。
9. focus 非空时按 focus 关心的点取舍和排序；focus 为空时按「有时间地点的通知 > 要我做的事 > 提到我」取舍。
```

User：

```
群：{{#start.chat_name#}}

我的身份与别名：{{#start.profile#}}

我关注：{{#start.focus#}}

messages:
{{#start.messages#}}
```

## 5. 离线体检

`QQMessageAssistantPython/qq_assistant_plugin/tests/test_memory_workflow_dsl.py` 会在每次跑 pytest 时检查：
应用模式与名称、六条边的接线、Start 变量名与 Spring 实际发送的键完全一致、
提示词里仍钉着 `[@我]` 与 `source_msg_ids` 规则、两个代码节点能真的跑通（含脏输出兜底）、
各结束节点的输出变量名互不重复（重名会被 Dify 的「发布」检查清单挡下来）。

## 6. 导入之后还要做的事（人工）

1. 执行增量迁移 `QQMessageAssistantJava/src/main/resources/db/migrations/2026-10-30-phase7b-digest-window-fix.sql`
   （去掉 `chat_digest_item.uk_chat_period`、`source_msg_ids` 放宽到 TEXT、`message_log` 补 `(chat_id, created_at)` 索引）。
2. 控制台导入 `design/QQAssistant-memory-workflow.yml`，确认依赖插件 `langgenius/tongyi` 已装、模型 `qwen3.8-flash` 可用。
3. 「访问 API」页拿 Key，配到后端环境变量：`DIFY_WORKFLOW_API_KEY`、`DIFY_WORKFLOW_BASE_URL`（形如 `http://<dify>/v1`）。
4. Dify 里 Run 一次，`task=digest` 贴几行假消息，确认 `items` 是合法 JSON 且 `source_msg_ids` 指向真实 id。
5. 打开整理调度：`app_setting.digest_enabled = 1`，并在前端「群设置」里勾选要整理的群。

> 这五步没做完之前，整理功能是**关着的**（总开关 `digest_enabled` 默认 `0`），不会产生任何数据，也不会报错刷屏。

**时钟**：`message_log.created_at` 与 `chat_digest_item.created_at` 由 Java 写入本地时间（不用库里的
`CURRENT_TIMESTAMP`）——那台 MySQL 跑在 UTC，靠库默认值会和 `LocalDateTime.now()` 差 8 小时，
每小时的窗口永远选不中消息。新加的时间相关表请照这个规矩来。
