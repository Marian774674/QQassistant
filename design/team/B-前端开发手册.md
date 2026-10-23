# B · 前端开发手册

> 这份文档只写 B 的职责与任务，不需要读 A 的手册。
> 开工前先读共享手册 [`README.md`](./README.md) 的 §1–§3；写代码前读 §4–§7。

---

## 1. 你的角色

你负责三个业务页面，是「用户日常使用频率最高」的部分：

| 你负责 | 具体内容 |
|---|---|
| 备忘录 | 列表、筛选、新建、编辑、完成、取消 |
| 记忆总控台 | 事实记忆 CRUD、风格画像查看/编辑 |
| 设置 | 总结频率、拦截规则管理 |

你不负责：脚手架、路由、布局、HTTP 层、类型定义、草稿中心、文件任务、公共组件（A 负责）；后端、LangBot 插件、Dify。

### 你的交付物（用户能看到什么）

1. 一个可以按状态筛选、新建、编辑、完成/取消的备忘录页面；
2. 一个可以增删改事实、查看和编辑风格画像的记忆总控台；
3. 一个可以修改总结频率、启停拦截规则的设置页面；
4. 三个页面都具备 loading / empty / error 三态。

---

## 2. 边界与依赖

### 2.1 你拥有的文件

```text
QQMessageAssistantVue/src/
├── api/
│   ├── memos.ts
│   ├── profile.ts
│   └── intercepts.ts
└── views/
    ├── memos/**
    ├── memory/**
    └── settings/**
```

### 2.2 你使用但不修改的文件

| 文件 | 提供者 | 用途 |
|---|---|---|
| `src/types/api.ts` | A | 所有接口类型 |
| `src/api/http.ts` | A | 统一请求封装 |
| `src/api/settings.ts` | A | 设置读写 |
| `src/router/index.ts` | A | 路由已注册完 |
| `src/layouts/AppLayout.vue` | A | 布局与当前用户 |
| `src/stores/app.ts` | A | `senderId` |
| `src/components/**` | A | `PageHeader`、`StatusTag`、`EmptyState`、`ConfirmButton` |

### 2.3 你不动的东西

- `QQMessageAssistantJava/**`、`QQMessageAssistantPython/**`、`design/**`；
- A 负责的所有文件；
- `package.json`、`vite.config.ts`、`tsconfig*.json`。

---

## 3. 等待 A 的期间：M0-B

A 的 PR #1（脚手架、类型、路由、布局、HTTP 层）合并前，你不能开始写页面。**这不是空等时间**，你要完成 M0-B：

### 3.1 接口摸底

1. 连上后端，打开 Swagger：`http://localhost:8080/swagger-ui/index.html`；
2. 逐个调用你负责的接口；
3. 记录真实响应：字段名、空值、排序、错误提示。

要记录的接口：

```text
GET    /api/memos
POST   /api/memos
PATCH  /api/memos/{id}
GET    /api/profile/facts
POST   /api/profile/facts
PUT    /api/profile/facts/{id}
DELETE /api/profile/facts/{id}
GET    /api/style-profile/{senderId}
POST   /api/style-profile/update
GET    /api/settings
PUT    /api/settings
GET    /api/intercept-rules
POST   /api/intercept-rules
PUT    /api/intercept-rules/{id}
DELETE /api/intercept-rules/{id}
```

### 3.2 种子数据

执行共享手册 §3.5 的全部命令，确认：

- 备忘录列表能看到「交作业」；
- 事实列表能看到「学号」「姓名」；
- 设置能读到 `summary_frequency`；
- 拦截规则能看到「学号」。

### 3.3 页面草图

画出三个页面的低保真草图：

| 页面 | 必须画出的区域 |
|---|---|
| 备忘录 | 顶部筛选、列表列、新建按钮、编辑弹窗 |
| 记忆总控台 | 当前用户、事实表格、新增/编辑弹窗、风格画像文本区 |
| 设置 | 总结频率选项、拦截规则表格、规则弹窗 |

### 3.4 验收脚本

为每个页面写一份「点哪里、看到什么」的验收步骤，后面直接用于自测和演示。

