import { chromium } from 'playwright'
import fs from 'node:fs'

const OUT = 'C:/Users/chenz/AppData/Local/Temp/qq-verify'
fs.mkdirSync(OUT, { recursive: true })
const BASE = 'http://localhost:5173'

const KW = '666,6666,哈哈哈,哈哈哈哈,哈哈,笑死,笑死我了,233,2333,绝了,牛逼,收到,好的,嗯嗯,哦哦,额'
const KEEP = '@|＠|\\?|？|文件|图片|截图|作业|考试|班会|开会|截止|报名|交表|明[天日]|今[天日]|周[一二三四五六日天]|\\d{1,2}[:：]\\d{2}|\\d{1,2}月\\d{1,2}[日号]|生日|学号|姓名|专业|班级|电话|地址'
const noise = {
  enabled: true,
  keywords: KW,
  regex: '^(.)\\1{2,}$',
  keepRegex: KEEP,
  defaultKeywords: KW,
  defaultRegex: '^(.)\\1{2,}$',
  defaultKeepRegex: KEEP,
}
let live = { ...noise }
const puts = []

const settingsWithOwner = { owner_sender_id: '1753800374', reply_enabled: '1', reply_prompt: '', reply_rate_limit: '20' }
const settingsNoOwner = { reply_enabled: '1', reply_prompt: '', reply_rate_limit: '20' }

function mockData(pathname, method, body, settings) {
  if (pathname === '/api/settings' && method === 'GET') return settings
  if (pathname === '/api/settings' && method === 'PUT') return 0
  if (pathname === '/api/settings/noise-filter' && method === 'GET') return live
  if (pathname === '/api/settings/noise-filter' && method === 'PUT') {
    puts.push(body)
    live = { ...live, ...body }
    return live
  }
  if (pathname === '/api/settings/noise-filter/preview') return {
    enabled: true,
    items: (body.texts || []).map((text) => ({ text, kept: text.includes('班会'), reason: text.includes('班会') ? null : 'keyword' })),
  }
  if (pathname === '/api/auth/me') return { username: 'dev' }
  if (method !== 'GET') return 0
  return []
}

async function makeContext(browser, settings) {
  const ctx = await browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
  })
  await ctx.addInitScript(() => {
    try { localStorage.setItem('qq-assistant.auth.token', 'dev-token') } catch {}
  })
  await ctx.route(/^http:\/\/localhost:5173\/api\//, async (route) => {
    const req = route.request()
    const url = new URL(req.url())
    let body = null
    try { body = req.postData() ? JSON.parse(req.postData()) : null } catch {}
    const data = mockData(url.pathname, req.method(), body, settings)
    await route.fulfill({
      status: 200,
      contentType: 'application/json;charset=utf-8',
      body: JSON.stringify({ code: 0, message: 'ok', data }),
    })
  })
  return ctx
}

const browser = await chromium.launch({ channel: 'chrome', headless: true })
const errors = []
const ctx = await makeContext(browser, settingsWithOwner)
const page = await ctx.newPage()
page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
page.on('pageerror', (e) => errors.push('pageerror: ' + e.message))
const visit = async (path) => {
  await page.goto(BASE + path, { waitUntil: 'networkidle' })
  await page.waitForTimeout(400)
}

/* ── 1. 主页面：只剩入口行，重内容已下沉 ─────────────────── */
await visit('/rules')
const mainPage = await page.evaluate(() => ({
  links: [...document.querySelectorAll('.rule-link')].map((a) => ({ href: a.getAttribute('href'), label: a.querySelector('.rule-link-label')?.textContent.trim(), hint: a.querySelector('.rule-link-hint')?.textContent.trim() })),
  hasKeywordsBox: Boolean(document.querySelector('#noise-keywords')),
  hasKeepInput: Boolean(document.querySelector('#noise-keep')),
  hasChipRow: Boolean(document.querySelector('.chip-row')),
  textareas: document.querySelectorAll('textarea').length,
  trigger: document.querySelector('.select-trigger')?.textContent.trim().replace(/\s+/g, ' '),
  hasNativeSelect: document.querySelectorAll('select').length,
  dots: document.querySelectorAll('.hint-dot').length,
}))
console.log('MAIN_PAGE', JSON.stringify(mainPage))
await page.screenshot({ path: `${OUT}/rules-main.png`, fullPage: true })

