# 群 / 私聊命名草稿与展示别名

日期：2026-11-04
状态：待实现（设计已与用户确认三个关键取舍）

## 1. 意图

现在界面上很多地方直接显示群号和 QQ 号（「群 367085619」「QQ 1753800374」），用户看不出这是哪个群、是谁。希望：

1. 新的群号（群聊的群号，不是群成员的 QQ 号）或私聊对方 QQ 号第一次进入系统时，在草稿中心生成一条「命名草稿」。
2. 用户在草稿里给这个会话起名字；可以起多个，用逗号分隔。
3. 以后凡是展示这个会话的地方，都用名字代替号码。
4. 已经入库的群和私聊也要生效（启动时补发命名草稿）。
5. 回复草稿要同时显示「对方说的话」和「对方的名字」；问答页的出处同理。
6. 删掉 Memory 页「认识的人」里的「认一个」手动入口——命名改由草稿驱动。

## 2. 已确认的决策

| 议题 | 决定 |
|---|---|
| 「认一个」删到什么程度 | **只删前端手动入口**。`contact` 表、`/api/contacts`、问答里的「这是谁」反问全部保留。群/私聊别名（本方案）和人物别名（contact）是两件事。 |
| 多个名字怎么用 | **第一个用于展示，逗号里的全部保留用于后续匹配**。本期先落地展示与持久化，匹配接口留好。 |
| 存量会话何时补发 | **后端启动时自动补发**，幂等：已有别名或已有命名草稿的会话跳过。 |

## 3. 非目标

- 不把群/私聊别名灌进 `contact`，也不参与问答的实体识别（「阿黄」仍然走 contact）。
- 不改 Dify 提示词或 DSL：别名在 Java 侧解析后再送模型。
- 不做独立的「别名管理页」。命名通过草稿完成；会话设置页只展示当前名字。
- 不改变 `chat_setting` 两个开关的语义。

## 4. 数据模型

新增表 `chat_alias`（迁移文件 `db/migrations/2026-11-04-chat-alias.sql`）：

