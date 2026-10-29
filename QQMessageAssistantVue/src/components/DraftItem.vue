<script setup lang="ts">
import { computed, ref } from 'vue'
import { confirmDraft, rejectDraft } from '@/api/drafts'
import type { Draft, DraftType } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { draftStatusLabel, draftTypeLabel, formatDateTime } from '@/utils/datetime'

const props = defineProps<{ draft: Draft; compact?: boolean }>()

const emit = defineEmits<{ resolved: [id: number, status: 'confirmed' | 'rejected'] }>()

const toast = useToast()
const busy = ref(false)

/** 每种草稿确认后真正发生的事，写清楚，别让人猜 */
const ACTION_LABEL: Record<DraftType, string> = {
  REPLY: '确认发送',
  FILE: '确认发送',
  MEMO: '确认记下',
  FACT: '确认记下',
}

const payload = computed<Record<string, unknown>>(() => props.draft.payload ?? {})
const isPending = computed(() => props.draft.status === 'PENDING')

function field(key: string): string {
  const value = payload.value[key]
  return typeof value === 'string' ? value : ''
}

const headline = computed(() => {
  const { title, type } = props.draft
  if (title) return title
  if (type === 'REPLY') return field('content').slice(0, 48) || '一条回复'
  if (type === 'MEMO') return field('title') || '一条备忘'
  if (type === 'FACT') return `${field('factKey') || '事实'}：${field('factValue')}`
  if (type === 'FILE') return field('fileName') || '一个文件'
  return draftTypeLabel(type)
})

const preview = computed(() => {
  const { type } = props.draft
  if (type === 'REPLY') return field('content')
  if (type === 'MEMO') {
    const parts = [field('content'), field('dueTime') ? formatDateTime(field('dueTime')) : '']
    return parts.filter(Boolean).join(' ／ ')
  }
  if (type === 'FACT') {
    const confidence = payload.value.confidence
    return typeof confidence === 'number' ? `把握 ${Math.round(confidence * 100)}%` : ''
  }
  if (type === 'FILE') return field('fileName')
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
  <article class="draft" :class="[`type-${draft.type}`, { compact, closed: !isPending }]">
    <header class="head">
      <span class="type">{{ draftTypeLabel(draft.type) }}</span>
      <span class="time num">{{ formatDateTime(draft.createdAt) }}</span>
      <span v-if="!isPending" class="state">{{ draftStatusLabel(draft.status) }}</span>
    </header>

    <p class="headline display">{{ headline }}</p>
    <p v-if="preview && !compact" class="preview">{{ preview }}</p>

    <div v-if="isPending" class="actions">
      <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="resolve('confirm')">
        {{ ACTION_LABEL[draft.type] }}
      </button>
      <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="resolve('reject')">
        拒绝
      </button>
    </div>
  </article>
</template>

<style scoped>
.draft {
  padding: 14px 16px 14px 15px;
  border: 1px solid var(--line);
  border-left: 3px solid var(--ink-faint);
  border-radius: 2px;
  background: var(--sheet);
}

.type-REPLY {
  border-left-color: var(--signal);
}
.type-FILE {
  border-left-color: var(--u4);
}
.type-MEMO {
  border-left-color: var(--u3);
}
.type-FACT {
  border-left-color: var(--u-low);
}

.closed {
  background: rgba(255, 255, 255, 0.55);
  border-left-color: var(--line);
}

.head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  margin-bottom: 6px;
}

.type {
  color: var(--ink-soft);
  font-size: 12px;
  font-weight: 600;
}

.time {
  color: var(--ink-faint);
  font-size: 12px;
}

.state {
  margin-left: auto;
  color: var(--ink-soft);
  font-size: 12px;
}

.headline {
  font-size: 16px;
  line-height: 1.4;
  overflow-wrap: anywhere;
}

.preview {
  margin-top: 6px;
  color: var(--ink-soft);
  font-size: 13.5px;
  line-height: 1.6;
  white-space: pre-wrap;
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.compact {
  padding: 11px 12px 11px 11px;
}

.compact .headline {
  font-size: 14.5px;
}

.compact .actions {
  margin-top: 8px;
}
</style>