### 3.5 产出

把「接口真实响应 + 草图 + 验收步骤」整理成一份笔记，贴在 A 的 PR #1 或 #2 评论里。这份笔记是后续联调和演示的底稿。

---

## 4. 开发顺序与时间表

| 天 | 任务 | 依赖 |
|---|---|---|
| D1 | M0-B 接口摸底、种子数据、草图、验收脚本 | 后端可访问 |
| D2 | B1 备忘录列表 | A 的 PR #1 合并 |
| D3 | B2 新建/编辑 + B3 完成/取消 | B1 |
| D4 | B4 事实 CRUD | PR #1 |
| D5 | B5 风格画像 + B6 设置页 | B4 / PR #1 |
| D6 | B7 拦截规则；评审 A 的 PR | B6 |
| D7 | 联调、演示准备 | 全部 |

> 如果 A 的 PR #1 延迟，优先完成 M0-B，不要自己搭第二套脚手架。

---

## 5. 任务卡

> 每张卡直接复制给 AI。卡里的「允许修改」是硬边界。

### B1 备忘录列表

- **分支**：`feat/frontend-memo-list`
- **允许修改**：`src/api/memos.ts`、`src/views/memos/MemoListView.vue`
- **接口**：`GET /api/memos?status=&senderId=`
- **目标**：按状态查看当前用户的备忘录
- **要点**：
  1. 顶部筛选：全部 + 6 种状态；
  2. 表格列：标题、重要性 1–5、分类、截止时间、状态、创建时间；
  3. `senderId` 从 `useAppStore()` 读取；
  4. 三态齐全。
- **验收**：
  - [ ] 状态筛选正确
  - [ ] 切换顶部用户，列表变化
  - [ ] 重要性以星级或标签清晰展示
  - [ ] 空 / 错误状态正常
- **演示**：种子数据 4 → 打开备忘录 → 筛选 ACTIVE
- **提交**：`feat(frontend/memos): add memo list with status filter`

### B2 新建与编辑备忘

- **分支**：`feat/frontend-memo-editor`
- **允许修改**：
  - `src/views/memos/MemoListView.vue`
  - `src/views/memos/components/MemoFormDialog.vue`
- **接口**：
  - `POST /api/memos`
  - `PATCH /api/memos/{id}`
- **目标**：在页面内新建和编辑备忘录
- **要点**：
  1. 新建：标题、内容、重要性 1–5、分类、截止时间；
  2. 编辑：预填现有值，只提交修改字段；
  3. 校验：标题必填、重要性必填；
  4. `dueTime` 用 `YYYY-MM-DDTHH:mm:ss`；
  5. 提交中禁用按钮；成功后关闭并刷新。
- **验收**：
  - [ ] 新建后列表出现新记录
  - [ ] 编辑后列表显示新值
  - [ ] 标题为空无法提交
  - [ ] 重复点击不会创建两条
- **演示**：新建「取快递」，重要性 2，不填时间；再改成 4 级
- **提交**：`feat(frontend/memos): add create and edit dialog`

### B3 完成与取消

- **分支**：`feat/frontend-memo-actions`
- **允许修改**：`src/views/memos/MemoListView.vue`
- **接口**：`PATCH /api/memos/{id}`，body `{ "status": "DONE" | "CANCELLED" }`
- **目标**：把备忘标记完成或取消
- **要点**：
  1. 行操作：完成、取消；
  2. 操作前二次确认；
  3. DONE / CANCELLED 不再显示操作；
  4. 成功后刷新当前筛选。
- **验收**：
  - [ ] 完成后状态变为 DONE，操作消失
  - [ ] 取消后状态变为 CANCELLED
  - [ ] 确认框不会误操作
- **演示**：完成一条备忘，再切到 DONE 筛选查看
- **提交**：`feat(frontend/memos): add done and cancel actions`

### B4 记忆总控台·事实 CRUD

- **分支**：`feat/frontend-memory-facts`
- **允许修改**：
  - `src/api/profile.ts`
  - `src/views/memory/MemoryView.vue`
  - `src/views/memory/components/FactFormDialog.vue`
