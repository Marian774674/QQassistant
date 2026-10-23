# 前端团队共享手册

> 这套文档一共三份：
>
> | 文档 | 谁读 | 内容 |
> |---|---|---|
> | **本文件** | 两人都读 | 项目、接口、规范、Git、AI 协作、两人交接 |
> | [`A-前端开发手册.md`](./A-前端开发手册.md) | A | A 的职责、任务卡、交付顺序 |
> | [`B-前端开发手册.md`](./B-前端开发手册.md) | B | B 的职责、任务卡、交付顺序 |
>
> **第一天最短路径**：§1.1 → §2 → §3.1–§3.5 → 你的角色手册 §1–§4 → §8。
> 其余章节在写代码、提 PR、卡住时按需查。
>
> **完整阅读顺序**：本文件 §1–§3 → 你自己的角色手册 → 本文件 §4–§9。
> 你不需要读对方的角色手册；两人之间需要对齐的事情都写在本文件 §9。
>
> A / B 是角色代号，不代表能力或资历排序，由项目负责人指定谁担任 A、谁担任 B。
>
> **发送方式**：本文件发给两人；`A-前端开发手册.md` 只发给担任 A 的同学；`B-前端开发手册.md` 只发给担任 B 的同学。

---

## 1. 项目与你们的边界

### 1.1 一句话

用户在 QQ 里聊天、发文件；系统理解消息，生成回复/备忘/文件，并把「AI 可能出错」的动作降级为草稿，等人确认。

```text
QQ
 │
 ▼
NapCat（QQ 协议）
 │
 ▼
LangBot（Python：收发、拦截、拟人延迟、文件读写）
 ├──────────────► Dify（语义理解、意图识别、决定「做什么」）
 └──────────────► Spring Boot（状态机、持久化、定时提醒、草稿中心）
                       ▲
                       │ REST（前端唯一的数据来源）
                       │
                      Vue3（你们在这里）
```

### 1.2 前端只做三件事

1. **展示**：把后端的数据清楚地呈现出来。
2. **人工确认**：用户点「确认 / 拒绝 / 完成 / 取消」。
3. **配置**：修改总结频率、拦截规则等。

前端不做：

- 不判断业务状态该往哪一步走；
- 不直连 LangBot / NapCat / Dify；
- 不直接访问数据库；
- 不修改 `QQMessageAssistantJava/`、`QQMessageAssistantPython/`、`design/`。

### 1.3 术语表

| 术语 | 含义 | 出现位置 |
|---|---|---|
| `senderId` | 用户 QQ 号（演示用 `1001`） | 几乎所有接口 |
| `chatId` | 会话 ID；私聊通常是对方 QQ，群聊为群号 | 草稿、备忘、文件任务 |
| 草稿 Draft | AI 生成、等待人工确认的结果 | 草稿中心 |
| `REPLY` | 拟人回复草稿，确认后发到 QQ | 草稿中心 |
| `FILE` | 文件回传草稿，确认后把文件发到 QQ | 草稿中心 |
| `MEMO` | 备忘草稿，确认后写入备忘录 | 草稿中心 → 备忘录 |
| `FACT` | 事实草稿，确认后写入画像 | 草稿中心 → 记忆总控台 |
| 备忘 Memo | 一条待办/提醒，带重要性 1–5 | 备忘录 |
| `importance` | 5 最强提醒，1 只记录 | 备忘录 |
| 事实记忆 ProfileFact | 「学号=2023001」这类键值对 | 记忆总控台 |
| `source` | `AUTO`（AI 提取）/ `MANUAL`（人工添加） | 记忆总控台 |
| 风格画像 StyleProfile | 描述用户说话风格的一段文本 | 记忆总控台 |
| 拦截规则 InterceptRule | 关键词 + 正则 + 固定回复，命中后绕过 Dify | 设置 |
| 文件任务 FileTask | 一次文件处理的状态镜像 | 文件任务 |
| `taskRef` | LangBot 侧任务 ID，文件任务唯一键 | 文件任务 |
| 拟人延迟 | 发消息前按字数等待，模拟真人输入 | 后端/桥实现，前端不感知 |

### 1.4 四条数据流

**草稿**

```text
QQ 消息 → LangBot → Dify 判断
  → POST /api/drafts（REPLY/FILE/MEMO/FACT）
  → 草稿中心 PENDING
  → 确认：REPLY/FILE 由后端调 LangBot 发送；MEMO/FACT 直接写业务表
  → 拒绝：REJECTED
```

**备忘**

```text
Dify 或前端 POST /api/memos
  → 后端按 importance 展开提醒
  → 定时任务推送到 QQ
  → 用户回「完成/取消」→ LangBot PATCH /api/memos/{id}
```

**记忆**

```text
事实：Dify/前端 PUT /api/profile/facts → 立即覆盖
风格：定时总结 → POST /api/style-profile/update → 覆盖画像
```

**文件任务**

```text
QQ 收文件 → LangBot 下载/解析/回写 → 发回 QQ
  → 每次状态变化 POST /api/file-tasks/sync
  → 前端只读展示
```

---

## 2. 页面地图与分工

| 路由 | 页面 | 负责人 | 核心动作 |
|---|---|---|---|
| `/drafts` | 草稿中心 | **A** | 筛选、查看详情、确认、拒绝 |
| `/file-tasks` | 文件任务 | **A** | 筛选、只读查看、展开 detail |
| `/memos` | 备忘录 | **B** | 筛选、新建、编辑、完成、取消 |
| `/memory` | 记忆总控台 | **B** | 事实 CRUD、风格画像查看/编辑 |
| `/settings` | 设置 | **B** | 总结频率、拦截规则 CRUD |

公共部分：

- 应用骨架、路由、布局、HTTP 层、类型、公共组件由 **A** 负责；
- B 的页面从 A 交付的骨架上开始；
- 两人都必须读本文件 §4–§9。

---

## 3. 环境、启动与联调

### 3.1 前端

