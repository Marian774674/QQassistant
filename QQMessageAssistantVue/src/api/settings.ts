import { http } from './http'

export function getSettings() {
  return http.get<Record<string, string>>('/api/settings')
}

export function putSettings(kv: Record<string, string>) {
  return http.put<number>('/api/settings', kv)
}
