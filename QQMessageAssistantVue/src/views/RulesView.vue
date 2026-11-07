<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Check, ChevronDown, ChevronRight } from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import { previewNoiseFilter, type NoisePreviewItem } from '@/api/rules'
import { useNoiseFilter } from '@/composables/useNoiseFilter'
import { useRefresh } from '@/composables/useRefresh'
import { REPEAT_OPTIONS, repeatOptionLabel } from '@/utils/noise'

/**
 * 噪音过滤的总览页。四条规则里内容最重的两块（整句噪音词、一定要保留）已经
 * 各自下沉成独立页面，这里只留总开关、两个入口、一处就地设置和试跑。
 * 三个页面共用 useNoiseFilter 的同一份草稿，所以子页保存不会冲掉这里的改动。
 */
const {
  error, loading, loaded, dirty, saving, formError,
  enabled, keywords, regex, keepRegex,
  repeatValue, repeatIsCustom, wordsSummary, keepSummary,
  load, save, resetDefaults, toggleEnabled, setRepeat,
} = useNoiseFilter()

const SAMPLE_TEXTS = ['666', '哈哈哈，明天 20:00 班会', '下周三交表', '……', '笑死我了']
const REASON_LABEL: Record<string, string> = {
  keyword: '命中噪音词',
  repeat: '重复字符',
  punctuation: '纯标点',
  blank: '空内容',
}

/** 这一页只剩一处字段级长解释，仍按全站口径收进 ? */
const hints = reactive({ regex: false })
function toggleHint(key: keyof typeof hints) {
  hints[key] = !hints[key]
}

/* ── 重复的刷屏话：自定义下拉 ─────────────────────────────── */

/**
 * 原生 <select> 展开的是系统菜单（白底方框 + 蓝色选中条），和这套装帧完全脱节。
 * 这里用「按钮 + 纸片浮层」自己画：同一个卡片圆角、同一条发丝线、选中用强调色对勾。
 */
const repeatOpen = ref(false)
const repeatSelect = ref<HTMLElement | null>(null)
const repeatOptions = computed(() => [
  { value: '0', label: '不把重复字符当噪音' },
  ...REPEAT_OPTIONS.map((n) => ({ value: String(n), label: repeatOptionLabel(n) })),
  ...(repeatIsCustom.value ? [{ value: 'custom', label: '自定义表达式（见下方高级）' }] : []),
])
const repeatLabel = computed(() =>
  repeatOptions.value.find((option) => option.value === repeatValue.value)?.label
  ?? '自定义表达式（见下方高级）',
)

function chooseRepeat(value: string) {
  if (value !== 'custom') setRepeat(value)
  repeatOpen.value = false
}

function onOutsidePointerDown(event: PointerEvent) {
  const root = repeatSelect.value
  if (repeatOpen.value && root && !root.contains(event.target as Node)) repeatOpen.value = false
}
function onEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') repeatOpen.value = false
}

/** 自定义表达式不在任何档位里，自动摊开高级区，别让人以为设置丢了 */
const advancedOpen = ref(false)
watch(repeatIsCustom, (isCustom) => {
  if (isCustom) advancedOpen.value = true
}, { immediate: true })

/* ── 试跑 ─────────────────────────────────────────────────── */

const previewText = ref(SAMPLE_TEXTS.join('\n'))
const previewItems = ref<NoisePreviewItem[]>([])
const previewing = ref(false)
const previewError = ref('')

async function runPreview() {
  previewError.value = ''
  const texts = previewText.value
    .split('\n')
    .map((line) => line.trim())
    .filter(Boolean)
  if (!texts.length) {
    previewError.value = '先写几句话，一行一句'
    return
  }
  previewing.value = true
  try {
    // 按编辑框里「还没保存」的草稿试跑，不写库
    const result = await previewNoiseFilter(texts.slice(0, 20), {
      enabled: enabled.value,
      keywords: keywords.value,
      regex: regex.value,
      keepRegex: keepRegex.value,
    })
    previewItems.value = result.items
  } catch (e) {
    previewError.value = e instanceof Error ? e.message : '试跑失败'
  } finally {
    previewing.value = false
  }
}

onMounted(() => {
  document.addEventListener('pointerdown', onOutsidePointerDown)
  document.addEventListener('keydown', onEscape)
  void load(loaded.value)
})
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onOutsidePointerDown)
  document.removeEventListener('keydown', onEscape)
})
useRefresh(() => load(true))
</script>