```bash
git clone https://github.com/Marian774674/QQassistant.git
cd QQassistant

cd QQMessageAssistantVue
npm install
npm run dev
```

要求：Node.js ≥ 22。开发地址 `http://localhost:5173`。

### 3.2 后端

由项目负责人提供。两种方式：

**方式 A：连负责人局域网内的后端（推荐）**

负责人在自己机器上启动 Spring Boot，前端把代理目标指向那台机器：

```bash
# QQMessageAssistantVue/.env.local（不提交）
VITE_API_TARGET=http://<负责人局域网 IP>:8080
```

**方式 B：自己启动后端**

```bash
cd QQMessageAssistantJava
mvn spring-boot:run
```

数据库不在默认地址时，用环境变量覆盖，不要改仓库里的 `application.yaml`：

```bash
SPRING_DATASOURCE_URL="jdbc:mysql://127.0.0.1:3306/qq_assistant?useUnicode=true&characterEncoding=utf-8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true" \
SPRING_DATASOURCE_USERNAME=root \
SPRING_DATASOURCE_PASSWORD=root \
mvn spring-boot:run
```

### 3.3 为什么必须用 Vite 代理

后端没有配置 CORS。前端请求一律写相对路径 `/api/...`，由 Vite 转发；不要写 `http://localhost:8080/...`，否则浏览器会拦截。

`vite.config.ts` 使用：

```ts
import { fileURLToPath, URL } from 'node:url'
import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')

  return {
    plugins: [vue()],
    resolve: {
      alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: env.VITE_API_TARGET || 'http://localhost:8080',
          changeOrigin: true,
        },
      },
    },
  }
})
```

### 3.4 Swagger

```text
http://localhost:8080/swagger-ui/index.html
http://localhost:8080/v3/api-docs
```

字段有疑问时，以 `design/api_contract.md`、Swagger、真实响应三者交叉确认；冲突时先记录并问负责人，不自行猜测。

### 3.5 种子数据（演示前执行）

```bash
# 1) REPLY 草稿
curl -s -X POST http://localhost:8080/api/drafts \
  -H "Content-Type: application/json" \
  -d '{"type":"REPLY","senderId":1001,"chatId":2002,"title":"回复草稿","payload":{"content":"好的，我看看","targetType":"private","targetId":2002}}'

# 2) MEMO 草稿（确认后进备忘录）
curl -s -X POST http://localhost:8080/api/drafts \
  -H "Content-Type: application/json" \
  -d '{"type":"MEMO","senderId":1001,"title":"备忘草稿","payload":{"title":"班会","content":"周四晚 7 点","importance":3,"category":"学业","dueTime":"2026-10-25T19:00:00"}}'

# 3) FACT 草稿
curl -s -X POST http://localhost:8080/api/drafts \
  -H "Content-Type: application/json" \
  -d '{"type":"FACT","senderId":1001,"title":"事实草稿","payload":{"factKey":"专业","factValue":"计算机科学与技术","confidence":0.72}}'

# 4) 直接创建备忘
curl -s -X POST http://localhost:8080/api/memos \
  -H "Content-Type: application/json" \
  -d '{"senderId":1001,"title":"交作业","content":"周五前提交","importance":4,"category":"学业","dueTime":"2026-10-24T18:00:00"}'

# 5) 两条事实
curl -s -X PUT http://localhost:8080/api/profile/facts \
  -H "Content-Type: application/json" \
  -d '{"senderId":1001,"factKey":"学号","factValue":"2023001","confidence":0.95}'

curl -s -X PUT http://localhost:8080/api/profile/facts \
  -H "Content-Type: application/json" \
  -d '{"senderId":1001,"factKey":"姓名","factValue":"张三","confidence":0.98}'

# 6) 文件任务状态
curl -s -X POST http://localhost:8080/api/file-tasks/sync \
  -H "Content-Type: application/json" \
  -d '{"taskRef":"ft_demo_001","senderId":1001,"chatId":2002,"fileName":"奖学金申请表.xlsx","status":"PROCESSING","detail":{"step":"填写中","progress":60}}'

# 7) 拦截规则
curl -s -X POST http://localhost:8080/api/intercept-rules \
  -H "Content-Type: application/json" \
  -d '{"name":"学号","keywords":"学号,姓名","regex":"(学号|姓名)\\s*[:：]?\\s*","replyTemplate":"学号 2023001，姓名 张三","priority":10,"enabled":true}'

# 8) 总结频率
curl -s -X PUT http://localhost:8080/api/settings \
  -H "Content-Type: application/json" \
  -d '{"summary_frequency":"DAILY_7D"}'
```

### 3.6 联调故障速查

| 现象 | 优先检查 |
|---|---|
| 浏览器 CORS 报错 | 是否直接写了 `http://localhost:8080`；改用 `/api/...` |
| `/api/...` 返回 404 | 后端是否启动、代理目标是否正确、路径是否带了重复的 `/api/api` |
| 请求超时 | 后端是否可达；局域网 IP 是否正确 |
| 确认 REPLY/FILE 失败 | 发送桥是否启动；草稿应保持 PENDING |
| 样式/弹窗异常 | `main.ts` 是否引入 Element Plus 与中文 locale |

---

## 4. 接口与数据契约

### 4.1 通用约定

| 项 | 约定 |
|---|---|
| 前端请求路径 | `/api/...`，由 Vite 代理 |
| 统一响应体 | `{ "code": 0, "message": "ok", "data": {} }` |
| 成功 | HTTP 200，`code = 0` |
| 参数/业务错误 | HTTP 400，`code = 400`，`message` 可读 |
| 系统错误 | HTTP 500，`code = 500` |
| 时间 | 本地 ISO，如 `2026-10-15T14:30:00`，不带时区 |
| ID | JSON 数字；QQ 号在安全整数范围内，前端用 `number` |
| 鉴权 | 无 |
| 分页 | 无，列表返回全部 |
| 空值 | 可能为 `null`，类型里已标注 |

`http.ts` 已统一解包：成功只返回 `data`，失败弹提示并抛异常。**页面里不要再判断 `code`。**

