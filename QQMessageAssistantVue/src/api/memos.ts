import { http } from './http'
import type {
  Memo,
  MemoChangeLog,
  MemoCreateBody,
  MemoPatchBody,
  MemoReminder,
  MemoStatus,
} from './types'

export function listMemos(params: { status?: MemoStatus; senderId?: number } = {}) {
  return http.get<Memo[]>('/api/memos', params)
}

export function getMemo(id: number) {
  return http.get<Memo>(`/api/memos/${id}`)
}

export function listReminders(id: number) {
  return http.get<MemoReminder[]>(`/api/memos/${id}/reminders`)
}

export function listMemoHistory(id: number) {
  return http.get<MemoChangeLog[]>(`/api/memos/${id}/history`)
}

export function createMemo(body: MemoCreateBody) {
  return http.post<Memo>('/api/memos', body)
}

export function patchMemo(id: number, body: MemoPatchBody) {
  return http.patch<Memo>(`/api/memos/${id}`, body)
}
