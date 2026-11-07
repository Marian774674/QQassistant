import { chromium } from 'playwright'
import fs from 'node:fs'

const OUT = 'C:/Users/chenz/AppData/Local/Temp/qq-shot'
fs.mkdirSync(OUT, { recursive: true })

const BASE = 'http://localhost:5173'
const pages = [
  ['memory', '/memory'],
  ['chat-settings', '/chat-settings'],
  ['rules', '/rules'],
  ['settings', '/settings'],
  ['more', '/more'],
  ['memos', '/memos'],
  ['usage', '/usage'],
  ['home', '/home'],
  ['files', '/files'],
  ['logs', '/logs'],
  ['password', '/password'],
  ['drafts', '/drafts'],
]

const now = '2026-10-26T13:10:00'
const facts = [
  { id: 1, senderId: 1753800374, factKey: 'id', factValue: '0256423', confidence: 1, source: 'USER', createdAt: now, updatedAt: now },
  { id: 2, senderId: 1753800374, factKey: '姓名', factValue: '陈子轩', confidence: 1, source: 'USER', createdAt: '2026-10-29T05:44:00', updatedAt: '2026-10-29T05:44:00' },
  { id: 3, senderId: 1753800374, factKey: '学校', factValue: '江西财经大学', confidence: 1, source: 'USER', createdAt: '2026-11-06T15:30:00', updatedAt: '2026-11-06T15:30:00' },
]
const settings = {
  owner_sender_id: '1753800374',
  digest_enabled: '1',
  owner_aliases: '小明,明哥',
  digest_focus_prompt: '导师和实习相关的通知优先；游戏群里的活动可以忽略',
  digest_refresh_min_messages: '1',
  digest_refresh_min_minutes: '60',
  summary_frequency: 'DAILY_7D',
  reply_enabled: '1',
  reply_prompt: '语气随便点，别提我的工作',
  reply_hourly_limit: '20',
}
const memos = [
  { id: 1, senderId: 1753800374, chatId: 930117, title: '交实习协议', content: '导师说周三前要交到学院办公室', importance: 4, category: 'STUDY', dueTime: '2026-11-07T18:00:00', status: 'ACTIVE', sourceMsgId: null, createdAt: now, updatedAt: now },
  { id: 2, senderId: 1753800374, chatId: 884421, title: '给小号充会员', content: null, importance: 2, category: null, dueTime: null, status: 'ACTIVE', sourceMsgId: null, createdAt: now, updatedAt: now },
  { id: 3, senderId: 1753800374, chatId: 930117, title: '班会带学生证', content: '下午两点综合楼', importance: 3, category: 'STUDY', dueTime: '2026-11-06T14:00:00', status: 'OVERDUE', sourceMsgId: null, createdAt: now, updatedAt: now },
]