```sql
CREATE TABLE IF NOT EXISTS `chat_alias` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `chat_id`    BIGINT UNSIGNED NOT NULL COMMENT '群号；私聊存对方 QQ',
  `kind`       VARCHAR(8)  NOT NULL COMMENT 'GROUP|PRIVATE',
  `aliases`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '逗号分隔；空串 = 用户拒绝命名',
  `created_at` DATETIME NOT NULL,
  `updated_at` DATETIME NOT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_chat_alias` (`chat_id`, `kind`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话展示名';
```

要点：

- `(chat_id, kind)` 唯一。群号和某个人的 QQ 可能相同，必须带 `kind` 区分。
- 单独建表而不并进 `chat_setting`：`chat_setting` 是开关，且「发现行」是虚拟的、不落库；别名是会话身份，需要独立持久化。
- `aliases = ''` 表示用户主动拒绝命名，用于「不再追问」，不等于「没设置过」。

## 5. 后端改动

### 5.1 实体 / Mapper / Service

- `entity/ChatAlias`、`mapper/ChatAliasMapper`。
- `service/ChatAliasService` + `impl/ChatAliasServiceImpl`：
  - `List<String> aliases(Long chatId, String kind)`：解析逗号，去空、去重、trim。
  - `String displayName(Long chatId, String kind)`：第一个别名；没有返回 `null`。
  - `Map<String, String> displayNames()`：一次性取全表，键 `kind + ":" + chatId`，供出处格式化批量使用。
  - `void saveAliases(Long chatId, String kind, String aliases)`：trim、丢空段、去重、截断到 255；upsert。
  - `boolean hasAliasRow(Long chatId, String kind)`。
  - `void ensureNamingDraft(Long chatId, String kind, String suggestedName, Long senderId)`。
  - `int backfillNamingDrafts()`：扫描日志里出现过的会话，补发缺失的命名草稿，返回新建条数。

### 5.2 新会话触发

在 `MessageLogServiceImpl.batchSave` 成功插入一条 `direction = IN` 的日志后：

- `chatId != null` → 会话键 `(chatId, GROUP)`。
- `chatId == null && senderId != null` → 会话键 `(senderId, PRIVATE)`。

调用 `chatAliasService.ensureNamingDraft(...)`。判定「需要建草稿」：

1. `chat_alias` 已有该键的行 → 跳过（含空串「拒绝过」）。
2. 已存在该键的任意状态命名草稿 → 跳过（拒绝过也不再问）。
3. 否则建一条 `type = NAMING` 的草稿：
   - `senderId`：这条消息的发送者。
   - `chatId`：群聊填群号；私聊填 `null`。
   - `title`：`给群 367085619 起个名字` / `给 QQ 1753800374 起个名字`。
   - `payload`：`{ "chatId": 367085619, "kind": "GROUP", "suggestedName": "拿到过的群名或昵称" }`。

**失败隔离**：建草稿的任何异常只记日志，不能让消息入库失败（日志是排查与整理的基础数据）。

同一批里同一个新会话多条消息：第一条建完草稿，后面的看到草稿存在即跳过，不会重复。

### 5.3 启动补发

新增 `ChatAliasBackfillRunner`（`ApplicationReadyEvent` 监听）。调用 `backfillNamingDrafts()`：

- 数据源用现有的 `MessageLogMapper.selectGroupPeers()` / `selectPrivatePeers()`。
- 对每个会话走与 5.2 相同的判定，幂等。
- 整段包 try/catch：补发失败不影响应用启动。

### 5.4 草稿类型与确认

- `DraftServiceImpl.TYPES` 增加 `NAMING`；`DraftCreateRequest` 的 `allowableValues` 同步。
- `confirmDraft(Long id, String aliases)`：
  - `NAMING` 必须有非空 `aliases`，否则 `BusinessException("先给它起个名字")`。
  - 写入 `chat_alias`（`payload.chatId` + `payload.kind`），把用户输入回写进 `payload.aliases`，再置 `CONFIRMED`。
  - 其他类型忽略 `aliases` 参数，行为不变。
- `rejectDraft(id)`：`NAMING` 拒绝时不写别名；因为 5.2 的判定会看到这条草稿，之后不再追问。
- 新增 `dto/DraftConfirmRequest { String aliases; }`；`POST /api/drafts/{id}/confirm` 的 body 可选，老调用方不传也能用。

### 5.5 展示替换

统一规则：**`aliases` 第一个 > 已有 `chatName` > 号码兜底**。

| 位置 | 改动 |
|---|---|
| `retrieval/SourceFormat` | `messageSource(MessageLog)` 增加别名参数；群聊用别名替代群名/群号，私聊用别名替代 `QQ xxx`。 |
| `AskServiceImpl` | 答题时取一次 `displayNames()`，构造出处与证据时使用；`formatSources` 传别名。 |
| `RetrievalServiceImpl.messageCandidate` | `label` 与 `source` 都用别名。 |
| `ChatDigestServiceImpl.listSince` | 输出 `DigestItemVO.chatName` 时用别名覆盖存储值，别名改了下次进首页即生效。 |
| `ChatDigestServiceImpl.digestChat` | 送 Dify 的 `chat_name` 用别名；行里的 `chatName` 也写别名。 |
| `ChatSettingServiceImpl.listAll` | `ChatSetting` 增加 `@TableField(exist=false) String aliases`；发现行与已建行都填上，供前端展示。 |
| `ReplyContextVO.RecentMessage` | 私聊会话的 `senderName` 用别名兜底（没有真实昵称时）。 |

不改 `DigestItemVO` 的字段形状（仍叫 `chatName`），前端无需适配；只改值。

### 5.6 回复草稿带上对方名字

Python 插件 `_respond` 创建 REPLY 草稿时，payload 增加：

- `sourceSenderName`：说话人的昵称（`peer_names(event)` 已能取到）。
- `sourceChatName`：群名（私聊为空）。

`_handle` 把已算出的两个名字传给 `_respond`，避免二次解析。`DraftItem.vue` 的「对方」引文区显示：

```
对方 · 小张
明天三点你能来吗
```

没有名字时退回只显示「对方」。

## 6. 前端改动

### 6.1 草稿中心

- `DraftsView` 在「待确认 / 已处理」之外增加类型筛选：`全部 / 回复 / 命名 / 其他`（Segmented 或第二排 Segmented）。命名草稿与回复草稿分开看。
- `DraftItem`：
  - `NAMING` 类型标签「命名」，图标用 `Tag`。
  - 卡片主体：显示 `给群 xxx 起个名字`，下面一个输入框，预填 `payload.suggestedName`，占位「多个名字用逗号分隔」。
  - 主按钮「保存名字」：调用 `confirmDraft(id, { aliases })`；空输入时前端先拦。
  - 次按钮「不用了」：`rejectDraft`。
- `api/drafts.ts`：`confirmDraft(id, body?)`。
- `api/types.ts`：`DraftType` 增加 `'NAMING'`；`ChatSetting` 增加 `aliases`。
- `utils/meta.ts`：`DRAFT_TYPE` / `DRAFT_ICON` / `ACTION_LABEL` / `IRREVERSIBLE` 等按 `NAMING` 补全（`NAMING` 不是不可逆操作，不需要二次确认）。

### 6.2 会话设置页

- `chatLabel` 改为 `chat.aliases（第一个） || chat.chatName || 群/QQ 号`。
- 列表行下方可只读展示别名，不新增编辑入口。

### 6.3 Memory 页

- 删除「认一个」按钮、`Sheet` 表单、`openContactSheet` / `saveContact`、`createContact` 引用。
- 保留联系人列表与删除；空态文案改为「夜间归纳会自动认人」。

## 7. 边界与失败路径

- 私聊草稿的 `draft.chat_id` 存 `null`，真实会话键在 `payload.chatId`，确认时按 `payload` 写别名。
- 群号与个人 QQ 相同：所有别名读写都带 `kind`。
- 一个会话已有人工 `chatName`（群名）时，别名优先；别名删空后回到群名。
- 启动补发在空库上是 no-op。
- 建草稿失败不回滚消息入库。
- 命名草稿拒绝后不再追问；若用户后来想命名，可在会话设置页后续版本加编辑入口（本期不做）。
- 别名里的重复段、空白段、超长：保存前清洗。

## 8. 验证

后端（`mvn -o test`）：

- `ChatAliasServiceImplTest`：解析 / 清洗 / 去重 / 截断；`ensureNamingDraft` 幂等、已存在别名跳过、已存在草稿跳过、群与私聊键区分。
- `MessageLogServiceImplTest`：新群 IN 消息建一条命名草稿；OUT 不建；同批重复消息只建一条；建草稿抛异常时消息仍然入库。
- `DraftServiceImplTest`：NAMING 确认写别名；空别名报错；拒绝不写别名；其他类型行为不变。
- `ChatAliasDisplayTest` / 相关既有测试：`SourceFormat`、问答出处、整理列表用别名。
- `ChatAliasBackfillTest`：存量会话补发；已有别名/草稿跳过。

插件（`pytest`）：REPLY 草稿 payload 带 `sourceSenderName` / `sourceChatName`。

前端：`npm run build` 通过；草稿页类型筛选、命名输入、回复草稿对方名字。

人工验收：重启后端 → 草稿中心出现存量会话的命名草稿 → 保存名字 → 首页整理卡片、问答出处、会话设置页都显示名字。
