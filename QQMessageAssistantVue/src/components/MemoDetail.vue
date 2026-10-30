<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BellOff } from 'lucide-vue-next'
import Sheet from '@/components/Sheet.vue'
import ImportanceMark from '@/components/ImportanceMark.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listMemoHistory, listReminders, patchMemo } from '@/api/memos'
import type { Memo, MemoChangeLog, MemoReminder } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'
import { importanceLabel, memoStatusMeta, reminderStatusMeta } from '@/utils/meta'

const props = defineProps<{
  open: boolean
  memo: Memo | null
  vtName?: string
  instant?: boolean
}>()

const emit = defineEmits<{
  close: []
  edit: [memo: Memo]
  changed: [memo: Memo]
}>()

const toast = useToast()

const reminders = ref<MemoReminder[]>([])
const loadingReminders = ref(false)
const history = ref<MemoChangeLog[]>([])
const loadingHistory = ref(false)
const busy = ref(false)
const confirmingCancel = ref(false)

const OFFSET_LABELS: Record<string, string> = {
  '24h': '提前 24 小时',
  '2h': '提前 2 小时',
  '1h': '提前 1 小时',
  '30min': '提前 30 分钟',
  当天: '当天 09:00',
}

const isClosed = computed(
  () => props.memo?.status === 'DONE' || props.memo?.status === 'CANCELLED',
)

const statusMeta = computed(() => memoStatusMeta(props.memo?.status ?? ''))
const pendingCount = computed(
  () => reminders.value.filter((reminder) => reminder.status === 'PENDING').length,
)

function offsetLabel(label: string) {
  return OFFSET_LABELS[label] ?? label
}

/* ── 变更记录 ───────────────────────────────────────────────
 * 后端存的是备忘快照 JSON（beforeJson / afterJson），差异在这里算，
 * 界面只说「改了什么」，不把原始 JSON 摆给用户看。 */

interface MemoSnapshot {
  title?: unknown
  content?: unknown
  importance?: unknown
  category?: unknown
  dueTime?: unknown
  status?: unknown
}

function snapshot(json: string | null): MemoSnapshot {
  if (!json) return {}
  try {
    const parsed: unknown = JSON.parse(json)
    return parsed && typeof parsed === 'object' ? (parsed as MemoSnapshot) : {}
  } catch {
    return {}
  }
}

function text(value: unknown): string {
  return typeof value === 'string' ? value : ''
}

function same(a: unknown, b: unknown) {
  return text(a) === text(b)
}

function dueLabel(value: unknown) {
  const iso = text(value)
  return iso ? formatDateTime(iso) : '未设置'
}

function changeText(entry: MemoChangeLog): string {
  const after = snapshot(entry.afterJson)
  if (entry.op === 'CREATE') return '记下了这条备忘'
  if (entry.op === 'CLOSE') {
    if (after.status === 'DONE') return '标记为已完成'
    if (after.status === 'CANCELLED') return '取消了这条备忘'
    return '关闭了这条备忘'
  }

  const before = snapshot(entry.beforeJson)
  const parts: string[] = []
  if (!same(before.dueTime, after.dueTime)) {
    parts.push(`截止时间 ${dueLabel(before.dueTime)} → ${dueLabel(after.dueTime)}`)
  }
  if (!same(before.status, after.status)) {
    parts.push(
      `状态 ${memoStatusMeta(text(before.status)).label} → ${memoStatusMeta(text(after.status)).label}`,
    )
  }
  if (!same(before.importance, after.importance)) {
    parts.push(
      `重要性 ${importanceLabel(Number(before.importance ?? 0))} → ${importanceLabel(
        Number(after.importance ?? 0),
      )}`,
    )
  }
  if (!same(before.title, after.title)) parts.push('改了标题')
  if (!same(before.category, after.category)) parts.push('改了分类')
  if (!same(before.content, after.content)) parts.push('改了正文')
  return parts.length ? parts.join('，') : '存了一次，内容没变'
}

