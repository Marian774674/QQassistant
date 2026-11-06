<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { Check, Pencil, X } from 'lucide-vue-next'
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

/** 等待期间按钮上的文案：动词换成进行时，别让按钮变成一块没有字的灰条 */
const WAIT_LABEL: Record<DraftType, string> = {
  REPLY: '发送中',
  FILE: '发送中',
  MEMO: '记录中',
  FACT: '记录中',
  NAMING: '保存中',
}

/**
 * 等太久了。
 *
 * 发送要绕一圈（后端 → 发送桥 → LangBot → QQ），几秒钟是常态。
 * 光转圈还不够：转久了人会怀疑是不是卡死。所以过了 2.5 秒补一句「还在等」，
 * 让「慢」和「坏了」能区分开——这一句比任何进度条都管用。
 *
 * 但话得说对：只有 REPLY / FILE 真的会走出这台服务器去找 QQ，
 * 备忘、事实、命名只是写库。对着一个写库的操作说「QQ 那边还没回话」，
 * 就是拿一句现成的话糊弄人。
 *
 * 后面那个秒数不是装饰：死掉的界面不会数数。数字一直在涨，就是最硬的
 * 「它还活着」的证据，比任何转圈动画都可信。
 */
const SLOW_AFTER = 2500
const SLOW_NOTE: Record<DraftType, string> = {
  REPLY: 'QQ 那边还没回话，再等一会儿',
  FILE: '文件还在路上，再等一会儿',
  MEMO: '还在写，再等一会儿',
  FACT: '还在写，再等一会儿',
  NAMING: '还在存，再等一会儿',
}

const slow = ref(false)
const waited = ref(0)
let slowTimer = 0
let tickTimer = 0

function beginWait() {
  slow.value = false
  waited.value = 0
  window.clearTimeout(slowTimer)
  window.clearInterval(tickTimer)
  slowTimer = window.setTimeout(() => {
    slow.value = true
  }, SLOW_AFTER)
  tickTimer = window.setInterval(() => {
    waited.value += 1
  }, 1000)
}

