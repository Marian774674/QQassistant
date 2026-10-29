# 值守台 · QQ 消息智能助手前端

Vue 3 + TypeScript + Vite 的**移动端**管理台。四件事：看备忘、确认草稿、管记忆、改配置。
业务判定全部在后端，这里只做展示和人工确认。

> 架构、需求、接口契约等设计文档在仓库根目录 [`../design/`](../design/)；本目录下的 `design/` 只放前端设计稿截图。

## 启动

```bash
cd QQMessageAssistantVue
npm install          # 首次
npm run dev          # http://localhost:5173，/api 代理到 http://localhost:8080
npm run build        # vue-tsc 类型检查 + 打包到 dist/
```

后端不在 8080 时用环境变量改代理目标（不必改代码）：

```bash
VITE_API_TARGET=http://localhost:8081 npm run dev
```

## 页面

底部四个 tab，其余入口收在「更多」里。

| 路由 | 页面 | 做什么 |
|---|---|---|
| `/memos` | 备忘 | 今天 / 待办 / 完成；时间轴 + 勾选完成；新建、编辑、取消；查看提醒计划 |
| `/drafts` | 草稿 | 待确认的回复、文件、备忘、事实；确认后由后端真正发出或写入 |
| `/memory` | 记忆 | 事实增删改、风格画像、总结频率 |
| `/more` | 更多 | 下面四页的入口 |
| `/files` | 文件任务 | LangBot 侧文件任务的状态镜像（只读） |
| `/logs` | 消息日志 | 收发流水，全链路排查 |
| `/rules` | 拦截规则 | 关键词 + 正则的 CRUD、启用开关、命中测试 |
| `/settings` | 设置 | QQ 号、提醒策略、后端配置 |

## 第一次用

1. 打开「更多 → 设置」，填自己的 QQ 号并保存。后端用它筛选「属于你」的备忘、事实和草稿。
2. 打开「备忘」点右下角 `+` 新建。标题必填，重要性决定提醒频率：

   | 重要性 | 提醒计划 |
   |---|---|
   | 5 紧要 | 提前 24 小时、2 小时、30 分钟 |
   | 4 重要 | 提前 24 小时、1 小时 |
   | 3 一般 | 提前 2 小时 |
   | 2 轻量 | 当天 09:00 |
   | 1 存档 | 不提醒 |

   提醒由后端定时任务扫描后经 LangBot 发到 QQ，前端只展示状态。

## 代码结构

```
src/
  api/           # fetch 封装 + 各业务域请求函数 + types.ts（与后端 DTO 对齐）
  components/    # AppShell / Sheet / MemoRow / MemoEditor / MemoDetail /
                 # DraftItem / DraftBanner / Segmented / StatusPill / …
  composables/   # useOwner（全局 QQ 号）、useToast、useFab、useDraftStore
  views/         # 8 个页面
  router/        # 8 条路由，路由级懒加载
  utils/         # datetime（时间解析/格式化）、meta（状态、类型、图标映射）
  style.css      # 设计 token + 基础排版
```

- 所有请求走相对路径 `/api/...`，由 Vite 代理，前端不配 BASE_URL。
- 后端统一返回 `{ code, message, data }`，`code !== 0` 时 `api/http.ts` 抛错，页面用 toast 或行内提示接住。
- 后端时间是不带时区的 `LocalDateTime`（`2026-10-28T19:00:00`），`utils/datetime.ts` 一律按本地时间解析。
- 待确认草稿在 `useDraftStore` 里共享，底部角标、首页条幅、草稿页用同一份数据。

## 设计说明

底子是**移动端卡片流**：冷白灰画布 `#EDEDED` + 纯白大圆角卡 + 极柔扩散阴影 + 近黑强调面。
基准 390×844，宽屏下居中成 430px 手机画布，不做第二套桌面布局。

- **一张深色卡承担强调**：备忘页「接下来这一件」、待确认草稿条幅、底部抽屉。全站只有这一处用反色。
- **时间轴用勾选圆点 + 虚线脊柱**，勾一下即完成，点卡片进详情。
- **详情与编辑都是底部抽屉**（深色 + 白色输入行），圆角 28，顶部悬浮深色圆形关闭键。
- **字**：标题 30px/600 收紧字距，正文 15px，说明 12.5px；数字用 `tabular-nums` 防跳。
- **重要度**：5 格单色刻度 + 文字（紧要/重要/一般/轻量/存档），不靠颜色单独传达信息。
- **强调色** `#E5387F` 只出现在角标与链接，面积 <2%。
- 键盘可达：抽屉有焦点陷阱与 Esc 关闭，图标按钮都有 `aria-label`，`prefers-reduced-motion` 下关闭动画。

### 动效与手势

动效只有三个时长：`--dur-1 160ms`（按压）/ `--dur-2 260ms`（内容）/ `--dur-3 400ms`（浮层与滑动），
回弹统一用 `--ease-spring`。原则是**慢一点、稳一点**。

| 交互 | 行为 |
|---|---|
| 切页 | 纯淡入淡出：旧页淡出 160ms → 新页淡入 280ms，不做位移 |
| 点备忘卡 | View Transitions 让卡片**变形**成抽屉；不支持或减少动效时退回抽屉滑入 |
| 完成一条 | 圆点填充 → 对勾描边 → 卡片收拢淡出（TransitionGroup FLIP），**乐观更新**，失败回滚 |
| 分段 / 底部导航 | 选中块是滑动的（`translateX` + spring），不是整块跳变 |
| 抽屉 | 按住标题区可**下拉关闭**，位移或速度到阈值就关，scrim 跟着变淡 |
| 滚动 | 页头吸顶并收起大标题，分组标题吸顶；滚过 12px 就切换 |
| 下拉 | 顶部下拉出现圆形指示器，过 52px 松手刷新 |
| 键盘 | 抽屉监听 `visualViewport`，弹起时整体上推，输入行不被遮 |
| 触感 | 完成 / 确认 / 删除触发 `navigator.vibrate(6)`（Android 有效） |

设计稿截图在 [`design/preview/`](./design/preview/)。

## 已知问题

- **记录时间差 8 小时**：MySQL 的 `CURRENT_TIMESTAMP` 按 UTC 写入，而应用侧计算的 `dueTime` / `remindAt` 是本地时间。属于后端配置问题，修法是在 JDBC URL 上加 `sessionVariables=time_zone='+08:00'`。
- 历史测试数据里存在 `type: "REPLAY"` 这类非法草稿类型（后端枚举是 `REPLY`），页面会原样显示该类型名。
- 消息日志接口无分页，界面用 limit 100 → 200 → 500 的「加载更多」兜着。
