import { http } from './http'

/**
 * 噪音过滤配置。原来的「拦截规则」（命中关键词 → 绕过模型回模板）已整体下线，
 * /rules 这个位置现在由噪音过滤接管，规则存 Spring 的 app_setting，
 * 消息入口、整理、夜间归纳共用同一条判定链。
 */
export interface NoiseFilterSettings {
  enabled: boolean
  keywords: string
  regex: string
  keepRegex: string
  defaultKeywords: string
  defaultRegex: string
  defaultKeepRegex: string
}

export interface NoisePreviewItem {
  text: string
  kept: boolean
  reason: string | null
}

export function getNoiseFilter() {
  return http.get<NoiseFilterSettings>('/api/settings/noise-filter')
}

export function putNoiseFilter(body: {
  enabled: boolean
  keywords: string
  regex: string
  keepRegex: string
}) {
  return http.put<NoiseFilterSettings>('/api/settings/noise-filter', body)
}

/** draft 传当前编辑框里的值：试跑按「还没保存的规则」判定，不写库 */
export function previewNoiseFilter(
  texts: string[],
  draft?: { enabled: boolean; keywords: string; regex: string; keepRegex: string },
) {
  return http.post<{ enabled: boolean; items: NoisePreviewItem[] }>(
    '/api/settings/noise-filter/preview',
    { texts, ...(draft ?? {}) },
  )
}