watch(
  () => [props.open, props.memo?.id] as const,
  async ([open, id]) => {
    confirmingCancel.value = false
    if (!open || !id) return
    reminders.value = []
    history.value = []
    loadingReminders.value = true
    loadingHistory.value = true
    try {
      reminders.value = (await listReminders(id)) ?? []
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '提醒计划读取失败')
    } finally {
      loadingReminders.value = false
    }
    try {
      history.value = (await listMemoHistory(id)) ?? []
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '变更记录读取失败')
    } finally {
      loadingHistory.value = false
    }
  },
)

async function markDone() {
  const memo = props.memo
  if (!memo) return
  busy.value = true
  try {
    const updated = await patchMemo(memo.id, { status: 'DONE' })
    toast.success('已完成，剩下的提醒一并取消')
    emit('changed', updated)
    emit('close')
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}

async function cancelMemo() {
  const memo = props.memo
  if (!memo) return
  busy.value = true
  try {
    const updated = await patchMemo(memo.id, { status: 'CANCELLED' })
    toast.push('已取消这条备忘')
    emit('changed', updated)
    emit('close')
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <Sheet
    :open="open"
    :title="memo?.title ?? '备忘'"
    :description="memo?.category ? `${memo.category} · 记录于 ${formatDateTime(memo.createdAt)}` : undefined"
    :vt-name="vtName"
    :instant="instant"
    @close="emit('close')"
  >
    <template v-if="memo">
      <div class="chips">
        <ImportanceMark :importance="memo.importance" inverse />
        <StatusPill :label="statusMeta.label" :tone="statusMeta.tone" />
      </div>

      <p v-if="memo.content" class="content">{{ memo.content }}</p>

      <div class="facts">
        <div class="fact">
          <span class="fact-label">截止时间</span>
          <span class="fact-value">{{ memo.dueTime ? formatDateTime(memo.dueTime) : '未设置' }}</span>
        </div>
        <div class="fact">
          <span class="fact-label">状态</span>
          <span class="fact-value">{{ statusMeta.label }}</span>
        </div>
        <div class="fact">
          <span class="fact-label">记录于</span>
          <span class="fact-value">{{ formatDateTime(memo.createdAt) }}</span>
        </div>
      </div>

      <section class="plan" aria-labelledby="plan-title">
        <h3 id="plan-title" class="plan-title">
          <span>提醒计划</span>
          <span v-if="pendingCount" class="plan-count num">还剩 {{ pendingCount }} 次</span>
        </h3>

        <p v-if="loadingReminders" class="plan-empty">正在读取…</p>
        <p v-else-if="!reminders.length" class="plan-empty">
          <BellOff :size="14" aria-hidden="true" />
          <span>没有提醒：未设置截止时间，或重要性为 1 级。</span>
        </p>
        <ul v-else class="reminders">
          <li
            v-for="reminder in reminders"
            :key="reminder.id"
            class="reminder"
            :class="{ off: reminder.status === 'CANCELLED' }"
          >
            <span class="reminder-offset">{{ offsetLabel(reminder.offsetLabel) }}</span>
            <span class="reminder-at num">{{ formatDateTime(reminder.remindAt) }}</span>
            <span class="reminder-state">{{ reminderStatusMeta(reminder.status).label }}</span>
          </li>
        </ul>
      </section>

      <section class="plan" aria-labelledby="history-title">
        <h3 id="history-title" class="plan-title">
          <span>变更记录</span>
          <span v-if="history.length" class="plan-count num">{{ history.length }} 次</span>
        </h3>

        <p v-if="loadingHistory" class="plan-empty">正在读取…</p>
        <p v-else-if="!history.length" class="plan-empty">
          <span>还没改过。</span>
        </p>
        <ul v-else class="changes">
          <li v-for="entry in history" :key="entry.id" class="change">
            <span class="change-source" :class="entry.source === 'AGENT' ? 'from-ai' : ''">
              {{ entry.source === 'AGENT' ? 'QQ 里改的' : '手动改的' }}
            </span>
            <span class="change-text">{{ changeText(entry) }}</span>
            <span class="change-at num">{{ formatDateTime(entry.createdAt) }}</span>
          </li>
        </ul>
      </section>

      <div v-if="confirmingCancel && !isClosed" class="confirm" role="alert">
        <p>取消后不会再提醒，这条备忘会留档。</p>
        <div class="confirm-actions">
          <button type="button" class="btn btn-sm btn-ghost" @click="confirmingCancel = false">
            算了
          </button>
          <button type="button" class="btn btn-sm btn-danger" :disabled="busy" @click="cancelMemo">
            确定取消
          </button>
        </div>
      </div>

      <button
        v-else-if="!isClosed"
        type="button"
        class="danger-link"
        @click="confirmingCancel = true"
      >
        取消这条备忘
      </button>
    </template>

    <template #footer>
      <button type="button" class="btn btn-ghost" :disabled="busy" @click="memo && emit('edit', memo)">
        编辑
      </button>
      <button
        v-if="!isClosed"
        type="button"
        class="btn btn-on-dark"
        :disabled="busy"
        @click="markDone"
      >
        标记完成
      </button>
      <button v-else type="button" class="btn btn-on-dark" @click="emit('close')">关闭</button>
    </template>
  </Sheet>
</template>

<style scoped>
.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.content {
  margin-bottom: 14px;
  padding: 14px 16px;
  border-radius: var(--r-md);
  background: rgba(255, 255, 255, 0.06);
  color: var(--on-dark);
  font-size: 14.5px;
  line-height: 1.65;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.facts {
  display: grid;
  gap: 1px;
  overflow: hidden;
  border-radius: var(--r-md);
  background: #fff;
}

.fact {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  color: var(--ink);
}

.fact + .fact {
  border-top: 1px solid var(--hairline);
}

.fact-label {
  color: var(--ink-3);
  font-size: 12.5px;
  flex: none;
}

.fact-value {
  font-size: 13.5px;
  text-align: right;
}

.plan {
  margin-top: 18px;
}

.plan-title {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 8px;
  color: var(--on-dark-2);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.plan-count {
  font-weight: 400;
  color: var(--on-dark-3);
}

.plan-empty {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--on-dark-3);
  font-size: 12.5px;
}

.reminders {
  display: grid;
  gap: 1px;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow: hidden;
  border-radius: var(--r-md);
  background: #fff;
}

.reminder {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 2px 12px;
  padding: 10px 14px;
  color: var(--ink);
}

.reminder + .reminder {
  border-top: 1px solid var(--hairline);
}

.reminder-offset {
  font-size: 13.5px;
}

.reminder-at {
  color: var(--ink-2);
  font-size: 12.5px;
  text-align: right;
}

.reminder-state {
  grid-column: 1 / -1;
  color: var(--ink-3);
  font-size: 11.5px;
}

.reminder.off {
  color: var(--ink-3);
}

.reminder.off .reminder-offset,
.reminder.off .reminder-at {
  color: var(--ink-3);
  text-decoration: line-through;
}

.changes {
  display: grid;
  gap: 1px;
  margin: 0;
  padding: 0;
  list-style: none;
  overflow: hidden;
  border-radius: var(--r-md);
  background: #fff;
}

.change {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: baseline;
  gap: 2px 8px;
  padding: 10px 14px;
  color: var(--ink);
}

.change + .change {
  border-top: 1px solid var(--hairline);
}

.change-source {
  padding: 1px 7px;
  border-radius: var(--r-pill);
  background: #f0f0f1;
  color: var(--ink-2);
  font-size: 11px;
  white-space: nowrap;
}

.change-source.from-ai {
  background: rgba(229, 56, 127, 0.12);
  color: #a82457;
}

.change-text {
  font-size: 13.5px;
  overflow-wrap: anywhere;
}

.change-at {
  grid-column: 1 / -1;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: right;
}

.confirm {
  margin-top: 16px;
  padding: 14px;
  border-radius: var(--r-md);
  background: rgba(191, 58, 43, 0.16);
  color: #ffc3ba;
  font-size: 12.5px;
  line-height: 1.55;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

.danger-link {
  margin-top: 18px;
  padding: 6px 0;
  border: 0;
  background: transparent;
  color: rgba(255, 140, 128, 0.9);
  font-size: 12.5px;
  cursor: pointer;
}
</style>