### 4.2 各页面接口

**草稿中心**

| 方法 | 路径 | 参数 / 请求体 | 返回 |
|---|---|---|---|
| GET | `/api/drafts` | `type?`、`status?` | `Draft[]`（`createdAt` 倒序） |
| POST | `/api/drafts/{id}/confirm` | 无 | `Draft` |
| POST | `/api/drafts/{id}/reject` | 无 | `Draft` |

- 列表**没有 `senderId` 参数**，返回所有用户草稿；
- `type = REPLY|FILE|MEMO|FACT`；`status = PENDING|CONFIRMED|REJECTED`；
- 非 PENDING 的草稿不能再次确认/拒绝。

**备忘录**

| 方法 | 路径 | 参数 / 请求体 | 返回 |
|---|---|---|---|
| GET | `/api/memos` | `status?`、`senderId?` | `Memo[]` |
| POST | `/api/memos` | `MemoCreateRequest` | `Memo` |
| PATCH | `/api/memos/{id}` | `MemoPatchRequest` | `Memo` |

- `importance` 必填，1–5；`title` 必填；
- `PATCH` 改 `importance`/`dueTime` 会重算提醒；
- `PATCH status: "DONE" | "CANCELLED"` 即完成/取消。

**记忆总控台**

| 方法 | 路径 | 参数 / 请求体 | 返回 |
|---|---|---|---|
| GET | `/api/profile/facts` | `senderId?` | `ProfileFact[]` |
| POST | `/api/profile/facts` | `{ senderId, factKey, factValue }` | `ProfileFact` |
| PUT | `/api/profile/facts/{id}` | `{ factKey?, factValue? }` | `ProfileFact` |
| DELETE | `/api/profile/facts/{id}` | 无 | `null`（物理删除） |
| GET | `/api/style-profile/{senderId}` | 路径参数 | `StyleProfile` 或 `null` |
| POST | `/api/style-profile/update` | `{ senderId, profilePrompt }` | `StyleProfile` |

- 同一用户同一 `factKey` 唯一；`POST` 已存在的键会覆盖原值，不报错；
- `PUT` 改名撞到已有键会返回 400；
- 画像不存在时 `data` 为 `null`，页面显示空态；
- `confidence` 可能为 `null`，显示「—」。

**文件任务**

| 方法 | 路径 | 参数 | 返回 |
|---|---|---|---|
| GET | `/api/file-tasks` | `senderId?`、`status?` | `FileTask[]` |

只读；`detail` 是任意 JSON，用可折叠代码块展示。

**设置与拦截规则**

| 方法 | 路径 | 参数 / 请求体 | 返回 |
|---|---|---|---|
| GET | `/api/settings` | 无 | `Record<string, string>` |
| PUT | `/api/settings` | 部分 kv，如 `{ summary_frequency: "WEEKLY" }` | 更新条数 |
| GET | `/api/intercept-rules` | 无 | `InterceptRule[]` |
| POST | `/api/intercept-rules` | `InterceptRuleRequest` | `InterceptRule` |
| PUT | `/api/intercept-rules/{id}` | `InterceptRuleRequest` | `InterceptRule` |
| DELETE | `/api/intercept-rules/{id}` | 无 | `null` |

`summary_frequency`：`DAILY_7D`（每日 + 近 7 天）/ `DAILY_TODAY`（每日仅当天）/ `WEEKLY`（每周）。

### 4.3 TypeScript 类型（`src/types/api.ts`）

由 A 在 M0 建好。**所有页面只能使用这些字段，不能自造。**

```ts
// ---------- 通用 ----------
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

// ---------- 草稿 ----------
export type DraftType = 'REPLY' | 'FILE' | 'MEMO' | 'FACT'
export type DraftStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

export interface ReplyPayload {
  content: string
  targetType?: 'private' | 'group'
  targetId?: number
}

export interface FileDraftPayload {
  taskRef: string
  fileName: string
  fileRef: string
}

export interface MemoDraftPayload {
  title: string
  content?: string
  importance: Importance
  category?: MemoCategory
  dueTime?: string
}

export interface FactDraftPayload {
  factKey: string
  factValue: string
  confidence?: number
}

interface DraftBase {
  id: number
  senderId: number
  chatId: number | null
  title: string | null
  status: DraftStatus
  confirmedAt: string | null
  createdAt: string
  updatedAt: string
}

export type Draft =
  | (DraftBase & { type: 'REPLY'; payload: ReplyPayload })
  | (DraftBase & { type: 'FILE'; payload: FileDraftPayload })
  | (DraftBase & { type: 'MEMO'; payload: MemoDraftPayload })
  | (DraftBase & { type: 'FACT'; payload: FactDraftPayload })

// ---------- 备忘录 ----------
export type Importance = 1 | 2 | 3 | 4 | 5
export type MemoCategory = '学业' | '社交' | '健康' | '财务' | '日常' | '其他'
export type MemoStatus =
  | 'ACTIVE'
  | 'REMINDED'
  | 'AWAITING_REPLY'
  | 'DONE'
  | 'CANCELLED'
  | 'OVERDUE'

export interface Memo {
  id: number
  senderId: number
  chatId: number | null
  title: string
  content: string | null
  importance: Importance
  category: MemoCategory | null
  dueTime: string | null
  status: MemoStatus
  sourceMsgId: number | null
  createdAt: string
  updatedAt: string
}

export interface MemoCreateRequest {
  senderId: number
  chatId?: number
  title: string
  content?: string
  importance: Importance
  category?: MemoCategory
  dueTime?: string
}

export interface MemoPatchRequest {
  title?: string
  content?: string
  importance?: Importance
  dueTime?: string
  status?: 'DONE' | 'CANCELLED'
}

// ---------- 文件任务 ----------
export type FileTaskStatus =
  | 'PENDING'
  | 'PROCESSING'
  | 'NEED_INPUT'
  | 'AWAITING_CONFIRM'
  | 'SENT'
  | 'FAILED'
  | 'CANCELLED'

export interface FileTask {
  id: number
  taskRef: string
  senderId: number
  chatId: number | null
  fileName: string | null
  status: FileTaskStatus
  detail: Record<string, unknown> | null
  createdAt: string
  updatedAt: string
}

// ---------- 画像 ----------
export type FactSource = 'AUTO' | 'MANUAL'

export interface ProfileFact {
  id: number
  senderId: number
  factKey: string
  factValue: string
  confidence: number | null
  source: FactSource
  createdAt: string
  updatedAt: string
}

export interface StyleProfile {
  id: number
  senderId: number
  profilePrompt: string
  lastSummarizedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ProfileView {
  facts: Record<string, string>
  stylePrompt: string
}

// ---------- 设置 ----------
export type SettingMap = Record<string, string>
export type SummaryFrequency = 'DAILY_7D' | 'DAILY_TODAY' | 'WEEKLY'

// ---------- 拦截规则 ----------
export interface InterceptRule {
  id: number
  name: string
  keywords: string | null
  regex: string | null
  replyTemplate: string | null
  priority: number
  enabled: boolean
  createdAt: string
  updatedAt: string
}

export interface InterceptRuleRequest {
  name: string
  keywords?: string
  regex?: string
  replyTemplate?: string
  priority?: number
  enabled?: boolean
}
```