/* ── 2. 自定义下拉：样式 / 选择 / 关闭 ─────────────────────── */
await page.locator('.select-trigger').click()
await page.waitForTimeout(220)
const menu = await page.evaluate(() => {
  const m = document.querySelector('.select-menu')
  if (!m) return null
  const s = getComputedStyle(m)
  const first = m.querySelector('.select-option')
  const selected = m.querySelector('.select-option.selected')
  return {
    options: m.querySelectorAll('.select-option').length,
    labels: [...m.querySelectorAll('.select-option')].map((b) => b.textContent.trim()),
    bg: s.backgroundColor,
    radius: s.borderRadius,
    border: s.borderColor,
    boxShadow: s.boxShadow.slice(0, 40),
    selectedLabel: selected?.textContent.trim(),
    selectedColor: selected ? getComputedStyle(selected).color : null,
    selectedBg: selected ? getComputedStyle(selected).backgroundColor : null,
    visible: m.getBoundingClientRect().height > 0,
    inViewport: m.getBoundingClientRect().bottom <= window.innerHeight,
    firstHit: (() => {
      const r = first.getBoundingClientRect()
      const el = document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
      return Boolean(el && m.contains(el))
    })(),
  }
})
console.log('MENU', JSON.stringify(menu))
await page.screenshot({ path: `${OUT}/rules-menu.png`, fullPage: false })

await page.locator('.select-option').filter({ hasText: '连续 5 个' }).click()
await page.waitForTimeout(220)
const afterPick = await page.evaluate(() => ({
  trigger: document.querySelector('.select-trigger')?.textContent.trim().replace(/\s+/g, ' '),
  menuOpen: Boolean(document.querySelector('.select-menu')),
  expanded: document.querySelector('.select-trigger')?.getAttribute('aria-expanded'),
}))
console.log('AFTER_PICK', JSON.stringify(afterPick))

// 高级区应看到 regex 同步成 ^(.)\1{4,}$
await page.locator('.advanced-toggle').click()
await page.waitForTimeout(220)
console.log('REGEX_AFTER_PICK', await page.locator('#noise-regex').inputValue())

// Esc 关闭：看 aria-expanded，别在离场过渡还没走完时数 DOM
await page.locator('.select-trigger').click()
await page.waitForTimeout(180)
await page.keyboard.press('Escape')
await page.waitForTimeout(450)
console.log('ESC_CLOSES', await page.locator('.select-trigger').getAttribute('aria-expanded') === 'false')
// 点外部关闭
await page.locator('.select-trigger').click()
await page.waitForTimeout(180)
await page.locator('.intro').click({ position: { x: 10, y: 8 } })
await page.waitForTimeout(450)
console.log('OUTSIDE_CLOSES', await page.locator('.select-trigger').getAttribute('aria-expanded') === 'false')

/* ── 3. 草稿跨页共享，子页保存不冲掉主页面改动 ────────────── */
await page.locator('.rule-link').first().click()
await page.waitForURL('**/rules/words')
await page.waitForTimeout(350)
const wordsPage = await page.evaluate(() => ({
  title: document.querySelector('.page-title')?.textContent.trim(),
  backHref: document.querySelector('.back')?.getAttribute('href'),
  value: document.querySelector('#noise-words')?.value.slice(0, 30),
  quickChips: document.querySelectorAll('.chip-row .chip').length,
  factory: document.querySelector('.factory')?.textContent.trim().slice(0, 24),
}))
console.log('WORDS_PAGE', JSON.stringify(wordsPage))
await page.screenshot({ path: `${OUT}/rules-words.png`, fullPage: true })

// 切一个常用词（"绝了" 已在词表里 → 点一下应移除）
await page.locator('.chip-row .chip').filter({ hasText: '绝了' }).click()
await page.waitForTimeout(120)
console.log('WORD_REMOVED', !(await page.locator('#noise-words').inputValue()).includes('绝了'))
await page.locator('.chip-row .chip').filter({ hasText: '绝了' }).click()
await page.waitForTimeout(120)
console.log('WORD_RESTORED', (await page.locator('#noise-words').inputValue()).includes('绝了'))

// 在这个子页保存，PUT 必须带上主页面刚改的 regex=5
await page.locator('.save').click()
await page.waitForTimeout(500)
console.log('PUT_BODY', JSON.stringify(puts.at(-1)))

