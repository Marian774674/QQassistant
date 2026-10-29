<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DraftItem from '@/components/DraftItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import Segmented from '@/components/Segmented.vue'
import Skeleton from '@/components/Skeleton.vue'
import { listDrafts } from '@/api/drafts'
import type { Draft } from '@/api/types'
import { useDraftStore } from '@/composables/useDraftStore'
import { useRefresh } from '@/composables/useRefresh'

type Filter = 'pending' | 'handled'

const draftStore = useDraftStore()

const drafts = ref<Draft[]>([])
const loading = ref(true)
const error = ref('')
const filter = ref<Filter>('pending')

async function load() {
  loading.value = true
  error.value = ''
  try {
    drafts.value = await listDrafts()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '草稿读取失败'
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

const shown = computed(() => (filter.value === 'pending' ? pending.value : handled.value))

const tabs = computed(() => [
  { key: 'pending' as const, label: '待确认', count: pending.value.length },
  { key: 'handled' as const, label: '已处理', count: handled.value.length },
])

async function onResolved(id: number) {
  draftStore.dropPendingDraft(id)
  await load()
}

useRefresh(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="eyebrow">草稿中心</p>
      <h1 class="page-title">等你过目</h1>
      <p class="page-sub">机器人拿不准的回复、文件、备忘和事实先停在这里，确认之后才真的发到 QQ 或写进记录。</p>
    </header>

    <Segmented v-model="filter" :items="tabs" label="按处理状态筛选" />

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
        hint="等 QQ 里出现拿不准的回复或记录时，会自动出现在这里。"
      />
      <EmptyState v-else title="还没有处理记录" hint="确认或拒绝过的草稿会留在这里，方便回头查。" />
    </div>
  </div>
</template>

<style scoped>
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
