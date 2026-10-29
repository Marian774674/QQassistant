<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Plus } from 'lucide-vue-next'
import DraftTray from '@/components/DraftTray.vue'
import EmptyState from '@/components/EmptyState.vue'
import MemoDetail from '@/components/MemoDetail.vue'
import MemoEditor from '@/components/MemoEditor.vue'
import MemoRow from '@/components/MemoRow.vue'
import { listMemos, patchMemo } from '@/api/memos'
import type { Memo } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { dayKey, formatFullDate, formatTime, parseLocal } from '@/utils/datetime'

type TabKey = 'today' | 'all' | 'done'

const WEEKDAYS = ['日', '一', '二', '三', '四', '五', '六']
const OPEN_STATUSES = new Set(['ACTIVE', 'REMINDED', 'AWAITING_REPLY', 'OVERDUE'])

const toast = useToast()

const memos = ref<Memo[]>([])
const loading = ref(true)
const error = ref('')
const tab = ref<TabKey>('today')
const now = ref(new Date())

const editorOpen = ref(false)
const editing = ref<Memo | null>(null)
const detailOpen = ref(false)
const detail = ref<Memo | null>(null)

let timer = 0

onMounted(() => {
  void load()
  timer = window.setInterval(() => {
    now.value = new Date()
  }, 30_000)
})

onBeforeUnmount(() => window.clearInterval(timer))

async function load() {
  loading.value = true
  error.value = ''
  try {
    memos.value = await listMemos()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '备忘读取失败'
  } finally {
    loading.value = false
  }
}

function byDue(a: Memo, b: Memo) {
  const at = parseLocal(a.dueTime)?.getTime() ?? Number.MAX_SAFE_INTEGER
  const bt = parseLocal(b.dueTime)?.getTime() ?? Number.MAX_SAFE_INTEGER
  return at - bt
}

const openMemos = computed(() => memos.value.filter((memo) => OPEN_STATUSES.has(memo.status)))
const doneMemos = computed(() => memos.value.filter((memo) => !OPEN_STATUSES.has(memo.status)))

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

const todayStart = computed(() => startOfDay(now.value))

const overdueMemos = computed(() =>
  openMemos.value
    .filter((memo) => {
      const due = parseLocal(memo.dueTime)
      return due !== null && due.getTime() < todayStart.value
    })
    .sort(byDue),
)

const todayMemos = computed(() =>
  openMemos.value
    .filter((memo) => {
      const due = parseLocal(memo.dueTime)
      return due !== null && dayKey(due) === dayKey(now.value)
    })
    .sort(byDue),
)

const undatedMemos = computed(() =>
  openMemos.value
    .filter((memo) => !memo.dueTime)
    .sort((a, b) => b.createdAt.localeCompare(a.createdAt)),
)

const todayPast = computed(() =>
  todayMemos.value.filter((memo) => (parseLocal(memo.dueTime)?.getTime() ?? 0) <= now.value.getTime()),
)

const todayUpcoming = computed(() =>
  todayMemos.value.filter((memo) => (parseLocal(memo.dueTime)?.getTime() ?? 0) > now.value.getTime()),
)

const doneSorted = computed(() =>
  [...doneMemos.value].sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
)

interface DayGroup {
  key: string
  title: string
  memos: Memo[]
}

function dayTitle(date: Date) {
  const days = Math.round((startOfDay(date) - todayStart.value) / 86_400_000)
  if (days === 0) return '今天'
  if (days === 1) return '明天'
  if (days === -1) return '昨天'
  return `${date.getMonth() + 1}月${date.getDate()}日 星期${WEEKDAYS[date.getDay()]}`
}

const allGroups = computed<DayGroup[]>(() => {
  const groups = new Map<string, DayGroup>()
  for (const memo of [...openMemos.value].sort(byDue)) {
    const due = parseLocal(memo.dueTime)
    if (!due) continue
    const key = dayKey(due)
    const group = groups.get(key)
    if (group) {
      group.memos.push(memo)
    } else {
      groups.set(key, { key, title: dayTitle(due), memos: [memo] })
    }
  }
  const result = [...groups.values()]
  if (undatedMemos.value.length) {
    result.push({ key: 'undated', title: '没有截止时间', memos: undatedMemos.value })
  }
  return result
})

