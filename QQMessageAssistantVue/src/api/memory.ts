import { http } from './http'
import type { ProfileFact, StyleProfile } from './types'

export function listFacts(senderId?: number) {
  return http.get<ProfileFact[]>('/api/profile/facts', senderId ? { senderId } : undefined)
}

export function createFact(body: { senderId: number; factKey: string; factValue: string }) {
  return http.post<ProfileFact>('/api/profile/facts', body)
}

export function updateFact(id: number, body: { factKey?: string; factValue?: string }) {
  return http.put<ProfileFact>(`/api/profile/facts/${id}`, body)
}

export function deleteFact(id: number) {
  return http.del<void>(`/api/profile/facts/${id}`)
}

export function getStyleProfile(senderId: number) {
  return http.get<StyleProfile>(`/api/style-profile/${senderId}`)
}

export function updateStyleProfile(body: { senderId: number; profilePrompt: string }) {
  return http.post<StyleProfile>('/api/style-profile/update', body)
}
