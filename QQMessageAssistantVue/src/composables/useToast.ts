import { reactive } from 'vue'

export type ToastTone = 'info' | 'success' | 'error'

export interface ToastAction {
  label: string
  run: () => void
}

export interface ToastItem {
  id: number
  message: string
  tone: ToastTone
  action?: ToastAction
}

const state = reactive<{ items: ToastItem[] }>({ items: [] })
const MAX_ITEMS = 3
let seq = 0

function dismiss(id: number) {
  const index = state.items.findIndex((item) => item.id === id)
  if (index >= 0) state.items.splice(index, 1)
}

function push(message: string, tone: ToastTone = 'info', action?: ToastAction, duration = 3200) {
  const id = ++seq
  state.items.push({ id, message, tone, action })
  // 只留最近几条，避免连续操作时堆成一片
  while (state.items.length > MAX_ITEMS) {
    state.items.shift()
  }
  window.setTimeout(() => dismiss(id), duration)
  return id
}

export function useToast() {
  return {
    items: state.items,
    push,
    dismiss,
    success: (message: string) => push(message, 'success'),
    error: (message: string) => push(message, 'error'),
    /** 带一个行内操作按钮，比如「撤销」；这类提示留久一点 */
    action: (message: string, action: ToastAction, duration = 5000) =>
      push(message, 'success', action, duration),
  }
}
