<script setup lang="ts">
import { computed } from 'vue'
import { Check } from 'lucide-vue-next'
import { categoryIcon, importanceLabel } from '@/utils/meta'
import { formatMonthDay, formatTime, parseLocal, relativeUntil } from '@/utils/datetime'
import type { Memo } from '@/api/types'

const props = defineProps<{
  memo: Memo
  /** 用深色卡突出「接下来这一件」 */
  featured?: boolean
  showDate?: boolean
  /** View Transitions 的共享元素名，只在点开的那一帧挂上 */
  vtName?: string | null
}>()

const emit = defineEmits<{ open: [memo: Memo]; done: [memo: Memo] }>()

const due = computed(() => parseLocal(props.memo.dueTime))
const timeText = computed(() => (due.value ? formatTime(due.value) : ''))
const icon = computed(() => categoryIcon(props.memo.category))
const isClosed = computed(
  () => props.memo.status === 'DONE' || props.memo.status === 'CANCELLED',
)
const isLate = computed(() => props.memo.status === 'OVERDUE')

const metaText = computed(() => {
  const parts: string[] = []
  if (props.showDate && due.value) parts.push(formatMonthDay(due.value))
  if (props.memo.category) parts.push(props.memo.category)
  if (isClosed.value) {
    parts.push(props.memo.status === 'DONE' ? '已完成' : '已取消')
  } else if (due.value) {
    parts.push(relativeUntil(props.memo.dueTime))
  } else {
    parts.push('没有截止时间')
  }
  return parts.join(' · ')
})
</script>

<template>
  <li class="tl-item">
    <button
      type="button"
      class="tl-dot"
      :class="{ done: isClosed }"
      :aria-label="isClosed ? `「${memo.title}」已结束` : `标记完成：${memo.title}`"
      :disabled="isClosed"
      @click="emit('done', memo)"
    >
      <Check v-if="isClosed" :size="12" :stroke-width="3" aria-hidden="true" />
    </button>

    <button
      type="button"
      class="memo"
      :class="{ featured, closed: isClosed }"
      :style="vtName ? { viewTransitionName: vtName } : undefined"
      @click="emit('open', memo)"
    >
      <span class="memo-icon" aria-hidden="true">
        <component :is="icon" :size="16" :stroke-width="1.8" />
      </span>

      <span class="memo-main">
        <span class="memo-title">{{ memo.title }}</span>
        <span class="memo-meta">
          <span :class="{ late: isLate }">{{ metaText }}</span>
          <span v-if="!isClosed" class="memo-imp">{{ importanceLabel(memo.importance) }}</span>
        </span>
      </span>

      <span v-if="timeText" class="memo-time num">{{ timeText }}</span>
    </button>
  </li>
</template>

<style scoped>
.tl-item {
  display: grid;
  grid-template-columns: 22px minmax(0, 1fr);
  align-items: center;
  gap: 12px;
}

.tl-dot {
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: 1.5px solid var(--dot);
  border-radius: 50%;
  background: var(--bg);
  color: #fff;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease),
    background-color var(--dur) var(--ease),
    transform var(--dur-1) var(--ease-spring);
}

.tl-dot:active:not(:disabled) {
  transform: scale(0.84);
}

.tl-dot.done {
  border-color: var(--card-inverse);
  background: var(--card-inverse);
  cursor: default;
}

/* 打勾是「画」出来的，不是突然出现 */
.tl-dot.done svg path {
  stroke-dasharray: 22;
  stroke-dashoffset: 22;
  animation: draw-check 340ms var(--ease-out) forwards;
}

@keyframes draw-check {
  to {
    stroke-dashoffset: 0;
  }
}

.memo {
  display: grid;
  grid-template-columns: 34px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 12px 14px;
  border: 0;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  text-align: left;
  cursor: pointer;
  transition: transform var(--dur) var(--ease),
    background-color var(--dur) var(--ease);
}

.memo:active {
  transform: scale(0.985);
}

.memo.featured {
  background: var(--card-inverse);
  color: var(--on-dark);
}

.memo-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-2);
  flex: none;
}

.featured .memo-icon {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.memo-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.memo-title {
  font-size: 15px;
  font-weight: 500;
  line-height: 1.35;
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}

.memo.closed .memo-title {
  color: var(--ink-3);
}

.memo-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.4;
  overflow: hidden;
  white-space: nowrap;
  text-overflow: ellipsis;
}

.memo-meta .late {
  color: var(--danger);
  font-weight: 500;
}

.memo-imp {
  padding-left: 6px;
  border-left: 1px solid var(--hairline);
}

.featured .memo-meta {
  color: var(--on-dark-3);
}

.memo-time {
  align-self: start;
  padding-top: 1px;
  color: var(--ink-3);
  font-size: 12.5px;
  letter-spacing: 0;
}

.featured .memo-time {
  color: var(--on-dark-2);
}
</style>
