<script setup lang="ts">
import { computed, ref } from 'vue'
import { Check, X } from 'lucide-vue-next'
import StatusPill from '@/components/StatusPill.vue'
import { confirmDraft, rejectDraft } from '@/api/drafts'
import type { Draft, DraftType } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'
import { draftStatusMeta, draftTypeIcon, draftTypeLabel } from '@/utils/meta'

const props = defineProps<{ draft: Draft }>()

const emit = defineEmits<{ resolved: [id: number, status: 'confirmed' | 'rejected'] }>()

const toast = useToast()
const busy = ref(false)

/** 每种草稿确认后真正发生的事，写清楚，别让人猜 */
const ACTION_LABEL: Record<DraftType, string> = {
  REPLY: '发送',
  FILE: '发送',
  MEMO: '记下',
  FACT: '记下',
}

const payload = computed<Record<string, unknown>>(() => props.draft.payload ?? {})
const isPending = computed(() => props.draft.status === 'PENDING')
const typeIcon = computed(() => draftTypeIcon(props.draft.type))
const statusMeta = computed(() => draftStatusMeta(props.draft.status))

function field(key: string): string {
  const value = payload.value[key]
  return typeof value === 'string' ? value : ''
}

const headline = computed(() => {
  const { title, type } = props.draft
  if (title) return title
  if (type === 'REPLY') return field('content').slice(0, 60) || '一条回复'
  if (type === 'MEMO') return field('title') || '一条备忘'
  if (type === 'FACT') return `${field('factKey') || '事实'}：${field('factValue')}`
  if (type === 'FILE') return field('fileName') || '一个文件'
  return draftTypeLabel(type)
})

const preview = computed(() => {
  const { type } = props.draft
  if (type === 'REPLY') {
    // 有标题时标题是摘要，正文必须完整露出来，否则确认前看不到要发什么
    return props.draft.title ? field('content') : ''
  }
  if (type === 'MEMO') {
    const parts = [field('content'), field('dueTime') ? formatDateTime(field('dueTime')) : '']
    return parts.filter(Boolean).join(' ／ ')
  }
  if (type === 'FACT') {
    const confidence = payload.value.confidence
    return typeof confidence === 'number' ? `把握 ${Math.round(confidence * 100)}%` : ''
  }
  if (type === 'FILE') return field('taskRef') ? `任务 ${field('taskRef')}` : ''
  return ''
})

async function resolve(action: 'confirm' | 'reject') {
  busy.value = true
  try {
    if (action === 'confirm') {
      await confirmDraft(props.draft.id)
      toast.success('已确认')
      emit('resolved', props.draft.id, 'confirmed')
    } else {
      await rejectDraft(props.draft.id)
      toast.push('已拒绝，草稿留档')
      emit('resolved', props.draft.id, 'rejected')
    }
  } catch (error) {
    toast.error(error instanceof Error ? error.message : '操作失败')
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <article class="draft" :class="{ closed: !isPending }">
    <header class="head">
      <span class="type">
        <component :is="typeIcon" :size="12" :stroke-width="2" aria-hidden="true" />
        {{ draftTypeLabel(draft.type) }}
      </span>
      <span class="time num">{{ formatDateTime(draft.createdAt) }}</span>
      <StatusPill v-if="!isPending" :label="statusMeta.label" :tone="statusMeta.tone" :dot="false" />
    </header>

    <p class="headline">{{ headline }}</p>
    <p v-if="preview" class="preview clamp-3">{{ preview }}</p>

    <div v-if="isPending" class="actions">
      <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="resolve('confirm')">
        <Check :size="14" :stroke-width="2.6" aria-hidden="true" />
        {{ ACTION_LABEL[draft.type] }}
      </button>
      <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="resolve('reject')">
        <X :size="14" :stroke-width="2.4" aria-hidden="true" />
        拒绝
      </button>
    </div>
  </article>
</template>

<style scoped>
.draft {
  padding: 14px 15px 15px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.draft.closed {
  background: rgba(255, 255, 255, 0.62);
  box-shadow: none;
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 9px;
}

.type {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: var(--r-pill);
  background: var(--card-inverse);
  color: #fff;
  font-size: 11px;
  font-weight: 500;
}

.closed .type {
  background: rgba(17, 18, 20, 0.07);
  color: var(--ink-2);
}

.time {
  color: var(--ink-3);
  font-size: 11.5px;
}

.head .status {
  margin-left: auto;
}

.headline {
  font-size: 15.5px;
  font-weight: 500;
  line-height: 1.42;
  letter-spacing: -0.01em;
  overflow-wrap: anywhere;
}

.closed .headline {
  color: var(--ink-2);
}

.preview {
  margin-top: 5px;
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}
</style>