### 4.4 推荐的 API 模块函数

| 文件 | 负责人 | 导出 |
|---|---|---|
| `api/http.ts` | A | `http.get/post/put/patch/delete` |
| `api/drafts.ts` | A | `draftApi.list/confirm/reject` |
| `api/fileTasks.ts` | A | `fileTaskApi.list` |
| `api/settings.ts` | A | `settingsApi.getAll/putAll` |
| `api/memos.ts` | B | `memoApi.list/create/patch` |
| `api/profile.ts` | B | `profileApi.listFacts/createFact/updateFact/deleteFact/getStyle/updateStyle` |
| `api/intercepts.ts` | B | `interceptApi.list/create/update/remove` |

### 4.5 已知约束

| # | 约束 | 应对 |
|---|---|---|
| 1 | 后端无 CORS | 只用 Vite 代理，写 `/api/...` |
| 2 | 草稿列表无 `senderId` 筛选 | 展示全部，不假装按用户过滤 |
| 3 | 风格画像可能为 `null` | 显示空态 |
| 4 | 确认 REPLY/FILE 依赖发送桥 | 失败时展示后端 message + 「请确认发送服务已启动」，刷新；草稿仍 PENDING |
| 5 | 列表无分页 | 演示数据控制在几十条 |
| 6 | 删除事实是物理删除 | 二次确认 |
| 7 | 设置接口接受任意键 | 只编辑已知键，不整表覆盖 |
| 8 | `confidence` 可能为 `null` | 显示「—」 |
| 9 | 时间不带时区 | Day.js 按本地时间处理，不加 `Z` |
| 10 | `POST /api/drafts` 仅用于造演示数据 | 不在 UI 中提供创建草稿 |

---

## 5. 前端工程约定

### 5.1 技术栈（固定）

Vite + Vue 3 + TypeScript + Vue Router 4 + Pinia + Axios + Element Plus（中文 locale）+ Day.js。

- 只用 `<script setup lang="ts">`；
- 不新增依赖、不升级版本；
- 不引入第二套 UI 库或 CSS 框架；
- 不用自动导入插件，保持依赖显式。

### 5.2 目录

```text
QQMessageAssistantVue/
├── .env.example
├── AGENTS.md                 # AI 规则（A 在 M0 建）
├── CLAUDE.md                 # 指向 AGENTS.md
├── .cursor/rules/project.mdc # Cursor 规则
├── index.html
├── package.json
├── vite.config.ts
├── tsconfig*.json
└── src/
    ├── main.ts
    ├── App.vue
    ├── api/
    │   ├── http.ts            # A
    │   ├── drafts.ts          # A
    │   ├── fileTasks.ts       # A
    │   ├── settings.ts        # A
    │   ├── memos.ts           # B
    │   ├── profile.ts         # B
    │   └── intercepts.ts      # B
    ├── components/            # A 创建，双方可新增
    ├── layouts/AppLayout.vue  # A
    ├── router/index.ts        # A（M0 一次注册完）
    ├── stores/app.ts          # A（senderId）
    ├── styles/main.css        # A
    ├── types/api.ts           # A
    └── views/
        ├── drafts/            # A
        ├── file-tasks/        # A
        ├── memos/             # B
        ├── memory/            # B
        └── settings/          # B
```

### 5.3 命名与组件写法

| 对象 | 规则 | 示例 |
|---|---|---|
| 视图 | PascalCase + `View` | `MemoListView.vue` |
| 组件 | PascalCase | `StatusTag.vue` |
| TS 模块 | camelCase | `fileTasks.ts` |
| 组合函数 | `use` 前缀 | `useMemoFilters.ts` |
| 常量 | UPPER_SNAKE_CASE | `MEMO_STATUS_OPTIONS` |
| 布尔 | `is/has/can` 前缀 | `isLoading`、`canConfirm` |

组件统一结构：`<script setup lang="ts">` → `<template>` → `<style scoped>`。props / emits 必须带类型；单文件超过约 300 行时拆分。

### 5.4 HTTP 层（A 建，所有人用）

```ts
import axios, { type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'

interface ApiResult<T> {
  code: number
  message: string
  data: T
}

const client = axios.create({
  baseURL: '/api',
  timeout: 15000,
})

client.interceptors.response.use(
  (response) => response,
  (error) => {
    const message =
      error.response?.data?.message ??
      (error.code === 'ECONNABORTED'
        ? '请求超时，请稍后重试'
        : '网络错误，请确认后端已启动')
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  },
)

async function request<T>(config: AxiosRequestConfig): Promise<T> {
  const response = await client.request<ApiResult<T>>(config)
  const body = response.data

  if (body.code !== 0) {
    ElMessage.error(body.message || '请求失败')
    throw new Error(body.message || '请求失败')
  }

  return body.data
}

export const http = {
  get: <T>(url: string, params?: object) => request<T>({ method: 'GET', url, params }),
  post: <T>(url: string, data?: unknown) => request<T>({ method: 'POST', url, data }),
  put: <T>(url: string, data?: unknown) => request<T>({ method: 'PUT', url, data }),
  patch: <T>(url: string, data?: unknown) => request<T>({ method: 'PATCH', url, data }),
  delete: <T>(url: string) => request<T>({ method: 'DELETE', url }),
}
```