function mockData(pathname, method) {
  if (pathname === '/api/settings' && method === 'GET') return settings
  if (pathname === '/api/settings/noise-filter') return {
    enabled: true,
    keywords: '666,6666,哈哈哈,哈哈哈哈,哈哈,笑死,笑死我了,233,2333,绝了,牛逼,收到,好的,嗯嗯,哦哦,额',
    regex: '^(.)\\1{2,}$',
    keepRegex: '@|@\\?|\\?|文件|图片|截图|作业|考试|班会|开会|截止',
    defaultKeywords: '666,6666,哈哈哈,哈哈哈哈,哈哈,笑死,笑死我了,233,2333,绝了,牛逼,收到,好的,嗯嗯,哦哦,额',
    defaultRegex: '^(.)\\1{2,}$',
    defaultKeepRegex: '@|@\\?|\\?|文件|图片|截图|作业|考试|班会|开会|截止',
  }
  if (pathname === '/api/profile/facts') return facts
  if (pathname.startsWith('/api/style-profile')) return {
    id: 1, senderId: 1753800374, profilePrompt: '说话简短，常用「行」「好嘞」，少用感叹号。',
    lastSummarizedAt: '2026-11-06T03:00:00', createdAt: now, updatedAt: now,
  }
  if (pathname === '/api/contacts') return [
    { id: 1, senderId: 1753800374, alias: '陈子轩 我自己', relation: null, source: 'USER', createdAt: now, updatedAt: now },
  ]
  if (pathname === '/api/chat-aliases') return [
    { id: 1, chatId: 1753800374, kind: 'PRIVATE', aliases: '陈子轩,子轩', createdAt: now },
    { id: 2, chatId: 930117, kind: 'GROUP', aliases: '小号,小号群', createdAt: now },
  ]
  if (pathname === '/api/chat-settings') return [
    { id: 1, chatId: 930117, kind: 'GROUP', chatName: '软件三班通知群', aliases: '三班群', autoReplyEnabled: true, digestEnabled: true },
    { id: 2, chatId: 884421, kind: 'GROUP', chatName: '开黑小分队', aliases: null, autoReplyEnabled: false, digestEnabled: false },
    { id: 3, chatId: 1753800374, kind: 'PRIVATE', chatName: '陈子轩', aliases: null, autoReplyEnabled: true, digestEnabled: false },
  ]
  if (pathname === '/api/memos') return memos
  if (pathname === '/api/memos/reminder-preview') return { items: [], text: '' }
  if (/^\/api\/memos\/\d+$/.test(pathname)) {
    return memos.find((m) => m.id === Number(pathname.split('/').pop())) ?? memos[0]
  }
  if (pathname.includes('/reminders') || pathname.includes('/changes')) return []
  if (pathname === '/api/token-usage/summary') return {
    days: 7, since: '2026-10-31', calls: 128, promptTokens: 82000, completionTokens: 31000, totalTokens: 113000,
    byTask: [{ task: 'digest', calls: 60, tokens: 62000 }, { task: 'reply', calls: 45, tokens: 38000 }],
    bySource: [{ source: 'web', calls: 100, tokens: 90000 }, { source: 'plugin', calls: 28, tokens: 23000 }],
    byDay: [{ day: '2026-11-01', calls: 20, tokens: 15000 }, { day: '2026-11-02', calls: 30, tokens: 22000 }],
    byChat: [{ chatId: 930117, label: '三班群', kind: 'chat', calls: 80, tokens: 70000 }, { chatId: null, label: '未归属会话', kind: 'unassigned', calls: 48, tokens: 43000 }],
  }
  if (pathname === '/api/token-usage/recent') return [
    { id: 1, source: 'web', task: 'digest', model: 'dify', promptTokens: 1200, completionTokens: 300, totalTokens: 1500, chatId: 930117, createdAt: now },
  ]
  if (pathname === '/api/digest') return [
    { id: 1, chatId: 930117, chatName: '软件三班通知群', summary: '明天下午两点班会，记得带学生证', importance: 5, mentionsMe: true, sourceMsgIds: '12', periodEnd: now, createdAt: now },
    { id: 2, chatId: 884421, chatName: '开黑小分队', summary: '晚上八点开黑，缺一个辅助', importance: 2, mentionsMe: false, sourceMsgIds: '13', periodEnd: now, createdAt: now },
  ]
  if (pathname === '/api/digest/last-seen') return ''
  if (pathname === '/api/digest/refresh') return { enabled: true, refreshed: false, messageCount: 12, minutesSince: 30, saved: 0, windowStart: null }
  if (pathname === '/api/auth/me') return { username: 'dev' }
  if (pathname === '/api/message-logs') return []
  if (pathname === '/api/file-tasks') return []
  if (pathname === '/api/drafts') return []
  if (pathname.startsWith('/api/ask')) return { answer: '这是演示回答。', sources: [] }
  if (method !== 'GET') return 0
  return []
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const ctx = await browser.newContext({
  viewport: { width: 390, height: 844 },
  deviceScaleFactor: 2,
  isMobile: true,
  hasTouch: true,
})
await ctx.addInitScript(() => {
  try { localStorage.setItem('qq-assistant.auth.token', 'dev-token') } catch {}
})
// 只拦真正的后端接口；Vite 的 /src/api/*.ts 模块路径里也含 "/api/"，别误伤
await ctx.route(/^http:\/\/localhost:5173\/api\//, async (route) => {
  const req = route.request()
  const url = new URL(req.url())
  const data = mockData(url.pathname, req.method())
  await route.fulfill({
    status: 200,
    contentType: 'application/json;charset=utf-8',
    body: JSON.stringify({ code: 0, message: 'ok', data }),
  })
})

