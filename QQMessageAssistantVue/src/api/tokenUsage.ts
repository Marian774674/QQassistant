import { http } from './http'

/** 分组桶：byTask / bySource / byDay 共用一套 { 名称, 次数, tokens } 结构 */
export interface UsageBucket {
  task?: string
  source?: string
  day?: string
  calls: number
  tokens: number
}

/**
 * 「花在哪个会话」饼图的一扇。
 * chat = 某个群；unassigned = 账本里没有 chat_id 的调用；other = Top6 之外合并。
 * 后两者 chatId 为 null，用中性色，别和真实群聊混在一起。
 */
export type ChatBucketKind = 'chat' | 'unassigned' | 'other'

export interface TokenUsageChatBucket {
  chatId: number | null
  label: string
  kind: ChatBucketKind
  calls: number
  tokens: number
}

export interface TokenUsageSummary {
  days: number
  since: string
  calls: number
  promptTokens: number
  completionTokens: number
  totalTokens: number
  byTask: UsageBucket[]
  bySource: UsageBucket[]
  byDay: UsageBucket[]
  /** 已在服务端合并成 Top6 + 其它 + 未归属，前端只负责画 */
  byChat: TokenUsageChatBucket[]
}

export interface TokenUsageRow {
  id: number
  source: string
  task: string
  model: string | null
  promptTokens: number
  completionTokens: number
  totalTokens: number
  chatId: number | null
  createdAt: string
}

/** 账本只读：网页侧没有写、改、删接口，避免「看着不顺眼改数字」 */
export function getTokenUsageSummary(days = 7) {
  return http.get<TokenUsageSummary>('/api/token-usage/summary', { days })
}

export function getRecentTokenUsage(days = 7, limit = 50) {
  return http.get<TokenUsageRow[]>('/api/token-usage/recent', { days, limit })
}
