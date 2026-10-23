# A · 前端开发手册

> 这份文档只写 A 的职责与任务，不需要读 B 的手册。
> 开工前先读共享手册 [`README.md`](./README.md) 的 §1–§3；写代码前读 §4–§7。

---

## 1. 你的角色

你是前端的技术底座负责人，同时负责两个业务页面：

| 你负责 | 具体内容 |
|---|---|
| 工程底座 | 脚手架、依赖、构建配置、Vite 代理、路径别名 |
| 公共契约 | `src/types/api.ts`、`src/api/http.ts`、`src/api/settings.ts` |
| 应用骨架 | 路由、布局、导航、当前用户 store |
| 公共组件 | `PageHeader`、`StatusTag`、`EmptyState`、`ConfirmButton` |
| 业务页面 | 草稿中心、文件任务 |
| 评审 | B 的所有 PR |

你不负责：备忘录、记忆总控台、设置的页面实现（B 负责）；后端、LangBot 插件、Dify。

### 你的交付物（用户能看到什么）

1. 一个能跑起来、有统一布局和导航的前端；
2. 草稿中心：筛选、查看四种草稿、确认/拒绝；
3. 文件任务：按用户/状态筛选、查看 detail；
4. 一套 B 可以直接使用的类型、HTTP 层、路由和公共组件。

---

## 2. 边界与交接

### 2.1 你拥有的文件

```text
QQMessageAssistantVue/
├── package.json / package-lock.json
├── vite.config.ts
├── tsconfig*.json
├── index.html
├── .env.example
├── AGENTS.md / CLAUDE.md / .cursor/rules/project.mdc
└── src/
    ├── main.ts
    ├── App.vue
    ├── api/http.ts
    ├── api/settings.ts
    ├── api/drafts.ts
    ├── api/fileTasks.ts
    ├── types/api.ts
    ├── stores/app.ts
    ├── router/index.ts
    ├── layouts/AppLayout.vue
    ├── styles/main.css
    ├── components/**
    └── views/
        ├── drafts/**
        └── file-tasks/**
```

### 2.2 你不动的文件

| 文件 | 归属 |
|---|---|
| `src/api/memos.ts`、`src/api/profile.ts`、`src/api/intercepts.ts` | B |
| `src/views/memos/**`、`src/views/memory/**`、`src/views/settings/**` | B |
| `QQMessageAssistantJava/**`、`QQMessageAssistantPython/**`、`design/**` | 项目负责人 |

### 2.3 你要先交给 B 的东西

B 的页面依赖你的底座。**PR #1 是 B 的开工信号。**

| 交付 | 时间 | 内容 |
|---|---|---|
| PR #1 | D1 提交，D2 合并 | 脚手架、`types/api.ts`、路由、布局、`store`、`http.ts`、`settings.ts`、5 个占位页 |
| PR #2 | D2 | 公共组件、前端 README、AI 规则文件 |
| 每周同步 | 合并后 | 发一条消息说明 B 可以开始什么 |

PR #1 合并后，在群里发：

```text
PR #1 已合并，B 可以开始 B1。
可用：src/types/api.ts、src/api/http.ts、src/api/settings.ts、
      router、layout、useAppStore、5 个占位页。
注意：路由已注册完，直接替换你自己的视图文件，不要改 router。
```

如果底座延期：优先交付 F1–F4，F5–F6 可以后置；B 先做 M0-B 的接口摸底与草图。

---

## 3. 开发顺序与时间表

| 天 | 任务 | 产出 |
|---|---|---|
| D1 | F1 脚手架 → F2 类型 → F3 骨架 → F4 HTTP | PR #1 |
| D2 | B 评审通过后合并 PR #1；F5 公共组件 → F6 文档 | PR #2 |
| D3 | A1 草稿列表 | PR #3 |
| D4 | A2 草稿详情与确认/拒绝 | 同 PR #3 或单独 PR |
| D5 | A3 文件任务 → A4 打磨 | PR #6 |
| D6 | 评审 B 的 PR；联调支持 | — |
| D7 | 全链路联调、演示准备 | 演示 |

---

## 4. 任务卡

> 每张卡直接复制给 AI。卡里的「允许修改」是硬边界。

### F1 脚手架与工程配置

