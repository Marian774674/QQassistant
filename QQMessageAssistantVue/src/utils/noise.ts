/**
 * 噪音过滤：后端只认四个字符串字段，界面却要给人看得懂的开关和标签。
 * 这里是两者之间唯一的翻译层——/rules 主页面和它的两个二级页都只经过它读写，
 * 不各写一份正则解析，免得同一份配置在三处被解释成三种样子。
 */

/** 「连续 N 个相同字符」下拉里可选的档位 */
export const REPEAT_OPTIONS = [3, 4, 5, 6] as const
const REPEAT_LITERAL = /^\^\(\.\)\\1\{(\d+),\}\$$/

/** 3+ 表示「连续 N 个相同字符」；0 = 停用；-1 = 认不出来的自定义表达式 */
export function parseRepeat(raw: string): number {
  const value = raw.trim()
  if (!value) return 0
  const matched = value.match(REPEAT_LITERAL)
  return matched ? Number(matched[1]) + 1 : -1
}

export function repeatRegex(n: number): string {
  return n === 0 ? '' : `^(.)\\1{${n - 1},}$`
}

export function repeatOptionLabel(n: number): string {
  return `连续 ${n} 个及以上相同字符${n === 3 ? '（默认）' : ''}`
}

/** 一组 = 界面上一颗能按的标签；tokens 与后端 DEFAULT_KEEP_REGEX 逐字对应 */
export const KEEP_GROUPS = [
  { id: 'at', label: '@我 / 被点名', tokens: ['@', '＠'] },
  { id: 'question', label: '问号', tokens: ['\\?', '？'] },
  { id: 'file', label: '文件 / 图片', tokens: ['文件', '图片', '截图'] },
  { id: 'task', label: '作业 / 考试 / 班会', tokens: ['作业', '考试', '班会', '开会'] },
  { id: 'deadline', label: '截止 / 报名 / 交表', tokens: ['截止', '报名', '交表'] },
  {
    id: 'time', label: '时间日期',
    tokens: ['明[天日]', '今[天日]', '周[一二三四五六日天]',
      '\\d{1,2}[:：]\\d{2}', '\\d{1,2}月\\d{1,2}[日号]'],
  },
  { id: 'profile', label: '姓名 / 学号 / 电话', tokens: ['生日', '学号', '姓名', '专业', '班级', '电话', '地址'] },
] as const

export const REGEX_META = /[\\\[\](){}.*+?^$|]/

/** 按 | 切，但别把 (a|b)、[x|y] 里的竖线切开；反斜杠转义要原样留着 */
export function splitTokens(raw: string): string[] {
  const out: string[] = []
  let buf = ''
  let depth = 0
  let inClass = false
  let escaped = false
  for (const ch of raw) {
    if (escaped) { buf += ch; escaped = false; continue }
    if (ch === '\\') { buf += ch; escaped = true; continue }
    if (ch === '[') { inClass = true; buf += ch; continue }
    if (ch === ']') { inClass = false; buf += ch; continue }
    if (!inClass && ch === '(') { depth += 1; buf += ch; continue }
    if (!inClass && ch === ')') { depth -= 1; buf += ch; continue }
    if (!inClass && depth === 0 && ch === '|') {
      if (buf.trim()) out.push(buf.trim())
      buf = ''
      continue
    }
    buf += ch
  }
  if (buf.trim()) out.push(buf.trim())
  return out
}

export function escapeRegex(word: string) {
  return word.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

/** 一个分组只有所有 token 都在，才算「已开启」 */
export function activeKeepGroups(tokens: string[]) {
  return KEEP_GROUPS.filter((group) =>
    group.tokens.every((token) => tokens.includes(token)),
  )
}

/**
 * 重排但绝不丢片段：完整分组按定义顺序在前，其余片段按原顺序跟在后面。
 * 用户手写过的复杂正则既不会被吞掉，也不会被改写成另一种写法。
 */
export function reorderKeepTokens(tokens: string[]): string {
  const active = activeKeepGroups(tokens)
  const owned = new Set<string>(active.flatMap((group) => [...group.tokens]))
  return [
    ...active.flatMap((group) => [...group.tokens]),
    ...tokens.filter((token) => !owned.has(token)),
  ].join('|')
}

/** 英文逗号 / 中文逗号都能当分隔符，空段不算词 */
export function countKeywords(raw: string): number {
  return raw.split(/[,，]/).map((word) => word.trim()).filter(Boolean).length
}
