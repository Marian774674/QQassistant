<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ArrowLeft, Plus, ShieldCheck, Trash2 } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import Sheet from '@/components/Sheet.vue'
import Skeleton from '@/components/Skeleton.vue'
import { createRule, deleteRule, listRules, updateRule } from '@/api/rules'
import type { InterceptRule, InterceptRuleBody } from '@/api/types'
import { tapFeedback, useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'

const toast = useToast()

const rules = ref<InterceptRule[]>([])
const loading = ref(true)
const error = ref('')

const sheetOpen = ref(false)
const editing = ref<InterceptRule | null>(null)
const saving = ref(false)
const formError = ref('')
const confirmingDelete = ref(false)
const togglingId = ref<number | null>(null)
const testText = ref('')

const form = reactive({
  name: '',
  keywords: '',
  regex: '',
  replyTemplate: '',
  priority: 10,
  enabled: true,
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    rules.value = await listRules()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '规则读取失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)

useRefresh(load)

const sorted = computed(() =>
  [...rules.value].sort((a, b) => a.priority - b.priority || a.id - b.id),
)

function openCreate() {
  editing.value = null
  form.name = ''
  form.keywords = ''
  form.regex = ''
  form.replyTemplate = ''
  form.priority = 10
  form.enabled = true
  formError.value = ''
  confirmingDelete.value = false
  testText.value = ''
  sheetOpen.value = true
}

function openEdit(rule: InterceptRule) {
  editing.value = rule
  form.name = rule.name
  form.keywords = rule.keywords ?? ''
  form.regex = rule.regex ?? ''
  form.replyTemplate = rule.replyTemplate ?? ''
  form.priority = rule.priority
  form.enabled = rule.enabled
  formError.value = ''
  confirmingDelete.value = false
  testText.value = ''
  sheetOpen.value = true
}

/** 正则写错会让整条链路静默失效，所以边写边校验 */
const regexCheck = computed(() => {
  const source = form.regex.trim()
  if (!source) return { state: 'empty' as const, message: '没填正则：只按关键词匹配' }
  try {
    new RegExp(source)
    return { state: 'ok' as const, message: '正则可编译' }
  } catch (e) {
    return {
      state: 'bad' as const,
      message: e instanceof Error ? e.message : '正则语法错误',
    }
  }
})

const testResult = computed(() => {
  const source = form.regex.trim()
  const text = testText.value
  if (!source || !text) return null
  try {
    const match = new RegExp(source).exec(text)
    if (!match) return { hit: false, detail: '没有命中' }
    const groups = match.slice(1).filter(Boolean)
    return {
      hit: true,
      detail: groups.length ? `命中，捕获：${groups.join(' / ')}` : `命中：${match[0]}`,
    }
  } catch {
    return { hit: false, detail: '正则无效，无法测试' }
  }
})

async function save() {
  if (!form.name.trim()) {
    formError.value = '规则要有个名字'
    return
  }
  if (regexCheck.value.state === 'bad') {
    formError.value = '正则语法有错，先改好再保存'
    return
  }
  saving.value = true
  formError.value = ''
  const body: InterceptRuleBody = {
    name: form.name.trim(),
    keywords: form.keywords.trim() || null,
    regex: form.regex.trim() || null,
    replyTemplate: form.replyTemplate.trim() || null,
    priority: form.priority,
    enabled: form.enabled,
  }
  try {
    if (editing.value) {
      const updated = await updateRule(editing.value.id, body)
      rules.value = rules.value.map((rule) => (rule.id === updated.id ? updated : rule))
      toast.success('规则已更新')
    } else {
      const created = await createRule(body)
      rules.value = [...rules.value, created]
      toast.success('规则已创建')
    }
    sheetOpen.value = false
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

async function toggle(rule: InterceptRule) {
  togglingId.value = rule.id
  tapFeedback()
  const next = !rule.enabled
  rules.value = rules.value.map((item) =>
    item.id === rule.id ? { ...item, enabled: next } : item,
  )
  try {
    const updated = await updateRule(rule.id, { enabled: next })
    rules.value = rules.value.map((item) => (item.id === updated.id ? updated : item))
  } catch (e) {
    rules.value = rules.value.map((item) =>
      item.id === rule.id ? { ...item, enabled: rule.enabled } : item,
    )
    toast.error(e instanceof Error ? e.message : '切换失败')
  } finally {
    togglingId.value = null
  }
}

async function remove() {
  const rule = editing.value
  if (!rule) return
  saving.value = true
  try {
    await deleteRule(rule.id)
    rules.value = rules.value.filter((item) => item.id !== rule.id)
    sheetOpen.value = false
    toast.push('规则已删除')
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '删除失败'
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="page-head">
      <RouterLink to="/more" class="back">
        <ArrowLeft :size="16" :stroke-width="2" aria-hidden="true" />
        更多
      </RouterLink>
      <div class="head-row">
        <div>
          <h1 class="page-title">拦截规则</h1>
          <p class="page-sub">命中就绕过模型直接回复。保存后插件 60 秒内热更新。</p>
        </div>
        <button type="button" class="add-btn" aria-label="新建规则" @click="openCreate">
          <Plus :size="18" :stroke-width="2.2" aria-hidden="true" />
        </button>
      </div>
    </header>

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load" />

    <Skeleton v-else-if="loading" :rows="3" />

    <ul v-else-if="sorted.length" class="stack">
      <li v-for="rule in sorted" :key="rule.id" class="rule" :class="{ off: !rule.enabled }">
        <button type="button" class="rule-main" @click="openEdit(rule)">
          <span class="rule-icon" aria-hidden="true">
            <ShieldCheck :size="16" :stroke-width="1.8" />
          </span>
          <span class="rule-text">
            <span class="rule-name">{{ rule.name }}</span>
            <span class="rule-meta">
              <span v-if="rule.keywords" class="clamp-1">{{ rule.keywords }}</span>
              <span v-else class="faint">无关键词</span>
            </span>
            <span v-if="rule.regex" class="rule-regex mono clamp-1">{{ rule.regex }}</span>
          </span>
          <span class="rule-priority num">P{{ rule.priority }}</span>
        </button>

        <button
          type="button"
          class="switch"
          role="switch"
          :aria-checked="rule.enabled"
          :aria-label="`${rule.enabled ? '停用' : '启用'}规则 ${rule.name}`"
          :disabled="togglingId === rule.id"
          @click="toggle(rule)"
        >
          <span class="switch-knob" />
        </button>
      </li>
    </ul>

    <EmptyState
      v-else
      title="还没有拦截规则"
      hint="比如报学号这类固定回复，配一条规则就能跳过模型，省 token 也更快。"
    >
      <button type="button" class="btn btn-sm btn-quiet" @click="openCreate">新建一条</button>
    </EmptyState>

    <Sheet
      :open="sheetOpen"
      :title="editing ? '编辑规则' : '新建规则'"
      description="关键词与正则满足其一即命中；命中后仍会先查防重再发送。"
      height="92%"
      @close="sheetOpen = false"
    >
      <div class="form">
        <div class="frow">
          <label class="frow-label" for="rule-name">名称</label>
          <input
            id="rule-name"
            v-model="form.name"
            class="frow-input"
            type="text"
            placeholder="报学号"
            maxlength="64"
          />
        </div>

        <div class="frow">
          <label class="frow-label" for="rule-keywords">关键词</label>
          <input
            id="rule-keywords"
            v-model="form.keywords"
            class="frow-input"
            type="text"
            placeholder="学号,姓名,专业"
          />
        </div>

        <div class="frow">
          <label class="frow-label" for="rule-regex">正则</label>
          <input
            id="rule-regex"
            v-model="form.regex"
            class="frow-input mono"
            type="text"
            placeholder="(学号|姓名|专业)\s*[:：]?\s*"
            spellcheck="false"
          />
          <p class="frow-hint" :class="{ bad: regexCheck.state === 'bad' }">
            {{ regexCheck.message }}
          </p>
        </div>

        <div class="frow">
          <label class="frow-label" for="rule-reply">回复模板</label>
          <textarea
            id="rule-reply"
            v-model="form.replyTemplate"
            class="frow-input"
            rows="2"
            placeholder="学号 2023001，姓名 张三"
          />
        </div>

        <div class="frow">
          <label class="frow-label" for="rule-priority">优先级（小的先匹配）</label>
          <input
            id="rule-priority"
            v-model.number="form.priority"
            class="frow-input num"
            type="number"
            min="0"
            max="999"
          />
        </div>

        <div class="frow row-inline">
          <span class="frow-label">启用</span>
          <button
            type="button"
            class="switch"
            role="switch"
            :aria-checked="form.enabled"
            aria-label="启用这条规则"
            @click="form.enabled = !form.enabled"
          >
            <span class="switch-knob" />
          </button>
        </div>

        <div class="frow">
          <label class="frow-label" for="rule-test">命中测试</label>
          <input
            id="rule-test"
            v-model="testText"
            class="frow-input"
            type="text"
            placeholder="粘一条群消息进来试试"
          />
          <p v-if="testResult" class="test" :class="{ hit: testResult.hit }">
            {{ testResult.detail }}
          </p>
        </div>

        <p v-if="formError" class="form-error" role="alert">{{ formError }}</p>

        <div v-if="editing" class="delete-zone">
          <button
            v-if="!confirmingDelete"
            type="button"
            class="danger-link"
            @click="confirmingDelete = true"
          >
            <Trash2 :size="13" aria-hidden="true" />
            删除这条规则
          </button>
          <div v-else class="confirm">
            <p>删除后插件下次拉取规则时就不再包含它。确定吗？</p>
            <div class="confirm-actions">
              <button type="button" class="btn btn-sm btn-ghost" @click="confirmingDelete = false">
                算了
              </button>
              <button type="button" class="btn btn-sm btn-danger" :disabled="saving" @click="remove">
                确定删除
              </button>
            </div>
          </div>
        </div>
      </div>

      <template #footer>
        <button type="button" class="btn btn-ghost" @click="sheetOpen = false">取消</button>
        <button type="button" class="btn btn-on-dark" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存' }}
        </button>
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

.head-row {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 14px;
}

.add-btn {
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  margin-top: 4px;
  border: 0;
  border-radius: 50%;
  background: var(--card-inverse);
  color: #fff;
  box-shadow: var(--shadow-card);
  cursor: pointer;
  flex: none;
}

.clamp-1 {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/* ── 规则卡 ───────────────────────────────────────────────── */

.stack {
  margin: 0;
  padding: 0;
  list-style: none;
}

.rule {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px 12px 12px 14px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.rule.off {
  background: rgba(255, 255, 255, 0.6);
  box-shadow: none;
}

.rule-main {
  flex: 1;
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr) auto;
  align-items: center;
  gap: 10px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.rule-icon {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--surface);
  color: var(--ink-2);
}

.rule-text {
  display: grid;
  gap: 2px;
  min-width: 0;
}

.rule-name {
  font-size: 14.5px;
  font-weight: 500;
}

.rule-meta {
  color: var(--ink-3);
  font-size: 11.5px;
  min-width: 0;
}

.rule-regex {
  color: var(--ink-3);
  font-size: 10.5px;
  min-width: 0;
}

.rule-priority {
  color: var(--ink-3);
  font-size: 10.5px;
}

/* ── 开关 ─────────────────────────────────────────────────── */

.switch {
  position: relative;
  width: 44px;
  height: 26px;
  padding: 0;
  border: 0;
  border-radius: var(--r-pill);
  background: rgba(17, 18, 20, 0.14);
  cursor: pointer;
  flex: none;
  transition: background-color var(--dur) var(--ease);
}

.switch[aria-checked="true"] {
  background: var(--card-inverse);
}

.switch:disabled {
  opacity: 0.5;
}

.switch-knob {
  position: absolute;
  top: 3px;
  left: 3px;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
  transition: transform var(--dur) var(--ease);
}

.switch[aria-checked="true"] .switch-knob {
  transform: translateX(18px);
}

/* ── 抽屉表单 ─────────────────────────────────────────────── */

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

.row-inline {
  grid-template-columns: 1fr auto;
  align-items: center;
}

.frow-label {
  color: var(--ink-3);
  font-size: 11.5px;
  font-weight: 500;
}

.frow-input {
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  font-size: 15px;
  outline: none;
  resize: none;
}

.frow-input::placeholder {
  color: var(--ink-3);
}

.frow-hint {
  color: var(--ink-3);
  font-size: 11px;
}

.frow-hint.bad {
  color: var(--danger);
}

.test {
  padding: 6px 9px;
  border-radius: var(--r-xs);
  background: rgba(17, 18, 20, 0.05);
  color: var(--ink-2);
  font-size: 11.5px;
}

.test.hit {
  background: rgba(47, 122, 85, 0.12);
  color: var(--ok);
}

.form-error {
  padding: 10px 14px;
  border-radius: var(--r-md);
  background: rgba(191, 58, 43, 0.18);
  color: #ffb4ab;
  font-size: 12.5px;
}

.delete-zone {
  margin-top: 4px;
}

.danger-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 8px 0;
  border: 0;
  background: transparent;
  color: rgba(255, 140, 128, 0.9);
  font-size: 12.5px;
  cursor: pointer;
}

.confirm {
  padding: 14px;
  border-radius: var(--r-md);
  background: rgba(191, 58, 43, 0.16);
  color: #ffc3ba;
  font-size: 12.5px;
  line-height: 1.55;
}

.confirm-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 10px;
}

</style>
