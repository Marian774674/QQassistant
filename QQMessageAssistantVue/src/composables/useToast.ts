import { reactive } from 'vue'

export type ToastTone = 'info' | 'success' | 'error'

export interface ToastItem {
  id: number
  message: string
  tone: ToastTone
}

const state = reactive<{ items: ToastItem[] }>({ items: [] })
let seq = 0

function dismiss(id: number) {
  const index = state.items.findIndex((item) => item.id === id)
  if (index >= 0) state.items.splice(index, 1)
}

function push(message: string, tone: ToastTone = 'info') {
  const id = ++seq
  state.items.push({ id, message, tone })
  window.setTimeout(() => dismiss(id), 4200)
  return id
}

export function useToast() {
  return {
    items: state.items,
    push,
    dismiss,
    success: (message: string) => push(message, 'success'),
    error: (message: string) => push(message, 'error'),
  }
}