// 页头滚动后会收成细栏、返回钮不可点，用浏览器返回做 SPA 内导航
await page.goBack()
await page.waitForURL('**/rules')
await page.waitForTimeout(400)
console.log('DRAFT_KEPT_ON_RETURN', (await page.locator('.select-trigger').textContent()).includes('连续 5 个'))

/* ── 4. 保留内容子页 ─────────────────────────────────────── */
await page.locator('.rule-link').nth(1).click()
await page.waitForURL('**/rules/keep')
await page.waitForTimeout(350)
const keepPage = await page.evaluate(() => ({
  title: document.querySelector('.page-title')?.textContent.trim(),
  groups: [...document.querySelectorAll('.chip-row .chip')].map((b) => b.textContent.trim()),
  pressed: document.querySelectorAll('.chip[aria-pressed="true"]').length,
  hasAdvanced: Boolean(document.querySelector('.advanced-toggle')),
}))
console.log('KEEP_PAGE', JSON.stringify(keepPage))
await page.screenshot({ path: `${OUT}/rules-keep.png`, fullPage: true })

await page.locator('.chip-input').fill('值日')
await page.locator('.chip-add-btn').click()
await page.waitForTimeout(150)
console.log('CUSTOM_WORD_ADDED', await page.locator('.chip-custom').count())
await page.locator('.advanced-toggle').click()
await page.waitForTimeout(220)
console.log('KEEP_REGEX_TAIL', (await page.locator('#noise-keep').inputValue()).slice(-8))
await page.locator('.actions .btn').last().click()
await page.waitForTimeout(450)
console.log('PUT_KEEP_TAIL', puts.at(-1)?.keepRegex?.slice(-8))

await page.goBack()
await page.waitForURL('**/rules')
await page.waitForTimeout(400)
console.log('KEEP_SUMMARY', await page.locator('.rule-link-hint').nth(1).textContent())

// 360dp 不横向溢出
await page.setViewportSize({ width: 360, height: 780 })
for (const p of ['/rules', '/rules/words', '/rules/keep']) {
  await visit(p)
  const w = await page.evaluate(() => ({ scroll: document.documentElement.scrollWidth, inner: window.innerWidth }))
  if (w.scroll > w.inner) console.log('OVERFLOW', p, JSON.stringify(w))
}
await page.setViewportSize({ width: 390, height: 844 })

/* ── 5. 记忆页自动跳转到 QQ 号输入框 ─────────────────────── */
const ctx2 = await makeContext(browser, settingsNoOwner)
const p2 = await ctx2.newPage()
p2.on('console', (m) => { if (m.type() === 'error') errors.push('ctx2: ' + m.text()) })
p2.on('pageerror', (e) => errors.push('ctx2 pageerror: ' + e.message))
await p2.goto(BASE + '/memory', { waitUntil: 'networkidle' })
await p2.waitForTimeout(900)
const jump = await p2.evaluate(() => ({
  url: location.pathname + location.hash,
  activeId: document.activeElement?.id,
  targetHighlight: Boolean(document.querySelector('#owner-qq.is-target')),
  inputInView: (() => {
    const el = document.getElementById('owner-qq')
    if (!el) return false
    const r = el.getBoundingClientRect()
    return r.top >= 0 && r.bottom <= window.innerHeight
  })(),
  toast: [...document.querySelectorAll('.toast')].map((t) => t.textContent.trim()).slice(0, 2),
}))
console.log('MEMORY_JUMP', JSON.stringify(jump))
await p2.screenshot({ path: `${OUT}/memory-jump.png`, fullPage: false })

// 有 owner 时记忆页正常显示，不跳
const ctx3 = await makeContext(browser, settingsWithOwner)
const p3 = await ctx3.newPage()
await p3.goto(BASE + '/memory', { waitUntil: 'networkidle' })
await p3.waitForTimeout(700)
console.log('MEMORY_WITH_OWNER', await p3.evaluate(() => ({
  path: location.pathname,
  title: document.querySelector('.page-title')?.textContent.trim(),
  hasEmpty: Boolean(document.querySelector('.empty')),
})))

console.log('CONSOLE_ERRORS', JSON.stringify(errors.slice(0, 10)))
await browser.close()
