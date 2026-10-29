/** 与 Spring Boot 的统一响应体 `{code,message,data}` 对应 */
export interface ApiResult<T> {
  code: number
  message: string
  data: T
}

export type MemoStatus =
  | 'ACTIVE'
  | 'REMINDED'
  | 'AWAITING_REPLY'
  | 'DONE'
  | 'CANCELLED'
  | 'OVERDUE'

export type Category = '学业' | '社交' | '健康' | '财务' | '日常' | '其他'

export const CATEGORIES: Category[] = ['学业', '社交', '健康', '财务', '日常', '其他']

export interface Memo {
  id: number
  senderId: number
  chatId: number | null
  title: string
  content: string | null
  importance: number
  category: Category | null
  dueTime: string | null
  status: MemoStatus
  sourceMsgId: number | null
  createdAt: string
  updatedAt: string
}

export type ReminderStatus = 'PENDING' | 'SENT' | 'CANCELLED'

export interface MemoReminder {
  id: number
  memoId: number
  offsetLabel: string
  remindAt: string
  status: ReminderStatus
  sentAt: string | null
  createdAt: string
}

export interface MemoDraftPayload {
  title?: string
  content?: string
  importance?: number
  category?: string
  dueTime?: string
}

export type DraftType = 'REPLY' | 'FILE' | 'MEMO' | 'FACT'
export type DraftStatus = 'PENDING' | 'CONFIRMED' | 'REJECTED'

export interface Draft {
  id: number
  type: DraftType
  senderId: number
  chatId: number | null
  title: string | null
  status: DraftStatus
  payload: Record<string, unknown> | null
  confirmedAt: string | null
  createdAt: string
  updatedAt: string
}

export interface ProfileFact {
  id: number
  senderId: number
  factKey: string
  factValue: string
  confidence: number
  source: string | null
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

export interface MemoCreateBody {
  senderId?: number
  chatId?: number | null
  title: string
  content?: string | null
  importance: number
  category?: Category | null
  dueTime?: string | null
}

export interface MemoPatchBody {
  title?: string
  content?: string
  importance?: number
  category?: Category
  dueTime?: string | null
  clearDueTime?: boolean
  status?: 'DONE' | 'CANCELLED'
}
