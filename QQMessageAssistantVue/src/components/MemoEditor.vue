<script setup lang="ts">
import { computed, reactive, ref, watch } from 'vue'
import { CalendarPlus } from 'lucide-vue-next'
import Modal from '@/components/Modal.vue'
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

const props = defineProps<{
  open: boolean
  memo?: Memo | null
}>()

const emit = defineEmits<{ close: []; saved: [] }>()

const owner = useOwner()
const toast = useToast()

const IMPORTANCE_OPTIONS = [
  { value: 5, label: '5 · 紧要', hint: '考试、面试、答辩、截止缴费' },
  { value: 4, label: '4 · 重要', hint: '重要邀约、班委通知' },
  { value: 3, label: '3 · 一般', hint: '班会、聚餐、一般截止' },
  { value: 2, label: '2 · 轻量', hint: '可做可不做' },
  { value: 1, label: '1 · 存档', hint: '纯信息，不提醒' },
]

const PLAN: Record<number, string[]> = {
  5: ['提前 24 小时', '提前 2 小时', '提前 30 分钟'],
  4: ['提前 24 小时', '提前 1 小时'],
  3: ['提前 2 小时'],
  2: ['当天 09:00'],
  1: [],
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

const isEdit = computed(() => Boolean(props.memo))
const selectedImportance = computed(
  () => IMPORTANCE_OPTIONS.find((option) => option.value === form.importance) ?? IMPORTANCE_OPTIONS[2],
)

const planText = computed(() => {
  const labels = PLAN[form.importance] ?? []
  if (!form.due) return '没有截止时间：只存档，不会创建提醒。'
  if (!labels.length) return '1 级备忘只存档，不会提醒。'
  return `会在 ${formatDateTime(form.due)} 前提醒：${labels.join('、')}。`
})

watch(
  () => props.open,
  (open) => {
    if (!open) return
    const memo = props.memo
    form.title = memo?.title ?? ''
    form.content = memo?.content ?? ''
    form.importance = memo?.importance ?? 3
    form.category = memo?.category ?? '学业'
    form.due = toDatetimeLocalValue(memo?.dueTime)
    errors.title = ''
    errors.form = ''
  },
)

async function save() {
  errors.form = ''
  if (!form.title.trim()) {
    errors.title = '写个标题，方便以后找'
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
  <Modal
    :open="open"
    :title="isEdit ? '编辑备忘' : '新建备忘'"
    :description="isEdit ? '改重要性或时间会重算提醒计划' : '写清楚要做的事，再决定什么时候提醒你'"
    @close="emit('close')"
  >
    <form class="form" @submit.prevent="save">
      <div class="field">
        <label for="memo-title">要做的事</label>
        <input
          id="memo-title"
          v-model="form.title"
          type="text"
          placeholder="例如：交实验报告"
          maxlength="120"
          :aria-invalid="Boolean(errors.title)"
          aria-describedby="memo-title-error"
        />
        <p v-if="errors.title" id="memo-title-error" class="error" role="alert">{{ errors.title }}</p>
      </div>

      <div class="field">
        <label for="memo-content">补充说明</label>
        <textarea
          id="memo-content"
          v-model="form.content"
          placeholder="地点、对象、要带的东西…"
          rows="3"
        />
      </div>

      <div class="row">
        <div class="field">
          <label for="memo-importance">重要性</label>
          <select id="memo-importance" v-model.number="form.importance">
            <option v-for="option in IMPORTANCE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </option>
          </select>
          <p class="hint">{{ selectedImportance.hint }}</p>
        </div>

        <div class="field">
          <label for="memo-category">分类</label>
          <select id="memo-category" v-model="form.category">
            <option v-for="category in CATEGORIES" :key="category" :value="category">
              {{ category }}
            </option>
          </select>
        </div>
      </div>

      <div class="field">
        <label for="memo-due">截止时间</label>
        <input id="memo-due" v-model="form.due" type="datetime-local" />
        <p class="hint">不填就只存档。填了才会按重要性生成提醒。</p>
      </div>

      <p class="plan">
        <CalendarPlus :size="16" aria-hidden="true" />
        <span>{{ planText }}</span>
      </p>

      <p v-if="errors.form" class="error form-error" role="alert">{{ errors.form }}</p>
      <p v-if="!owner.senderId" class="error form-error" role="alert">
        还没有记录你的 QQ 号，去<RouterLink to="/settings">设置</RouterLink>里填一下再保存。
      </p>

      <div class="actions">
        <button type="button" class="btn" @click="emit('close')">取消</button>
        <button type="submit" class="btn btn-primary" :disabled="saving || !owner.senderId">
          {{ saving ? '保存中…' : isEdit ? '保存修改' : '记下来' }}
        </button>
      </div>
    </form>
  </Modal>
</template>

<style scoped>
.form {
  display: grid;
  gap: 16px;
}

.row {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.plan {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 11px 12px;
  border-left: 2px solid var(--signal);
  border-radius: 0 2px 2px 0;
  background: var(--signal-wash);
  color: var(--signal-deep);
  font-size: 13.5px;
  line-height: 1.5;
}

.plan svg {
  margin-top: 2px;
  flex: none;
}

.form-error {
  margin: 0;
}

.actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 4px;
}

@media (max-width: 560px) {
  .row {
    grid-template-columns: 1fr;
  }
}
</style>