API 模块模板：

```ts
import { http } from './http'
import type { Memo, MemoCreateRequest, MemoPatchRequest, MemoStatus } from '@/types/api'

export const memoApi = {
  list(params: { status?: MemoStatus; senderId?: number } = {}) {
    return http.get<Memo[]>('/memos', params)
  },
  create(body: MemoCreateRequest) {
    return http.post<Memo>('/memos', body)
  },
  patch(id: number, body: MemoPatchRequest) {
    return http.patch<Memo>(`/memos/${id}`, body)
  },
}
```

### 5.5 状态与路由

- 只建一个 Pinia store：`useAppStore`，保存当前 `senderId`（默认 `1001`，写 `localStorage`）；
- 列表数据不进全局 store，各页面自己 `ref` + 重新拉取；
- 路由在 M0 一次注册完，页面负责人只替换自己的视图文件，不改 `router/index.ts`：

| 路由 | 文件 |
|---|---|
| `/drafts` | `views/drafts/DraftListView.vue` |
| `/memos` | `views/memos/MemoListView.vue` |
| `/memory` | `views/memory/MemoryView.vue` |
| `/file-tasks` | `views/file-tasks/FileTaskListView.vue` |
| `/settings` | `views/settings/SettingsView.vue` |
| `/` | 重定向到 `/drafts` |

### 5.6 页面三态与交互

任何列表/详情必须处理：

| 状态 | 表现 |
|---|---|
| loading | `v-loading` 或 `el-skeleton` |
| empty | `el-empty` + 下一步提示 |
| error | `el-alert` + 重试按钮 |

交互约定：

- 提交按钮点击后立即 loading，防重复；
- 删除、确认、取消必须二次确认；
- 成功后 `ElMessage.success` + 刷新列表；
- 失败时保留弹窗内容，方便修改后重试；
- 错误提示由 `http.ts` 统一弹出，页面只恢复状态，不重复弹。

### 5.7 表单与时间

- 标题必填；重要性用 1–5 的 `el-rate` 或 `el-radio-group`；
- 分类固定：学业 / 社交 / 健康 / 财务 / 日常 / 其他；
- 日期用 Day.js，发送格式 `YYYY-MM-DDTHH:mm:ss`；
- `el-date-picker` 设置 `value-format="YYYY-MM-DDTHH:mm:ss"`；
- 空值显示「—」，不要显示 `null`。

```ts
import dayjs from 'dayjs'
import relativeTime from 'dayjs/plugin/relativeTime'
import 'dayjs/locale/zh-cn'

dayjs.extend(relativeTime)
dayjs.locale('zh-cn')

export const formatDateTime = (v: string | null) =>
  v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '—'

export const fromNow = (v: string | null) => (v ? dayjs(v).fromNow() : '—')
```

### 5.8 脚本与禁止写法

`package.json`：

```json
{
  "scripts": {
    "dev": "vite",
    "build": "vue-tsc -b && vite build",
    "type-check": "vue-tsc --noEmit",
    "preview": "vite preview"
  }
}
```

提交前必须：

```bash
npm run type-check
npm run build
```

| 禁止 | 替代 |
|---|---|
| 组件里直接 `axios` / `fetch` | 走 `src/api/` |
| `any`、`@ts-ignore` | 补类型 |
| 写死 `senderId` | `useAppStore().senderId` |
| `console.log` / `debugger` 留在代码里 | 删除 |
| 空 `catch` | 提示或恢复状态 |
| 模板里写长链表达式 | 抽 `computed` |
| 一个文件塞多个组件 | 拆文件 |
| 私自加依赖 | 先说明理由，由 A 统一改 |
| 改路由 / 公共类型 / `http.ts` | 找 A |
| 改对方负责的文件 | 提 PR 或请对方改 |
| `setTimeout` 假装接口成功 | 接真实接口，失败就报错 |

---

## 6. Git 协作

### 6.1 分支

| 分支 | 用途 |
|---|---|
| `main` | 始终可运行，只接受 PR |
| `feat/frontend-<页面>` | 新功能 |
| `fix/frontend-<问题>` | 修 bug |
| `chore/frontend-<事项>` | 配置、文档 |

一个任务一条分支，一个分支一个 PR。

### 6.2 每日循环

```bash
git checkout main
git pull origin main
git checkout feat/frontend-xxx
git merge origin/main

# 开发、验证
cd QQMessageAssistantVue
npm run type-check
npm run build
cd ..

git status
git diff --stat
git add <具体文件>
git commit -m "feat(frontend/memos): 备忘列表与状态筛选"
git push
```

### 6.3 Commit

```text
<type>(<scope>): <一句话说明>
```

示例：

```text
feat(frontend/drafts): 增加类型与状态筛选
fix(frontend/memory): 切换用户后事实列表未刷新
chore(frontend): 配置 Vite 代理与路径别名
```

一次提交只做一件事；不写 `update`、`fix bug`。

### 6.4 PR

1. push 后在 GitHub 点 **Compare & pull request**；
2. base = `main`；
3. 模板自动带出自查清单，逐项填写；
4. 评审人 = 另一位前端同学；涉及公共文件时 @ 负责人；
5. **Squash and merge**，合并后删除分支。

PR 必须回答：用户能看到什么、改了哪些文件、怎么验证、有没有已知问题。

**评审时限**：24 小时内。

### 6.5 冲突