function endWait() {
  window.clearTimeout(slowTimer)
  window.clearInterval(tickTimer)
  slow.value = false
  waited.value = 0
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

/** 只有待处理的回复草稿能改正文：改的是「发什么」，不是「发不发」 */
const canEdit = computed(() => isPending.value && props.draft.type === 'REPLY')

/** 和后端 DraftServiceImpl.MAX_REPLY_LENGTH 对齐，前端先拦一次省一趟往返 */
const MAX_REPLY_LENGTH = 2000

const editing = ref(false)
const draftText = ref('')
const editorRef = ref<HTMLTextAreaElement | null>(null)

function startEdit() {
  draftText.value = field('content')
  cancelConfirm()
  editing.value = true
  void nextTick(() => editorRef.value?.focus())
}

function cancelEdit() {
  editing.value = false
  draftText.value = ''
}

/**
 * 编辑态下的「发送」就是确认，不再走二次确认：
 * 正文是用户刚亲手改过的，再问一遍「确定要发吗」只是多一次点击。
 * 非编辑态保留二次确认，因为那段话是模型写的，可能没细看就点了。
 */
async function sendEdited() {
  const text = draftText.value.trim()
  if (!text) {
    toast.error('回复内容不能为空')
    return
  }
  await resolve('confirm', text)
}

/** 只有真带了改写内容才放进 body：NAMING 只认 aliases，REPLY 只认 content */
function confirmBody(editedContent?: string) {
  if (props.draft.type === 'NAMING') return { aliases: nameInput.value.trim() }
  if (editedContent !== undefined) return { content: editedContent }
  return undefined
}

const confirmToast = computed(() => {
  if (props.draft.type === 'NAMING') return '名字已保存'
  // 回复是「真的发出去了」，说「已确认」会让人不确定到底发没发
  if (props.draft.type === 'REPLY') return '已发送'
  return '已确认'
})

onBeforeUnmount(() => {
  window.clearTimeout(confirmTimer)
  window.clearTimeout(slowTimer)
  window.clearInterval(tickTimer)
})

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
 *
 * 只认 payload.sourceContent。以前还拿 title 兜底，结果「回复：是否忘记带书」
 * 这种标题被当成**对方说的话**显示在「对方」那一行——既和正文重复一遍，
 * 又是一句假话：对方根本没说过这句话。
 * 不知道对方说了什么就什么都不显示，比编一句出来强。
 */
const context = computed(() => {
  if (props.draft.type !== 'REPLY') return ''
  return field('sourceContent').trim()
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

/**
 * 正文是空的。
 *
 * 模型偶尔会输出一条 `payload.content` 为空的回复草稿（提示词里曾允许「留空让用户去
 * 草稿中心写」）。这种草稿：卡片上不能拿 title 顶上来（会和「对方」那一行撞成两遍），
 * 「发送」也不能点——后端会拦，但让人点进一个必然失败的按钮更糟。
 */
const emptyReply = computed(() => props.draft.type === 'REPLY' && !field('content').trim())

const headline = computed(() => {
  const { title, type } = props.draft
  // 回复的主句永远是「要发出去的那句话」，标题只是摘要，不能替代它
  if (type === 'REPLY') return emptyReply.value ? '这条还没写内容' : field('content')
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

async function resolve(action: 'confirm' | 'reject', editedContent?: string) {
  const aliases = nameInput.value.trim()
  if (action === 'confirm' && props.draft.type === 'NAMING' && !aliases) {
    toast.error('先给它起个名字')
    return
  }
  busy.value = true
  beginWait()
  try {
    if (action === 'confirm') {
      await confirmDraft(props.draft.id, confirmBody(editedContent))
      editing.value = false
      toast.success(confirmToast.value)
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
    endWait()
  }
}
</script>

<template>
  <article
    class="draft"
    :class="{ closed: !isPending, 'is-sending': busy }"
    :aria-busy="busy"
  >
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
    <p v-if="!editing" class="headline" :class="{ 'is-empty': emptyReply }">{{ headline }}</p>
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

    <div v-if="isPending && editing" class="editor">
      <textarea
        ref="editorRef"
        v-model="draftText"
        class="editor-input"
        rows="3"
        :maxlength="MAX_REPLY_LENGTH"
        aria-label="修改要发送的回复"
        @keydown.ctrl.enter.prevent="sendEdited"
        @keydown.meta.enter.prevent="sendEdited"
      />
      <p class="editor-hint">
        <span>改完直接发出去，发出后无法撤回。</span>
        <span class="editor-count num">{{ draftText.trim().length }} / {{ MAX_REPLY_LENGTH }}</span>
      </p>
      <div class="actions">
        <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="cancelEdit">
          取消
        </button>
        <button
          type="button"
          class="btn btn-sm btn-primary"
          :class="{ 'is-waiting': busy }"
          :disabled="busy"
          @click="sendEdited"
        >
          <span v-if="busy" class="spinner" aria-hidden="true" />
          <Check v-else :size="14" :stroke-width="2.6" aria-hidden="true" />
          <span class="btn-label">{{ busy ? WAIT_LABEL[draft.type] : '发送' }}</span>
        </button>
      </div>
    </div>

    <div v-else-if="isPending && confirming" class="confirm">
      <p class="confirm-text">{{ confirmHint }}</p>
      <div class="actions">
        <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="cancelConfirm">
          再想想
        </button>
        <button
          type="button"
          class="btn btn-sm btn-primary"
          :class="{ 'is-waiting': busy }"
          :disabled="busy"
          @click="resolve('confirm')"
        >
          <span v-if="busy" class="spinner" aria-hidden="true" />
          <Check v-else :size="14" :stroke-width="2.6" aria-hidden="true" />
          <span class="btn-label">{{ busy ? WAIT_LABEL[draft.type] : `确认${ACTION_LABEL[draft.type]}` }}</span>
        </button>
      </div>
    </div>

    <div v-else-if="isPending" class="actions">
      <button
        type="button"
        class="btn btn-sm btn-primary"
        :class="{ 'is-waiting': busy }"
        :disabled="busy || emptyReply"
        :title="emptyReply ? '这条草稿还没有正文，点「改一下」写一句再发' : undefined"
        @click="requestConfirm"
      >
        <span v-if="busy" class="spinner" aria-hidden="true" />
        <Check v-else :size="14" :stroke-width="2.6" aria-hidden="true" />
        <span class="btn-label">{{ busy ? WAIT_LABEL[draft.type] : ACTION_LABEL[draft.type] }}</span>
      </button>
      <button
        v-if="canEdit"
        type="button"
        class="btn btn-sm btn-ghost"
        :disabled="busy"
        @click="startEdit"
      >
        <Pencil :size="13" :stroke-width="2.4" aria-hidden="true" />
        改一下
      </button>
      <button type="button" class="btn btn-sm btn-ghost" :disabled="busy" @click="resolve('reject')">
        <X :size="14" :stroke-width="2.4" aria-hidden="true" />
        {{ draft.type === 'NAMING' ? '不用了' : '拒绝' }}
      </button>
    </div>

    <p v-if="busy && slow" class="wait-note" role="status">
      <span class="wait-dot" aria-hidden="true" />
      <span>{{ SLOW_NOTE[draft.type] }}</span>
      <span class="wait-count num" aria-hidden="true">{{ waited }}s</span>
    </p>
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

/*
 * 等待态。
 *
 * 这里真正难看的不是「慢」，是「空」：按钮一 disable 就被 .btn:disabled 压到
 * 40% 透明，三颗按钮一起变灰，屏幕上只剩一小圈转圈——看不出系统在动，也看不出
 * 在等什么，几秒钟就变成一大片死掉的灰。
 *
 * 所以等待期间做三件事：
 * 1. 主按钮保持实心，一道光从左扫到右，把整条按钮变成进度条；
 * 2. 卡片外圈极慢地呼吸一次，整块都在「工作」，不是只有按钮在转；
 * 3. 次要按钮再压暗一档，明确「现在别碰它们」，视觉焦点只剩正在干活的那个。
 */
.draft.is-sending {
  animation: draft-breathe 2400ms var(--ease) infinite;
}

@keyframes draft-breathe {
  0%,
  100% {
    box-shadow: var(--shadow-card), 0 0 0 0 rgba(46, 38, 26, 0);
  }
  50% {
    box-shadow: var(--shadow-card), 0 0 0 3px rgba(46, 38, 26, 0.055);
  }
}

.draft.is-sending .btn-ghost:disabled {
  opacity: 0.26;
}

/* 正在干活的按钮不能变灰：灰条等于「这里什么都没有」 */
.btn.is-waiting:disabled {
  opacity: 1;
  cursor: progress;
}

.btn.is-waiting {
  position: relative;
  overflow: hidden;
}

/*
 * 扫过去的那道光。比转圈更能填满「空白时间」——它占的是整条按钮的宽度。
 * 光带要窄：btn-sm 只有 104px 宽，渐变拉太开就不是「一道光」，而是糊在
 * 深色按钮上的一团脏印子。
 */
.btn.is-waiting::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    100deg,
    transparent 34%,
    rgba(255, 255, 255, 0.1) 44%,
    rgba(255, 255, 255, 0.22) 50%,
    rgba(255, 255, 255, 0.1) 56%,
    transparent 66%
  );
  transform: translateX(-100%);
  animation: btn-sweep 1500ms linear infinite;
  pointer-events: none;
}

@keyframes btn-sweep {
  to {
    transform: translateX(100%);
  }
}

/*
 * 光从字底下走，别糊在字上面。
 *
 * 这里必须逐个点名，不能用 `> *`：按压波纹的 .ripple-ink 也是直接子元素，
 * 它是靠 position: absolute 才不占位的，一旦被这条规则改成 relative，
 * 就变成一个 150×160 的普通 flex item，把按钮直接撑成一块黑板。
 * 标签外面那层 .btn-label 就是为这件事加的——文字是裸文本节点，选不中。
 */
.btn.is-waiting > .btn-label,
.btn.is-waiting > .spinner,
.btn.is-waiting > svg {
  position: relative;
  z-index: 1;
}

/* 全局 .spinner 是 15px，配 btn-sm 的 13.5px 字太大，会把文字挤到换行 */
.btn.is-waiting .spinner {
  width: 13px;
  height: 13px;
  border-width: 1.5px;
}

/* 2.5 秒后才出现的那句话：晚一点来，才不像一开始就在找借口 */
.wait-note {
  display: flex;
  align-items: center;
  gap: 7px;
  margin-top: 10px;
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.5;
  animation: wait-note-in 260ms var(--ease-out) both;
}

@keyframes wait-note-in {
  from {
    opacity: 0;
    transform: translateY(-3px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.wait-dot {
  flex: none;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--ok);
  animation: wait-pulse 1600ms var(--ease) infinite;
}

@keyframes wait-pulse {
  0%,
  100% {
    opacity: 0.3;
    transform: scale(0.8);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}

.wait-count {
  margin-left: auto;
  flex: none;
  font-size: 11px;
  font-variant-numeric: tabular-nums;
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

/* 空正文的占位：浅一档、不用正文的字重，一眼看出「这里还缺一句话」而不是「这就是内容」 */
.headline.is-empty {
  color: var(--ink-3);
  font-weight: 400;
  font-style: italic;
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

/* 改「发什么」：正文就地变成可编辑的，不跳页面——对方那句话还留在上面当上下文 */
.editor {
  margin-top: 2px;
}

.editor-input {
  width: 100%;
  min-height: 76px;
  padding: 10px 11px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--card-soft, rgba(255, 255, 255, 0.7));
  color: var(--ink);
  font: inherit;
  font-size: 15px;
  line-height: 1.5;
  resize: vertical;
}

.editor-input:focus {
  outline: none;
  border-color: var(--ink-3);
}

.editor-hint {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  margin-top: 6px;
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.5;
}

.editor-count {
  flex: none;
  font-size: 11px;
}

.actions {
  display: flex;
  gap: 8px;
  margin-top: 12px;
}

/*
 * 主按钮定个下限：「发送」两个字和「发送中」三个字宽度不一样，
 * 不定住的话点下去按钮会自己长胖，把旁边的「改一下」「拒绝」推着走。
 * 104px 刚好装得下最长的「确认发送」，三个状态共用同一个宽度，一动不动。
 */
.actions .btn-primary {
  min-width: 104px;
}

.btn-label {
  white-space: nowrap;
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
