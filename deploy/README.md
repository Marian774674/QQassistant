# 虚拟机部署（Docker）：LangBot + NapCat + 发送桥

本目录把 QQ 助手在 Linux 虚拟机上的运行方式固化成一份 compose：**LangBot（含插件运行时）+ NapCat + send_bridge**。
Spring Boot、Dify、MySQL 不在这里——它们各自的位置见最后一节「地址对照表」。

```
QQ ── NapCat(容器) ──WS 3001── LangBot(容器) ──插件──┐
      └──HTTP 3000──────────────────────────────┐     │
                                                  ▼     ▼
Windows: Spring Boot :8080 ◄── send_bridge(容器) :5301
                                   └── 只读挂载 ./data，读写插件产物文件
```

## 0. 前置条件

- 虚拟机装好 Docker + compose 插件（`docker compose version` 有输出即可）
- 虚拟机有固定局域网 IP（下文记作 `<VM_IP>`），Windows 记作 `<WIN_IP>`
- 防火墙放行：`5301`（Spring → 桥）。`5300/6098` 建议只走 SSH 隧道，不对外开
- 仓库已拉到虚拟机，或至少把 `deploy/` 和 `QQMessageAssistantPython/` 一起拷过去

> 首次登录 QQ 必须扫码，建议在能开浏览器的机器上用 SSH 隧道：
> `ssh -L 5300:127.0.0.1:5300 -L 6098:127.0.0.1:6098 user@<VM_IP>`
> （NapCat WebUI 映射到宿主机 6098；容器内仍是 6099，由 `.env` 的 `NAPCAT_WEBUI_PORT` 控制）

## 1. 起容器

```bash
cd deploy
cp .env.example .env
vim .env                       # 至少填 NAPCAT_WEBUI_TOKEN / SPRING_BASE / LANGBOT_* （见下）
docker compose up -d
docker compose ps              # 4 个服务都应是 running
```

`.env` 里先能填的：

| 变量 | 怎么来 |
|---|---|
| `NAPCAT_WEBUI_TOKEN` / `NAPCAT_HTTP_TOKEN` | 自己定的强口令（HTTP 那个稍后在 NapCat WebUI 里填同一个） |
| `SPRING_BASE` | `http://<WIN_IP>:8080`，虚拟机 curl 得通再往下走 |
| `NAPCAT_UID` / `NAPCAT_GID` | 虚拟机 `id -u` / `id -g` |
| `LANGBOT_BOT_UUID` / `LANGBOT_API_KEY` | 第 3 步拿到后再补，补完 `docker compose up -d send_bridge` 重启桥 |

## 2. NapCat：登录 QQ + 打开 HTTP/WS

1. 浏览器开 `http://127.0.0.1:6098/webui/`（走 SSH 隧道），用 `NAPCAT_WEBUI_TOKEN` 登录
   - 这个版本的 NapCat WebUI 是**明文 HTTP**（实测 `curl -I http://127.0.0.1:6098/webui` 返回 `301 → /webui/`）；用 `https://` 会报 `SSL received a record that exceeded the maximum permissible length`
   - 在 Windows 浏览器里访问时，`127.0.0.1` 指的是 Windows 自己，必须先建隧道或把 `NAPCAT_WEBUI_BIND` 改成 `0.0.0.0`
2. 扫码登录 QQ。登录态落在 `./napcat/qq`，重建容器不用重扫
3. **网络配置**里开两个服务，**Host 必须填 `0.0.0.0`**（默认 `127.0.0.1` 只监听容器自己的 loopback，别的容器连 `napcat:3001` 会 `ConnectionRefusedError`）：
   - **HTTP 服务器**：Host `0.0.0.0`，端口 `3000`，Token 填 `NAPCAT_HTTP_TOKEN` —— 插件下载/回传文件和发送桥发文件都走它
   - **WebSocket 服务器**：Host `0.0.0.0`，端口 `3001`，Token 与 LangBot 机器人的「访问令牌」一致 —— LangBot 的平台适配器走它
4. 两个服务都只在容器网络内，宿主机不用映射端口（`0.0.0.0` 指的是容器内网卡，不是宿主机）

> 扫码登录后若提示设备锁/异地登录，按 QQ 提示在手机上确认即可。

## 3. LangBot：机器人 + API 密钥

1. 浏览器开 `http://127.0.0.1:5300`，首次进入创建管理员
2. **机器人 → 新建**：平台选 `aiocqhttp`（OneBot），连接地址填
   `ws://napcat:3001`，保存后状态应为已连接
3. 拿机器人 **UUID**：v4.11 的机器人详情页不直接显示 UUID，它就在地址栏里——
   `/home/bots?id=98cdd79b-ad16-492b-8420-276c86cc8d94` 的 `id` 就是。
   也可用 API 查（需要先有 API 密钥）：
   `curl -H "X-API-Key: <key>" http://127.0.0.1:5300/api/v1/platform/bots`
   把它填进 `.env` 的 `LANGBOT_BOT_UUID`
4. 左侧 **API 集成**（或 设置 → API 密钥）里生成/复制密钥 → `.env` 的 `LANGBOT_API_KEY`
5. `docker compose up -d send_bridge` 让桥读到新值；`curl http://127.0.0.1:5301/health` 应返回 `{"code":0,...}`