```bash
git fetch origin
git merge origin/main
# 编辑冲突文件，删除 <<<<<<< ======= >>>>>>> 标记
git add <冲突文件>
git commit -m "merge: 同步 main 并解决冲突"
git push
```

规则：

- 不用 `rebase` 改写已推送历史；
- 不让 AI 自动解决冲突；
- 冲突文件不属于你时，找负责人一起看；
- 不确定就 `git merge --abort`。

### 6.6 禁止操作

| 禁止 | 原因 |
|---|---|
| `git push --force` / `--force-with-lease` | 覆盖远端 |
| `git reset --hard` | 丢失改动 |
| `git clean -fd` | 删除未跟踪文件 |
| `git checkout -- .` / `git restore .` | 丢弃全部本地改动 |
| 对已推送分支 `rebase` | 改写公共历史 |
| 直接 push `main` | 绕过评审 |
| 提交 `.env.local`、密钥、`node_modules/`、`dist/` | 安全/仓库膨胀 |

AI 给出的 Git 命令要逐条读懂再执行；涉及 `reset`、`clean`、`force`、`rebase` 的一律拒绝。

### 6.7 文件所有权

| 范围 | 负责人 |
|---|---|
| `package.json`、`package-lock.json`、`vite.config.ts`、`tsconfig*.json`、`index.html` | A |
| `src/main.ts`、`src/App.vue`、`src/router/**`、`src/layouts/**`、`src/styles/**` | A |
| `src/stores/app.ts` | A |
| `src/api/http.ts`、`src/api/settings.ts`、`src/types/api.ts` | A |
| `src/api/drafts.ts`、`src/api/fileTasks.ts` | A |
| `src/views/drafts/**`、`src/views/file-tasks/**` | A |
| `src/api/memos.ts`、`src/api/profile.ts`、`src/api/intercepts.ts` | B |
| `src/views/memos/**`、`src/views/memory/**`、`src/views/settings/**` | B |
| `src/components/**` | A 创建；双方可新增新文件；改已有文件需 A review |
| `QQMessageAssistantJava/**`、`QQMessageAssistantPython/**`、`design/**` | 项目负责人 |

约定：

- 路由 M0 一次注册完，页面负责人不改路由；
- 公共类型缺字段 → 找 A 改 `types/api.ts`；
- 需要新依赖 → 说明理由，A 改 `package.json`；
- B 使用 `api/settings.ts`，但不修改它。

### 6.8 评审清单

- [ ] PR 只改了负责人范围内的文件
- [ ] `npm run type-check`、`npm run build` 通过（作者贴了输出）
- [ ] 接口路径/字段/枚举与契约一致
- [ ] 没有 `any`、`@ts-ignore`
- [ ] 组件里没有直接 axios
- [ ] 列表有三态
- [ ] 破坏性操作有二次确认
- [ ] 没有 `console.log`、写死测试数据
- [ ] PR 描述有验证步骤，作者能解释每一处改动

---

## 7. AI 协作规约

### 7.1 角色

| 角色 | 职责 |
|---|---|
| **你** | 理解需求、设边界、审查产出、运行验证、提交 PR、演示 |
| **AI** | 读代码、给方案、写局部实现、解释报错、补类型 |

判断标准：**合并前，你能用自己的话解释每一处改动。**

### 7.2 每次会话开场

1. 粘贴 §8 的「AI 开场上下文」；
2. 贴角色手册里的任务卡；
3. 先要计划，不要代码：

```text
先不要写代码。请：
1. 阅读我列出的相关文件，概括现状；
2. 给出不超过 6 条的实现计划，说明改哪些文件、为什么；
3. 列出不确定项和需要我确认的问题。
在我回复「按计划开始」之前，不要修改文件。
```

### 7.3 八条护栏

| # | 规则 | 要求 |
|---|---|---|
| 1 | 契约唯一 | 只认 `design/api_contract.md`、`src/types/api.ts`、Swagger；缺字段就停 |
| 2 | 文件边界 | 只改任务卡允许的文件 |
| 3 | 先读后写 | 先概括现状，再动手 |
| 4 | 先计划后实现 | 计划确认后才写代码 |
| 5 | 小步 | 一次一个文件；单次约 200 行；PR 手写代码 ≤ 300 行 |
| 6 | 依赖冻结 | 不加依赖、不改构建配置 |
| 7 | Git 禁区 | 不执行 destructive 命令 |
| 8 | 证据闭环 | 附 `type-check`、`build` 真实输出与手动步骤 |

补充：前端不做状态判定；不把密钥、真实聊天记录粘贴给 AI。

### 7.4 标准循环

```text
读现状 → 出计划 → 你确认 → 写一个文件 → 本地验证
   ↑                                      ↓
   └──────── 你审查并解释得通 ← 提交 / 下一小步
```

### 7.5 提示词模板

**实现一个文件**

```text
只实现 <文件路径>。
要求：
1. 使用 <script setup lang="ts">；
2. 只调用 src/api/ 下已有函数，不在组件里用 axios；
3. 列表包含 loading / empty / error；
4. 不新增依赖，不改其他文件。
输出：完整文件、改动说明、手动验证步骤。
```

**修 Bug**

```text
【现象】<用户看到什么>
【复现】1... 2...
【期望】<应该怎样>
【报错】<完整堆栈>
【限制】先定位根因；只改相关代码；根因在范围外就停下。
先给 2–3 个可能原因和验证方法，确认后再改。
```

**让 AI 自审**

```text
以「会拒绝这个 PR」为标准检查：
- 是否自造接口字段；
- 是否有 any / @ts-ignore；
- 是否在组件里直接 axios；
- 三态是否齐全；
- 是否有写死 ID、console.log、空 catch；
- 是否改了范围外文件。
只列问题，不改代码。
```

**解释代码**

```text
逐段解释这段代码：
1. 每段做什么；
2. 数据从哪来、到哪去；
3. 哪些依赖后端行为；
4. 如果我自己重写，按什么顺序写。
最后用 3 个问题考我。
```

### 7.6 完成定义

