<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowDownLeft, ArrowUpRight } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Segmented from '@/components/Segmented.vue'
import Skeleton from '@/components/Skeleton.vue'
import { listMessageLogs } from '@/api/logs'
import type { LogDirection, MessageLog } from '@/api/types'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { dayKey, formatHeadDate, formatTime, parseLocal } from '@/utils/datetime'

type Filter = 'ALL' | 'IN' | 'OUT'

const toast = useToast()

const logs = ref<MessageLog[]>([])
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const filter = ref<Filter>('ALL')
const limit = ref(100)

const LIMITS = [100, 200, 500]

/** silent = 下拉刷新：保留当前列表，不闪骨架屏 */
async function load(more = false, silent = false) {
  if (more) loadingMore.value = true
  else if (!silent) loading.value = true
  if (!silent) error.value = ''
  try {
    logs.value = await listMessageLogs({
      direction: filter.value === 'ALL' ? undefined : (filter.value as LogDirection),
      limit: limit.value,
    })
  } catch (e) {
    const message = e instanceof Error ? e.message : '日志读取失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
    loadingMore.value = false
  }
}

onMounted(() => void load())

watch(filter, () => {
  limit.value = 100
  void load()
})

function loadMore() {
  const next = LIMITS.find((value) => value > limit.value)
  if (!next) return
  limit.value = next
  void load(true)
}

useRefresh(() => load(false, true))

const canLoadMore = computed(() => logs.value.length >= limit.value && limit.value < 500)

interface LogEntry {
  log: MessageLog
  /** 同一个会话连续出现时只在第一条上标出来，避免每行重复 */
  showConv: boolean
}

interface LogGroup {
  key: string
  title: string
  items: LogEntry[]
}

const groups = computed<LogGroup[]>(() => {
  const map = new Map<string, LogGroup>()
  let lastConv: string | null = null
  for (const log of logs.value) {
    const date = parseLocal(log.createdAt)
    const key = date ? dayKey(date) : 'unknown'
    const showConv = Boolean(log.conversationId) && log.conversationId !== lastConv
    lastConv = log.conversationId

    const entry: LogEntry = { log, showConv }
    const group = map.get(key)
    if (group) {
      group.items.push(entry)
    } else {
      map.set(key, {
        key,
        title: date ? formatHeadDate(date) : '时间未知',
        items: [entry],
      })
    }
  }
  return [...map.values()]
})

const tabs = computed(() => [
  { key: 'ALL' as const, label: '全部' },
  { key: 'IN' as const, label: '收到' },
  { key: 'OUT' as const, label: '发出' },
])
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      title="Logs"
      sub="每一条进出 QQ 的消息都会记一笔，排查链路问题时先看这里。"
    />

    <Segmented v-model="filter" :items="tabs" label="按方向筛选" />

    <div class="body">
      <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load()" />

      <Skeleton v-else-if="loading" :rows="5" variant="log" />

      <template v-else-if="groups.length">
        <section v-for="group in groups" :key="group.key" class="group">
          <h2 class="group-title">{{ group.title }}</h2>
          <ul class="logs">
            <li v-for="entry in group.items" :key="entry.log.id" class="log">
              <span class="log-time num">{{ formatTime(entry.log.createdAt) }}</span>
              <div class="log-bubble" :class="entry.log.direction === 'OUT' ? 'out' : 'in'">
                <div class="log-head">
                  <span class="log-dir">
                    <ArrowDownLeft
                      v-if="entry.log.direction === 'IN'"
                      :size="11"
                      :stroke-width="2.6"
                      aria-hidden="true"
                    />
                    <ArrowUpRight v-else :size="11" :stroke-width="2.6" aria-hidden="true" />
                    {{ entry.log.direction === 'IN' ? '收到' : '发出' }}
                  </span>
                  <span v-if="entry.log.msgType !== 'text'" class="log-type">
                    {{ entry.log.msgType }}
                  </span>
                  <span v-if="entry.showConv && entry.log.conversationId" class="log-conv mono">
                    {{ entry.log.conversationId.slice(0, 8) }}
                  </span>
                </div>
                <p v-if="entry.log.content" class="log-text clamp-3">{{ entry.log.content }}</p>
                <p v-else class="log-text empty">（无文本内容）</p>
              </div>
            </li>
          </ul>
        </section>

        <button
          v-if="canLoadMore"
          type="button"
          class="btn btn-quiet btn-block more"
          :disabled="loadingMore"
          @click="loadMore"
        >
          {{ loadingMore ? '读取中…' : `加载更多（已显示 ${logs.length} 条）` }}
        </button>
        <p v-else class="faint tail">已经到最早的一条了</p>
      </template>

      <EmptyState v-else title="没有日志" hint="还没有消息进出，或者筛选条件下没有记录。在 QQ 里给机器人发一句试试。">
        <button type="button" class="btn btn-sm btn-quiet" @click="load()">重新读取</button>
      </EmptyState>
    </div>
  </div>
</template>

<style scoped>

.body {
  margin-top: 16px;
}

.group + .group {
  margin-top: 20px;
}

/* 分节标题由 style.css 的「栏目线」统一实现，这里只管块间距 */
.group-title {
  margin-bottom: 8px;
}

.logs {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.log {
  display: grid;
  grid-template-columns: 40px minmax(0, 1fr);
  gap: 8px;
  align-items: start;
}

.log-time {
  padding-top: 11px;
  color: var(--ink-3);
  font-size: 10.5px;
  text-align: right;
}

.log-bubble {
  padding: 9px 12px;
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.log-bubble.out {
  background: var(--card-inverse);
  color: var(--on-dark);
  box-shadow: none;
}

.log-head {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-bottom: 4px;
}

.log-dir {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  color: var(--ink-3);
  font-size: 10px;
  letter-spacing: 0.02em;
}

.out .log-dir {
  color: var(--on-dark-3);
}

.log-type {
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: rgba(46, 38, 26, 0.06);
  color: var(--ink-2);
  font-size: 10px;
}

.out .log-type {
  background: rgba(255, 255, 255, 0.12);
  color: var(--on-dark-2);
}

.log-conv {
  color: var(--ink-3);
  font-size: 10px;
}

.out .log-conv {
  color: var(--on-dark-3);
}

.log-text {
  font-size: 13.5px;
  line-height: 1.55;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

.log-text.empty {
  color: var(--ink-3);
}

.out .log-text.empty {
  color: var(--on-dark-3);
}

.more {
  margin-top: 18px;
}

.tail {
  margin-top: 16px;
  text-align: center;
}

</style>
