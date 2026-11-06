import { http } from './http'
import type { ChatKind } from './chatSettings'

export interface ChatAlias {
  id?: number | null
  chatId: number
  kind: ChatKind
  /** 逗号分隔；第一个用于展示 */
  aliases: string
  createdAt?: string | null
  updatedAt?: string | null
}

export function listAliases() {
  return http.get<ChatAlias[]>('/api/chat-aliases')
}

export function putAlias(body: { chatId: number; kind: ChatKind; aliases: string }) {
  return http.put<void>('/api/chat-aliases', body)
}

export function deleteAlias(chatId: number, kind: ChatKind) {
  return http.del<void>(`/api/chat-aliases/${chatId}?kind=${encodeURIComponent(kind)}`)
}
