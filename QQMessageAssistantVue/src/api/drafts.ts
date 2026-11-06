import { http } from './http'
import type { Draft, DraftStatus, DraftType } from './types'

export function listDrafts(params: { type?: DraftType; status?: DraftStatus } = {}) {
  return http.get<Draft[]>('/api/drafts', params)
}

/** 命名草稿在 body 里带 aliases；其他类型不传，行为不变 */
export function confirmDraft(id: number, body?: { aliases?: string }) {
  return http.post<Draft>(`/api/drafts/${id}/confirm`, body)
}

export function rejectDraft(id: number) {
  return http.post<Draft>(`/api/drafts/${id}/reject`)
}
