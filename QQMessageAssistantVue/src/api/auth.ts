import { http } from './http'

export interface CaptchaResponse {
  captchaId: string
  image: string
}

export interface LoginResponse {
  token: string
  username: string
}

export interface LoginPayload {
  username: string
  password: string
  captchaId: string
  captchaCode: string
}

export interface QqStatus {
  bound: boolean
  maskedQq: string
}

export interface QqCodeResult {
  /** false = 还在冷却期，没发新码，retryAfterSeconds 是剩余秒数 */
  sent: boolean
  expiresInSeconds: number
  retryAfterSeconds: number
}

export function getCaptcha() {
  return http.get<CaptchaResponse>('/api/auth/captcha')
}

/** QQ 授权登录：机器人私聊推一次性验证码，不用记密码。 */
export function getQqStatus() {
  return http.get<QqStatus>('/api/auth/qq/status')
}

export function sendQqCode(qq: string) {
  return http.post<QqCodeResult>('/api/auth/qq/code', { qq })
}

export function verifyQqCode(qq: string, code: string) {
  return http.post<LoginResponse>('/api/auth/qq/verify', { qq, code })
}

export function login(payload: LoginPayload) {
  return http.post<LoginResponse>('/api/auth/login', payload)
}

export function logout() {
  return http.post<void>('/api/auth/logout')
}

export function me() {
  return http.get<{ username: string }>('/api/auth/me')
}

export function changePassword(oldPassword: string, newPassword: string) {
  return http.post<void>('/api/auth/password', { oldPassword, newPassword })
}