## 4. 安装插件并配置

> **升级顺序（0.2.31 起必须遵守）**：插件的前置噪音判定依赖 Spring 的
> `POST /api/agent/noise-filter/check`。请**先**部署包含该端点的 Spring Boot 版本，再装插件。
> 顺序反了判定接口会 404，插件按 fail-open 全部放行——不会吞消息，但噪音过滤会静默失效。
> 同时 `/api/intercept-rules` 已下线，旧版前端的「拦截规则」页不再可用；
> 噪音规则改到网页 **更多 → 噪音过滤（`/rules`）** 配置，插件侧没有对应配置项。

1. LangBot WebUI → 插件 → 安装 → 上传
   `QQMessageAssistantPython/qq_assistant_plugin/dist/Marian-qq_assistant_plugin-0.2.34.lbpkg`
2. 打开插件配置，按容器网络填：

| 配置项 | 值 |
|---|---|
| `spring_base_url` | `http://<WIN_IP>:8080` |
| `dify_base_url` | Dify 的地址（Windows 或别的机器），如 `http://<WIN_IP>/v1` |
| `dify_api_key` | Dify 应用密钥 |
| `dify_file_workflow_key` | 文件填写 Workflow 的密钥 |
| `napcat_api_url` | `http://napcat:3000` |
| `napcat_token` | 与 `NAPCAT_HTTP_TOKEN` 一致 |
| `napcat_backfill_enabled` | 默认「开启」；NapCat 重连 / 重登 / 周期兜底回查历史补日志，只写日志、不回复 |
| `napcat_backfill_sweep_s` | 默认 `300`；兜底回查间隔（秒），即使没探到掉线也会周期性补一次，最小 30 |
| `token_usage_enabled` | 默认「开启」；把 Dify 对话调用的用量旁路记进网页只读账本，关掉后插件不上报（Spring 侧记账不受影响） |
| `dify_chat_model` | **留空即可**；Dify 不回传模型名，这里只是账本里的显示标注，留空显示「未标注」 |
| `bot_uuid` | 第 3 步的 UUID |
| `bot_qq` | 机器人 QQ 号 |
| `data_dir` | **留空**（自动落到 `/app/data/qq_assistant/data`） |

3. 启用插件，给机器人发一条私聊消息，看是否回复
4. 打开网页「更多 → 噪音过滤（`/rules`）」，保存默认规则后试跑一句「……」，应判为噪音；
   再给机器人发「……」，应只在「消息日志」看到原文、收不到回复。
   若所有消息都被判为「保留」且插件日志出现 `noise check unavailable`，说明 Spring 还没升级
   或 `X-Agent-Token` 不一致，插件正在按 fail-open 放行（不会吞消息，但过滤失效）

### 4.1 掉线补拉（默认开启）

NapCat 掉线期间 QQ 根本没把消息推给它，所以重连后插件会向 QQ 回查一次历史，
把游标之后的消息补写进 `message_log`：

- **触发（插件 0.2.32 起）**：每 10 秒同时探 `get_status`（OneBot 服务）和
  `get_login_info`（QQ 账号登录态）。两者都就绪、且「从没补过 / 上次不就绪 / 探活失败后恢复」
  时立即补一次；**另外每 `napcat_backfill_sweep_s`（默认 300 秒）无条件兜底补一次**。
  边沿只当加速信号，不能当唯一触发条件：登录过期时 NapCat 进程和 HTTP 服务都还在，
  `get_status` 可能照常报在线；重登也可能落在两次探活之间被漏采样；LangBot 与 NapCat
  的 WS 断开时 NapCat 更是完全正常——这三种情况都不会产生 `offline → online` 边沿，
  只靠边沿就会一直不补。补拉幂等（`dedupeKey` + 游标 GREATEST），重复跑只多几次本机 HTTP；
  补拉抛异常也要等下一个周期再试，不能每轮探活都全量扫历史。
- 范围：最近 7 天有消息、或已开启自动回复 / 整理的会话，单次最多 50 个会话，
  每会话最多 200 条 / 5 页，请求间隔 1 秒（避免触发 QQ 风控）；
- 只写日志：补回来的消息**不进 Dify、不触发自动回复**，但整理、夜间归纳、检索
  都会自然读到它们；
- 去重：实时与补拉统一 `dedupeKey = msg:<message_id>`，重叠部分被唯一索引吞掉。

日志里看到 `NapCat 就绪（上线/登录恢复 或 周期兜底），开始补拉历史消息` 与
`补拉完成：N 个会话，写入 M 条` 即为生效；出现
`NapCat 未就绪（服务在线=True，账号已登录=False）` 说明 QQ 掉登录但 NapCat 服务还活着，
重登后会自动补。游标存在 MySQL `napcat_cursor` 表，容器重建不丢。
想关掉就把插件配置 `napcat_backfill_enabled` 选「关闭」——不影响文件下载/回传。

## 5. 插件产物路径与桥的对应关系

插件把文件写到 `<LangBot data>/qq_assistant/data/files/<taskRef>/`，容器内即
`/app/data/qq_assistant/data/files/...`。`send_bridge` 以**同一路径**只读挂载 `./data`，
所以插件回传的 `local:///app/data/qq_assistant/data/files/...` 在桥里能直接打开。
这是两个容器必须挂同一宿主目录、同一容器路径的原因，改路径就会「文件不存在」。

