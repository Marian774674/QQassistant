<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Check, X } from 'lucide-vue-next'
import StatusPill from '@/components/StatusPill.vue'
import { confirmDraft, rejectDraft } from '@/api/drafts'
import type { Draft, DraftType } from '@/api/types'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'
import { draftStatusMeta, draftTypeIcon, draftTypeLabel } from '@/utils/meta'
import { firstSegment, joinNames } from '@/utils/name'

const props = defineProps<{ draft: Draft }>()

const emit = defineEmits<{ resolved: [id: number, status: 'confirmed' | 'rejected'] }>()

const toast = useToast()
const busy = ref(false)
const confirming = ref(false)
let confirmTimer = 0

/** 每种草稿确认后真正发生的事，写清楚，别让人猜 */
const ACTION_LABEL: Record<DraftType, string> = {
  REPLY: '发送',
  FILE: '发送',
  MEMO: '记下',
  FACT: '记下',
  NAMING: '保存名字',
}

/** 回复和文件是「以你的身份发出去」，发出去就收不回，必须先问一句 */
const IRREVERSIBLE: DraftType[] = ['REPLY', 'FILE']

const payload = computed<Record<string, unknown>>(() => props.draft.payload ?? {})
const isPending = computed(() => props.draft.status === 'PENDING')
const typeIcon = computed(() => draftTypeIcon(props.draft.type))
const statusMeta = computed(() => draftStatusMeta(props.draft.status))
const needsConfirm = computed(() => IRREVERSIBLE.includes(props.draft.type))
const confirmHint = computed(() =>
  props.draft.type === 'FILE' ? '文件会直接发到对应会话，发出后无法撤回。' : '这条回复会以你的身份发出，发出后无法撤回。',
)

onBeforeUnmount(() => window.clearTimeout(confirmTimer))

function requestConfirm() {
  if (!needsConfirm.value) {
    void resolve('confirm')
    return
  }
  confirming.value = true
  window.clearTimeout(confirmTimer)
  confirmTimer = window.setTimeout(() => (confirming.value = false), 6000)
}

function cancelConfirm() {
  window.clearTimeout(confirmTimer)
  confirming.value = false
}

function field(key: string): string {
  const value = payload.value[key]
  return typeof value === 'string' ? value : ''
}

/** 命名草稿的输入框：预填建议名（群名/昵称），用户可以改成任意多个 */
const nameInput = ref(field('suggestedName'))

/**
 * 回复草稿要让人看清「在回谁」。
 * 新草稿把对方那句话存在 payload.sourceContent；老草稿没有，只能退回 title——
 * 但 title 要是和正文一样（旧版本拿正文前 20 字当标题），就不能再显示一遍。
 */
const context = computed(() => {
  if (props.draft.type !== 'REPLY') return ''
  const source = field('sourceContent').trim()
  if (source) return source
  const title = (props.draft.title ?? '').trim()
  return title && title !== field('content').trim() ? title : ''
})

/**
 * 对方的名字：用户起过名字就用第一个（后端放 sourceDisplayName），
 * 再把插件记下的昵称附在后面——群聊里「群名 · 发言人」才看得清是谁。
 * 两个字段都可能是逗号串起来的多个名字，所以各自只取第一段，
 * 否则同一个名字会连着出现两三遍，把整段引用挤成两行。
 */
const contextName = computed(() =>
  joinNames(firstSegment(field('sourceDisplayName')), firstSegment(field('sourceSenderName'))),
)

/** 悬停时才看得到的完整名字（含被省略号吃掉的别名），不参与布局 */
const contextFullName = computed(() =>
  joinNames(field('sourceDisplayName'), field('sourceSenderName')),
)

const headline = computed(() => {
  const { title, type } = props.draft
  // 回复的主句永远是「要发出去的那句话」，标题只是摘要，不能替代它
  if (type === 'REPLY') return field('content') || title || '一条回复'
  if (title) return title
  if (type === 'MEMO') return field('title') || '一条备忘'
  if (type === 'FACT') return `${field('factKey') || '事实'}：${field('factValue')}`
  if (type === 'FILE') return field('fileName') || '一个文件'
  return draftTypeLabel(type)
})

