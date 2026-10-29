<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { BellOff, CalendarClock, Pencil } from 'lucide-vue-next'
import Modal from '@/components/Modal.vue'
import ImportanceMark from '@/components/ImportanceMark.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listReminders, patchMemo } from '@/api/memos'
import type { Memo, MemoReminder } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { formatDateTime, memoStatusMeta } from '@/utils/datetime'

const props = defineProps<{ open: boolean; memo: Memo | null }>()

const emit = defineEmits<{
  close: []
  edit: [memo: Memo]
  changed: [memo: Memo]
}>()

const toast = useToast()

const reminders = ref<MemoReminder[]>([])
const loadingReminders = ref(false)
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
const pendingReminders = computed(() =>
  reminders.value.filter((reminder) => reminder.status === 'PENDING'),
)

function offsetLabel(label: string) {
  return OFFSET_LABELS[label] ?? label
}

function reminderState(reminder: MemoReminder) {
  if (reminder.status === 'SENT') {
    return `已发送 ${formatDateTime(reminder.sentAt)}`
  }
  if (reminder.status === 'CANCELLED') return '已取消'
  return '待发送'
}

watch(
  () => [props.open, props.memo?.id] as const,
  async ([open, id]) => {
    confirmingCancel.value = false
    if (!open || !id) return
    reminders.value = []
    loadingReminders.value = true
    try {
      reminders.value = await listReminders(id)
    } catch (error) {
      toast.error(error instanceof Error ? error.message : '提醒计划读取失败')
    } finally {
      loadingReminders.value = false
    }
  },
)

async function markDone() {
  const memo = props.memo
  if (!memo) return
  busy.value = true
  try {
    const updated = await patchMemo(memo.id, { status: 'DONE' })
    toast.success('已标记完成，剩下的提醒一并取消')
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
    toast.success('已取消这条备忘')
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
  <Modal
    :open="open"
    :title="memo?.title ?? '备忘'"
    width="600px"
    @close="emit('close')"
  >
    <template v-if="memo">
      <div class="chips">
        <ImportanceMark :importance="memo.importance" />
        <span v-if="memo.category" class="cat">{{ memo.category }}</span>
        <StatusPill :status="memo.status" />
      </div>

      <p v-if="memo.content" class="content">{{ memo.content }}</p>

      <dl class="facts">
        <div class="fact">
          <dt>截止时间</dt>
          <dd>{{ memo.dueTime ? formatDateTime(memo.dueTime) : '未设置' }}</dd>
        </div>
        <div class="fact">
          <dt>记录于</dt>
          <dd>{{ formatDateTime(memo.createdAt) }}</dd>
        </div>
        <div class="fact">
          <dt>状态</dt>
          <dd>{{ memoStatusMeta(memo.status).label }}</dd>
        </div>
      </dl>

      <section class="plan" aria-labelledby="plan-title">
        <h3 id="plan-title" class="plan-title">
          <CalendarClock :size="16" aria-hidden="true" />
          <span>提醒计划</span>
          <span v-if="pendingReminders.length" class="faint">
            还剩 {{ pendingReminders.length }} 次
          </span>
        </h3>

        <p v-if="loadingReminders" class="faint">正在读取…</p>
        <p v-else-if="!reminders.length" class="none">
          <BellOff :size="15" aria-hidden="true" />
          <span>没有提醒：未设置截止时间，或重要性为 1 级。</span>
        </p>
        <ol v-else class="reminders">
          <li
            v-for="reminder in reminders"
            :key="reminder.id"
            class="reminder"
            :class="{ off: reminder.status === 'CANCELLED' }"
          >
            <span class="offset">{{ offsetLabel(reminder.offsetLabel) }}</span>
            <span class="num at">{{ formatDateTime(reminder.remindAt) }}</span>
            <span class="state">{{ reminderState(reminder) }}</span>
          </li>
        </ol>
      </section>

      <div v-if="confirmingCancel && !isClosed" class="confirm" role="alert">
        <span>取消后不会再提醒，这条备忘会留档。</span>
        <div class="confirm-actions">
          <button type="button" class="btn btn-sm" @click="confirmingCancel = false">算了</button>
          <button type="button" class="btn btn-sm btn-danger" :disabled="busy" @click="cancelMemo">
            确定取消
          </button>
        </div>
      </div>

      <div class="actions">
        <button
          v-if="!isClosed"
          type="button"
          class="btn btn-ghost link-danger"
          @click="confirmingCancel = true"
        >
          取消备忘
        </button>
        <span class="spacer" />
        <button type="button" class="btn" @click="emit('close')">关闭</button>
        <button type="button" class="btn" :disabled="busy" @click="emit('edit', memo)">
          <Pencil :size="15" aria-hidden="true" />
          编辑
        </button>
        <button
          v-if="!isClosed"
          type="button"
          class="btn btn-primary"
          :disabled="busy"
          @click="markDone"
        >
          标记完成
        </button>
      </div>
    </template>
  </Modal>
</template>

<style scoped>
.chips {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 16px;
}

.cat {
  padding: 0 7px;
  border: 1px solid var(--line);
  border-radius: 2px;
  color: var(--ink-soft);
  font-size: 12px;
  line-height: 18px;
}

.content {
  margin-bottom: 18px;
  padding-left: 13px;
  border-left: 2px solid var(--line);
  color: var(--ink);
  font-size: 14.5px;
  line-height: 1.7;
  white-space: pre-wrap;
}

.facts {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin: 0 0 22px;
  padding: 14px 0;
  border-top: 1px solid var(--line-soft);
  border-bottom: 1px solid var(--line-soft);
}

.fact dt {
  color: var(--ink-faint);
  font-size: 12px;
}

.fact dd {
  margin: 2px 0 0;
  font-size: 14px;
}

.plan {
  margin-bottom: 20px;
}

.plan-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 10px;
  color: var(--ink-soft);
  font-size: 13px;
  font-weight: 600;
}

.plan-title .faint {
  margin-left: auto;
  font-weight: 400;
}

.none {
  display: flex;
  align-items: center;
  gap: 7px;
  color: var(--ink-soft);
  font-size: 13.5px;
}

.reminders {
  margin: 0;
  padding: 0;
  list-style: none;
}

.reminder {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto auto;
  gap: 14px;
  align-items: baseline;
  padding: 9px 0;
  border-bottom: 1px dashed var(--line-soft);
  font-size: 13.5px;
}

.reminder:last-child {
  border-bottom: 0;
}

.reminder.off {
  color: var(--ink-faint);
  text-decoration: line-through;
  text-decoration-color: var(--line);
}

.offset {
  color: var(--ink);
}

.reminder.off .offset {
  color: var(--ink-faint);
}

.at {
  color: var(--ink-soft);
}

.state {
  color: var(--ink-faint);
  font-size: 12.5px;
}

.confirm {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
  padding: 12px 14px;
  border-left: 2px solid var(--danger);
  border-radius: 0 2px 2px 0;
  background: var(--danger-wash);
  font-size: 13.5px;
}

.confirm-actions {
  display: flex;
  gap: 8px;
}

.actions {
  display: flex;
  align-items: center;
  gap: 10px;
  padding-top: 4px;
}

.link-danger {
  color: var(--danger);
}

.spacer {
  flex: 1;
}

@media (max-width: 560px) {
  .facts {
    grid-template-columns: 1fr;
    gap: 8px;
  }

  .reminder {
    grid-template-columns: 1fr;
    gap: 2px;
  }

  .actions {
    flex-wrap: wrap;
  }
}
</style>
