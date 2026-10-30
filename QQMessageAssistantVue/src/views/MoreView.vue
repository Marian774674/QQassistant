<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ChevronRight,
  FileSpreadsheet,
  MessagesSquare,
  Settings,
  ShieldCheck,
} from 'lucide-vue-next'
import { listMemos } from '@/api/memos'
import type { Memo } from '@/api/types'
import { useDraftStore } from '@/composables/useDraftStore'
import { useOwner } from '@/composables/useOwner'
import { formatTime, parseLocal } from '@/utils/datetime'

const owner = useOwner()
const draftStore = useDraftStore()

const OPEN_STATUSES = new Set(['ACTIVE', 'REMINDED', 'AWAITING_REPLY', 'OVERDUE'])

const memos = ref<Memo[]>([])
const loading = ref(true)
const syncedAt = ref<Date | null>(null)

const items = [
  {
    to: '/chat-settings',
    label: '群设置',
    hint: '哪些群参与整理、要不要自动回复',
    icon: MessagesSquare,
  },
  {
    to: '/files',
    label: '文件任务',
    hint: '表格填写进度与产物',
    icon: FileSpreadsheet,
  },
  {
    to: '/logs',
    label: '消息日志',
    hint: '收发流水，排查用',
    icon: MessagesSquare,
  },
  {
    to: '/rules',
    label: '拦截规则',
    hint: '关键词与正则，热更新',
    icon: ShieldCheck,
  },
  {
    to: '/settings',
    label: '设置',
    hint: '身份、提醒策略与后端配置',
    icon: Settings,
  },
]

onMounted(async () => {
  void draftStore.refreshPendingDrafts()
  try {
    memos.value = await listMemos()
    syncedAt.value = new Date()
  } catch {
    // 概览只是顺手看一眼，拉不到就不摆数字，不打扰
  } finally {
    loading.value = false
  }
})

function startOfDay(date: Date) {
  return new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()
}

const openMemos = computed(() => memos.value.filter((memo) => OPEN_STATUSES.has(memo.status)))

const todayCount = computed(() => {
  const start = startOfDay(new Date())
  const end = start + 86_400_000
  return openMemos.value.filter((memo) => {
    const due = parseLocal(memo.dueTime)
    return due !== null && due.getTime() >= start && due.getTime() < end
  }).length
})

const overdueCount = computed(() => {
  const start = startOfDay(new Date())
  return openMemos.value.filter((memo) => {
    const due = parseLocal(memo.dueTime)
    return due !== null && due.getTime() < start
  }).length
})

const pendingCount = computed(() => draftStore.pendingCount.value)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="eyebrow">更多</p>
      <h1 class="page-title">{{ owner.senderId ? `QQ ${owner.senderId}` : '还没绑定身份' }}</h1>
      <p class="page-sub">
        {{
          owner.senderId
            ? '一眼看今天的量，下面是排查与配置入口，日常用不到。'
            : '先去设置里填上你的 QQ 号，备忘和记忆才会归到你名下。'
        }}
      </p>
    </header>

    <section class="overview" aria-label="今日概览">
      <RouterLink to="/memos" class="stat">
        <span class="stat-num num">{{ loading ? '—' : todayCount }}</span>
        <span class="stat-label">今天要做</span>
      </RouterLink>
      <RouterLink to="/memos" class="stat">
        <span class="stat-num num" :class="{ alert: overdueCount > 0 }">
          {{ loading ? '—' : overdueCount }}
        </span>
        <span class="stat-label">已逾期</span>
      </RouterLink>
      <RouterLink to="/drafts" class="stat">
        <span class="stat-num num" :class="{ alert: pendingCount > 0 }">{{ pendingCount }}</span>
        <span class="stat-label">待确认</span>
      </RouterLink>
    </section>
    <p class="sync">
      {{ syncedAt ? `数据更新于 ${formatTime(syncedAt)}` : loading ? '正在读取…' : '没读到数据，下拉可重试' }}
    </p>

    <nav class="rows" aria-label="更多功能">
      <RouterLink v-for="item in items" :key="item.to" :to="item.to" class="row">
        <span class="row-icon" aria-hidden="true">
          <component :is="item.icon" :size="17" :stroke-width="1.8" />
        </span>
        <span class="row-text">
          <span class="row-label">{{ item.label }}</span>
          <span class="row-hint">{{ item.hint }}</span>
        </span>
        <ChevronRight :size="16" :stroke-width="2" class="row-arrow" aria-hidden="true" />
      </RouterLink>
    </nav>

    <p class="version">值守台 · 本地单实例</p>
  </div>
</template>

<style scoped>
/* ── 今日概览 ─────────────────────────────────────────────── */

.overview {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  overflow: hidden;
  border-radius: var(--r-xl);
  background: var(--card-inverse);
  color: var(--on-dark);
}

.stat {
  display: grid;
  justify-items: center;
  gap: 2px;
  padding: 18px 4px 16px;
  color: inherit;
  text-decoration: none;
  transition: background-color var(--dur-1) var(--ease-out);
}

.stat:active {
  background: rgba(255, 255, 255, 0.06);
}

.stat + .stat {
  border-left: 1px solid var(--hairline-dark);
}

.stat-num {
  font-size: 27px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.03em;
}

.stat-num.alert {
  color: #ff9d92;
}

.stat-label {
  color: var(--on-dark-3);
  font-size: 11.5px;
  letter-spacing: 0.02em;
}

.sync {
  margin: 8px 2px 20px;
  color: var(--ink-3);
  font-size: 11px;
}

/* ── 入口 ─────────────────────────────────────────────────── */

.rows {
  display: block;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 62px;
  padding: 12px 14px;
  color: var(--ink);
  text-decoration: none;
}

.row:active {
  background: var(--surface);
}

.row-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: var(--surface);
  color: var(--ink-2);
  flex: none;
}

.row-text {
  flex: 1;
  display: grid;
  gap: 2px;
  min-width: 0;
}

.row-label {
  font-size: 15px;
  font-weight: 500;
}

.row-hint {
  color: var(--ink-3);
  font-size: 11.5px;
}

.row-arrow {
  color: var(--ink-3);
  flex: none;
}

.version {
  margin-top: 26px;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: center;
}
</style>