<template>
  <div class="page">
    <PageHead :back="{ to: '/more', label: '更多' }" title="噪音过滤" />

    <p class="intro">
      消息进来时先判一次：命中就<strong>不再调用模型、不再自动回复</strong>，但原文照常写进消息日志。
      同一套规则也用在整理和夜间归纳之前，改一次三处同时生效。@我、问号、时间、作业这些一律保留。
    </p>

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="() => load()" />
    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else>
      <div class="card card-pad">
        <div class="switch-row">
          <span class="switch-text">
            <span class="switch-label">启用过滤</span>
            <span class="switch-hint">关掉后所有消息都照常进模型，token 用量会明显上升。</span>
          </span>
          <input
            type="checkbox"
            class="switch"
            role="switch"
            :checked="enabled"
            :aria-checked="enabled"
            :disabled="!loaded"
            aria-label="启用噪音过滤"
            @change="toggleEnabled"
          />
        </div>

        <!-- 两块重内容各自下沉成页面，这里只留一行能扫的入口 -->
        <ul class="rule-links">
          <li>
            <RouterLink to="/rules/words" class="rule-link">
              <span class="rule-link-text">
                <span class="rule-link-label">整句噪音词</span>
                <span class="rule-link-hint">{{ wordsSummary }}</span>
              </span>
              <ChevronRight :size="15" :stroke-width="2" class="rule-link-arrow" aria-hidden="true" />
            </RouterLink>
          </li>
          <li>
            <RouterLink to="/rules/keep" class="rule-link">
              <span class="rule-link-text">
                <span class="rule-link-label">一定要保留的内容</span>
                <span class="rule-link-hint">{{ keepSummary }}</span>
              </span>
              <ChevronRight :size="15" :stroke-width="2" class="rule-link-arrow" aria-hidden="true" />
            </RouterLink>
          </li>
        </ul>

        <div class="field">
          <div class="field-head">
            <label for="noise-repeat">重复的刷屏话</label>
            <button
              type="button"
              class="icon-btn hint-dot"
              :aria-expanded="hints.regex"
              aria-controls="hint-noise-regex"
              aria-label="「重复的刷屏话」的说明"
              @click="toggleHint('regex')"
            >?</button>
          </div>

          <div ref="repeatSelect" class="select">
            <button
              id="noise-repeat"
              type="button"
              class="input select-trigger"
              :aria-expanded="repeatOpen"
              aria-haspopup="listbox"
              aria-label="重复的刷屏话"
              @click="repeatOpen = !repeatOpen"
            >
              <span class="select-value">{{ repeatLabel }}</span>
              <ChevronDown
                :size="16"
                :stroke-width="2"
                class="select-caret"
                :class="{ open: repeatOpen }"
                aria-hidden="true"
              />
            </button>
            <Transition name="menu">
              <ul v-if="repeatOpen" class="select-menu" role="listbox" aria-label="重复的刷屏话">
                <li v-for="option in repeatOptions" :key="option.value">
                  <button
                    type="button"
                    class="select-option"
                    role="option"
                    :aria-selected="option.value === repeatValue"
                    :class="{ selected: option.value === repeatValue }"
                    @click="chooseRepeat(option.value)"
                  >
                    <span>{{ option.label }}</span>
                    <Check
                      v-if="option.value === repeatValue"
                      :size="15"
                      :stroke-width="2.4"
                      aria-hidden="true"
                    />
                  </button>
                </li>
              </ul>
            </Transition>
          </div>

          <p class="rule-example">例如：666、哈哈哈、……</p>
          <Transition name="hint">
            <p v-if="hints.regex" id="hint-noise-regex" class="field-hint">
              只判断「整句是不是同一个字重复出来的」。数字调大就更保守；
              选「不把重复字符当噪音」等于停用这条规则。
            </p>
          </Transition>
        </div>

        <div class="advanced">
          <button
            type="button"
            class="advanced-toggle"
            :aria-expanded="advancedOpen"
            aria-controls="noise-advanced"
            @click="advancedOpen = !advancedOpen"
          >
            <span>高级：直接编辑重复规则表达式</span>
            <span class="advanced-state">{{ advancedOpen ? '收起' : '展开' }}</span>
          </button>
          <Transition name="fold">
            <div v-if="advancedOpen" id="noise-advanced" class="advanced-body">
              <p v-if="repeatIsCustom" class="field-hint advanced-note">
                当前是自定义表达式，上面的下拉只管内置档位；要改它请用这里。
              </p>
              <div class="field">
                <label for="noise-regex">噪音正则</label>
                <input
                  id="noise-regex"
                  v-model="regex"
                  class="input mono-input"
                  type="text"
                  spellcheck="false"
                  placeholder="^(.)\1{2,}$"
                />
              </div>
              <p class="field-hint">
                下拉和这里改的是同一份配置，改任意一边另一边会跟着变；写坏了保存时会直接报错。
              </p>
            </div>
          </Transition>
        </div>

        <div v-if="formError" class="error" role="alert">{{ formError }}</div>

        <div class="noise-actions">
          <button
            type="button"
            class="btn btn-quiet"
            :disabled="!loaded || saving"
            @click="resetDefaults"
          >
            恢复默认
          </button>
          <button
            type="button"
            class="btn"
            :class="dirty ? 'btn-primary' : 'btn-quiet'"
            :disabled="!loaded || saving || !dirty"
            @click="save"
          >
            {{ saving ? '保存中…' : dirty ? '保存' : '已保存' }}
          </button>
        </div>

        <div class="preview">
          <label for="noise-preview">试一试（一行一句，按当前编辑框内容判定，不用先保存）</label>
          <textarea
            id="noise-preview"
            v-model="previewText"
            class="input textarea noise-textarea"
            rows="4"
          ></textarea>
          <button
            type="button"
            class="btn btn-quiet preview-btn"
            :disabled="previewing || !loaded"
            @click="runPreview"
          >
            试跑
          </button>
          <p v-if="previewError" class="error" role="alert">{{ previewError }}</p>
          <ul v-if="previewItems.length" class="preview-list">
            <li v-for="(item, i) in previewItems" :key="i">
              <span class="preview-tag" :class="item.kept ? 'kept' : 'dropped'">
                {{ item.kept ? '保留' : '丢弃' }}
              </span>
              <span class="preview-text">{{ item.text }}</span>
              <span v-if="!item.kept && item.reason" class="preview-reason">
                {{ REASON_LABEL[item.reason] ?? item.reason }}
              </span>
            </li>
          </ul>
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 14px;
  color: var(--ink-2);
  font-size: 12.5px;
  line-height: 1.65;
}

