import type { ApiResult } from './types'

export class ApiError extends Error {
  readonly code: number

  constructor(message: string, code: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

type Query = Record<string, string | number | boolean | null | undefined>

function withQuery(path: string, query?: Query): string {
  if (!query) return path
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null || value === '') continue
    search.set(key, String(value))
  }
  const qs = search.toString()
  return qs ? `${path}?${qs}` : path
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: {
        Accept: 'application/json',
        'Content-Type': 'application/json',
        ...(init.headers ?? {}),
      },
    })
  } catch {
    throw new ApiError('连不上后端服务，确认 Spring Boot 已在 8080 运行', 0)
  }

  let body: ApiResult<T> | null = null
  try {
    body = (await response.json()) as ApiResult<T>
  } catch {
    body = null
  }

  if (!response.ok || !body || body.code !== 0) {
    throw new ApiError(
      body?.message || `请求失败（HTTP ${response.status}）`,
      body?.code ?? response.status,
    )
  }
  return body.data
}

export const http = {
  get: <T>(path: string, query?: Query) => request<T>(withQuery(path, query)),
  post: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) }),
  put: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PUT', body: body === undefined ? undefined : JSON.stringify(body) }),
  patch: <T>(path: string, body?: unknown) =>
    request<T>(path, { method: 'PATCH', body: body === undefined ? undefined : JSON.stringify(body) }),
  del: <T>(path: string) => request<T>(path, { method: 'DELETE' }),
}
