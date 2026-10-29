import { computed, reactive } from 'vue'
import { listDrafts } from '@/api/drafts'
import type { Draft } from '@/api/types'

/**
 * 待确认草稿的共享缓存。
 * 底部导航的角标、首页条幅、草稿页共用同一份数据，避免每个页面各拉一次全量。
 */
const state = reactive<{
  pending: Draft[]
  loaded: boolean
  loading: boolean
  error: string
}>({
  pending: [],
  loaded: false,
  loading: false,
  error: '',
})

export async function refreshPendingDrafts() {
  if (state.loading) return
  state.loading = true
  state.error = ''
  try {
    state.pending = await listDrafts({ status: 'PENDING' })
    state.loaded = true
  } catch (error) {
    state.error = error instanceof Error ? error.message : '草稿读取失败'
  } finally {
    state.loading = false
  }
}

/** 确认 / 拒绝后从缓存里摘掉，不必重拉 */
export function dropPendingDraft(id: number) {
  state.pending = state.pending.filter((draft) => draft.id !== id)
}

export function useDraftStore() {
  return {
    state,
    pendingCount: computed(() => state.pending.length),
    refreshPendingDrafts,
    dropPendingDraft,
  }
}
