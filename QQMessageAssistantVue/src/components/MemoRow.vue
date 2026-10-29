<script setup lang="ts">
import { computed } from 'vue'
import { Check } from 'lucide-vue-next'
import ImportanceMark from '@/components/ImportanceMark.vue'
import StatusPill from '@/components/StatusPill.vue'
import { formatMonthDay, formatTime, parseLocal, relativeUntil } from '@/utils/datetime'
import type { Memo } from '@/api/types'

const props = defineProps<{ memo: Memo; showDate?: boolean }>()

const emit = defineEmits<{ open: [memo: Memo]; done: [memo: Memo] }>()

const due = computed(() => parseLocal(props.memo.dueTime))
const timeText = computed(() => (due.value ? formatTime(due.value) : '未定'))
const dateText = computed(() => (due.value ? formatMonthDay(due.value) : ''))
const isClosed = computed(() => props.memo.status === 'DONE' || props.memo.status === 'CANCELLED')
const isLate = computed(() => props.memo.status === 'OVERDUE')
const remaining = computed(() => (isClosed.value ? '' : relativeUntil(props.memo.dueTime)))
</script>

<template>
  <li class="row" :class="{ 'is-closed': isClosed, 'is-late': isLate }">
    <div class="gutter">
      <span class="num time">{{ timeText }}</span>
      <span v-if="showDate && due" class="date">{{ dateText }}</span>
      <ImportanceMark :importance="memo.importance" />
    </div>

    <button type="button" class="entry" @click="emit('open', memo)">
      <span class="title display">{{ memo.title }}</span>
      <span class="meta">
        <span v-if="memo.category" class="cat">{{ memo.category }}</span>
        <span v-if="remaining" class="remain" :class="{ late: isLate }">{{ remaining }}</span>
        <span v-else-if="!due && !isClosed" class="remain">没有截止时间</span>
      </span>
    </button>

    <div class="trail">
      <StatusPill :status="memo.status" />
      <button
        v-if="!isClosed"
        type="button"
        class="done"
        aria-label="标记完成"
        :title="`标记完成：${memo.title}`"
        @click="emit('done', memo)"
      >
        <Check :size="17" aria-hidden="true" />
      </button>
    </div>
  </li>
</template>

<style scoped>
.row {
  display: grid;
  grid-template-columns: 78px minmax(0, 1fr) auto;
  align-items: stretch;
}

.gutter {
  display: grid;
  align-content: start;
  gap: 3px;
  padding: 16px 16px 12px 0;
  border-right: 1px solid var(--line);
  text-align: right;
  position: relative;
}

.gutter::after {
  content: '';
  position: absolute;
  top: 23px;
  right: -3px;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--line);
}

.time {
  font-size: 15px;
  color: var(--ink);
}

.is-late .time {
  color: var(--u5);
}

.date {
  font-size: 11.5px;
  color: var(--ink-faint);
}

.entry {
  display: grid;
  gap: 5px;
  min-width: 0;
  padding: 14px 14px 12px 18px;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
  transition: background-color 0.15s var(--ease);
}

.entry:hover {
  background: rgba(22, 35, 44, 0.035);
}

.title {
  font-size: 17px;
  line-height: 1.35;
  color: var(--ink);
  overflow-wrap: anywhere;
}

.is-closed .title {
  color: var(--ink-soft);
  text-decoration: line-through;
  text-decoration-color: var(--line);
}

.meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  min-height: 18px;
}

.cat {
  padding: 0 6px;
  border: 1px solid var(--line);
  border-radius: 2px;
  color: var(--ink-soft);
  font-size: 11.5px;
  line-height: 16px;
}

.remain {
  font-size: 12.5px;
  color: var(--ink-soft);
}

.remain.late {
  color: var(--u5);
  font-weight: 600;
}

.trail {
  display: flex;
  align-items: center;
  gap: 2px;
  padding: 14px 6px 12px 0;
}

.done {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 2px;
  background: transparent;
  color: var(--ink-faint);
  cursor: pointer;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.done:hover {
  background: var(--u3-wash);
  color: var(--u3);
}

@media (max-width: 900px) {
  .row {
    grid-template-columns: 58px minmax(0, 1fr) auto;
  }

  .gutter {
    padding-right: 12px;
  }

  .entry {
    padding: 14px 8px 12px 12px;
  }

  .title {
    font-size: 16px;
  }
}
</style>