- **接口**：
  - `GET /api/profile/facts?senderId=`
  - `POST /api/profile/facts`
  - `PUT /api/profile/facts/{id}`
  - `DELETE /api/profile/facts/{id}`
- **目标**：查看、新增、修改、删除当前用户的事实记忆
- **要点**：
  1. 顶部显示当前用户；
  2. 表格：键、值、来源、置信度、更新时间、操作；
  3. 新增/编辑弹窗；删除二次确认；
  4. `confidence` 为 null 显示「—」；
  5. 修改 key 撞到已有键时，展示后端 message；新增已存在的键按覆盖处理。
- **验收**：
  - [ ] 新增事实后出现在表格
  - [ ] 修改后立即反映
  - [ ] 删除后消失（物理删除）
  - [ ] 新增已存在的键会覆盖原值并刷新；改 key 撞键时提示可读
- **演示**：新增「目标=考研」→ 改为「目标=保研」→ 删除
- **提交**：`feat(frontend/memory): add fact CRUD`

### B5 记忆总控台·风格画像

- **分支**：`feat/frontend-memory-style`
- **允许修改**：`src/views/memory/MemoryView.vue`
- **接口**：
  - `GET /api/style-profile/{senderId}`
  - `POST /api/style-profile/update`
- **目标**：查看并编辑当前用户的风格画像
- **要点**：
  1. 画像不存在（`data` 为 `null`）时显示空态和「创建画像」入口；
  2. 文本域编辑，显示字数；
  3. 内容为空禁止保存；
  4. 保存成功后显示 `lastSummarizedAt`；
  5. 切换用户时重新加载。
- **验收**：
  - [ ] 无画像时页面不报错
  - [ ] 保存后刷新仍存在
  - [ ] 空内容无法保存
  - [ ] 切换用户后内容正确切换
- **演示**：为 1001 保存一段画像，再切到 1002 看到空态
- **提交**：`feat(frontend/memory): add style profile editor`

### B6 设置页

- **分支**：`feat/frontend-settings`
- **允许修改**：`src/views/settings/SettingsView.vue`
  - `src/api/settings.ts` 由 A 创建，你直接使用，不修改
- **接口**：
  - `GET /api/settings`
  - `PUT /api/settings`
- **目标**：读取并修改总结频率
- **要点**：
  1. 三个选项：`DAILY_7D` / `DAILY_TODAY` / `WEEKLY`，显示中文说明；
  2. 读取时给缺省值；未知键原样保留，不整表覆盖；
  3. 保存后提示成功；
  4. 三态齐全。
- **验收**：
  - [ ] 页面显示当前值
  - [ ] 修改后保存成功，刷新仍生效
  - [ ] 后端关闭时显示错误
- **演示**：从 DAILY_7D 改为 WEEKLY，刷新确认
- **提交**：`feat(frontend/settings): add summary frequency setting`

### B7 拦截规则管理

- **分支**：`feat/frontend-intercept-rules`
- **允许修改**：
  - `src/api/intercepts.ts`
  - `src/views/settings/SettingsView.vue`
  - `src/views/settings/components/InterceptRuleDialog.vue`
- **接口**：
  - `GET /api/intercept-rules`
  - `POST /api/intercept-rules`
  - `PUT /api/intercept-rules/{id}`
  - `DELETE /api/intercept-rules/{id}`
- **目标**：在设置页维护前置拦截规则
- **要点**：
  1. 表格：名称、关键词、正则、回复模板、优先级、启用、操作；
  2. 新增/编辑弹窗：`name` 必填；
  3. 启用开关直接调 `PUT`；
  4. 删除二次确认；
  5. 正则输入框下方给示例提示。
- **验收**：
  - [ ] 新增规则后出现在表格
  - [ ] 启用/停用即时生效
  - [ ] 编辑与删除正常
  - [ ] 空 / 错误状态正常
- **演示**：新增「学号」规则 → 停用 → 删除
- **提交**：`feat(frontend/settings): add intercept rule management`

---

## 6. 需要 A 配合时

### 6.1 发现公共类型缺失

