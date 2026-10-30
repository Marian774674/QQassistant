<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import Sheet from '@/components/Sheet.vue'
import { createMemo, patchMemo } from '@/api/memos'
import { CATEGORIES } from '@/api/types'
import type { Category, Memo, MemoPatchBody } from '@/api/types'
import { useOwner } from '@/composables/useOwner'
import { useToast } from '@/composables/useToast'
import {
  formatDateTime,
  fromDatetimeLocalValue,
  toDatetimeLocalValue,
} from '@/utils/datetime'
import { importanceLabel } from '@/utils/meta'

const props = defineProps<{
  open: boolean
  memo?: Memo | null
}>()

const emit = defineEmits<{ close: []; saved: [] }>()

const owner = useOwner()
const toast = useToast()

const IMPORTANCE_OPTIONS = [
  { value: 5, label: '紧要' },
  { value: 4, label: '重要' },
  { value: 3, label: '一般' },
  { value: 2, label: '轻量' },
  { value: 1, label: '存档' },
]

const PLAN: Record<number, string> = {
  5: '提前 24 小时、2 小时、30 分钟各提醒一次',
  4: '提前 24 小时、1 小时各提醒一次',
  3: '提前 2 小时提醒一次',
  2: '当天 09:00 提醒一次',
  1: '只存档，不提醒',
}

const form = reactive({
  title: '',
  content: '',
  importance: 3,
  category: '学业' as Category,
  due: '',
})

const errors = reactive({ title: '', form: '' })
const saving = ref(false)
const shaking = ref(false)
const titleInput = ref<HTMLInputElement | null>(null)

const isEdit = computed(() => Boolean(props.memo))

/** 校验失败时抖一下并把焦点送回出错的地方，别让人自己找 */
function rejectField(message: string) {
  errors.title = message
  shaking.value = false
  requestAnimationFrame(() => {
    shaking.value = true
    window.setTimeout(() => (shaking.value = false), 340)
  })
  titleInput.value?.focus({ preventScroll: true })
}

/* ── 时间快捷块 ─────────────────────────────────────────────
 * 手机上点 datetime-local 要滚三列滚轮，多数场景其实只是
 * 「今晚」「明早」这几种。给四个常用档位，剩下的才走自定义。 */

const anchor = ref(new Date())
const customOpen = ref(false)

function atTime(base: Date, hours: number, minutes = 0) {
  const next = new Date(base)
  next.setHours(hours, minutes, 0, 0)
  return next
}

const presets = computed(() => {
  const base = anchor.value

  const tonight = atTime(base, 20)
  const tonightRolled = tonight.getTime() <= base.getTime()
  if (tonightRolled) tonight.setDate(tonight.getDate() + 1)

  const tomorrowMorning = atTime(base, 9)
  tomorrowMorning.setDate(tomorrowMorning.getDate() + 1)

  const tomorrowEvening = atTime(base, 18)
  tomorrowEvening.setDate(tomorrowEvening.getDate() + 1)

  const nextMonday = atTime(base, 9)
  const offset = (8 - nextMonday.getDay()) % 7 || 7
  nextMonday.setDate(nextMonday.getDate() + offset)

  return [
    {
      key: 'tonight',
      label: `${tonightRolled ? '明晚' : '今晚'} 20:00`,
      value: toDatetimeLocalValue(tonight),
    },
    { key: 'tomorrow-morning', label: '明早 09:00', value: toDatetimeLocalValue(tomorrowMorning) },
    { key: 'tomorrow-evening', label: '明天 18:00', value: toDatetimeLocalValue(tomorrowEvening) },
    { key: 'next-monday', label: '下周一 09:00', value: toDatetimeLocalValue(nextMonday) },
  ]
})

function pickPreset(value: string) {
  form.due = value
  customOpen.value = false
}

function openCustom() {
  customOpen.value = true
  if (!form.due) {
    const next = new Date(anchor.value.getTime() + 60 * 60 * 1000)
    next.setMinutes(0, 0, 0)
    form.due = toDatetimeLocalValue(next)
  }
}

function pickNone() {
  form.due = ''
  customOpen.value = false
}

const dueText = computed(() => {
  if (!form.due) return '不设截止时间，只存档'
  return `${formatDateTime(form.due)} 截止`
})