手工确认一次：

```bash
docker compose exec send_bridge ls /app/data/qq_assistant/data/files
docker compose exec send_bridge python -c "import os; print(os.path.isfile('/app/data/qq_assistant/data/files'))"
```

插件把每次处理的痕迹写到同一个目录的 `_debug.log`（`dify ok ... answer=... action=...`、
异常栈）。`docker compose exec` 后面跟的是**服务名**（`langbot_plugin_runtime`），不是容器名：

```bash
cd deploy
docker compose exec langbot_plugin_runtime tail -n 60 /app/data/qq_assistant/data/files/_debug.log
```

## 6. Spring Boot 与数据库

- `QQMessageAssistantJava/src/main/resources/application.yaml`：
  `langbot.base-url: http://<VM_IP>:5301`，重启后端
- Windows 防火墙放行 8080（给插件和桥调用）
- MySQL 跑在虚拟机上的话，Spring 的 JDBC 地址照旧用 `192.168.100.128:3307`
- 已有库记得执行 `db/migrations/2026-10-27-phase3-file-task-and-log.sql`
  以及 `db/migrations/2026-10-31-embedding-retrieval.sql`（两张表 + `message_log.ft_content` 全文索引，
  需要 MySQL 8.0+ 的 `ngram` 分词；脚本可重复执行）
- 掉线补拉还需要 `db/migrations/2026-11-05-napcat-cursor.sql`（一张 `napcat_cursor` 表）。
  **不建这张表，插件每次探测在线都会报 `Table 'napcat_cursor' doesn't exist`**，
  补拉整体不可用（不影响收发消息与文件任务）
- 5.5 的用量账本与会话别名还需要 `db/migrations/2026-11-05-token-usage.sql`
  和 `db/migrations/2026-11-04-chat-alias.sql`。脚本都是 `CREATE TABLE IF NOT EXISTS`，可重复执行。
  缺前者 `/usage` 汇总会报错；缺后者「花在哪个会话」饼图取展示名时的 LEFT JOIN 会报
  `Table 'chat_alias' doesn't exist`（群号本身仍能分账）

> **2026-11 用量页饼图部署顺序**：`/api/token-usage/summary` 新增的 `byChat` 字段由 Spring 侧
> `TokenUsageService` / `TokenUsageMapper` 产出，前端 `/usage` 消费。上线时**必须同时**
> 重新部署 Spring Boot 与前端 `dist/`；只换前端会拿到没有 `byChat` 的响应（饼图整块不渲染，
> 其它分账照常），只换后端则页面不显示饼图。**数据库不用再迁移**；那一轮插件本身不用动
> （但后续的风格画像修正已把插件升到 `0.2.33`，见下一段）。

> **2026-11 风格画像部署顺序**：本轮修了三件事——①插件取风格画像的来源由「消息发送者」
> 改为「主人」；②风格画像的定期总结此前**根本没实现**，本轮补了 Dify `style` 分支 +
> `StyleSummaryServiceImpl` + `StyleSummaryScheduler`；③所有定时任务加「启动补做」，
> 宕机错过时间点后重启会补。
>
> 上线必须按顺序：
> 1. **Dify**：重新导入 `design/QQAssistant-memory-workflow.yml` 并**发布**。不发布会仍跑旧图，
>    `task=style` 会走到 `end` 之外的兜底分支，Spring 拿到空 `style_prompt` 就不覆盖旧画像
>    （不会报错、也不会写坏数据，但总结永远不生效）。
> 2. **重启 Spring Boot**：新 Service/Scheduler 是新增 Bean，热更新不生效；启动日志应能看到
>    `风格总结补做` 或 `风格总结跳过` 中的一条。
> 3. **重新上传插件 `0.2.33`**：`style_prompt` 的来源改了，旧插件仍按发送者取画像。
>
> **数据库不用迁移**：`style_profile` 表结构与 `app_setting.summary_frequency` 都没变，
> 频率取值仍是 `DAILY_7D` / `DAILY_TODAY` / `WEEKLY`。

### 6.1 语义检索模型（可选，但 `/api/ask` 的语义通道需要）

模型文件**不进仓库**（`QQMessageAssistantJava/models/` 已在 `.gitignore`）。在 `QQMessageAssistantJava/`
下执行仓库自带脚本：

```bash
cd QQMessageAssistantJava
bash scripts/fetch-embedding-model.sh
```

也可以手动下载同样两个文件（保持文件名与目录）：

```text
https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/onnx/model.onnx
https://hf-mirror.com/Xenova/bge-small-zh-v1.5/resolve/main/vocab.txt
```

放到 `QQMessageAssistantJava/models/bge-small-zh-v1.5/`（`model.onnx` 和 `vocab.txt` 同级），
并校验字节数：

| 本地文件 | 来源 | 大小（字节） |
|---|---|---|
| `model.onnx` | 远端 `onnx/model.onnx` | **94851877** |
| `vocab.txt` | 远端 `vocab.txt` | **109540** |

> 计划文档里写的 94951877 是笔误，以脚本与上表为准。

对应配置在 `src/main/resources/application.yaml` 的 `app.embedding.*`：