const preview = computed(() => {
  const { type } = props.draft
  // REPLY 的正文已经是主句，再放一遍预览就是同一句话出现两遍
  if (type === 'REPLY') return ''
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
  const aliases = nameInput.value.trim()
  if (action === 'confirm' && props.draft.type === 'NAMING' && !aliases) {
    toast.error('先给它起个名字')
    return
  }
  busy.value = true
  try {
    if (action === 'confirm') {
      await confirmDraft(props.draft.id, props.draft.type === 'NAMING' ? { aliases } : undefined)
      toast.success(props.draft.type === 'NAMING' ? '名字已保存' : '已确认')
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

    <p v-if="context" class="context">
      <span class="context-label">对方{{ contextName ? ' · ' : '' }}</span>
      <span v-if="contextName" class="context-name" :title="contextFullName">{{ contextName }}</span>
      {{ context }}
    </p>
    <p class="headline">{{ headline }}</p>
    <p v-if="preview" class="preview clamp-3">{{ preview }}</p>

    <div v-if="isPending && draft.type === 'NAMING'" class="naming">
      <input
        v-model="nameInput"
        type="text"
        class="naming-input"
        maxlength="255"
        placeholder="多个名字用逗号分隔，例如：三班群,班群"
        aria-label="给这个会话起个名字"
      />
      <p class="naming-hint">第一个名字用于展示，全部保留下来方便以后匹配。</p>
    </div>

    <div v-if="isPending && confirming" class="confirm">
      <p class="confirm-text">{{ confirmHint }}</p>
      <div class="actions">
        <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="cancelConfirm">
          再想想
        </button>
        <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="resolve('confirm')">
          <Check :size="14" :stroke-width="2.6" aria-hidden="true" />
          确认{{ ACTION_LABEL[draft.type] }}
        </button>
      </div>
    </div>

    <div v-else-if="isPending" class="actions">
      <button type="button" class="btn btn-sm btn-primary" :disabled="busy" @click="requestConfirm">
        <Check :size="14" :stroke-width="2.6" aria-hidden="true" />
        {{ ACTION_LABEL[draft.type] }}
      </button>
      <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="resolve('reject')">
        <X :size="14" :stroke-width="2.4" aria-hidden="true" />
        {{ draft.type === 'NAMING' ? '不用了' : '拒绝' }}
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
  background: rgba(46, 38, 26, 0.07);
  color: var(--ink-2);
}

.time {
  color: var(--ink-3);
  font-size: 11.5px;
}

.head .status {
  margin-left: auto;
}

/* 对方那句话：引线在左，压低一档，别和要发出去的正文抢注意力 */
.context {
  display: -webkit-box;
  margin-bottom: 7px;
  padding-left: 9px;
  border-left: 2px solid var(--hairline);
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.55;
  overflow: hidden;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.context-label {
  font-weight: 600;
}

/* 名字再长也只占一行，超出打省略号；摸上去可以横向滚动看全（和会话列表一致） */
.context-name {
  display: inline-block;
  max-width: 68%;
  margin-right: 5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: bottom;
  user-select: none;
}

.context-name:hover,
.context-name:active {
  overflow-x: auto;
  text-overflow: clip;
  scrollbar-width: none;
}

.context-name::-webkit-scrollbar {
  display: none;
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

.naming {
  margin-top: 11px;
}

.naming-input {
  width: 100%;
  padding: 9px 11px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--card-soft, rgba(255, 255, 255, 0.7));
  color: var(--ink);
  font: inherit;
  font-size: 14px;
}

.naming-input:focus {
  outline: none;
  border-color: var(--ink-3);
}

.naming-hint {
  margin-top: 5px;
  color: var(--ink-3);
  font-size: 11.5px;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

.confirm {
  margin-top: 12px;
  padding: 11px 12px 12px;
  border-radius: var(--r-md);
  background: var(--danger-wash);
}

.confirm-text {
  color: var(--danger);
  font-size: 12.5px;
  line-height: 1.55;
}

.confirm .actions {
  margin-top: 10px;
}
</style>
