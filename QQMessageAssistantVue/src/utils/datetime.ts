const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']

/** 后端返回的 LocalDateTime 没有时区（2026-10-28T19:00:00），按本地时间解析 */
export function parseLocal(iso: string | null | undefined): Date | null {
  if (!iso) return null
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? null : date
}

export function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

export function formatTime(iso: string | Date | null | undefined): string {
  const date = typeof iso === 'string' ? parseLocal(iso) : iso
  if (!date) return '--:--'
  return `${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

export function formatMonthDay(date: Date): string {
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

export function formatDateTime(iso: string | null | undefined): string {
  const date = parseLocal(iso)
  if (!date) return '未设置'
  return `${formatMonthDay(date)} ${formatTime(date)}`
}

export function formatFullDate(date: Date): string {
  return `${date.getFullYear()}年${date.getMonth() + 1}月${date.getDate()}日 星期${
    WEEKDAYS[date.getDay()]
  }`
}

export function dayKey(date: Date): string {
  return `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())}`
}

export function isSameDay(a: Date, b: Date): boolean {
  return dayKey(a) === dayKey(b)
}

export function isToday(date: Date): boolean {
  return isSameDay(date, new Date())
}

/** 给 <input type="datetime-local"> 用的值 */
export function toDatetimeLocalValue(iso: string | null | undefined): string {
  const date = parseLocal(iso)
  if (!date) return ''
  return `${dayKey(date)}T${pad2(date.getHours())}:${pad2(date.getMinutes())}`
}

/** datetime-local 的值是本地时间，直接补秒，后端按本地时间存 */
export function fromDatetimeLocalValue(value: string): string | null {
  if (!value) return null
  return value.length === 16 ? `${value}:00` : value
}

/** 距离截止还有多久：用于条目右侧的提醒锚点 */
export function relativeUntil(iso: string | null | undefined): string {
  const date = parseLocal(iso)
  if (!date) return ''
  const diffMs = date.getTime() - Date.now()
  const past = diffMs < 0
  const minutes = Math.round(Math.abs(diffMs) / 60000)

  let text: string
  if (minutes < 1) text = '刚刚'
  else if (minutes < 60) text = `${minutes} 分钟`
  else if (minutes < 60 * 24) {
    const hours = Math.floor(minutes / 60)
    const rest = minutes % 60
    text = rest ? `${hours} 小时 ${rest} 分` : `${hours} 小时`
  } else {
    const days = Math.floor(minutes / (60 * 24))
    const rest = Math.round((minutes - days * 60 * 24) / 60)
    text = rest ? `${days} 天 ${rest} 小时` : `${days} 天`
  }
  return past ? `已过 ${text}` : `还有 ${text}`
}

export interface Tone {
  label: string
  cls: string
  wash: string
}

const IMPORTANCE_TONES: Record<number, Tone> = {
  5: { label: '紧要', cls: 'tone-u5', wash: 'var(--u5-wash)' },
  4: { label: '重要', cls: 'tone-u4', wash: 'var(--u4-wash)' },
  3: { label: '一般', cls: 'tone-u3', wash: 'var(--u3-wash)' },
  2: { label: '轻量', cls: 'tone-low', wash: 'var(--u-low-wash)' },
  1: { label: '存档', cls: 'tone-low', wash: 'var(--u-low-wash)' },
}

export function importanceTone(importance: number): Tone {
  return IMPORTANCE_TONES[importance] ?? IMPORTANCE_TONES[3]
}

export function memoStatusMeta(status: string): { label: string; cls: string } {
  switch (status) {
    case 'ACTIVE':
      return { label: '待提醒', cls: 'status-open' }
    case 'REMINDED':
      return { label: '已提醒', cls: 'status-open' }
    case 'AWAITING_REPLY':
      return { label: '等收尾', cls: 'status-wait' }
    case 'OVERDUE':
      return { label: '已逾期', cls: 'status-late' }
    case 'DONE':
      return { label: '已完成', cls: 'status-done' }
    case 'CANCELLED':
      return { label: '已取消', cls: 'status-done' }
    default:
      return { label: status, cls: 'status-done' }
  }
}

export function draftTypeLabel(type: string): string {
  switch (type) {
    case 'REPLY':
      return '回复'
    case 'FILE':
      return '文件'
    case 'MEMO':
      return '备忘'
    case 'FACT':
      return '事实'
    default:
      return type
  }
}

export function draftStatusLabel(status: string): string {
  switch (status) {
    case 'PENDING':
      return '待处理'
    case 'CONFIRMED':
      return '已确认'
    case 'REJECTED':
      return '已拒绝'
    default:
      return status
  }
}
