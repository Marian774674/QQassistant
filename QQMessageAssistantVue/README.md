# 值守台 · QQ 消息智能助手前端

Vue 3 + TypeScript + Vite 的管理台，负责四件事：**看备忘、确认草稿、管记忆、改设置**。
业务判定全部在后端，这里只做展示和人工确认。

## 启动

```bash
cd QQMessageAssistantVue
npm install          # 首次
npm run dev          # 默认 http://localhost:5173，/api 代理到 http://localhost:8080
npm run build        # vue-tsc 类型检查 + 打包到 dist/
```

后端不在 8080 时，用环境变量改代理目标（不必改代码）：

```bash
# Git Bash
VITE_API_TARGET=http://localhost:8081 npm run dev
```

## 页面

| 路由 | 页面 | 做什么 |
|---|---|---|
| `/memos` | 备忘 | 今天 / 全部待办 / 已完成；新建、编辑、标记完成、取消；查看每个备忘的提醒计划 |
| `/drafts` | 草稿中心 | 待确认的回复、文件、备忘、事实；确认后由后端真正发出或写入 |
| `/memory` | 记忆 | 事实的增删改；风格画像提示词 |
| `/settings` | 设置 | 填自己的 QQ 号（`owner_sender_id`），查看提醒策略与其余配置 |

备忘页右侧常驻草稿托盘，任何页面都能看到「等你过目」的待确认条目。

## 第一次用

1. 打开 `/settings`，填上自己的 QQ 号并保存。后端用它筛选「属于你」的备忘、事实和草稿；没填时记忆页会提示去填。
2. 打开 `/memos` 点「新建备忘」，标题必填；重要性决定提醒频率：

   | 重要性 | 提醒计划 |
   |---|---|
   | 5 紧要 | 提前 24 小时、2 小时、30 分钟 |
   | 4 重要 | 提前 24 小时、1 小时 |
   | 3 一般 | 提前 2 小时 |
   | 2 轻量 | 当天 09:00 |
   | 1 存档 | 不提醒 |

   提醒由后端定时任务扫描后经 LangBot 发到 QQ，前端只负责展示状态。

## 代码结构

```
src/
  api/           # fetch 封装 + 每个业务域的请求函数 + types.ts（与后端 DTO 对齐）
  components/    # AppShell、MemoRow、MemoEditor、MemoDetail、DraftItem、DraftTray、Modal、Toast…
  composables/   # useOwner（全局 QQ 号）、useToast、useAsync
  views/         # MemosView / DraftsView / MemoryView / SettingsView
  router/        # 4 条路由，路由级懒加载
  utils/         # 时间格式化、状态文案
  style.css      # 设计 token + 基础排版
```

- 所有请求走相对路径 `/api/...`，由 Vite 代理，前端不需要配置 BASE_URL。
- 后端统一返回 `{ code, message, data }`，`code !== 0` 时 `api/http.ts` 抛错，页面用 toast 或行内提示接住。
- 后端返回的时间是不带时区的 `LocalDateTime`（`2026-10-28T19:00:00`），`utils/datetime.ts` 一律按本地时间解析。

## 设计说明

底子是「值守台 · 值日表」：浅冷灰纸面 + 32px 横线，纸张白卡片，青绿信号色。重要性用砖红→苔绿的单一色阶，并且**永远配文字**（紧要/重要/一般/轻量），不靠颜色单独传达信息。

- 备忘录左侧是竖排时间标尺，`今天` 分栏里有一条跟着当前时间走的「现在」线。
- 草稿托盘是整页唯一有浮起阴影的面板——它的语义就是「等着你动手」。
- 字体：标题用衬线（Palatino / 宋体），界面文字用系统中文黑体；数字用 tabular-nums 防跳。
- 键盘可操作：模态框有焦点陷阱、Esc 关闭，图标按钮都有 `aria-label`，`prefers-reduced-motion` 下关闭动画。

## 已知问题

- **记录时间差 8 小时**：MySQL 的 `CURRENT_TIMESTAMP` 按 UTC 写入，而应用侧计算的时间（`dueTime`、`remindAt`）是本地时间，所以列表里的「记录于」会早 8 小时。属于后端配置问题，修法是在 JDBC URL 上加 `sessionVariables=time_zone='+08:00'`。
- 历史测试数据里存在 `type: "REPLAY"` 这类非法草稿类型（后端枚举是 `REPLY`），页面会原样显示该类型名，可以忽略或清理该行。
