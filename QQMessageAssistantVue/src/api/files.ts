import { http } from './http'
import type { FileTask, FileTaskStatus } from './types'

export function listFileTasks(params: { senderId?: number; status?: FileTaskStatus } = {}) {
  return http.get<FileTask[]>('/api/file-tasks', params)
}