| 配置键 | 默认 | 说明 |
|---|---|---|
| `app.embedding.enabled` | `true` | `false` 或模型文件缺失 → 语义通道关闭，其余六路照常 |
| `app.embedding.mode` | `local` | `local`（进程内 ONNX）\| `http`（OpenAI 兼容 `/embeddings`） |
| `app.embedding.model-dir` | `./models/bge-small-zh-v1.5` | 相对模块根目录（运行目录 = `QQMessageAssistantJava`） |
| `app.embedding.model-id` | `bge-small-zh-v1.5` | 换模型 = 全量重建 |
| `app.embedding.dim` | `512` | 向量维度 |
| `app.embedding.http.base-url` | 空 | `mode=http` 时使用，例：`http://127.0.0.1:8081/v1` |
| `app.embedding.http.api-key` | 空 | **只从环境变量 `EMBEDDING_HTTP_API_KEY` 注入，不要写进仓库** |
| `app.embedding.http.model` | 空 | 外部端点模型名，环境变量 `EMBEDDING_HTTP_MODEL` |
| `app.retrieval.pre-run-index-limit` | `500` | 每次 `/api/ask` 前同步补跑向量的上限；超出部分计入 `scope.lag` |

部署后用 `GET /api/memory/embeddings/status` 验证：`semantic=true` 即模型就绪。
模型缺失或 ONNX 初始化失败时应用照常启动，只是语义通道关闭——不会让 `/api/ask` 500。

### 6.2 登录（Redis + QQ 授权码）

登录依赖 Redis：会话、图形验证码、接口限流都放那里。`docker-compose.yml` 里的 `redis` 服务
已经把 `6379` 暴露出来，Spring 侧用环境变量指过去：

| 变量 | 说明 |
|---|---|
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 指向 compose 起的 Redis；密码与 `.env` 的 `REDIS_PASSWORD` 一致 |
| `AUTH_INITIAL_PASSWORD` | 初始登录密码；不设则启动日志打印随机临时密码 |
| `AUTH_OWNER_QQ` | 主人 QQ 号，QQ 授权登录的收码账号；也可先用密码登录，在「设置 → 我的 QQ 号」里绑定（绑定后以数据库为准） |

两种登录方式：

1. **QQ 授权登录（默认）**：网页填 QQ 号 → 机器人私聊推 6 位码 → 输入即登录。
   码 5 分钟有效、用一次即删、60 秒冷却、连错 5 次作废；**只对已绑定的主人号发码**。
2. **密码登录（兜底）**：账号 + 密码 + 图形验证码，机器人掉线时用它进。

**改兜底密码**：网页「更多 → 系统 → 修改密码」（`/password`），填旧密码 + 新密码两次即可，
改完当前会话作废、自动回登录页用新密码登一次；QQ 验证码登录不受影响。
首次部署如果用的是启动日志里那串随机临时密码，建议第一次进来就在这里换掉。
注意哈希一旦写进 `app_setting.auth_password_hash`，之后再改 `AUTH_INITIAL_PASSWORD` 不再生效。

> 为什么不用 QQ 互联 OAuth：那套要求已备案的回调域名，内网环境拿不到。
> 取舍见 [`../design/design_overview.md`](../design/design_overview.md) §5.2。

## 7. 验收（真机）

按 `design/dify_chatflow.md` §11：

1. 私聊发「我要发个文件，把姓名填上」→ 回「发过来吧」
2. 发 xlsx → 不再反问，直接填写/进草稿
3. 低置信时去 Vue 草稿中心确认 → QQ 真的收到文件
4. 群聊里 @机器人 才触发，普通群消息不打扰

## 8. 排错对照表

