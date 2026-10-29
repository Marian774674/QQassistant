import { onBeforeUnmount, reactive } from 'vue'

/**
 * 右下角悬浮按钮只有部分页面需要（备忘页的「新建」）。
 * 按钮本体由 AppShell 渲染，页面只负责登记一个动作，避免每页各画一个导致位置不一致。
 */
const state = reactive<{
  visible: boolean
  label: string
  action: (() => void) | null
}>({
  visible: false,
  label: '新建',
  action: null,
})

export function useFabState() {
  return state
}

/** 页面挂载时登记，卸载时自动收回 */
export function useFab(label: string, action: () => void) {
  state.visible = true
  state.label = label
  state.action = action

  onBeforeUnmount(() => {
    state.visible = false
    state.action = null
  })
}