不要自己改 `src/types/api.ts`。在 A 的 PR 评论或群里发：

```text
【页面】记忆总控台
【接口】GET /api/profile/facts
【缺什么】ProfileFact 里没有 xxx 字段
【期望类型】xxx: string | null
【影响】无法展示 xxx
【证据】真实响应：{...}
```

### 6.2 需要公共组件

- 新组件：可以在 `src/components/` 新建文件，建完告诉 A；
- 改已有组件：先沟通，由 A 修改或 A review 你的修改；
- 不要在每个页面各造一套状态标签/空态。

### 6.3 A 的合并延迟

- 先做 M0-B，不空等；
- 不要复制一套脚手架；
- 不要在 A 的分支上直接开发，等 PR #1 合并后从 `main` 建自己的分支。

---

## 7. 你作为评审人

B 评审 A 的 PR：

| PR | 内容 | 重点 |
|---|---|---|
| #1 | 脚手架、类型、路由、布局、HTTP | 类型是否与契约一致、代理是否可用、路由是否齐全 |
| #2 | 公共组件、文档 | 组件 props 是否够用、三态是否覆盖 |
| #3 | 草稿列表与操作 | 四种 payload 渲染、确认/拒绝状态 |
| #6 | 文件任务与打磨 | 只读语义、detail 展示、角标更新 |
| #9 | 联调修复（按需） | 只改根因相关代码、回归验证 |

评审时额外确认：

- [ ] A 没有把业务判定写进前端；
- [ ] `http.ts` 的错误提示不会重复弹；
- [ ] 公共组件不会迫使你的页面写死样式；
- [ ] 路由/类型改动已提前通知你。

任何一项不满足，退回修改。

---

## 8. 第一周检查清单

### D1

- [ ] clone、读共享手册
- [ ] 连上后端，打开 Swagger
- [ ] 执行种子数据
- [ ] 记录 15 个接口的真实响应
- [ ] 画三张页面草图
- [ ] 写三份验收步骤
- [ ] 评审 A 的 PR #1（如果已提交）

### D2

- [ ] A 的 PR #1 合并后，从 `main` 建 `feat/frontend-memo-list`
- [ ] 完成 B1

### D3–D4

- [ ] B2 新建/编辑
- [ ] B3 完成/取消
- [ ] B4 事实 CRUD

### D5–D7

- [ ] B5 风格画像
- [ ] B6 设置页
- [ ] B7 拦截规则
- [ ] 评审 A 的 PR
- [ ] 参与联调与演示

---

## 9. 演示脚本（B 的部分）

1. 备忘录：切换状态筛选，展示列表与重要性；
2. 新建「取快递」，重要性 2；
3. 编辑为重要性 4，保存后列表更新；
4. 完成该备忘，切到 DONE 筛选确认；
5. 记忆总控台：新增「目标=考研」，改为「目标=保研」；
6. 保存一段风格画像；切换到另一个用户展示空态；
7. 设置：把总结频率改为 WEEKLY，刷新确认；
8. 拦截规则：新增「学号」→ 停用 → 删除。

---

## 10. B 的 AI 使用要点

共享手册 §7 的规则全部适用。额外注意：

1. **不要改公共文件**：`types/api.ts`、`http.ts`、`settings.ts`、`router`、`package.json` 都不是你的；缺什么就提需求。
2. **不要重复造组件**：状态标签、空态、页头先用 A 提供的公共组件。
3. **画像可能为 null**：任何对 `styleProfile.profilePrompt` 的访问前先判空。
4. **事实是 upsert**：新增已存在的键会覆盖，不要把它当错误处理；改 key 撞键才是 400。
5. **日期格式**：发送 `YYYY-MM-DDTHH:mm:ss`，不要带 `Z`。
6. **senderId 从 store 读**：切换用户后要重新拉取，不要缓存上一次的数据。
7. **一次只做一个页面**：先列表，再弹窗，再动作；不要一次生成整个页面。

---

## 11. 交付记录

| 日期 | 任务 | 我负责的部分 | PR | 演示结果 |
|---|---|---|---|---|
| | | | | |