.intro strong {
  color: var(--ink);
  font-weight: 600;
}

.switch-row {
  margin-bottom: 14px;
}

/* ── 两块重内容的入口 ─────────────────────────────────────── */
.rule-links {
  margin: 0 0 20px;
  padding: 0;
  list-style: none;
}

/* 只有两行之间一道线，外面不套第二层卡片 */
.rule-links > li + li {
  border-top: 1px solid var(--hairline);
}

.rule-link {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 54px;
  padding: 9px 2px;
  color: var(--ink);
  text-decoration: none;
  transition: background-color var(--dur-1) var(--ease-out);
}

.rule-link:active {
  background: var(--surface);
}

.rule-link-text {
  flex: 1;
  display: grid;
  gap: 3px;
  min-width: 0;
}

.rule-link-label {
  font-size: 14px;
  font-weight: 500;
}

.rule-link-hint {
  overflow: hidden;
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.45;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.rule-link-arrow {
  flex: none;
  color: var(--ink-3);
  opacity: 0.7;
}

/* ── 字段说明的 ? 折叠（与 Chats / Settings 页同一套） ─────── */
.field-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

.field label {
  display: block;
  color: var(--ink);
  font-size: 14px;
  font-weight: 500;
}

.field .field-head label {
  margin-bottom: 0;
}

.hint-dot {
  width: 18px;
  height: 18px;
  margin-left: auto;
  flex: none;
  border: 1px solid var(--hairline);
  border-radius: 50%;
  background: var(--card);
  color: var(--ink-3);
  font-family: var(--font-ui);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1;
  letter-spacing: 0;
  text-transform: none;
  overflow: hidden;
}

.hint-dot[aria-expanded='true'] {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.field-hint {
  margin: 6px 0 0;
}

.hint-enter-active,
.hint-leave-active {
  overflow: hidden;
  transition: opacity var(--dur) var(--ease),
    max-height var(--dur-2) var(--ease),
    margin var(--dur-2) var(--ease);
}

.hint-enter-from,
.hint-leave-to {
  opacity: 0;
  max-height: 0;
  margin-top: 0;
  margin-bottom: 0;
}

.hint-enter-to,
.hint-leave-from {
  opacity: 1;
  max-height: 120px;
}

/* ── 重复的刷屏话：自定义下拉 ─────────────────────────────── */
.select {
  position: relative;
}

.select-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  min-height: 48px;
  padding: 10px 14px;
  text-align: left;
  font-family: var(--font-ui);
  font-size: 13.5px;
  color: var(--ink);
  cursor: pointer;
}

.select-trigger:active {
  background: var(--surface);
}

.select-trigger[aria-expanded='true'] {
  border-color: var(--ink);
}

.select-value {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.select-caret {
  flex: none;
  color: var(--ink-3);
  transition: transform var(--dur-2) var(--ease);
}

.select-caret.open {
  transform: rotate(180deg);
}

/* 和卡片同一套材质：纸白底 + 发丝线 + 一层几乎看不见的托起阴影 */
.select-menu {
  position: absolute;
  z-index: 20;
  top: calc(100% + 6px);
  left: 0;
  right: 0;
  margin: 0;
  padding: 5px;
  list-style: none;
  max-height: 288px;
  overflow-y: auto;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-lift);
}

.select-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  width: 100%;
  min-height: 40px;
  padding: 8px 10px;
  border: 0;
  border-radius: var(--r-sm);
  background: transparent;
  color: var(--ink-2);
  font-family: var(--font-ui);
  font-size: 12.5px;
  line-height: 1.4;
  text-align: left;
  cursor: pointer;
}

