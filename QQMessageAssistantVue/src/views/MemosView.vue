<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { Search, X } from 'lucide-vue-next'
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

/** 勾完给五秒反悔，这段时间只改本地，不发请求 */
const UNDO_WINDOW = 5000

const toast = useToast()

const memos = ref<Memo[]>([])
const loading = ref(true)
const error = ref('')
const tab = ref<TabKey>('today')
const now = ref(new Date())
const query = ref('')

const editorOpen = ref(false)
const editing = ref<Memo | null>(null)
const detailOpen = ref(false)
const detail = ref<Memo | null>(null)

let clock = 0

onMounted(() => {
  void load()
  clock = window.setInterval(() => {
    now.value = new Date()
  }, 30_000)
})

onBeforeUnmount(() => {
  window.clearInterval(clock)
  // 还没落地的完成操作，走之前先发出去，别让人以为白点了
  void flushDone()
})

async function load() {
  await flushDone()
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

/* ── 搜索：只在本地过滤，不发请求 ─────────────────────────── */

const normalizedQuery = computed(() => query.value.trim().toLowerCase())
const searching = computed(() => normalizedQuery.value.length > 0)

const searched = computed(() => {
  const q = normalizedQuery.value
  if (!q) return memos.value
  return memos.value.filter((memo) => {
    const haystack = `${memo.title} ${memo.content ?? ''} ${memo.category ?? ''}`.toLowerCase()
    return haystack.includes(q)
  })
})

const openMemos = computed(() => searched.value.filter((memo) => OPEN_STATUSES.has(memo.status)))
const doneMemos = computed(() => searched.value.filter((memo) => !OPEN_STATUSES.has(memo.status)))

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

const resultCount = computed(() => {
  if (tab.value === 'today') return overdueMemos.value.length + todayMemos.value.length + undatedMemos.value.length
  if (tab.value === 'all') return openMemos.value.length
  return doneMemos.value.length
})

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
  if (!updated) return
  memos.value = memos.value.map((memo) => (memo.id === updated.id ? updated : memo))
  if (detail.value?.id === updated.id) detail.value = updated
}

/* ── 完成：先落界面，五秒内可撤销，之后才真发请求 ───────────
 * 后端 PATCH 只接受 DONE / CANCELLED，撤销只能靠「还没发出去」实现，
 * 所以这里用延迟提交换一个真正的反悔窗口。 */

interface PendingDone {
  timer: number
  original: Memo
}

const pendingDone = new Map<number, PendingDone>()

function complete(memo: Memo) {
  if (pendingDone.has(memo.id)) return
  tapFeedback()

  memos.value = memos.value.map((item) =>
    item.id === memo.id ? { ...item, status: 'DONE' as MemoStatus } : item,
  )

  const timer = window.setTimeout(() => {
    void commitDone(memo.id)
  }, UNDO_WINDOW)
  pendingDone.set(memo.id, { timer, original: memo })

  toast.action(
    `「${memo.title}」已完成`,
    { label: '撤销', run: () => undoDone(memo.id) },
    UNDO_WINDOW,
  )
}

function undoDone(id: number) {
  const pending = pendingDone.get(id)
  if (!pending) return
  window.clearTimeout(pending.timer)
  pendingDone.delete(id)
  memos.value = memos.value.map((item) => (item.id === id ? pending.original : item))
  if (detail.value?.id === id) detail.value = pending.original
  toast.push('已撤销，这条还在待办里')
}

async function commitDone(id: number) {
  const pending = pendingDone.get(id)
  if (!pending) return
  pendingDone.delete(id)
  try {
    const updated = await patchMemo(id, { status: 'DONE' })
    replaceLocal(updated)
  } catch (e) {
    memos.value = memos.value.map((item) => (item.id === id ? pending.original : item))
    toast.error(e instanceof Error ? e.message : '标记完成失败，已还原')
  }
}