- [ ] 任务卡验收项全部通过
- [ ] `npm run type-check` 通过
- [ ] `npm run build` 通过
- [ ] 浏览器按演示脚本走通
- [ ] 三态齐全
- [ ] 没有 `any`、`@ts-ignore`、`console.log`
- [ ] 没有自造字段、没有直接 axios
- [ ] 只改允许文件、没有新增依赖
- [ ] 能用三句话说明改了什么、为什么、怎么验证
- [ ] PR 描述完整

### 7.7 失控信号

| 信号 | 处理 |
|---|---|
| 出现契约外字段/接口 | 停止，对照契约 |
| 大量 `any` / `@ts-ignore` | 补类型，否则不合并 |
| 组件里直接 axios | 移到 `api/` |
| 一次改 8 个文件 | 拆小步 |
| 主动加依赖 | 拒绝，走审批 |
| 空 catch | 提示或抛出 |
| 写死 senderId | 从 store 读 |
| `setTimeout` 模拟接口 | 接真实接口 |
| 「应该可以」但没 build | 要求贴输出 |
| 删报错代码/注释校验 | 还原，定位根因 |
| 改了路由/公共文件 | 还原，走公共流程 |
| 一次生成整页几百行 | 按「类型→API→组件→页面」拆 |

### 7.8 让成果属于你

每个任务做三件事：

1. **讲一遍**：合上 AI 回复，用自己的话说明页面怎么工作；
2. **改一处**：不看 AI，独立改一个小地方；
3. **留记录**：PR 描述自己写，演示自己操作，维护交付清单。

> AI 一次给出整页代码时，要求拆成「类型 → API → 子组件 → 页面」四步，每步确认后再继续。

### 7.9 卡住时

- 0–30 分钟：看控制台、Network、Swagger；
- 30 分钟：让 AI 定位，先给复现和完整报错；
- 60 分钟：带「目标 / 已尝试 / 证据」找负责人。

### 7.10 工具接入

| 工具 | 放置方式 |
|---|---|
| Claude Code | `QQMessageAssistantVue/CLAUDE.md` 写 `@AGENTS.md` |
| Cursor | `.cursor/rules/project.mdc`，`alwaysApply: true` |
| Codex / 其他 | `QQMessageAssistantVue/AGENTS.md` |
| 普通聊天 | 每次粘贴 §8 |

---

## 8. AI 开场上下文（复制给 AI）

```text
你正在参与「QQ 消息智能助手」的 Vue3 前端开发。严格遵守以下约束。

【架构】
QQ → NapCat → LangBot（消息/文件 I/O）
                 ├→ Dify（语义/意图）
                 └→ Spring Boot（状态/持久化/草稿）
                        ↑ REST
                      Vue3（当前工作）
前端只做：展示、人工确认、配置。
不做：业务状态判定、直连 QQ/LangBot/Dify、访问数据库、修改后端。

【工作范围】
只改当前任务列出的文件，且位于 QQMessageAssistantVue/。
禁止修改：
- QQMessageAssistantJava/**、QQMessageAssistantPython/**、design/**
- src/router/index.ts、src/types/api.ts、src/api/http.ts、src/api/settings.ts
- package.json、vite.config.ts
- 对方负责的页面/API 文件
除非任务明确授权。

【技术栈】
Vite + Vue3 + TypeScript + Vue Router4 + Pinia + Axios + Element Plus（中文）+ Day.js。
只用 <script setup lang="ts">；不加依赖；不引入第二套 UI 库。

【目录】
src/api/ 所有 HTTP（组件里禁止 axios/fetch）
src/types/ 接口类型唯一来源
src/views/ 页面
src/components/ 公共组件
src/stores/ 仅 senderId

【接口】
baseURL=/api；API 模块写 /drafts、/memos，不写 /api/drafts。
统一响应 { code, message, data }；成功 code=0；400 业务错误；500 系统错误。
http.ts 已统一解包和弹错，页面不要判断 code。

可用接口：
草稿 GET /api/drafts?type=&status=
     POST /api/drafts/{id}/confirm
     POST /api/drafts/{id}/reject
备忘 GET /api/memos?status=&senderId=
     POST /api/memos
     PATCH /api/memos/{id}
事实 GET /api/profile/facts?senderId=
     POST /api/profile/facts
     PUT /api/profile/facts/{id}
     DELETE /api/profile/facts/{id}
风格 GET /api/style-profile/{senderId}
     POST /api/style-profile/update
文件 GET /api/file-tasks?senderId=&status=
设置 GET /api/settings
     PUT /api/settings
规则 GET/POST /api/intercept-rules
     PUT/DELETE /api/intercept-rules/{id}

字段与枚举以 src/types/api.ts 为准。缺字段就停下来问，不猜、不造。
关键枚举：
- 草稿 type=REPLY|FILE|MEMO|FACT；status=PENDING|CONFIRMED|REJECTED
- 备忘 status=ACTIVE|REMINDED|AWAITING_REPLY|DONE|CANCELLED|OVERDUE；importance=1..5
- 文件任务 PENDING|PROCESSING|NEED_INPUT|AWAITING_CONFIRM|SENT|FAILED|CANCELLED
- 来源 AUTO|MANUAL；总结频率 DAILY_7D|DAILY_TODAY|WEEKLY
时间 2026-10-15T14:30:00，本地时间，无时区；ID 用 number。

【编码】
- 类型集中在 src/types/api.ts；禁止 any/@ts-ignore
- 列表三态：loading/empty/error
- 删除/确认/取消二次确认
- 提交按钮防重复
- senderId 从 useAppStore 读，禁止写死
- 日期 Day.js，发送 YYYY-MM-DDTHH:mm:ss
- 不留 console.log/debugger/测试假数据
- 单文件 >300 行拆分
- <style scoped>，不覆盖 Element Plus 全局样式

【工作方式】
1. 先读相关文件，概括现状；
2. 给 ≤6 条计划，列出改动文件与原因；
3. 等我回复「按计划开始」再改；
4. 一次只实现一个文件，单次约 200 行；
5. 提醒我运行 npm run type-check、npm run build；
6. 报告：改动文件、用户可见变化、验证步骤、未完成项。

【完成定义】
验收项通过；type-check 通过；build 通过；浏览器演示走通；三态齐全；
只改允许文件；能说明改了什么、为什么、怎么验证。

【禁止】
不执行 reset --hard、clean -fd、push --force、rebase；
不提交 .env.local、密钥；
不安装依赖、不改构建配置；
不改后端、插件、设计文档；
不删除/注释报错代码来让编译通过；
不声称完成而没有构建输出。

【主动提问】
接口字段/枚举/返回结构不明确；
任务要求的文件不在允许范围；
需要新增依赖或公共类型；
后端行为与文档冲突；
同一问题两次修复仍失败。
```