.select-option:active {
  background: var(--surface);
}

.select-option.selected {
  background: var(--accent-soft);
  color: var(--accent-ink);
  font-weight: 500;
}

.menu-enter-active,
.menu-leave-active {
  transform-origin: top center;
  transition: opacity var(--dur-1) var(--ease-out),
    transform var(--dur-1) var(--ease-out);
}

.menu-enter-from,
.menu-leave-to {
  opacity: 0;
  transform: translateY(-4px) scale(0.985);
}

.rule-example {
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.5;
}

/* ── 高级：原始表达式 ─────────────────────────────────────── */
.advanced {
  margin-top: 18px;
}

.advanced-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink-3);
  font-family: var(--font-ui);
  font-size: 12px;
  cursor: pointer;
}

.advanced-state {
  color: var(--accent-ink);
}

.advanced-body {
  padding-top: 12px;
}

.advanced-note {
  margin-bottom: 8px;
  color: var(--accent-ink);
}

.fold-enter-active,
.fold-leave-active {
  overflow: hidden;
  transition: opacity var(--dur) var(--ease),
    max-height var(--dur-2) var(--ease),
    padding var(--dur-2) var(--ease);
}

.fold-enter-from,
.fold-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
}

.fold-enter-to,
.fold-leave-from {
  opacity: 1;
  max-height: 240px;
}

@media (prefers-reduced-motion: reduce) {
  .hint-enter-active,
  .hint-leave-active,
  .fold-enter-active,
  .fold-leave-active,
  .menu-enter-active,
  .menu-leave-active {
    transition: none;
  }
}

/* ── 表单 / 试跑 ─────────────────────────────────────────── */
.mono-input {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.noise-textarea {
  min-height: 64px;
}

.noise-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.noise-actions .btn {
  flex: 1 1 0;
}

.preview {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid var(--hairline);
}

.preview > label {
  display: block;
  margin-bottom: 8px;
  color: var(--ink-2);
  font-size: 12.5px;
  font-weight: 600;
}

.preview-btn {
  margin-top: 10px;
}

.preview-list {
  margin: 12px 0 0;
  padding: 0;
  list-style: none;
}

.preview-list li {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 7px 0;
  border-bottom: 1px solid var(--hairline);
  font-size: 12.5px;
}

.preview-list li:last-child {
  border-bottom: 0;
}

.preview-tag {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
}

.preview-tag.kept {
  color: var(--ink-2);
  background: var(--fill, rgba(127, 127, 127, 0.12));
}

.preview-tag.dropped {
  color: #b42318;
  background: rgba(180, 35, 24, 0.1);
}

.preview-text {
  flex: 1 1 auto;
  min-width: 0;
  overflow-wrap: anywhere;
  color: var(--ink);
}

.preview-reason {
  flex: 0 0 auto;
  color: var(--ink-3);
  font-size: 11px;
}
</style>