| 现象 | 先查 |
|---|---|
| `send_bridge` 起不来、日志报缺环境变量 | `.env` 里 `LANGBOT_BOT_UUID` / `LANGBOT_API_KEY` / `SPRING_BASE` 是否为空（compose 里的 `:?` 会直接拒绝启动） |
| QQ 发消息没反应，NapCat 正常 | LangBot 机器人是否「已连接」；日志里 `ConnectionRefusedError` 基本就是 NapCat 的 HTTP/WS 服务 Host 还写着 `127.0.0.1`，改成 `0.0.0.0` 并保存 |
| 插件/桥连 NapCat 被拒 | 同上：HTTP 服务器的 Host 也要 `0.0.0.0`；Token 三处（NapCat、插件 `napcat_token`、`.env` 的 `NAPCAT_HTTP_TOKEN`）要一致 |
| 插件日志 `httpx.ConnectError: All connection attempts failed` | 插件跑在 `langbot_plugin_runtime` 容器里，`localhost` / `127.0.0.1` 指的是**容器自己**。`spring_base_url`、`dify_base_url` 必须填容器可达的地址（Windows 的局域网 IP）；同时 Windows 防火墙放行 8080 与 Dify 端口 |
| LangBot 显示处理成功但 QQ 无回复、Dify 无日志、MySQL 无记录 | 插件在调 Dify/Spring 之前就抛异常了（先弹 profile 请求）。看 `docker compose logs qq-langbot-runtime` 里第一条 `ConnectError` 指向哪个地址 |
| Dify 返回 400 `{"code":"invalid_param","message":"not supported value type integer"}`，**任何消息都失败且秒回** | 会话变量 `pending_file_expires_at` 默认值写成了 `0`，DSL 里存成 `value_type: integer`；Dify 加载变量池时不认这个类型，节点一个都没跑 | Dify 里把该变量默认值改成 `0.1`（保存后导出 DSL 应显示 `value_type: number`）；UI 改不动就改 DSL 重新导入，并同步插件里的 `dify_api_key`。见 `design/dify_chatflow.md` §6.1 |
| Dify 返回 400 `1 validation error for HttpRequestNodeData` / `authorization Field required`，**每条消息都 400** | 工作流里 HTTP 请求节点（`OP_HTTP`）的「授权」没选。在节点里把授权选成「无」（token 是填在 Headers 里的），保存即可；重新导入 `design/QQAssistant.chatflow.yml` 也行，但 secret 要重填 |
| 插件报 502 / 下载失败 | 插件 `napcat_api_url` 是否 `http://napcat:3000`、Token 是否一致 |
| VM / 容器访问 Windows 上的 Spring 返回 `http_code=000` | Windows 防火墙或监听地址问题：`netstat -ano \| findstr :8080` 要看到 `0.0.0.0:8080`；再加显式规则 `New-NetFirewallRule -DisplayName "SpringBoot 8080" -Direction Inbound -Protocol TCP -LocalPort 8080 -Action Allow -Profile Any`（虚拟机网卡常被判为「公用」profile） |
| 桥返回 400「fileRef 指向的文件不存在」 | 第 5 节两条路径是否一致；插件 `data_dir` 是否被改过 |
| 桥返回 409 | 同一 `bizRef` 正在发送中，属预期保护；稍后重试会返回上次 `messageId` |
| 补拉日志一直是「NapCat 补拉未启用」 | 插件 `napcat_backfill_enabled` 选了关闭，或 `napcat_api_url` 为空 |
| QQ 登录过期、或 LangBot 与 NapCat 的 WS 断过之后，中间消息一直没补回来 | 0.2.31 及更早版本**只靠 `offline → online` 边沿**触发，这几种情况 NapCat 服务一直报在线、边沿根本不出现。升级到 **0.2.32**：每 10s 同时探 `get_status` 与 `get_login_info`，并每 300s 无条件兜底回查。升级后日志应出现 `周期兜底`；若没有，检查 `napcat_backfill_sweep_s` 是否被配得过大、以及 `napcat_api_url` / Token 是否正确 |
| 补拉日志报 `HTTP 401/403` | 插件 `napcat_token` 与 `NAPCAT_HTTP_TOKEN` 不一致（补拉和文件下载共用同一套鉴权） |
| 补拉报 `chatType 只能是 GROUP 或 PRIVATE` | 插件与后端版本不匹配：`/api/agent/napcat/cursor` 是 2026-11 新增的，后端要一起更新 |
| 补拉「跑完了但写入 0 条」 | 正常：游标已是最新（`napcat_cursor.last_message_seq`），或者历史里全是机器人自己的消息（已按 `bot_qq` 过滤） |
| 提醒只收到第一条 | 确认后端是本次改动后的版本（bizRef 按 `<memoId>:<reminderId>`） |
| Java 调桥超时 | `langbot.read-timeout-ms` 必须 > 25s（当前 35000）；VM 到 Windows 是否通 |
| `docker compose up` 拉镜像超时 | Docker Hub 不可达：`LANGBOT_IMAGE` 换官方国内源，`PYTHON_IMAGE` 换国内 registry 后重建 |
| 浏览器打不开 NapCat WebUI | 先分清是谁在访问：VM 上 `curl -I http://127.0.0.1:<宿主机端口>/webui/` 通就说明服务正常，问题在同机访问/隧道；HTTPS 报 SSL 错说明用了 `https://`，改 `http://` |
| 端口被旧容器占用 | `docker ps` 里若有更早手工起的 `langbot-napcat` 之类，先 `docker stop <旧容器>`（可回滚，不要急着 `rm`）；同类容器跑两份会互相顶掉 QQ 登录 |
| 登录页报「验证码发送失败」 | 桥 / 机器人在线与否：`docker compose logs qq-send-bridge`，再看 LangBot 机器人是否「已连接」。急用就切「密码登录」页签 |
| 登录页报「还没有绑定主人 QQ 号」 | `AUTH_OWNER_QQ` 没设，且 `app_setting.owner_sender_id` 为空。先用密码登录，在「设置 → 我的 QQ 号」填一次 |
| 登录页报「这个 QQ 号不是已绑定的主人账号」 | 输入的号与 `app_setting.owner_sender_id` 不一致（设置页改过就以库里为准） |
| 登录后立刻 401 / 反复跳登录页 | Redis 没起或密码不对：`GET /api/health` 之外任何接口都会 503/401；核对 `REDIS_PASSWORD` 与容器一致 |

> 本目录的 compose 已通过 `docker compose config` 校验；镜像构建与真机联调需在虚拟机（或能访问镜像仓库的机器）上执行。

## 9. Dify → Spring 的写入口（`AGENT_OPS_TOKEN`）

Phase 6 起，Dify 的 `OP_HTTP` 节点会直连 Spring 的 `POST /api/agent/ops` 去改/取消备忘。
这条链路需要一个共享口令，**两边必须一致**：

