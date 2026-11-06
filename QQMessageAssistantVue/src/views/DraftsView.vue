<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DraftItem from '@/components/DraftItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Segmented from '@/components/Segmented.vue'
import Skeleton from '@/components/Skeleton.vue'
import { listDrafts } from '@/api/drafts'
import type { Draft } from '@/api/types'
import { useDraftStore } from '@/composables/useDraftStore'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'

type Filter = 'pending' | 'handled'
type TypeFilter = 'all' | 'reply' | 'naming' | 'other'

const draftStore = useDraftStore()
const toast = useToast()

const drafts = ref<Draft[]>([])
const loading = ref(true)
const error = ref('')
const filter = ref<Filter>('pending')
/** 回复草稿和命名草稿分开看：命名只是「给这个号起名」，不是要发出去的话 */
const typeFilter = ref<TypeFilter>('all')

/** silent = 下拉刷新：保留当前列表，不闪骨架屏 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    drafts.value = await listDrafts()
  } catch (e) {
    const message = e instanceof Error ? e.message : '草稿读取失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

onMounted(load)

const pending = computed(() => drafts.value.filter((draft) => draft.status === 'PENDING'))
const handled = computed(() =>
  drafts.value
    .filter((draft) => draft.status !== 'PENDING')
    .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
)

function matchesType(draft: Draft): boolean {
  if (typeFilter.value === 'all') return true
  if (typeFilter.value === 'reply') return draft.type === 'REPLY'
  if (typeFilter.value === 'naming') return draft.type === 'NAMING'
  return draft.type !== 'REPLY' && draft.type !== 'NAMING'
}

const base = computed(() => (filter.value === 'pending' ? pending.value : handled.value))
const shown = computed(() => base.value.filter(matchesType))

const tabs = computed(() => [
  { key: 'pending' as const, label: '待确认', count: pending.value.length },
  { key: 'handled' as const, label: '已处理', count: handled.value.length },
])

const typeTabs = computed(() => [
  { key: 'all' as const, label: '全部', count: base.value.length },
  {
    key: 'reply' as const,
    label: '回复',
    count: base.value.filter((draft) => draft.type === 'REPLY').length,
  },
  {
    key: 'naming' as const,
    label: '命名',
    count: base.value.filter((draft) => draft.type === 'NAMING').length,
  },
  {
    key: 'other' as const,
    label: '其他',
    count: base.value.filter((draft) => draft.type !== 'REPLY' && draft.type !== 'NAMING').length,
  },
])

async function onResolved(id: number) {
  draftStore.dropPendingDraft(id)
  await load()
}

useRefresh(() => load(true))
</script>

<template>
  <div class="page">
    <PageHead
      eyebrow="草稿中心"
      title="Review"
      sub="机器人拿不准的回复、文件、备忘和事实先停在这里，确认之后才真的发到 QQ 或写进记录。"
    />

    <div class="filters">
      <Segmented v-model="filter" :items="tabs" label="按处理状态筛选" />
      <Segmented v-model="typeFilter" :items="typeTabs" label="按草稿类型筛选" />
    </div>

    <div class="body">
      <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load" />

      <Skeleton v-else-if="loading" :rows="3" />

      <TransitionGroup v-else-if="shown.length" name="list" tag="ul" class="list" appear>
        <li v-for="draft in shown" :key="draft.id">
          <DraftItem :draft="draft" @resolved="onResolved" />
        </li>
      </TransitionGroup>

      <EmptyState
        v-else-if="filter === 'pending'"
        title="都处理完了"
        :hint="
          typeFilter === 'naming'
            ? '新出现的群或私聊会在这里请你起个名字。'
            : '等 QQ 里出现拿不准的回复或记录时，会自动出现在这里。'
        "
      />
      <EmptyState
        v-else
        title="还没有处理记录"
        hint="确认或拒绝过的草稿会留在这里，方便回头查。"
      />
    </div>
  </div>
</template>

<style scoped>
.filters {
  display: grid;
  gap: 10px;
}

.body {
  margin-top: 18px;
}

.list {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}
</style>
