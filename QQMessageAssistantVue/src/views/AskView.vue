<script setup lang="ts">
import { computed, nextTick, ref } from 'vue'
import { ArrowUp, Quote } from 'lucide-vue-next'
import { ask, type ClarifyCandidate, type ClarifyState, type Evidence } from '@/api/ask'
import { useToast } from '@/composables/useToast'

interface Bubble {
  id: number
  role: 'user' | 'assistant'
  text: string
  /** true=模型引用了候选集之外的东西，结论作废，只剩证据 */
  degraded?: boolean
  evidence?: Evidence[]
  clarify?: ClarifyState
  /** 触发这次反问的原问题：认完人要用它重问，存在气泡上而不是靠下标回找 */
  question?: string
}

const toast = useToast()

const bubbles = ref<Bubble[]>([])
const question = ref('')
const loading = ref(false)
const flowEl = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)

let seq = 0

const EXAMPLES = ['阿黄什么时候考试', '上周群里说的班会在哪开', '我学号是多少']

const canSend = computed(() => question.value.trim().length > 0 && !loading.value)

const EVIDENCE_LABELS: Record<Evidence['type'], string> = {
  msg: '原文',
  note: '事实',
  memo: '备忘',
  file: '文件',
}

function push(bubble: Omit<Bubble, 'id'>) {
  bubbles.value.push({ ...bubble, id: ++seq })
}

async function scrollToEnd() {
  await nextTick()
  flowEl.value?.lastElementChild?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
}

/** 一次提问 + 一次作答；clarify 只在用户刚认过人时带上 */
async function run(text: string, clarify?: { alias: string; senderId: number; from?: string }) {
  loading.value = true
  try {
    const response = await ask(text, clarify)
    if (response.needClarify) {
      push({
        role: 'assistant',
        text: '',
        clarify: response.needClarify,
        question: text,
      })
    } else if (response.degraded) {
      push({
        role: 'assistant',
        text: '没找到能对上的原文，只列了候选。',
        degraded: true,
        evidence: response.evidence,
      })
    } else if (!response.answer && !response.evidence?.length) {
      push({ role: 'assistant', text: '翻了一遍，没有关于这件事的记录。' })
    } else {
      push({
        role: 'assistant',
        text: response.answer || '没有直接结论，下面是翻到的原文。',
        evidence: response.evidence,
      })
    }
  } catch (e) {
    push({ role: 'assistant', text: e instanceof Error ? e.message : '查询失败' })
  } finally {
    loading.value = false
    await scrollToEnd()
    inputEl.value?.focus()
  }
}

async function submit() {
  if (!canSend.value) return
  const text = question.value.trim()
  question.value = ''
  push({ role: 'user', text })
  await run(text)
}

function useExample(example: string) {
  question.value = example
  inputEl.value?.focus()
}

async function pickCandidate(candidate: ClarifyCandidate, bubble: Bubble) {
  const state = bubble.clarify
  if (!state || loading.value) return
  const original = bubble.question
  if (!original) {
    toast.error('找不到原问题了，请重新问一次')
    return
  }
  bubble.clarify = undefined
  const who = candidate.relation ? `${candidate.alias}（${candidate.relation}）` : candidate.alias
  push({ role: 'user', text: `「${state.alias}」就是 ${who}` })
  await run(original, {
    alias: state.alias,
    senderId: candidate.senderId,
    from: candidate.relation ?? '',
  })
}

async function submitUnknown(bubble: Bubble, senderId: number, relation: string) {
  const state = bubble.clarify
  if (!state || loading.value) return
  const original = bubble.question
  if (!original) {
    toast.error('找不到原问题了，请重新问一次')
    return
  }
  bubble.clarify = undefined
  push({ role: 'user', text: `「${state.alias}」的 QQ 是 ${senderId}` })
  await run(original, { alias: state.alias, senderId, from: relation })
}

const unknownForm = ref({ senderId: '', relation: '' })
const unknownError = ref('')

