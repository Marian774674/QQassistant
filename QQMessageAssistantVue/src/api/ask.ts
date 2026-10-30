import { http } from './http'

/** 一条能点回原文的证据；id 带类型前缀（msg: / note: / memo: / file:） */
export interface Evidence {
  type: 'msg' | 'note' | 'memo' | 'file'
  id: string
  label: string
  content: string
}

export interface ClarifyCandidate {
  senderId: number
  alias: string
  relation: string | null
  /** 让用户认得出的线索，例如「QQ 1753800374」 */
  hint: string
}

/** 认不出人时的反问：alias 是模型抽出来的称呼，candidates 为空表示库里根本没这个人 */
export interface ClarifyState {
  alias: string
  candidates: ClarifyCandidate[]
}

export interface AskResponse {
  answer: string
  /** true=模型引用了候选集之外的东西，结论作废，只列证据 */
  degraded: boolean
  evidence: Evidence[]
  needClarify: ClarifyState | null
}

export interface ClarifyAnswer {
  alias: string
  senderId: number
  /** 关系或用户原话，记成 contact.relation */
  from?: string
}

/** 认过人之后带 clarify 重发同一个问题 */
export function ask(question: string, clarify?: ClarifyAnswer) {
  return http.post<AskResponse>('/api/ask', clarify ? { question, clarify } : { question })
}

export interface Contact {
  id: number
  senderId: number
  alias: string
  relation: string | null
  importance: number
  note: string | null
  source: 'AUTO' | 'USER'
  createdAt?: string
  updatedAt: string
}

export function listContacts(senderId?: number) {
  return http.get<Contact[]>('/api/contacts', senderId ? { senderId } : undefined)
}

export function createContact(body: { senderId: number; alias: string; relation?: string }) {
  return http.post<Contact>('/api/contacts', body)
}

export function deleteContact(id: number) {
  return http.del<number>(`/api/contacts/${id}`)
}