| 位置 | 怎么配 |
|---|---|
| Spring Boot（Windows） | 启动前设环境变量 `AGENT_OPS_TOKEN=<32 位随机串>`；也可以直接 `setx` 或写进 IDE 的 Run Configuration。**不要**写进 `application.yaml` |
| Dify 控制台 | 「环境变量」里建 `AGENT_OPS_TOKEN`，类型选 **Secret**，值同上；另建 `SPRING_BASE_URL` = Dify 容器能访问到的 Spring 地址（见地址对照表） |

```bash
# 生成口令（任选其一）
openssl rand -hex 16
python -c "import secrets;print(secrets.token_hex(16))"
```

- **留空 = 功能关闭**：Spring 未配置 token 时 `/api/agent/*` 一律 503（fail closed），
  不会意外开一个无鉴权的写入口。
- secret 类型**不随 DSL 导出**：重新导入 `design/QQAssistant.chatflow.yml` 后，值一定是空的，
  必须在 UI 里重填——忘了填的表现就是 QQ 上一句“刚才没办成，后台没连上”。
- Dify 走 `ssrf_proxy`，默认拦私网。VM/容器里的 Dify 访问 Windows 上的 8080 前先验：
  `docker compose exec -T dify-api curl -s -o /dev/null -w '%{http_code}\n' http://<WIN_IP>:8080/api/agent/ops`
  返回 `401`/`503` 就说明通了（这两个都是“鉴权没过”，不是“连不上”）。
- 若 Dify 日志里出现 `blocked by SSRF protection`，要在 **Dify 自己的** `docker/.env` 里放行 Spring 地址所在的网段
  （值是 CIDR，不是 `true`），然后重启 `ssrf_proxy api worker`：

  ```bash
  # 1) 先看 host.docker.internal（或你的 SPRING_BASE_URL 主机）解析成哪个 IP
  docker compose -f docker/docker-compose.yaml exec api \
    python -c "import socket;print(socket.gethostbyname('host.docker.internal'))"
  # 2) 写进 docker/.env
  SSRF_PROXY_ALLOW_PRIVATE_IPS=172.21.0.0/16
  docker compose -f docker/docker-compose.yaml up -d ssrf_proxy api worker
  ```

  放行私网等于给 Dify 的 HTTP 节点开了内网出口，只适合自家/实验室环境。

## 10. 安全与运维

- **不要**把 `5401` 映射到宿主机（插件运行器调试口，官方安全通告指出空密钥可被劫持）
- `5300` / `6098` 默认只绑 `127.0.0.1`，用 SSH 隧道访问；确需局域网访问时改 `.env` 的 `*_BIND` 并加防火墙白名单
- NapCat 宿主机端口由 `NAPCAT_WEBUI_PORT` 决定（默认 6098）；以后再冲突改这个值即可，容器内不用动
- 所有 Token / API Key 都放 `.env`，不要写进 compose
- 备份：`./data`（LangBot 配置与数据库、插件、任务文件）、`./napcat/qq`（QQ 登录态）、`./bridge-data`（幂等状态）
- 升级：改 `LANGBOT_IMAGE` 到固定版本 → `docker compose pull && docker compose up -d`；`./data` 不要删

### 10.1 Spring Boot 密钥外置（5.5）

`application.yaml` 里不再有任何明文口令、API Key 或部署专用 IP。全部走环境变量；
本机开发可以写进 `deploy/java.properties`（模板是 `deploy/java.properties.example`），
该文件已被 `.gitignore` 排除，由 `spring.config.import` 以 `optional:file:` 载入——
文件不存在时不会报错，此时值必须来自真实环境变量。生产环境建议只用环境变量，不建这个文件。

| 变量 | 用途 | 落点 |
| --- | --- | --- |
| `DB_URL` / `DB_USERNAME` / `DB_PASSWORD` | JDBC 地址与账号口令 | `java.properties` 或环境变量 |
| `LANGBOT_BRIDGE_URL` | 主动发送桥地址（默认 `127.0.0.1:5301`） | 同上 |
| `DIFY_WORKFLOW_BASE_URL` / `DIFY_WORKFLOW_API_KEY` | 记忆与整理 Workflow；**Key 无默认值，漏配会明确报错** | 同上 |
| `DIFY_WORKFLOW_MODEL` | 只用于 Token 账本的模型标注，可留空 | 同上 |
| `AGENT_OPS_TOKEN` | Dify 回写 `/api/agent/*`，漏配该入口整体 503 | 环境变量 / Dify Secret 变量 |
| `AUTH_INITIAL_PASSWORD` / `AUTH_OWNER_QQ` / `AUTH_USERNAME` | 首次登录与 QQ 授权码 | 环境变量 |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | 登录会话、验证码、限流 | 环境变量（与 `.env` 的 `REDIS_PASSWORD` 一致） |
| `EMBEDDING_HTTP_*` | `app.embedding.mode=http` 时才用 | 环境变量 |

本机启动（Git Bash）：

```bash
cd QQMessageAssistantJava
# 值已在 deploy/java.properties 里；要临时覆盖就直接 export DB_PASSWORD=... 等
mvn spring-boot:run
```

测试库另用 `TEST_DB_URL` / `TEST_DB_USERNAME` / `TEST_DB_PASSWORD` 覆盖
`application-test.yaml`，默认仍指向隔离的 `qq_assistant_test`。

