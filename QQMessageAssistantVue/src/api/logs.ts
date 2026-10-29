import { http } from './http'
import type { MessageLog, LogDirection } from './types'

export function listMessageLogs(
  params: { senderId?: number; conversationId?: string; direction?: LogDirection; limit?: number } = {},
) {
  return http.get<MessageLog[]>('/api/message-logs', params)
}