const page = await ctx.newPage()
const errors = []
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))

const shot = async (name) => {
  await page.waitForTimeout(600)
  await page.screenshot({ path: `${OUT}/${name}.png` })
}
const visit = async (path) => {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(450)
}

for (const [name, path] of pages) {
  await visit(path)
  await shot(name)
}

// 折叠态：设置页往下滚，页头收成一条细栏
await visit('/settings')
await page.evaluate(() => {
  const s = document.querySelector('.screen')
  if (s) s.scrollTop = 320
})
await shot('header-collapsed')

// 详情抽屉：切到「待办」tab 再点第一条
await visit('/memos')
const seg = page.locator('.seg-item').filter({ hasText: '待办' })
if (await seg.count()) await seg.dispatchEvent('click')
await page.waitForTimeout(400)
const memoCount = await page.locator('.memo').count()
console.log('MEMO_ROWS', memoCount)
if (memoCount) {
  await page.locator('.memo').first().dispatchEvent('click')
  await shot('sheet-detail')
}

// 11：新建备忘抽屉（不设时间的基础态）
await visit('/memos')
await page.locator('.fab').dispatchEvent('click')
const none = page.locator('.preset').filter({ hasText: '不设时间' })
if (await none.count()) await none.dispatchEvent('click')
await shot('sheet-editor')

// 14/15：点「自定义」会直接弹选择器，所以展开态用新建时的默认态，
// 再点时间线才进月历滚轮。两张不能拍成同一个画面。
await visit('/memos')
await page.locator('.fab').dispatchEvent('click')
await shot('sheet-custom-time')
await page.locator('.time-line').dispatchEvent('click')
await shot('sheet-picker')

// 记忆页「添加事实」抽屉
await visit('/memory')
await page.locator('.add-btn').dispatchEvent('click')
await shot('sheet-fact')

// 桌面宽：手机框居中
await page.setViewportSize({ width: 1280, height: 900 })
await visit('/memos')
await shot('desktop')
await page.setViewportSize({ width: 390, height: 844 })

// 360dp 窄屏横向溢出检查
await page.setViewportSize({ width: 360, height: 780 })
for (const [name, path] of pages) {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(250)
  const w = await page.evaluate(() => ({
    scroll: document.documentElement.scrollWidth,
    body: document.body.scrollWidth,
    inner: window.innerWidth,
  }))
  if (w.scroll > w.inner || w.body > w.inner) console.log('OVERFLOW', name, JSON.stringify(w))
}

// 登录页：不带 token 的独立会话
const guest = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 2 })
await guest.route(/^http:\/\/localhost:5173\/api\//, async (route) => {
  await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ code: 0, message: 'ok', data: { captchaId: 'x', image: '' } }) })
})
const guestPage = await guest.newPage()
await guestPage.goto(BASE + '/login', { waitUntil: 'networkidle' })
await guestPage.waitForTimeout(500)
await guestPage.screenshot({ path: `${OUT}/login.png` })

// 「五个主 tab 页保留微标签」豁免验证：读计算样式，不靠肉眼
await page.setViewportSize({ width: 390, height: 844 })
for (const [name, path] of [...pages, ['ask', '/ask']]) {
  await visit(path)
  const probe = await page.evaluate(() => {
    const el = document.querySelector('.section-title, .block-title, .group-title, .group-label')
    const legacy = Boolean(document.querySelector('.screen.legacy-titles'))
    if (!el) return { legacy, title: null }
    const s = getComputedStyle(el)
    return {
      legacy,
      title: (el.textContent || '').trim().slice(0, 14),
      cls: el.className,
      fontSize: s.fontSize,
      fontWeight: s.fontWeight,
      color: s.color,
      before: getComputedStyle(el, '::before').content,
    }
  })
  console.log('TITLE_PROBE', name.padEnd(14), JSON.stringify(probe))
}

console.log('CONSOLE_ERRORS', JSON.stringify(errors.slice(0, 12)))
await browser.close()