- **分支**：`chore/frontend-scaffold`
- **允许修改**：`QQMessageAssistantVue/` 下所有新建文件
- **目标**：`npm run dev` 能打开页面，`npm run build` 通过
- **步骤**：
  1. `npm create vite@latest . -- --template vue-ts`，包名 `qq-message-assistant-vue`
  2. 安装 `vue-router@4 pinia axios element-plus @element-plus/icons-vue dayjs`
  3. 配置 Vite 代理与 `@` 别名（共享手册 §3.3）
  4. 配置 `.env.example`、`.gitignore`（含 `.env.local`）
  5. `src/main.ts` 接入 Pinia、Router、Element Plus 中文 locale
  6. `package.json` 增加 `"type-check": "vue-tsc --noEmit"`
- **验收**：
  - [ ] `npm run dev` 打开 `http://localhost:5173`
  - [ ] `npm run type-check` 通过
  - [ ] `npm run build` 通过
  - [ ] Network 中 `/api/...` 被代理到后端
- **提交**：`chore(frontend): scaffold Vite + Vue3 + TS project`

### F2 接口类型

- **分支**：`chore/frontend-types`
- **允许修改**：`src/types/api.ts`
- **目标**：把共享手册 §4.3 的类型完整落入代码
- **要点**：
  1. 通用响应体、草稿、备忘、文件任务、画像、设置、拦截规则；
  2. 草稿用可判别联合类型（按 `type` 区分 payload）；
  3. 可能为空的字段显式 `| null`。
- **验收**：
  - [ ] `npm run type-check` 通过
  - [ ] 无 `any`
  - [ ] 类型名与共享手册一致
- **提交**：`feat(frontend/types): add API domain types`

### F3 应用骨架

- **分支**：`chore/frontend-shell`
- **允许修改**：
  - `src/App.vue`
  - `src/router/index.ts`
  - `src/layouts/AppLayout.vue`
  - `src/stores/app.ts`
  - `src/styles/main.css`
  - 5 个占位视图：`views/drafts/DraftListView.vue`、`views/memos/MemoListView.vue`、`views/memory/MemoryView.vue`、`views/file-tasks/FileTaskListView.vue`、`views/settings/SettingsView.vue`
- **目标**：5 个路由可切换；顶部当前用户可切换并持久化
- **要点**：
  0. 5 个占位页由你在 M0 创建；合并后 memos / memory / settings 三个文件归 B，你不再修改；
  1. 路由一次注册完（共享手册 §5.5）；
  2. 左侧导航 + 顶部用户选择 + 内容区；
  3. `useAppStore` 保存 `senderId`，默认 `1001`，写 `localStorage`；
  4. 占位页只放标题和一句说明。
- **验收**：
  - [ ] 5 个路由都能打开
  - [ ] 刷新后 `senderId` 保持
  - [ ] 导航高亮当前页
- **提交**：`feat(frontend/shell): add layout, routes and sender store`

### F4 HTTP 层与后端连通

- **分支**：`feat/frontend-http`
- **允许修改**：`src/api/http.ts`、`src/api/settings.ts`、`src/layouts/AppLayout.vue`
- **目标**：统一请求封装可用；顶栏显示后端连接状态
- **要点**：
  1. 按共享手册 §5.4 实现 `http.ts`；
  2. `settings.ts` 实现 `getAll` / `putAll`；
  3. 顶栏挂载时请求 `GET /api/settings`，显示「后端已连接 / 后端未连接」。
- **验收**：
  - [ ] 后端启动时显示「后端已连接」
  - [ ] 后端停止时显示「后端未连接」，页面不崩
  - [ ] 失败请求弹出可读错误
  - [ ] 组件里没有直接 axios
- **提交**：`feat(frontend/http): add axios client and backend status`

> **PR #1 = F1 + F2 + F3 + F4。** 合并后 B 即可开始页面开发。

### F5 公共组件

- **分支**：`feat/frontend-components`
- **允许修改**：
  - `src/components/PageHeader.vue`
  - `src/components/StatusTag.vue`
  - `src/components/EmptyState.vue`
  - `src/components/ConfirmButton.vue`
  - `src/views/dev/ComponentPreviewView.vue`
  - `src/router/index.ts`（只加 `/dev/components`）
- **目标**：四个组件可复用；有内部预览页
- **要点**：
  1. `PageHeader`：标题 + 描述 + 右侧操作插槽；
  2. `StatusTag`：草稿/备忘/文件任务状态、草稿类型的颜色映射；
  3. `EmptyState`：图标 + 说明 + 可选操作；
  4. `ConfirmButton`：二次确认后触发事件；
  5. `/dev/components` 不进导航。