async function flushDone() {
  const ids = [...pendingDone.keys()]
  if (!ids.length) return
  await Promise.all(ids.map((id) => commitDone(id)))
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

    <div class="search" :class="{ filled: searching }">
      <Search :size="15" :stroke-width="2" class="search-icon" aria-hidden="true" />
      <input
        v-model="query"
        type="search"
        class="search-input"
        placeholder="搜标题、正文、分类"
        aria-label="搜索备忘"
        enterkeyhint="search"
        autocomplete="off"
      />
      <button
        v-if="searching"
        type="button"
        class="search-clear"
        aria-label="清空搜索"
        @click="query = ''"
      >
        <X :size="13" :stroke-width="2.6" aria-hidden="true" />
      </button>
    </div>

    <p v-if="searching" class="search-count" role="status">
      找到 <span class="num">{{ resultCount }}</span> 条
    </p>

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
              :now="now.getTime()"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
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
              :now="now.getTime()"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              @open="openDetail"
              @done="complete"
            />

            <li v-if="todayMemos.length" key="now" class="now" aria-label="当前时间">
              <span class="now-time num">{{ formatTime(now) }}</span>
              <span class="now-rule" aria-hidden="true" />
              <span class="now-label">现在</span>
            </li>

            <MemoRow
              v-for="memo in todayUpcoming"
              :key="memo.id"
              :memo="memo"
              :now="now.getTime()"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              :featured="memo.id === featuredId"
              @open="openDetail"
              @done="complete"
            />

            <li v-if="!todayMemos.length" key="empty" class="day-empty">
              {{ searching ? '今天没有匹配的事' : '今天没有安排' }}
            </li>
          </TransitionGroup>
        </section>

        <section v-if="undatedMemos.length" class="group">
          <h2 class="group-title">没有截止时间 <span class="num">{{ undatedMemos.length }}</span></h2>
          <TransitionGroup name="list" tag="ul" class="timeline" appear>
            <MemoRow
              v-for="memo in undatedMemos"
              :key="memo.id"
              :memo="memo"
              :now="now.getTime()"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              @open="openDetail"
              @done="complete"
            />
          </TransitionGroup>
        </section>

        <EmptyState
          v-if="todayEmpty"
          :title="searching ? '没有匹配的备忘' : '今天空着'"
          :hint="
            searching
              ? '换个关键词试试，或者清空搜索框。'
              : '想记点什么就直接写；在 QQ 里说一句，机器人也会替你记下来。'
          "
        >
          <button v-if="searching" type="button" class="btn btn-sm btn-quiet" @click="query = ''">
            清空搜索
          </button>
          <button v-else type="button" class="btn btn-sm btn-quiet" @click="openCreate">
            新建一条
          </button>
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
              :now="now.getTime()"
              :vt-name="vtCardId === memo.id ? VT_NAME : null"
              grouped
              @open="openDetail"
              @done="complete"
            />
          </TransitionGroup>
        </section>

        <EmptyState
          v-if="!allGroups.length"
          :title="searching ? '没有匹配的待办' : '没有待办'"
          :hint="
            searching
              ? '换个关键词试试，或者清空搜索框。'
              : '新建一条，或者等 QQ 里提到的事自动进来。'
          "
        >
          <button v-if="searching" type="button" class="btn btn-sm btn-quiet" @click="query = ''">
            清空搜索
          </button>
          <button v-else type="button" class="btn btn-sm btn-quiet" @click="openCreate">
            新建一条
          </button>
        </EmptyState>
      </template>

      <template v-else>
        <TransitionGroup v-if="doneSorted.length" name="list" tag="ul" class="timeline" appear>
          <MemoRow
            v-for="memo in doneSorted"
            :key="memo.id"
            :memo="memo"
            :now="now.getTime()"
            :vt-name="vtCardId === memo.id ? VT_NAME : null"
            @open="openDetail"
            @done="complete"
          />
        </TransitionGroup>
        <EmptyState
          v-else
          :title="searching ? '没有匹配的记录' : '还没有完成的备忘'"
          :hint="
            searching
              ? '换个关键词试试，或者清空搜索框。'
              : '处理完的事会归档到这里，随时能翻回来看。'
          "
        >
          <button v-if="searching" type="button" class="btn btn-sm btn-quiet" @click="query = ''">
            清空搜索
          </button>
        </EmptyState>
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
/* ── 搜索 ─────────────────────────────────────────────────── */

.search {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 38px;
  margin-top: 14px;
  padding: 0 12px;
  border-radius: var(--r-pill);
  background: rgba(17, 18, 20, 0.045);
  transition: background-color var(--dur-2) var(--ease-out),
    box-shadow var(--dur-2) var(--ease-out);
}

.search.filled {
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.search-icon {
  flex: none;
  color: var(--ink-3);
}

.search-input {
  flex: 1;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-size: 14px;
  outline: none;
}

.search-input::placeholder {
  color: var(--ink-3);
}

/* 去掉 Safari / Chrome 自带的清除按钮，用自己的 */
.search-input::-webkit-search-cancel-button {
  display: none;
}

.search-clear {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  padding: 0;
  border: 0;
  border-radius: 50%;
  background: rgba(17, 18, 20, 0.1);
  color: var(--ink-2);
  cursor: pointer;
}

.search-count {
  margin-top: 8px;
  padding-left: 4px;
  color: var(--ink-3);
  font-size: 12px;
}

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
