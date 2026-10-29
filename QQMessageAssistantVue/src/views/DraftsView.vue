<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import DraftItem from '@/components/DraftItem.vue'
import EmptyState from '@/components/EmptyState.vue'
import { listDrafts } from '@/api/drafts'
import type { Draft } from '@/api/types'

type Filter = 'pending' | 'handled'

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

function onResolved() {
  void load()
}
</script>

<template>
  <div class="page">
    <header class="head">
      <h1 class="display page-title">草稿中心</h1>
      <p class="page-sub">
        机器人拿不准的回复、文件、备忘和事实都先停在这里。确认之后才会真的发到 QQ 或写进记录。
      </p>
    </header>

    <div class="filters" role="group" aria-label="按处理状态筛选">
      <button
        type="button"
        class="filter"
        :class="{ active: filter === 'pending' }"
        :aria-pressed="filter === 'pending'"
        @click="filter = 'pending'"
      >
        待确认
        <span class="count num">{{ pending.length }}</span>
      </button>
      <button
        type="button"
        class="filter"
        :class="{ active: filter === 'handled' }"
        :aria-pressed="filter === 'handled'"
        @click="filter = 'handled'"
      >
        已处理
        <span class="count num">{{ handled.length }}</span>
      </button>
    </div>

    <p v-if="error" class="notice" role="alert">
      <span>{{ error }}</span>
      <button type="button" class="btn btn-sm" @click="load">重试</button>
    </p>

    <p v-else-if="loading" class="faint loading">正在读取…</p>

    <ul v-else-if="shown.length" class="list">
      <li v-for="draft in shown" :key="draft.id">
        <DraftItem :draft="draft" @resolved="onResolved" />
      </li>
    </ul>

    <EmptyState
      v-else-if="filter === 'pending'"
      title="没有待确认的草稿"
      hint="一切都已处理。等 QQ 里出现拿不准的回复或记录时，会自动出现在这里。"
    />
    <EmptyState
      v-else
      title="还没有处理记录"
      hint="确认或拒绝过的草稿会留在这里，方便回头查。"
    />
  </div>
</template>

<style scoped>
.head {
  margin-bottom: 22px;
}

.page-title {
  font-size: 34px;
  line-height: 1.15;
}

.page-sub {
  max-width: 62ch;
  margin-top: 6px;
  color: var(--ink-soft);
  font-size: 13.5px;
  line-height: 1.65;
}

.filters {
  display: flex;
  gap: 4px;
  margin-bottom: 20px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--line);
}

.filter {
  display: inline-flex;
  align-items: baseline;
  gap: 7px;
  min-height: 40px;
  padding: 0 13px;
  border: 1px solid transparent;
  border-radius: 2px;
  background: transparent;
  color: var(--ink-soft);
  font-size: 14px;
  cursor: pointer;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.filter:hover {
  background: rgba(22, 35, 44, 0.045);
  color: var(--ink);
}

.filter.active {
  background: var(--sheet);
  border-color: var(--line);
  color: var(--ink);
  font-weight: 600;
}

.count {
  color: var(--ink-faint);
  font-size: 12px;
  font-weight: 400;
}

.filter.active .count {
  color: var(--signal-deep);
}

.list {
  display: grid;
  gap: 12px;
  max-width: 780px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.loading {
  padding: 26px 0;
}

.notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 13px 15px;
  border-left: 2px solid var(--danger);
  border-radius: 0 2px 2px 0;
  background: var(--danger-wash);
  color: var(--danger);
  font-size: 13.5px;
}

@media (max-width: 900px) {
  .page-title {
    font-size: 29px;
  }
}
</style>
