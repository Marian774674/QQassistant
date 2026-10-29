import { http } from './http'
import type { Draft, DraftStatus, DraftType } from './types'

export function listDrafts(params: { type?: DraftType; status?: DraftStatus } = {}) {
  return http.get<Draft[]>('/api/drafts', params)
}

export function confirmDraft(id: number) {
  return http.post<Draft>(`/api/drafts/${id}/confirm`)
}

export function rejectDraft(id: number) {
  return http.post<Draft>(`/api/drafts/${id}/reject`)
}