---

## 9. 两人协作接口

### 9.1 PR 计划

| PR | 内容 | 负责人 | 评审 |
|---|---|---|---|
| #1 | F1–F4：脚手架、类型、骨架、HTTP | A | B |
| #2 | F5–F6：公共组件、说明与 AI 规则 | A | B + 负责人 |
| #3 | A1–A2：草稿列表与确认/拒绝 | A | B |
| #4 | B1–B2：备忘列表与编辑器 | B | A |
| #5 | B3–B4：备忘动作与事实 CRUD | B | A |
| #6 | A3–A4：文件任务与草稿打磨 | A | B |
| #7 | B5–B6：风格画像与设置 | B | A |
| #8 | B7：拦截规则 | B | A |
| #9 | 联调修复（按需拆小） | 各自 | 对方 |

每人同一时间只保留 1 个进行中的 PR；合并后再开下一个。

**整体节奏**

| 天 | A | B |
|---|---|---|
| D1 | F1–F4 底座，提交 PR #1 | M0-B 接口摸底、种子数据、草图 |
| D2 | B 评审后合并 PR #1；F5–F6，提交 PR #2 | PR #1 合并后开始 B1 |
| D3 | A1 草稿列表 | B2 + B3 备忘编辑与动作 |
| D4 | A2 草稿确认/拒绝 | B4 事实 CRUD |
| D5 | A3 文件任务 + A4 打磨 | B5 画像 + B6 设置 |
| D6 | 评审 B 的 PR；联调支持 | B7 拦截规则；评审 A 的 PR |
| D7 | I1 联调 + I2 演示准备 | I1 联调 + I2 演示准备 |

### 9.2 A 先交给 B 的东西

| 交付 | 时间 | 内容 |
|---|---|---|
| PR #1 | 第 1 天提交、第 2 天合并 | 脚手架、`types/api.ts`、路由、布局、`http.ts`、`settings.ts`、5 个占位页、`useAppStore` |
| PR #2 | 第 2 天 | 公共组件、`README.md`、`AGENTS.md` / `CLAUDE.md` / Cursor 规则 |
| 口头同步 | 每次合并后 | 发一条消息：合并了什么、B 可以开始什么、有没有接口变化 |

B 在 PR #1 合并前做 M0-B（接口摸底、种子数据、草图），不空等。

### 9.3 B 交给 A 的东西

- B 的每个 PR 由 A 评审；
- B 发现公共类型缺字段时，在 PR 评论里写清楚：页面、接口、缺什么、期望类型；
- B 不直接改 `types/api.ts`、`http.ts`、路由、`package.json`。

### 9.4 共享文件规则

| 文件 | 规则 |
|---|---|
| `src/types/api.ts` | A 修改；B 提需求 |
| `src/api/http.ts` | A 修改；B 只使用 |
| `src/api/settings.ts` | A 修改；B 使用 |
| `src/router/index.ts` | A 修改；路由已一次注册完 |
| `src/layouts/AppLayout.vue` | A 修改 |
| `src/components/**` | 新文件谁需要谁建；改已有文件先沟通，由 A review |
| `package.json` / `vite.config.ts` | A 修改；B 提需求 |

### 9.5 评审与同步

- 评审：收到请求 24 小时内；只看边界、契约、可验证性；
- 每日 10 分钟同步：昨天完成什么、今天做什么、有没有阻塞；
- 合并后由作者在群里发一条「合并了什么、影响了谁」；
- 任何接口/类型变化，A 必须当天通知 B。

### 9.6 集成检查点

| 检查点 | 两人一起确认 |
|---|---|
| M1 结束 | 草稿列表 + 备忘列表能同时演示 |
| M2 结束 | 草稿确认 MEMO → 备忘录出现；事实 CRUD 可用 |
| M3 结束 | 全部页面可用，三态齐全 |
| M4 | 全链路演示，问题清单清零 |

### 9.7 冲突升级

1. 文件归属明确 → 归属人解决；
2. 共享文件 → A 解决，B 提供上下文；
3. 接口/字段争议 → 查 `api_contract.md` + Swagger + 真实响应；
4. 仍不一致 → 项目负责人拍板，前端不自行改后端。

---

## 10. 联调与演示总则

> 任务编号：**I1 = 全链路联调**，**I2 = 演示准备**。联调中发现的问题按「页面 / 复现步骤 / 负责人 / 状态」记录。

### 10.1 联调顺序

1. 后端启动，Swagger 可打开；
2. `curl http://localhost:8080/api/settings` 返回 `code:0`；
3. 前端 `npm run dev`，Network 中 `/api/settings` 返回 200；
4. 执行 §3.5 种子数据；
5. 逐页对照 §4.2 验证；
6. 最后验证需要 LangBot 的链路（REPLY/FILE 确认、提醒推送）。

### 10.2 演示标准

- 每个里程碑有一次 3 分钟演示，由页面负责人主讲并操作；
- 演示必须包含：正常流程、空态、错误态；
- 演示前固定种子数据，准备录屏或截图备份；
- 演示后记录问题：页面 / 复现步骤 / 负责人 / 状态。

### 10.3 交付记录

每人维护一张表：

| 日期 | 任务 | 我负责的部分 | PR | 演示结果 |
|---|---|---|---|---|
| | | | | |
