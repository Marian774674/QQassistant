<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ChevronRight,
  FileSpreadsheet,
  MessagesSquare,
  ScrollText,
  Settings,
  ShieldCheck,
  Sparkles,
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

/**
 * 入口按「这是什么」分组，不再是一条条平铺：
 * 记忆是自己的人设，群与规则是机器人在哪儿做事，运行记录是出问题时看的地方。
 */
const groups = [
  {
    key: 'memory',
    label: '关于我',
    items: [
      {
        to: '/memory',
        label: '记忆',
        hint: '事实、认识的人和说话风格',
        icon: Sparkles,
      },
    ],
  },
  {
    key: 'chats',
    label: '群与规则',
    items: [
      {
        to: '/chat-settings',
        label: '群设置',
        hint: '哪些群参与整理、要不要自动回复',
        icon: MessagesSquare,
      },
      {
        to: '/rules',
        label: '拦截规则',
        hint: '关键词与正则，热更新',
        icon: ShieldCheck,
      },
    ],
  },
  {
    key: 'records',
    label: '运行记录',
    items: [
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
        icon: ScrollText,
      },
    ],
  },
  {
    key: 'system',
    label: '系统',
    items: [
      {
        to: '/settings',
        label: '设置',
        hint: '身份、自动回复与后端配置',
        icon: Settings,
      },
    ],
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
      <p class="eyebrow">{{ owner.senderId ? `QQ ${owner.senderId}` : '还没绑定身份' }}</p>
      <h1 class="page-title">更多</h1>
      <p class="page-sub">
        {{
          owner.senderId
            ? '记忆、群与规则、运行记录都收在这里，日常用不到。'
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

    <section v-for="group in groups" :key="group.key" class="group" :aria-label="group.label">
      <h2 class="group-label">{{ group.label }}</h2>
      <ul class="rows">
        <li v-for="item in group.items" :key="item.to">
          <RouterLink :to="item.to" class="row">
            <span class="row-icon" aria-hidden="true">
              <component :is="item.icon" :size="17" :stroke-width="1.8" />
            </span>
            <span class="row-text">
              <span class="row-label">{{ item.label }}</span>
              <span class="row-hint">{{ item.hint }}</span>
            </span>
            <ChevronRight :size="15" :stroke-width="2" class="row-arrow" aria-hidden="true" />
          </RouterLink>
        </li>
      </ul>
    </section>

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
  box-shadow: 0 14px 34px -26px rgba(17, 18, 20, 0.7);
}

.stat {
  display: grid;
  justify-items: center;
  gap: 3px;
  padding: 17px 4px 15px;
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
  font-size: 25px;
  font-weight: 600;
  line-height: 1.1;
  letter-spacing: -0.035em;
}

.stat-num.alert {
  color: #ff9d92;
}

.stat-label {
  color: var(--on-dark-3);
  font-size: 11px;
  letter-spacing: 0.03em;
}

.sync {
  margin: 9px 2px 22px;
  color: var(--ink-3);
  font-size: 11px;
}

/* ── 分组入口 ─────────────────────────────────────────────── */

.group + .group {
  margin-top: 20px;
}

.group-label {
  padding: 0 4px 8px;
  color: var(--ink-3);
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
}

.rows {
  margin: 0;
  padding: 0;
  list-style: none;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 60px;
  padding: 12px 15px;
  color: var(--ink);
  text-decoration: none;
  transition: background-color var(--dur-1) var(--ease-out);
}

.row:active {
  background: var(--surface);
}

.row-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-2);
  flex: none;
}

.row-text {
  flex: 1;
  display: grid;
  gap: 1px;
  min-width: 0;
}

.row-label {
  font-size: 14.5px;
  font-weight: 500;
  letter-spacing: -0.01em;
}

.row-hint {
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.row-arrow {
  color: var(--ink-3);
  opacity: 0.7;
  flex: none;
}

.version {
  margin-top: 30px;
  color: var(--ink-3);
  font-size: 11px;
  letter-spacing: 0.02em;
  text-align: center;
}
</style>