const tabs = computed(() => [
  { key: 'today' as const, label: '今天', count: todayMemos.value.length },
  { key: 'all' as const, label: '全部待办', count: openMemos.value.length },
  { key: 'done' as const, label: '已完成', count: doneMemos.value.length },
])

const pageTitle = computed(() => {
  if (tab.value === 'today') return '今天'
  if (tab.value === 'all') return '全部待办'
  return '已完成'
})

const todayEmpty = computed(
  () => !overdueMemos.value.length && !todayMemos.value.length && !undatedMemos.value.length,
)

function openCreate() {
  editing.value = null
  editorOpen.value = true
}

function openEdit(memo: Memo) {
  editing.value = memo
  editorOpen.value = true
  detailOpen.value = false
}

function openDetail(memo: Memo) {
  detail.value = memo
  detailOpen.value = true
}

function replaceLocal(updated: Memo) {
  memos.value = memos.value.map((memo) => (memo.id === updated.id ? updated : memo))
  if (detail.value?.id === updated.id) detail.value = updated
}

async function complete(memo: Memo) {
  try {
    const updated = await patchMemo(memo.id, { status: 'DONE' })
    replaceLocal(updated)
    toast.success(`「${memo.title}」已完成`)
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '操作失败')
  }
}

function onChanged(updated: Memo) {
  replaceLocal(updated)
}
</script>

<template>
  <div class="page">
    <header class="head">
      <div class="head-text">
        <h1 class="display page-title">{{ pageTitle }}</h1>
        <p class="page-sub">
          {{ formatFullDate(now) }}<template v-if="openMemos.length">，还有 {{ openMemos.length }} 件待处理</template>
        </p>
      </div>
      <button type="button" class="btn btn-primary" @click="openCreate">
        <Plus :size="17" aria-hidden="true" />
        新建备忘
      </button>
    </header>

    <div class="layout">
      <section class="board" aria-label="备忘列表">
        <div class="filters" role="group" aria-label="按状态筛选">
          <button
            v-for="item in tabs"
            :key="item.key"
            type="button"
            class="filter"
            :class="{ active: tab === item.key }"
            :aria-pressed="tab === item.key"
            @click="tab = item.key"
          >
            {{ item.label }}
            <span class="count num">{{ item.count }}</span>
          </button>
        </div>

        <p v-if="error" class="notice" role="alert">
          <span>{{ error }}</span>
          <button type="button" class="btn btn-sm" @click="load">重试</button>
        </p>

        <p v-else-if="loading" class="faint loading">正在读取…</p>

        <template v-else-if="tab === 'today'">
          <section v-if="overdueMemos.length" class="group">
            <h2 class="group-title">
              早该处理
              <span class="count num">{{ overdueMemos.length }} 件</span>
            </h2>
            <ul class="rows sheet">
              <MemoRow
                v-for="memo in overdueMemos"
                :key="memo.id"
                :memo="memo"
                show-date
                @open="openDetail"
                @done="complete"
              />
            </ul>
          </section>

          <section class="group">
            <h2 class="group-title">今天</h2>
            <ul class="rows sheet">
              <MemoRow
                v-for="memo in todayPast"
                :key="memo.id"
                :memo="memo"
                @open="openDetail"
                @done="complete"
              />
              <li class="now" aria-label="当前时间线">
                <span class="now-time num">{{ formatTime(now) }}</span>
                <span class="now-line">
                  <i class="dot" aria-hidden="true" />
                  <span class="now-text">现在</span>
                  <i class="rule" aria-hidden="true" />
                </span>
              </li>
              <MemoRow
                v-for="memo in todayUpcoming"
                :key="memo.id"
                :memo="memo"
                @open="openDetail"
                @done="complete"
              />
              <li v-if="!todayMemos.length" class="day-empty">
                <span class="day-empty-time" aria-hidden="true" />
                <span class="day-empty-text">今天没有安排</span>
              </li>
            </ul>
          </section>

          <section v-if="undatedMemos.length" class="group">
            <h2 class="group-title">
              没有截止时间
              <span class="count num">{{ undatedMemos.length }} 件</span>
            </h2>
            <ul class="rows sheet">
              <MemoRow
                v-for="memo in undatedMemos"
                :key="memo.id"
                :memo="memo"
                @open="openDetail"
                @done="complete"
              />
            </ul>
          </section>

          <EmptyState
            v-if="todayEmpty"
            title="今天没有安排"
            hint="想记点什么就直接写；说一句话发到 QQ，机器人也会替你记下来。"
          >
            <button type="button" class="btn btn-sm" @click="openCreate">新建一条备忘</button>
          </EmptyState>
        </template>

        <template v-else-if="tab === 'all'">
          <section v-for="group in allGroups" :key="group.key" class="group">
            <h2 class="group-title">
              {{ group.title }}
              <span class="count num">{{ group.memos.length }} 件</span>
            </h2>
            <ul class="rows sheet">
              <MemoRow
                v-for="memo in group.memos"
                :key="memo.id"
                :memo="memo"
                @open="openDetail"
                @done="complete"
              />
            </ul>
          </section>

          <EmptyState v-if="!allGroups.length" title="没有待办" hint="新建一条，或者等 QQ 里提到的事自动进来。">
            <button type="button" class="btn btn-sm" @click="openCreate">新建一条备忘</button>
          </EmptyState>
        </template>

        <template v-else>
          <ul v-if="doneSorted.length" class="rows sheet">
            <MemoRow
              v-for="memo in doneSorted"
              :key="memo.id"
              :memo="memo"
              show-date
              @open="openDetail"
              @done="complete"
            />
          </ul>
          <EmptyState
            v-else
            title="还没有完成的备忘"
            hint="处理完的事会归档到这里，随时能翻回来看。"
          />
        </template>
      </section>

      <DraftTray />
    </div>

    <MemoEditor
      :open="editorOpen"
      :memo="editing"
      @close="editorOpen = false"
      @saved="load"
    />

    <MemoDetail
      :open="detailOpen"
      :memo="detail"
      @close="detailOpen = false"
      @edit="openEdit"
      @changed="onChanged"
    />
  </div>