- **验收**：
  - [ ] 预览页展示四个组件的所有状态
  - [ ] 状态颜色一致、文字可读
  - [ ] props 有类型
- **提交**：`feat(frontend/components): add shared UI components`

### F6 说明与 AI 规则文件

- **分支**：`docs/frontend-readme`
- **允许修改**：
  - `QQMessageAssistantVue/README.md`
  - `QQMessageAssistantVue/AGENTS.md`
  - `QQMessageAssistantVue/CLAUDE.md`
  - `QQMessageAssistantVue/.cursor/rules/project.mdc`
- **目标**：任何人按 README 能跑起来；AI 工具能自动读到规则
- **要点**：
  1. README：环境、安装、启动、代理、目录、常用命令；
  2. `AGENTS.md`：复制共享手册 §8 的 AI 开场上下文，加一行指向共享文档；
  3. `CLAUDE.md`：写 `@AGENTS.md`；
  4. Cursor 规则：`alwaysApply: true`，写四条核心约束并指向 `AGENTS.md`。
- **验收**：
  - [ ] 按 README 从零可启动
  - [ ] 不含真实密钥
  - [ ] 三个规则文件指向同一套约束
- **提交**：`docs(frontend): add setup guide and AI rules`

> **PR #2 = F5 + F6。**

### A1 草稿列表

- **分支**：`feat/frontend-draft-list`
- **依赖**：PR #1 合并
- **允许修改**：
  - `src/views/drafts/DraftListView.vue`
  - `src/views/drafts/components/DraftFilters.vue`（可选）
- **接口**：`GET /api/drafts?type=&status=`
- **目标**：按状态/类型查看所有草稿
- **要点**：
  1. 状态筛选：全部 / PENDING / CONFIRMED / REJECTED；
  2. 类型筛选：全部 / REPLY / FILE / MEMO / FACT；
  3. 列表列：类型标签、标题、senderId、chatId、状态、创建时间；
  4. 三态齐全；
  5. 点击行预留详情插槽（A2 实现）。
- **验收**：
  - [ ] 状态与类型可组合筛选，Network 参数正确
  - [ ] 四种类型正常展示
  - [ ] 空结果 / 后端关闭有对应状态
- **演示**：种子数据 1–3 → 看到 3 条 PENDING → 切到 FACT 只剩 1 条
- **提交**：`feat(frontend/drafts): add draft list with filters`

### A2 草稿详情与确认/拒绝

- **分支**：`feat/frontend-draft-actions`
- **依赖**：A1
- **允许修改**：
  - `src/views/drafts/DraftListView.vue`
  - `src/views/drafts/components/DraftDetailDrawer.vue`
  - `src/views/drafts/components/DraftPayloadView.vue`
- **接口**：
  - `POST /api/drafts/{id}/confirm`
  - `POST /api/drafts/{id}/reject`
- **目标**：看清每种草稿内容，并安全确认/拒绝
- **要点**：
  1. 按 `draft.type` 渲染：
     - REPLY：正文、targetType、targetId；
     - FILE：taskRef、fileName、fileRef；
     - MEMO：标题、内容、重要性、分类、截止时间；
     - FACT：键、值、置信度；
  2. 确认/拒绝前二次确认；
  3. 按钮 loading，防重复；
  4. 非 PENDING 禁用；
  5. 成功后刷新；失败保留现场。
- **验收**：
  - [ ] 确认 MEMO → 备忘录出现新记录
  - [ ] 确认 FACT → 事实列表出现或更新
  - [ ] 拒绝后状态 REJECTED，按钮禁用
  - [ ] 发送桥未启动时确认 REPLY 失败，状态仍 PENDING
- **演示**：确认 MEMO → 打开备忘录看到「班会」；拒绝 REPLY → 状态已拒绝
- **提交**：`feat(frontend/drafts): add detail view and confirm/reject actions`

### A3 文件任务列表

- **分支**：`feat/frontend-file-tasks`
- **依赖**：PR #1
- **允许修改**：`src/api/fileTasks.ts`、`src/views/file-tasks/FileTaskListView.vue`
- **接口**：`GET /api/file-tasks?senderId=&status=`
- **目标**：只读查看文件处理进度
- **要点**：
  1. 筛选当前用户、状态；
  2. 表格：taskRef、文件名、发送者、状态、更新时间；
  3. 行展开显示 `detail` 格式化 JSON；
  4. 标注「只读」；
  5. 三态齐全。