### 10.2 密钥轮换清单

以下值曾随仓库/示例文件一起出现过。**改值才是真正的修复**——从工作区删掉不等于从
Git 历史里删掉。按顺序换，换完在方框里打勾：

- [ ] **Dify「记忆与整理」Workflow API Key**：Dify 控制台重建密钥 → 更新 `DIFY_WORKFLOW_API_KEY`
      （或本地 `java.properties`）；旧 key 立即停用
- [ ] **Dify 对话 Chatflow API Key**：LangBot 插件配置 `dify_api_key` 重建并填回（不在仓库里，但同属一套 Dify）
- [ ] **MySQL 账号口令**：改库口令 → `DB_PASSWORD` / `TEST_DB_PASSWORD` 同步；不要用 root 跑应用库
- [ ] **Redis 口令**：改 `.env` 的 `REDIS_PASSWORD` 与 Spring 的 `REDIS_PASSWORD`，重启 compose 与后端
- [ ] **NapCat WebUI 口令 / OneBot HTTP Token**：`NAPCAT_WEBUI_TOKEN`、`NAPCAT_HTTP_TOKEN`，
      以及 NapCat WebUI 里 HTTP 服务器和插件 `napcat_token` 三处要一致
- [ ] **LangBot API Key**：LangBot 后台重新生成 → `.env` 的 `LANGBOT_API_KEY`，重启发送桥
- [ ] **`AGENT_OPS_TOKEN`**：Spring 与 Dify 环境变量两处同时换；插件 `spring_agent_token` 用同一个值
- [ ] **`PLUGIN_RUNTIME_CONTROL_TOKEN`**：若曾设置，重新生成；留空则保持两端都为空
- [ ] **初始登录口令**：首次部署设 `AUTH_INITIAL_PASSWORD`，登录后尽快在设置里改掉；
      `app_setting.auth_password_hash` 已禁止经 `PUT /api/settings` 从网页改写
- [ ] **Git 历史**：如仓库曾推送到远端，改用 `git filter-repo` 清理历史中的密钥文件，或干脆删库重建；
      只删当前文件无效

`.env.example` 与 `java.properties.example` 只保留空值占位，compose 用 `:?` 强制必填，
避免「复制模板就带着示例口令上线」。

### 10.3 测试库 FULLTEXT 索引维护（跑全量 Java 测试前看）

`RetrievalServiceTest` / `AskSingleRoundTest` / `RetrievalAcceptanceMatrixTest` /
`RetrievalFoundationTest` 用的是共享测试库 `qq_assistant_test` 上的
`message_log.ft_content`（`ngram` FULLTEXT），不是 H2 / 内存库。
这些用例为了能命中 `MATCH … AGAINST`，用 `Propagation.NOT_SUPPORTED` 让种子数据**真提交**，
查完再显式删掉。InnoDB 不会立刻把删掉的行从全文索引里清走，而是记进
`INNODB_FT_DELETED`；攒到几千条「幽灵文档」后，中文词的相关度被稀释，
症状是**单个测试类跑全绿、全量 `mvn test` 一堆「召回为空」**。

判断是否需要重建：

```bash
mysql -h <TEST_DB_HOST> -P <PORT> -u <USER> -p qq_assistant_test \
  -e "SET GLOBAL innodb_ft_aux_table='qq_assistant_test/message_log'; \
      SELECT COUNT(*) AS fts_deleted_docs FROM information_schema.INNODB_FT_DELETED;"
```

`fts_deleted_docs` 上千就该清理（实测 7328 时 14 个用例失败，清理后 428/428 全绿）：

```bash
mysql -h <TEST_DB_HOST> -P <PORT> -u <USER> -p qq_assistant_test \
  -e "OPTIMIZE TABLE message_log;"
```

> `OPTIMIZE TABLE` 在 InnoDB 上会走 `recreate + analyze`，需要建表权限且会短暂锁表，
> **只对隔离的测试库做**，不要拿它当生产库的日常维护。
> 这个现象与业务代码无关：把 `HEAD`（未含 5.5 改动）单独起工作树跑同一批用例，
> 一样会复现；清理之后两边都是全绿。

### 10.4 重新打包插件（开发者）

改完插件代码后先升 `manifest.yaml` 的 `version`，再在插件目录执行：

```bash
cd QQMessageAssistantPython/qq_assistant_plugin
../.venv/Scripts/lbp.exe build -o dist
```

产物是 zip 格式的 `dist/Marian-qq_assistant_plugin-<version>.lbpkg`，
上传前用 `zipfile` 校验一遍（能打开、`version` 对、无旧配置残留）。

