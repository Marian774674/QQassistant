import {
  BookOpen,
  Coffee,
  HeartPulse,
  Inbox,
  Layers,
  Sparkles,
  Users,
  Wallet,
} from 'lucide-vue-next'
import type { Component } from 'vue'
import type { Category, DraftType } from '@/api/types'

export type Tone = 'neutral' | 'ok' | 'warn' | 'danger' | 'quiet'

export interface StatusMeta {
  label: string
  tone: Tone
}

/* ── 重要性 ───────────────────────────────────────────────── */

const IMPORTANCE: Record<number, string> = {
  5: '紧要',
  4: '重要',
  3: '一般',
  2: '轻量',
  1: '存档',
}

export function importanceLabel(importance: number): string {
  return IMPORTANCE[importance] ?? '一般'
}

/* ── 备忘状态 ─────────────────────────────────────────────── */

export function memoStatusMeta(status: string): StatusMeta {
  switch (status) {
    case 'ACTIVE':
      return { label: '待提醒', tone: 'neutral' }
    case 'REMINDED':
      return { label: '已提醒', tone: 'neutral' }
    case 'AWAITING_REPLY':
      return { label: '等收尾', tone: 'warn' }
    case 'OVERDUE':
      return { label: '已逾期', tone: 'danger' }
    case 'DONE':
      return { label: '已完成', tone: 'quiet' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'quiet' }
    default:
      return { label: status, tone: 'quiet' }
  }
}

/* ── 草稿 ─────────────────────────────────────────────────── */

const DRAFT_TYPE: Record<DraftType, string> = {
  REPLY: '回复',
  FILE: '文件',
  MEMO: '备忘',
  FACT: '事实',
}

export function draftTypeLabel(type: string): string {
  return DRAFT_TYPE[type as DraftType] ?? type
}

const DRAFT_ICON: Record<DraftType, Component> = {
  REPLY: Inbox,
  FILE: Layers,
  MEMO: BookOpen,
  FACT: Sparkles,
}

export function draftTypeIcon(type: string): Component {
  return DRAFT_ICON[type as DraftType] ?? Inbox
}

export function draftStatusMeta(status: string): StatusMeta {
  switch (status) {
    case 'PENDING':
      return { label: '待确认', tone: 'warn' }
    case 'CONFIRMED':
      return { label: '已确认', tone: 'quiet' }
    case 'REJECTED':
      return { label: '已拒绝', tone: 'quiet' }
    default:
      return { label: status, tone: 'quiet' }
  }
}

/* ── 文件任务 ─────────────────────────────────────────────── */

export function fileStatusMeta(status: string): StatusMeta {
  switch (status) {
    case 'PENDING':
      return { label: '排队中', tone: 'neutral' }
    case 'PROCESSING':
      return { label: '处理中', tone: 'warn' }
    case 'NEED_INPUT':
      return { label: '等你补充', tone: 'warn' }
    case 'AWAITING_CONFIRM':
      return { label: '等你确认', tone: 'warn' }
    case 'SENT':
      return { label: '已发送', tone: 'ok' }
    case 'FAILED':
      return { label: '失败', tone: 'danger' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'quiet' }
    default:
      return { label: status, tone: 'quiet' }
  }
}

/** 文件任务在界面上的四组归类 */
export const FILE_GROUPS = [
  { key: 'open', label: '进行中', statuses: ['PENDING', 'PROCESSING'] },
  { key: 'wait', label: '等你处理', statuses: ['NEED_INPUT', 'AWAITING_CONFIRM'] },
  { key: 'done', label: '已完成', statuses: ['SENT'] },
  { key: 'failed', label: '没成功', statuses: ['FAILED', 'CANCELLED'] },
] as const

/* ── 提醒 ─────────────────────────────────────────────────── */

export function reminderStatusMeta(status: string): StatusMeta {
  switch (status) {
    case 'PENDING':
      return { label: '待发送', tone: 'neutral' }
    case 'SENT':
      return { label: '已发送', tone: 'ok' }
    case 'CANCELLED':
      return { label: '已取消', tone: 'quiet' }
    default:
      return { label: status, tone: 'quiet' }
  }
}

/* ── 分类图标 ─────────────────────────────────────────────── */

const CATEGORY_ICON: Record<Category, Component> = {
  学业: BookOpen,
  社交: Users,
  健康: HeartPulse,
  财务: Wallet,
  日常: Coffee,
  其他: Layers,
}

export function categoryIcon(category: string | null): Component {
  if (!category) return Layers
  return CATEGORY_ICON[category as Category] ?? Layers
}
