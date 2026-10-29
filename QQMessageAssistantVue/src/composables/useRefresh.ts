import { onBeforeUnmount, reactive } from 'vue'

/**
 * 下拉刷新：页面注册自己的刷新动作，手势和指示器统一由 AppShell 负责。
 */
const state = reactive<{
  handler: (() => Promise<void> | void) | null
}>({ handler: null })

export function useRefresh(handler: () => Promise<void> | void) {
  state.handler = handler
  onBeforeUnmount(() => {
    state.handler = null
  })
}

export function useRefreshState() {
  return state
}

/** Android 有效、iOS 忽略；只给「确认类」动作用 */
export function tapFeedback() {
  if (typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function') {
    navigator.vibrate(6)
  }
}
