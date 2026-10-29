import { http } from './http'
import type { InterceptRule, InterceptRuleBody } from './types'

export function listRules() {
  return http.get<InterceptRule[]>('/api/intercept-rules')
}

export function createRule(body: InterceptRuleBody) {
  return http.post<InterceptRule>('/api/intercept-rules', body)
}

export function updateRule(id: number, body: Partial<InterceptRuleBody>) {
  return http.put<InterceptRule>(`/api/intercept-rules/${id}`, body)
}

export function deleteRule(id: number) {
  return http.del<void>(`/api/intercept-rules/${id}`)
}
