<script setup lang="ts">
import { computed } from 'vue'
import { Check } from 'lucide-vue-next'
import { categoryIcon, importanceLabel } from '@/utils/meta'
import {
  formatMonthDay,
  formatTime,
  isSameDay,
  parseLocal,
  relativeUntil,
} from '@/utils/datetime'
import type { Memo } from '@/api/types'

const props = defineProps<{
  memo: Memo
  /** 用深色卡突出「接下来这一件」 */
  featured?: boolean
  /** View Transitions 的共享元素名，只在点开的那一帧挂上 */
  vtName?: string | null
  /** 父级统一的时间基准，用来判断「是否已过时」，避免每行各算各的 */
  now?: number
  /** 列表已按天分组时，组标题就是日期，行内不必再重复一遍 */
  grouped?: boolean
}>()

const emit = defineEmits<{ open: [memo: Memo]; done: [memo: Memo] }>()

const due = computed(() => parseLocal(props.memo.dueTime))
const timeText = computed(() => (due.value ? formatTime(due.value) : ''))
const icon = computed(() => categoryIcon(props.memo.category))
const isClosed = computed(
  () => props.memo.status === 'DONE' || props.memo.status === 'CANCELLED',
)

const nowTs = computed(() => props.now ?? Date.now())

/** 状态没来得及翻成 OVERDUE 也算逾期：只看时间，别等人刷新 */
const isOverdue = computed(() => {
  if (isClosed.value || !due.value) return false
  return due.value.getTime() < nowTs.value
})

/** 不是今天的截止时间，时间块上方补一个日期，否则只剩 20:00 会误读成今天 */
const dayText = computed(() => {
  if (!due.value || props.grouped) return ''
  return isSameDay(due.value, new Date(nowTs.value)) ? '' : formatMonthDay(due.value)
})

const metaText = computed(() => {
  const parts: string[] = []
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
      :class="{ done: isClosed, late: isOverdue }"
      :aria-label="isClosed ? `「${memo.title}」已结束` : `标记完成：${memo.title}`"
      :disabled="isClosed"
      @click="emit('done', memo)"
    >
      <Check v-if="isClosed" :size="12" :stroke-width="3" aria-hidden="true" />
    </button>

    <button
      type="button"
      class="memo"
      :class="{ featured, closed: isClosed, overdue: isOverdue }"
      :style="vtName ? { viewTransitionName: vtName } : undefined"
      @click="emit('open', memo)"
    >
      <span class="memo-icon" aria-hidden="true">
        <component :is="icon" :size="16" :stroke-width="1.8" />
      </span>

      <span class="memo-main">
        <span class="memo-title">{{ memo.title }}</span>
        <span class="memo-meta">
          <span :class="{ late: isOverdue }">{{ metaText }}</span>
          <span v-if="!isClosed" class="memo-imp">{{ importanceLabel(memo.importance) }}</span>
        </span>
      </span>

      <span v-if="timeText" class="memo-time num" :class="{ late: isOverdue }">
        <span v-if="dayText" class="memo-day">{{ dayText }}</span>
        {{ timeText }}
      </span>
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
  width: 21px;
  height: 21px;
  padding: 0;
  border: 1px solid rgba(122, 112, 96, 0.42);
  border-radius: 50%;
  background: var(--bg);
  color: var(--bg);
  cursor: pointer;
  transition: border-color var(--dur) var(--ease),
    background-color var(--dur) var(--ease),
    transform var(--dur-1) var(--ease-spring);
}

.tl-dot:active:not(:disabled) {
  transform: scale(0.84);
}

.tl-dot.late {
  border-color: var(--danger);
  border-width: 1.5px;
}

.tl-dot.done {
  border-color: var(--card-inverse);
  background: var(--card-inverse);
  color: var(--card);
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
  border: 1px solid var(--hairline);
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  text-align: left;
  cursor: pointer;
  transition: transform var(--dur) var(--ease),
    background-color var(--dur) var(--ease),
    box-shadow var(--dur) var(--ease);
}

.memo:active {
  transform: scale(0.985);
}

/* 逾期：左侧一道窄红边，扫一眼就知道哪条欠着 */
.memo.overdue {
  border-color: rgba(191, 58, 43, 0.22);
  box-shadow: inset 3px 0 0 var(--danger), var(--shadow-card);
}

.memo.featured {
  background: linear-gradient(158deg, #221f26 0%, #16151a 62%, #121116 100%);
  border-color: transparent;
  color: var(--on-dark);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.07), var(--shadow-lift);
}

.memo-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
  flex: none;
}

.featured .memo-icon {
  border-color: rgba(255, 255, 255, 0.14);
  color: var(--on-dark);
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

/*
 * 时间列是这一行的「排版锚点」：衬线数字。
 * 整页正文都是无衬线，只有这一列和页头大数字用衬线——
 * 两处衬线遥相呼应，版面才像被设计过，而不是被排满过。
 */
.memo-time {
  align-self: start;
  display: grid;
  justify-items: end;
  gap: 2px;
  padding-top: 0;
  color: var(--ink-2);
  font-family: var(--font-display);
  font-size: 16px;
  font-variant-numeric: lining-nums tabular-nums;
  letter-spacing: -0.01em;
  line-height: 1.1;
}

.memo-time.late {
  color: var(--danger);
}

/* 日期是辅助信息，回到微标签：小、宽字距、大写感 */
.memo-day {
  color: var(--ink-3);
  font-family: var(--font-ui);
  font-size: 9px;
  font-weight: 600;
  letter-spacing: 0.1em;
  text-transform: uppercase;
}

.memo-time.late .memo-day {
  color: var(--danger);
  opacity: 0.75;
}

.featured .memo-time {
  color: var(--on-dark);
}

.featured .memo-day {
  color: var(--on-dark-3);
}
</style>
