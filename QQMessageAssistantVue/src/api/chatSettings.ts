import { http } from './http'

export type ChatKind = 'GROUP' | 'PRIVATE'

export interface ChatSetting {
  id?: number | null
  chatId: number
  kind: ChatKind
  chatName: string | null
  autoReplyEnabled: boolean
  digestEnabled: boolean
}

/** 已配置的会话 + 日志里见过但还没配置的群（开关默认关） */
export function listChatSettings() {
  return http.get<ChatSetting[]>('/api/chat-settings')
}

/** 部分更新：只传要改的那个开关，另一个不传就不会被覆盖 */
export function putChatSetting(body: {
  chatId: number
  kind: ChatKind
  chatName?: string | null
  autoReplyEnabled?: boolean
  digestEnabled?: boolean
}) {
  return http.put<ChatSetting>('/api/chat-settings', body)
}
