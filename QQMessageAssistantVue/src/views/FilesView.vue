<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ArrowLeft, FileSpreadsheet } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import Sheet from '@/components/Sheet.vue'
import Skeleton from '@/components/Skeleton.vue'
import StatusPill from '@/components/StatusPill.vue'
import { listFileTasks } from '@/api/files'
import type { FileTask } from '@/api/types'
import { useRefresh } from '@/composables/useRefresh'
import { formatDateTime } from '@/utils/datetime'
import { FILE_GROUPS, fileStatusMeta } from '@/utils/meta'

const tasks = ref<FileTask[]>([])
const loading = ref(true)
const error = ref('')

const selected = ref<FileTask | null>(null)
const sheetOpen = ref(false)

async function load() {
  loading.value = true
  error.value = ''
  try {
    tasks.value = await listFileTasks()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '文件任务读取失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

useRefresh(load)

const groups = computed(() =>
  FILE_GROUPS.map((group) => ({
    key: group.key,
    label: group.label,
    items: tasks.value
      .filter((task) => (group.statuses as readonly string[]).includes(task.status))
      .sort((a, b) => b.updatedAt.localeCompare(a.updatedAt)),
  })).filter((group) => group.items.length),
)

function open(task: FileTask) {
  selected.value = task
  sheetOpen.value = true
}

const detailText = computed(() => {
  const detail = selected.value?.detail
  if (!detail) return ''
  if (typeof detail === 'string') return detail
  try {
    return JSON.stringify(detail, null, 2)
  } catch {
    return String(detail)
  }
})

const selectedMeta = computed(() => fileStatusMeta(selected.value?.status ?? ''))
</script>

<template>
  <div class="page">
    <header class="page-head">
      <RouterLink to="/more" class="back">
        <ArrowLeft :size="16" :stroke-width="2" aria-hidden="true" />
        更多
      </RouterLink>
      <h1 class="page-title">文件任务</h1>
      <p class="page-sub">LangBot 那边每处理一份表格都会镜像到这里，只读。</p>
    </header>

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load" />

    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else-if="groups.length">
      <section v-for="group in groups" :key="group.key" class="group">
        <h2 class="group-title">{{ group.label }} <span class="num">{{ group.items.length }}</span></h2>
        <ul class="stack">
          <li v-for="task in group.items" :key="task.id">
            <button type="button" class="task" @click="open(task)">
              <span class="task-icon" aria-hidden="true">
                <FileSpreadsheet :size="17" :stroke-width="1.8" />
              </span>
              <span class="task-main">
                <span class="task-name">{{ task.fileName || '未命名文件' }}</span>
                <span class="task-meta mono">{{ task.taskRef }}</span>
              </span>
              <span class="task-side">
                <StatusPill
                  :label="fileStatusMeta(task.status).label"
                  :tone="fileStatusMeta(task.status).tone"
                />
                <span class="task-time num">{{ formatDateTime(task.updatedAt) }}</span>
              </span>
            </button>
          </li>
        </ul>
      </section>
    </template>

    <EmptyState
      v-else
      title="还没有文件任务"
      hint="在 QQ 里发一份 xlsx 或 docx，处理进度会出现在这里。"
    />

    <Sheet
      :open="sheetOpen"
      :title="selected?.fileName || '文件任务'"
      :description="selected ? selected.taskRef : undefined"
      @close="sheetOpen = false"
    >
      <template v-if="selected">
        <div class="facts">
          <div class="fact">
            <span class="fact-label">状态</span>
            <StatusPill :label="selectedMeta.label" :tone="selectedMeta.tone" />
          </div>
          <div class="fact">
            <span class="fact-label">会话</span>
            <span class="fact-value num">{{ selected.chatId ?? '私聊' }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">创建</span>
            <span class="fact-value">{{ formatDateTime(selected.createdAt) }}</span>
          </div>
          <div class="fact">
            <span class="fact-label">更新</span>
            <span class="fact-value">{{ formatDateTime(selected.updatedAt) }}</span>
          </div>
        </div>

        <template v-if="detailText">
          <h3 class="detail-title">状态详情</h3>
          <pre class="detail-json">{{ detailText }}</pre>
        </template>
      </template>

      <template #footer>
        <button type="button" class="btn btn-on-dark" @click="sheetOpen = false">关闭</button>
      </template>
    </Sheet>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 10px;
  color: var(--ink-2);
  font-size: 13px;
}

.group + .group {
  margin-top: 22px;
}

.group-title {
  display: flex;
  align-items: baseline;
  gap: 6px;
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.group-title .num {
  font-weight: 400;
}

.stack {
  margin: 0;
  padding: 0;
  list-style: none;
}

.task {
  display: grid;
  grid-template-columns: 36px minmax(0, 1fr) auto;
  align-items: center;
  gap: 11px;
  width: 100%;
  padding: 13px 14px;
  border: 0;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
  text-align: left;
  cursor: pointer;
  transition: transform var(--dur) var(--ease);
}

.task:active {
  transform: scale(0.985);
}

.task-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: var(--surface);
  color: var(--ink-2);
}

.task-main {
  display: grid;
  gap: 3px;
  min-width: 0;
}

.task-name {
  font-size: 14.5px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.task-meta {
  color: var(--ink-3);
}

.task-side {
  display: grid;
  justify-items: end;
  gap: 4px;
}

.task-time {
  color: var(--ink-3);
  font-size: 10.5px;
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
  align-items: center;
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
}

.fact-value {
  font-size: 13.5px;
}

.detail-title {
  margin: 18px 0 8px;
  color: var(--on-dark-2);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
}

.detail-json {
  margin: 0;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: rgba(255, 255, 255, 0.06);
  color: var(--on-dark-2);
  font-family: var(--font-mono);
  font-size: 11px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

</style>