- **验收**：
  - [ ] 7 种状态都有标签
  - [ ] detail 可展开、可读
  - [ ] 切换用户后列表变化
- **演示**：种子数据 6 → 打开文件任务 → 展开详情
- **提交**：`feat(frontend/file-tasks): add read-only task list`

### A4 草稿中心打磨

- **分支**：`feat/frontend-draft-polish`
- **依赖**：A2
- **允许修改**：`src/layouts/AppLayout.vue`、`src/views/drafts/**`
- **目标**：导航显示待处理数量；时间更易读
- **要点**：
  1. 路由切换时请求 `GET /api/drafts?status=PENDING`，导航显示角标；
  2. 时间用相对时间，悬浮显示完整时间；
  3. 窄屏表格可横向滚动。
- **验收**：
  - [ ] 处理草稿后切回，角标更新
  - [ ] 时间显示正确
  - [ ] 1280px 下布局完整
- **提交**：`feat(frontend/drafts): add pending badge and relative time`

---

## 5. 你作为评审人

A 评审 B 的 PR：

| PR | 内容 | 重点 |
|---|---|---|
| #4 | 备忘列表与编辑器 | 字段/枚举、日期格式、三态 |
| #5 | 备忘动作与事实 CRUD | 物理删除确认、覆盖语义、错误展示 |
| #7 | 画像与设置 | 画像为 null 的空态、设置只提交已知键 |
| #8 | 拦截规则 | 启停、删除确认、正则字段 |
| #9 | 联调修复（按需） | 只改根因相关代码、回归验证 |

评审时额外确认：

- [ ] B 没有改 `types/api.ts`、`http.ts`、`settings.ts`、`router`、`package.json`；
- [ ] B 的页面使用了 `useAppStore().senderId`；
- [ ] B 的 API 模块路径没有重复 `/api`；
- [ ] 共享组件使用一致，没有各自造一套标签/空态。

任何一项不满足，退回修改。

---

## 6. 第一周检查清单

### D1

- [ ] clone、`npm install`、跑通空项目
- [ ] 建分支 `chore/frontend-scaffold`
- [ ] 按 F1 配置代理与别名
- [ ] 确认 `/api/settings` 能通过代理访问
- [ ] 完成 F2–F4
- [ ] 提交 PR #1，请 B 评审

### D2

- [ ] 合并 PR #1，通知 B 可以开始
- [ ] 完成 F5–F6，提交 PR #2
- [ ] 评审 B 的第一个 PR

### D3–D4

- [ ] A1 草稿列表
- [ ] A2 草稿操作
- [ ] 确认 MEMO/FACT 分发链路

### D5–D7

- [ ] A3 文件任务
- [ ] A4 打磨
- [ ] 评审 B 的 PR
- [ ] 参与联调与演示

---

## 7. 演示脚本（A 的部分）

1. 打开首页，展示导航与当前用户；
2. 草稿中心：切换 PENDING / REPLY，展示筛选；
3. 打开一条 REPLY 草稿详情，展示正文与目标；
4. 确认一条 MEMO 草稿 → 切到备忘录，指出新记录；
5. 拒绝一条 REPLY 草稿 → 状态变为 REJECTED；
6. 文件任务：按状态筛选，展开一条 detail；
7. 停掉发送桥，再确认一条 REPLY，展示错误提示与仍为 PENDING 的状态。

---

## 8. A 的 AI 使用要点

共享手册 §7 的规则全部适用。额外注意：

1. **公共文件改动影响两人**：改 `types/api.ts`、`http.ts`、`router`、`package.json` 前，先说明影响；合并后当天通知 B。
2. **脚手架阶段不要让 AI 自由发挥**：包管理器、目录、代理、别名按共享手册固定，禁止引入 UI 框架、自动导入插件、状态持久化插件。
3. **生成整页时拆分**：要求 AI 按「类型 → API → 子组件 → 页面」推进。
4. **公共组件先定 props**：`StatusTag` 的 props 一旦定下，B 也会用；改动要通知。
5. **评审 B 的代码时，让 AI 当第二评审**：用共享手册 §7.5 的「自审」提示词，但最终结论由你下。

---

## 9. 交付记录

| 日期 | 任务 | 我负责的部分 | PR | 演示结果 |
|---|---|---|---|---|
| | | | | |