async function confirmUnknown(bubble: Bubble) {
  if (!bubble.clarify) return
  const raw = unknownForm.value.senderId.trim()
  if (!/^\d{5,12}$/.test(raw)) {
    unknownError.value = 'QQ 号是 5–12 位数字'
    return
  }
  unknownError.value = ''
  const id = Number(raw)
  const relation = unknownForm.value.relation.trim()
  unknownForm.value = { senderId: '', relation: '' }
  await submitUnknown(bubble, id, relation)
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="eyebrow">历史查询</p>
      <h1 class="page-title">问一句</h1>
      <p class="page-sub">只在聊天记录、事实、备忘和文件里找答案；找不到就说找不到，不编。</p>
    </header>

    <form class="ask" @submit.prevent="submit">
      <input
        ref="inputEl"
        v-model="question"
        type="text"
        class="ask-input"
        placeholder="谁什么时候说了什么…"
        enterkeyhint="send"
        maxlength="200"
        aria-label="要问的问题"
      />
      <button type="submit" class="ask-send" :disabled="!canSend" aria-label="发送">
        <ArrowUp :size="17" :stroke-width="2.4" aria-hidden="true" />
      </button>
    </form>

    <div v-if="!bubbles.length" class="examples">
      <p class="examples-hint">可以先试试：</p>
      <button
        v-for="example in EXAMPLES"
        :key="example"
        type="button"
        class="example"
        @click="useExample(example)"
      >
        {{ example }}
      </button>
    </div>

    <ol ref="flowEl" class="flow" aria-live="polite">
      <li v-for="bubble in bubbles" :key="bubble.id" class="turn" :class="bubble.role">
        <p v-if="bubble.text" class="bubble">{{ bubble.text }}</p>

        <div v-if="bubble.clarify" class="clarify">
          <p class="clarify-q">
            你说的「{{ bubble.clarify.alias }}」是哪一个？
          </p>

          <div v-if="bubble.clarify.candidates.length" class="choices">
            <button
              v-for="candidate in bubble.clarify.candidates"
              :key="candidate.senderId"
              type="button"
              class="choice"
              :disabled="loading"
              @click="pickCandidate(candidate, bubble)"
            >
              <span class="choice-name">
                {{ candidate.alias }}
                <span v-if="candidate.relation" class="choice-relation">{{ candidate.relation }}</span>
              </span>
              <span class="choice-hint num">{{ candidate.hint }}</span>
            </button>
          </div>

          <div v-else class="unknown">
            <p class="unknown-hint">我还不认识 TA，知道 QQ 号就能记住，下次不用再问。</p>
            <div class="unknown-form">
              <input
                v-model="unknownForm.senderId"
                type="text"
                inputmode="numeric"
                class="unknown-input"
                placeholder="QQ 号"
                maxlength="12"
                aria-label="QQ 号"
              />
              <input
                v-model="unknownForm.relation"
                type="text"
                class="unknown-input"
                placeholder="关系（可空）"
                maxlength="16"
                aria-label="关系"
              />
              <button
                type="button"
                class="btn btn-sm btn-primary"
                :disabled="loading"
                @click="confirmUnknown(bubble)"
              >
                记住
              </button>
            </div>
            <p v-if="unknownError" class="unknown-error" role="alert">{{ unknownError }}</p>
          </div>
        </div>

        <ul v-if="bubble.evidence?.length" class="evidence">
          <li v-for="item in bubble.evidence" :key="`${item.type}:${item.id}`" class="ev">
            <div class="ev-head">
              <span class="ev-type">{{ EVIDENCE_LABELS[item.type] ?? item.type }}</span>
              <span class="ev-label">{{ item.label }}</span>
            </div>
            <p class="ev-body">
              <Quote :size="12" class="ev-quote" aria-hidden="true" />
              {{ item.content }}
            </p>
          </li>
        </ul>
      </li>

      <li v-if="loading" class="turn assistant">
        <p class="bubble thinking">正在翻记录…</p>
      </li>
    </ol>
  </div>
</template>

<style scoped>
/* ── 输入 ─────────────────────────────────────────────────── */

.ask {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 6px 6px 16px;
  border-radius: var(--r-xl);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.ask-input {
  flex: 1;
  min-width: 0;
  min-height: 40px;
  border: 0;
  background: transparent;
  color: var(--ink);
  font-size: 15.5px;
  outline: none;
}

.ask-input::placeholder {
  color: var(--ink-3);
}

.ask-send {
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: var(--r-pill);
  background: var(--card-inverse);
  color: var(--on-dark);
  cursor: pointer;
  transition: opacity var(--dur-1) var(--ease-out);
  flex: none;
}

.ask-send:disabled {
  opacity: 0.4;
  cursor: default;
}

/* ── 示例 ─────────────────────────────────────────────────── */

.examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 16px;
}

.examples-hint {
  width: 100%;
  color: var(--ink-3);
  font-size: 12px;
}

.example {
  padding: 7px 13px;
  border: 0;
  border-radius: var(--r-pill);
  background: var(--surface);
  color: var(--ink-2);
  font-size: 12.5px;
  cursor: pointer;
}

/* ── 消息流 ───────────────────────────────────────────────── */

.flow {
  display: grid;
  gap: 14px;
  margin: 20px 0 0;
  padding: 0;
  list-style: none;
}

.turn {
  display: grid;
  gap: 8px;
}

.turn.user {
  justify-items: end;
}

.bubble {
  max-width: 86%;
  padding: 11px 14px;
  border-radius: var(--r-lg);
  font-size: 14.5px;
  line-height: 1.6;
  overflow-wrap: anywhere;
}

.turn.user .bubble {
  background: var(--card-inverse);
  color: var(--on-dark);
  border-bottom-right-radius: 6px;
}

.turn.assistant .bubble {
  background: var(--card);
  box-shadow: var(--shadow-card);
  border-bottom-left-radius: 6px;
}

.turn.assistant .bubble.thinking {
  color: var(--ink-3);
}

/* ── 反问 ─────────────────────────────────────────────────── */

.clarify {
  width: 100%;
  padding: 14px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.clarify-q {
  margin-bottom: 10px;
  font-size: 14px;
  font-weight: 500;
}

.choices {
  display: grid;
  gap: 8px;
}

.choice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  min-height: 44px;
  padding: 9px 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: transparent;
  color: var(--ink);
  text-align: left;
  cursor: pointer;
}

.choice:active {
  background: var(--surface);
}

.choice:disabled {
  opacity: 0.55;
  cursor: default;
}

.choice-name {
  font-size: 14.5px;
}

.choice-relation {
  margin-left: 6px;
  color: var(--ink-3);
  font-size: 12px;
}

.choice-hint {
  color: var(--ink-3);
  font-size: 11.5px;
}

.unknown-hint {
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.unknown-form {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 10px;
}

.unknown-input {
  flex: 1;
  min-width: 110px;
  min-height: 38px;
  padding: 0 12px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: transparent;
  color: var(--ink);
  font-size: 14px;
  outline: none;
}

.unknown-error {
  margin-top: 8px;
  color: var(--danger);
  font-size: 12px;
}

/* ── 证据 ─────────────────────────────────────────────────── */

.evidence {
  display: grid;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 0;
  list-style: none;
}

.ev {
  padding: 10px 12px;
  border-radius: var(--r-md);
  background: var(--surface);
}

.ev-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 4px;
}

.ev-type {
  padding: 1px 7px;
  border-radius: var(--r-pill);
  background: var(--card);
  color: var(--ink-2);
  font-size: 10.5px;
}

.ev-label {
  color: var(--ink-3);
  font-size: 11.5px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ev-body {
  color: var(--ink-2);
  font-size: 13px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.ev-quote {
  margin-right: 3px;
  color: var(--ink-3);
  vertical-align: -1px;
}
</style>
