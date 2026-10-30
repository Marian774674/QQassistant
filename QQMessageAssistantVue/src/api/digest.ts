import { http } from './http'

export interface DigestItem {
  id: number
  chatId: number
  chatName: string | null
  summary: string
  /** 1–5，越大越该先看 */
  importance: number
  mentionsMe: boolean
  /** 原文证据：message_log.id，逗号分隔 */
  sourceMsgIds: string
  periodEnd: string | null
  createdAt: string
}

/** 上次离开之后的整理结果；不传 since 时后端按最近 24 小时算 */
export function listDigest(since?: string) {
  return http.get<DigestItem[]>('/api/digest', { since })
}

/** 上次「已看过」的时间；没看过返回空串 */
export function getLastSeen() {
  return http.get<string>('/api/digest/last-seen')
}

export function markSeen() {
  return http.post<number>('/api/digest/seen')
}
