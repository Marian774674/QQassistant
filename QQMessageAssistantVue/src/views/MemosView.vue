<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import DraftBanner from '@/components/DraftBanner.vue'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import MemoDetail from '@/components/MemoDetail.vue'
import MemoEditor from '@/components/MemoEditor.vue'
import MemoRow from '@/components/MemoRow.vue'
import Segmented from '@/components/Segmented.vue'
import Skeleton from '@/components/Skeleton.vue'
import { listMemos, patchMemo } from '@/api/memos'
import type { Memo, MemoStatus } from '@/api/types'
import { useFab } from '@/composables/useFab'
import { tapFeedback, useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import {
  dayKey,
  formatHeadDate,
  formatTime,
  parseLocal,
} from '@/utils/datetime'

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

/** 接下来这一件用深色卡突出，其余保持白卡 */
const featuredId = computed(() => todayUpcoming.value[0]?.id ?? null)

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
  return `${date.getMonth() + 1}月${date.getDate()}日 周${WEEKDAYS[date.getDay()]}`
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
  { key: 'today' as const, label: '今天', count: todayMemos.value.length + overdueMemos.value.length },
  { key: 'all' as const, label: '待办', count: openMemos.value.length },
  { key: 'done' as const, label: '完成', count: doneMemos.value.length },
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

/* ── 卡片 → 抽屉的共享元素过渡 ────────────────────────────── */

type VtDocument = Document & {
  startViewTransition?: (callback: () => Promise<void>) => { finished: Promise<void> }
}

const VT_NAME = 'memo-morph'
const vtCardId = ref<number | null>(null)
const vtOpening = ref(false)

async function openDetail(memo: Memo) {
  const doc = document as VtDocument
  const reduceMotion = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  // 不支持或用户要求减少动效时，退回普通的抽屉滑入
  if (typeof doc.startViewTransition !== 'function' || reduceMotion) {
    detail.value = memo
    detailOpen.value = true
    return
  }

  // 先把名字挂到被点的那张卡上，让旧快照能定位到它
  vtCardId.value = memo.id
  await nextTick()

  const transition = doc.startViewTransition(async () => {
    vtCardId.value = null
    vtOpening.value = true
    detail.value = memo
    detailOpen.value = true
    await nextTick()
  })

  void transition.finished.finally(() => {
    vtOpening.value = false
  })
}

function replaceLocal(updated: Memo) {
  memos.value = memos.value.map((memo) => (memo.id === updated.id ? updated : memo))
  if (detail.value?.id === updated.id) detail.value = updated
}

async function complete(memo: Memo) {
  // 先落界面再发请求：勾下去就该立刻有反应，失败了再滚回来
  const snapshot = memos.value
  memos.value = memos.value.map((item) =>
    item.id === memo.id ? { ...item, status: 'DONE' as MemoStatus } : item,
  )
  tapFeedback()

  try {
    const updated = await patchMemo(memo.id, { status: 'DONE' })
    replaceLocal(updated)
    toast.success(`「${memo.title}」已完成`)
  } catch (e) {
    memos.value = snapshot
    toast.error(e instanceof Error ? e.message : '操作失败')
  }
}

function onChanged(updated: Memo) {
  replaceLocal(updated)
}

useFab('新建备忘', openCreate)
useRefresh(load)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="eyebrow">{{ formatHeadDate(now) }}</p>
      <h1 class="page-title">{{ pageTitle }}</h1>
    </header>

    <DraftBanner />

    <Segmented v-model="tab" :items="tabs" label="按状态筛选" />

    <div class="body">
      <ErrorState
        v-if="error"
        :message="error"
        hint="确认后端已在 8080 运行，也可以下拉重试。"
        @retry="load"
      />

      <Skeleton v-else-if="loading" :rows="4" variant="timeline" />

      <template v-else-if="tab === 'today'">
        <section v-if="overdueMemos.length" class="group">
          <h2 class="group-title">早该处理 <span class="num">{{ overdueMemos.length }}</span></h2>
          <TransitionGroup name="list" tag="ul" class="timeline" appear>
            <MemoRow
              v-for="memo in overdueMemos"
              :key="memo.id"
              :memo="memo"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              show-date
              @open="openDetail"
              @done="complete"
            />
          </TransitionGroup>
        </section>

        <section class="group">
          <h2 class="group-title">今天</h2>
          <TransitionGroup name="list" tag="ul" class="timeline" appear>
            <MemoRow
              v-for="memo in todayPast"
              :key="memo.id"
              :memo="memo"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              @open="openDetail"
              @done="complete"
            />

            <li key="now" class="now" aria-label="当前时间">
              <span class="now-time num">{{ formatTime(now) }}</span>
              <span class="now-rule" aria-hidden="true" />
              <span class="now-label">现在</span>
            </li>

            <MemoRow
              v-for="memo in todayUpcoming"
              :key="memo.id"
              :memo="memo"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              :featured="memo.id === featuredId"
              @open="openDetail"
              @done="complete"
            />

            <li v-if="!todayMemos.length" key="empty" class="day-empty">今天没有安排</li>
          </TransitionGroup>
        </section>

        <section v-if="undatedMemos.length" class="group">
          <h2 class="group-title">没有截止时间 <span class="num">{{ undatedMemos.length }}</span></h2>
          <TransitionGroup name="list" tag="ul" class="timeline" appear>
            <MemoRow
              v-for="memo in undatedMemos"
              :key="memo.id"
              :memo="memo"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              @open="openDetail"
              @done="complete"
            />
          </TransitionGroup>
        </section>

        <EmptyState
          v-if="todayEmpty"
          title="今天空着"
          hint="想记点什么就直接写；在 QQ 里说一句，机器人也会替你记下来。"
        >
          <button type="button" class="btn btn-sm btn-quiet" @click="openCreate">新建一条</button>
        </EmptyState>
      </template>

      <template v-else-if="tab === 'all'">
        <section v-for="group in allGroups" :key="group.key" class="group">
          <h2 class="group-title">
            {{ group.title }} <span class="num">{{ group.memos.length }}</span>
          </h2>
          <TransitionGroup name="list" tag="ul" class="timeline" appear>
            <MemoRow
              v-for="memo in group.memos"
              :key="memo.id"
              :memo="memo"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              @open="openDetail"
              @done="complete"
            />
          </TransitionGroup>
        </section>

        <EmptyState v-if="!allGroups.length" title="没有待办" hint="新建一条，或者等 QQ 里提到的事自动进来。">
          <button type="button" class="btn btn-sm btn-quiet" @click="openCreate">新建一条</button>
        </EmptyState>
      </template>

      <template v-else>
        <TransitionGroup v-if="doneSorted.length" name="list" tag="ul" class="timeline" appear>
          <MemoRow
            v-for="memo in doneSorted"
            :key="memo.id"
            :memo="memo"
            :vt-name="vtCardId === memo.id ? VT_NAME : null"
            show-date
            @open="openDetail"
            @done="complete"
          />
        </TransitionGroup>
        <EmptyState v-else title="还没有完成的备忘" hint="处理完的事会归档到这里，随时能翻回来看。" />
      </template>
    </div>

    <MemoEditor :open="editorOpen" :memo="editing" @close="editorOpen = false" @saved="load" />

    <MemoDetail
      :open="detailOpen"
      :memo="detail"
      :vt-name="vtOpening ? VT_NAME : undefined"
      :instant="vtOpening"
      @close="detailOpen = false"
      @edit="openEdit"
      @changed="onChanged"
    />
  </div>
</template>

<style scoped>
.body {
  margin-top: 18px;
}

.group + .group {
  margin-top: 24px;
}

.group-title {
  position: sticky;
  top: calc(44px + env(safe-area-inset-top, 0px));
  z-index: 10;
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 2px;
  padding: 8px 0 8px 34px;
  background: var(--bg);
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.group-title .num {
  font-weight: 400;
}

/* ── 时间轴 ───────────────────────────────────────────────── */

.timeline {
  position: relative;
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.timeline::before {
  content: "";
  position: absolute;
  left: 10.25px;
  top: 20px;
  bottom: 20px;
  border-left: 1.5px dotted var(--dot);
  pointer-events: none;
}

.now {
  display: flex;
  align-items: center;
  gap: 8px;
  padding-left: 34px;
  height: 22px;
}

.now-time {
  color: var(--ink-2);
  font-size: 11px;
  letter-spacing: 0;
}

.now-rule {
  flex: 1;
  height: 1px;
  background: linear-gradient(
    to right,
    var(--dot) 0%,
    var(--dot) 60%,
    transparent 100%
  );
  opacity: 0.8;
}

.now-label {
  color: var(--ink-3);
  font-size: 11px;
  letter-spacing: 0.06em;
}

.day-empty {
  padding: 18px 0 18px 34px;
  color: var(--ink-3);
  font-size: 13px;
}
</style>