> **打包前务必清干净插件目录**。`lbp build` 会把插件目录**整棵目录树**打进包，
> 不按 `.gitignore` 排除。实测把 pytest 的 `--basetemp=.pytest_tmp_rel/run` 放在插件目录内，
> 包从 62 个文件涨到 186 个，多出 124 个 `xlsx` / `_debug.log` 测试临时文件（474 KB vs 287 KB）。
> 跑测试时把干净 basetemp 放到**仓库根**（在仓库根先 `mkdir -p .pytest_tmp`，
> 再 `--basetemp=../../.pytest_tmp/run`），或直接删掉插件目录内的临时目录再打包；
> 仓库根已有 `.pytest_tmp*/` 忽略规则。
>
> **另查历史遗留目录**（2026-11 补）：`.pytest_cache` / `__pycache__` / `dist` 会被 `lbp` 自动跳过，
> 但任意其它目录都会照打。实测 `build_tmp/restore_021.py`（10 月一次性回退脚本，已 tracked，
> 引用的还是 0.2.21 包）被安静地打进了 0.2.32 包。它不会被 LangBot 加载、无功能风险，
> 但属于包污染；已移出插件目录并重新打包（0.2.32 → **61 文件 / 285267 B**）。
>
> **它会自己长回来**（2026-11 二次确认）：0.2.33 打包前发现 `build_tmp/restore_021.py`
> 又出现在插件目录里——仓库的 git 索引当时处于失步状态（索引丢了 88 个条目，`git status`
> 把 HEAD 已删除的文件重新标成「暂存新增」，工作树就被补回了副本）。
>
> **索引已修复**（2026-11-06，见 §10.5）：`git add -A` 重算全部哈希后，`build_tmp/`、
> `core/rules.py`、`tests/test_rules.py`、`InterceptRule*` 这批「复活」文件已移出工作树，
> 0.2.33 最终产物 **61 文件 / 289641 B**，与 0.2.32 的文件数一致、无死代码。
> 但**每次打包前仍然建议 `ls -a` 复查**，这类污染成本低但很容易漏。
>
> 打包后建议把下面这条也加进校验：
>
> ```bash
> python -c "import zipfile,sys; z=zipfile.ZipFile(sys.argv[1]); bad=[n for n in z.namelist() if any(k in n.lower() for k in ('build_tmp','pytest_tmp','.pyc','__pycache__','_debug.log'))]; print('残留:', bad); sys.exit(bool(bad))" dist/Marian-qq_assistant_plugin-0.2.33.lbpkg
> ```

### 10.5 git 索引失步的识别与修复（2026-11-06）

**症状**（这几种同时出现基本可以确诊）：

- `git status` 显示大量 `D`（暂存删除），但 `ls` 看文件好好在磁盘上；
- 同一批文件又被列成 `??`（未跟踪）——**同一文件出现两行**，这是索引里根本没这条记录；
- `git status` 报几百个 ` M`，但 `git diff` 只列出十几个，且对某个 ` M` 文件跑
  `git diff -- <file>` 输出为空。

**确诊命令**（拿一个可疑文件）：

```bash
git ls-files --error-unmatch <file>   # 报 "did not match any file(s) known to git" = 索引没这条
git rev-parse "HEAD:<file>"           # 有值 = HEAD 里确实有
git hash-object <file>                # 与上面相同 = 工作树内容其实是干净的，只是 stat 缓存坏了
```

**根因**：索引条目丢了（本次丢了 88 条），且存活条目的 stat 缓存（size/mtime）与工作树不匹配。
`core.autocrlf=true` 让磁盘上是 CRLF（1499 B）而索引记的是 LF（1441 B），size 对不上后 git
不再逐字节比对，直接按「已修改」上报。

**修复步骤**（全程不动工作树内容）：

```bash
cp .git/index ".git/index.bak.$(date +%Y%m%d_%H%M%S)"   # 先备份索引
git reset                                              # 索引重建为 HEAD（工作树不动）
git add --refresh .                                    # 刷 stat；修不干净时直接下一步
git add -A                                             # 重算全部哈希，顺带修掉幽灵 M
git status                                             # 应只剩真实改动
```

> `git reset` 单独用不够：它重建的索引条目 stat 全为零，`git status` 仍会把 200+ 个文件报成
> ` M`（`git diff` 却是空的）。必须再用 `git add -A` 让 git 真正重算哈希。
> 注意 `git add -A` 会把真实改动和新文件**暂存**；如果不想暂存，别用 `git reset` 去撤销
> ——那会把 stat 清零，幽灵 ` M` 立刻复发。想保持未暂存只能在修之前就别 `add`。

**副作用清理**：索引失步期间被「复活」的 HEAD 已删除文件（本次是 `build_tmp/`、
`core/rules.py`、`tests/test_rules.py`、6 个 `InterceptRule*`）修复后会变成 `??`，
确认 `git ls-tree -r HEAD --name-only | grep -x <file>` 为空即可移出工作树。
移出后跑一遍 `mvn -o -q test` 与插件 `pytest`，确认没有残留引用。

## 11. 地址对照表

| 调用方 | 目标 | 地址 |
|---|---|---|
| 插件（langbot 容器内） | Spring Boot | `http://<WIN_IP>:8080` |
| 插件 | Dify | `http://<WIN_IP>`（按实际） |
| 插件 | NapCat HTTP | `http://napcat:3000` |
| 插件 | LangBot（发送） | 经 LangBot 内部 API，无需配置 |
| send_bridge | LangBot | `http://langbot:5300` |
| send_bridge | NapCat | `http://napcat:3000` |
| send_bridge | Spring Boot | `http://<WIN_IP>:8080` |
| Spring Boot | 发送桥 | `http://<VM_IP>:5301` |
| LangBot 平台适配 | NapCat WS | `ws://napcat:3001` |