const planText = computed(() => {
  if (!form.due) return '只存档，不会创建提醒'
  return PLAN[form.importance]
})

watch(
  () => props.open,
  (open) => {
    if (!open) return
    const memo = props.memo
    anchor.value = new Date()
    form.title = memo?.title ?? ''
    form.content = memo?.content ?? ''
    form.importance = memo?.importance ?? 3
    form.category = memo?.category ?? '学业'
    const dueValue = toDatetimeLocalValue(memo?.dueTime)
    form.due = dueValue
    customOpen.value = Boolean(dueValue) && !presets.value.some((item) => item.value === dueValue)
    errors.title = ''
    errors.form = ''
  },
)

async function save() {
  errors.form = ''
  if (!form.title.trim()) {
    rejectField('写个标题，方便以后找')
    return
  }
  errors.title = ''
  if (!owner.senderId) {
    errors.form = '先在设置里填你的 QQ 号'
    return
  }

  saving.value = true
  try {
    if (props.memo) {
      const body: MemoPatchBody = {
        title: form.title.trim(),
        content: form.content.trim(),
        importance: form.importance,
        category: form.category,
      }
      if (form.due) {
        body.dueTime = fromDatetimeLocalValue(form.due)
      } else {
        body.clearDueTime = true
      }
      await patchMemo(props.memo.id, body)
      toast.success('已保存')
    } else {
      await createMemo({
        senderId: owner.senderId,
        title: form.title.trim(),
        content: form.content.trim() || null,
        importance: form.importance,
        category: form.category,
        dueTime: fromDatetimeLocalValue(form.due),
      })
      toast.success('已记下')
    }
    emit('saved')
    emit('close')
  } catch (error) {
    errors.form = error instanceof Error ? error.message : '保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <Sheet
    :open="open"
    :title="isEdit ? '编辑备忘' : '新建备忘'"
    :description="isEdit ? '改重要性或时间会重算提醒计划' : '写清楚要做的事，再决定什么时候提醒你'"
    @close="emit('close')"
  >
    <form class="form" @submit.prevent="save">
      <div class="frow" :class="{ shake: shaking }">
        <label class="frow-label" for="memo-title">要做的事</label>
        <input
          id="memo-title"
          ref="titleInput"
          v-model="form.title"
          class="frow-input"
          type="text"
          placeholder="例如：交实验报告"
          maxlength="120"
          :aria-invalid="Boolean(errors.title)"
        />
        <p v-if="errors.title" class="frow-error" role="alert">{{ errors.title }}</p>
      </div>

      <div class="frow">
        <label class="frow-label" for="memo-content">补充说明</label>
        <textarea
          id="memo-content"
          v-model="form.content"
          class="frow-input"
          rows="2"
          placeholder="地点、对象、要带的东西…"
        />
      </div>

      <div class="frow">
        <span class="frow-label">截止时间</span>
        <div class="presets">
          <button
            v-for="preset in presets"
            :key="preset.key"
            type="button"
            class="preset"
            :aria-pressed="form.due === preset.value"
            @click="pickPreset(preset.value)"
          >
            {{ preset.label }}
          </button>
          <button
            type="button"
            class="preset"
            :aria-pressed="customOpen"
            @click="openCustom"
          >
            自定义
          </button>
          <button
            type="button"
            class="preset"
            :aria-pressed="!form.due"
            @click="pickNone"
          >
            不设时间
          </button>
        </div>
        <input
          v-if="customOpen"
          v-model="form.due"
          class="frow-input time-input"
          type="datetime-local"
          aria-label="自定义截止时间"
        />
        <p class="frow-hint">{{ dueText }}</p>
      </div>

      <div class="frow">
        <span class="frow-label">分类</span>
        <div class="grid3">
          <button
            v-for="category in CATEGORIES"
            :key="category"
            type="button"
            class="pick"
            :aria-pressed="form.category === category"
            @click="form.category = category"
          >
            {{ category }}
          </button>
        </div>
      </div>

      <div class="frow">
        <span class="frow-label">重要性</span>
        <div class="scale">
          <button
            v-for="option in IMPORTANCE_OPTIONS"
            :key="option.value"
            type="button"
            class="scale-cell"
            :aria-pressed="form.importance === option.value"
            :aria-label="`${option.value} 级 ${option.label}`"
            @click="form.importance = option.value"
          >
            <span class="scale-num num">{{ option.value }}</span>
            <span class="scale-label">{{ option.label }}</span>
          </button>
        </div>
        <p class="frow-hint">{{ importanceLabel(form.importance) }} · {{ planText }}</p>
      </div>

      <p v-if="errors.form" class="form-error" role="alert">{{ errors.form }}</p>
      <p v-if="!owner.senderId" class="form-error" role="alert">
        还没有记录你的 QQ 号，去设置里填一下再保存。
      </p>
    </form>

    <template #footer>
      <button type="button" class="btn btn-ghost" @click="emit('close')">取消</button>
      <button
        type="button"
        class="btn btn-on-dark"
        :disabled="saving || !owner.senderId"
        @click="save"
      >
        <span v-if="saving" class="spinner" aria-hidden="true" />
        {{ saving ? '保存中' : isEdit ? '保存修改' : '记下来' }}
      </button>
    </template>
  </Sheet>
</template>

<style scoped>
.form {
  display: grid;
  gap: 10px;
}

.frow {
  display: grid;
  gap: 6px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: #fff;
  color: var(--ink);
}

.frow-label {
  color: var(--ink-3);
  font-size: 11.5px;
  font-weight: 500;
  letter-spacing: 0.01em;
}

.frow-input {
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 15.5px;
  line-height: 1.45;
  color: var(--ink);
  outline: none;
  resize: none;
}

.frow-input::placeholder {
  color: var(--ink-3);
}

.frow-error {
  color: var(--danger);
  font-size: 12px;
}

.frow-hint {
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.5;
}

/* ── 时间快捷块 ───────────────────────────────────────────── */

.presets {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.preset {
  min-height: 32px;
  padding: 0 11px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-pill);
  background: #fff;
  color: var(--ink-2);
  font-size: 12.5px;
  white-space: nowrap;
  cursor: pointer;
  transition: background-color var(--dur-1) var(--ease-out),
    border-color var(--dur-1) var(--ease-out),
    color var(--dur-1) var(--ease-out),
    transform var(--dur-1) var(--ease-spring);
}

.preset:active {
  transform: scale(0.96);
}

.preset[aria-pressed="true"] {
  border-color: var(--card-inverse);
  background: var(--card-inverse);
  color: #fff;
}

.time-input {
  margin-top: 2px;
  padding: 9px 0 2px;
  border-top: 1px solid var(--hairline);
  font-size: 14.5px;
  color: var(--ink-2);
}

.grid3 {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

/* 重要性五档：数字大一点、带文字，比只有 1–5 更容易对上「紧要 / 一般」 */
.scale {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 6px;
}

.scale-cell {
  display: grid;
  justify-items: center;
  gap: 1px;
  min-height: 46px;
  padding: 7px 0 6px;
  border: 1px solid var(--hairline);
  border-radius: 10px;
  background: #fff;
  color: var(--ink-2);
  cursor: pointer;
  transition: background-color var(--dur) var(--ease),
    color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.scale-num {
  font-size: 15px;
  font-weight: 600;
  line-height: 1.1;
}

.scale-label {
  font-size: 9.5px;
  font-weight: 500;
  opacity: 0.72;
  letter-spacing: 0.01em;
}

.scale-cell[aria-pressed="true"] {
  border-color: var(--card-inverse);
  background: var(--card-inverse);
  color: #fff;
}

.scale-cell[aria-pressed="true"] .scale-label {
  opacity: 0.66;
}

.pick {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 5px;
  min-height: 34px;
  padding: 0 11px;
  border: 1px solid var(--hairline);
  border-radius: 10px;
  background: #fff;
  color: var(--ink-2);
  font-size: 13px;
  cursor: pointer;
  transition: background-color var(--dur) var(--ease),
    color var(--dur) var(--ease), border-color var(--dur) var(--ease);
}

.pick[aria-pressed="true"] {
  border-color: var(--card-inverse);
  background: var(--card-inverse);
  color: #fff;
}

.form-error {
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: rgba(191, 58, 43, 0.18);
  color: #ffb4ab;
  font-size: 12.5px;
}
</style>