</template>

<style scoped>
.page {
  max-width: 1180px;
  margin: 0 auto;
}

.head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 22px;
}

.page-title {
  font-size: 38px;
  line-height: 1.1;
}

.page-sub {
  margin-top: 5px;
  color: var(--ink-soft);
  font-size: 13.5px;
}

.layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 316px;
  gap: 28px;
  align-items: start;
}

.board {
  min-width: 0;
}

.filters {
  display: flex;
  flex-wrap: wrap;
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

.group + .group {
  margin-top: 26px;
}

.group-title {
  display: flex;
  align-items: baseline;
  gap: 9px;
  margin-bottom: 9px;
  color: var(--ink-soft);
  font-size: 13px;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.rows {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rows + .rows {
  margin-top: 14px;
}

.rows :deep(li + li) {
  border-top: 1px dashed var(--line-soft);
}

/* ── 现在线 ─────────────────────────────────────────────── */

.now {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  align-items: center;
  border-top: 1px dashed var(--line-soft);
}

.now-time {
  padding: 6px 16px 6px 0;
  border-right: 1px solid var(--line);
  color: var(--signal-deep);
  font-size: 12.5px;
  text-align: right;
}

.now-line {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 16px 6px 18px;
}

.dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--signal);
  animation: breathe 2.6s var(--ease) infinite;
}

.now-text {
  color: var(--signal-deep);
  font-size: 11.5px;
  letter-spacing: 0.04em;
}

.day-empty {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr);
  align-items: center;
  border-top: 1px dashed var(--line-soft);
}

.day-empty-time {
  border-right: 1px solid var(--line);
  align-self: stretch;
}

.day-empty-text {
  padding: 13px 16px 13px 18px;
  color: var(--ink-soft);
  font-size: 13.5px;
}

.rule {
  flex: 1;
  height: 1px;
  background: var(--signal);
  opacity: 0.45;
}

@keyframes breathe {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.35;
  }
}

/* ── 状态 ───────────────────────────────────────────────── */

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

@media (max-width: 1100px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
    gap: 22px;
  }
}

@media (max-width: 900px) {
  .head {
    align-items: flex-start;
    flex-direction: column;
    gap: 14px;
  }

  .page-title {
    font-size: 31px;
  }

  .now {
    grid-template-columns: 58px minmax(0, 1fr);
  }

  .now-time {
    padding-right: 12px;
  }

  .now-line {
    padding-left: 12px;
  }
}
</style>
