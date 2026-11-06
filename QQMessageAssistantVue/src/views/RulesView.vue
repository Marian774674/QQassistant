<script setup lang="ts">
import { onMounted, ref } from 'vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import {
  getNoiseFilter,
  previewNoiseFilter,
  putNoiseFilter,
  type NoiseFilterSettings,
  type NoisePreviewItem,
} from '@/api/rules'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'

/**
 * 噪音过滤配置页。原来的「拦截规则」（命中关键词 → 绕过模型回模板）已下线，
 * 这个菜单位置由噪音过滤接管：消息入口、整理、夜间归纳共用同一套规则。
 */
const toast = useToast()

const SAMPLE_TEXTS = ['666', '哈哈哈，明天 20:00 班会', '下周三交表', '……', '笑死我了']
const REASON_LABEL: Record<string, string> = {
  keyword: '命中噪音词',
  repeat: '重复字符',
  punctuation: '纯标点',
  blank: '空内容',
}

const loading = ref(true)
const error = ref('')
const loaded = ref(false)
const dirty = ref(false)
const saving = ref(false)
const formError = ref('')

const enabled = ref(true)
const keywords = ref('')
const regex = ref('')
const keepRegex = ref('')
const defaults = ref<NoiseFilterSettings | null>(null)

const previewText = ref(SAMPLE_TEXTS.join('\n'))
const previewItems = ref<NoisePreviewItem[]>([])
const previewing = ref(false)
const previewError = ref('')

/** silent = 下拉刷新：保留正在编辑的内容，不闪骨架屏 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const noise = await getNoiseFilter()
    defaults.value = noise
    if (!dirty.value) {
      enabled.value = noise.enabled
      keywords.value = noise.keywords ?? ''
      regex.value = noise.regex ?? ''
      keepRegex.value = noise.keepRegex ?? ''
    }
    loaded.value = true
  } catch (e) {
    const message = e instanceof Error ? e.message : '规则读取失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

onMounted(load)
useRefresh(() => load(true))

function toggleEnabled() {
  enabled.value = !enabled.value
  dirty.value = true
}

function resetDefaults() {
  const d = defaults.value
  if (!d) return
  enabled.value = true
  keywords.value = d.defaultKeywords
  regex.value = d.defaultRegex
  keepRegex.value = d.defaultKeepRegex
  dirty.value = true
  formError.value = ''
}

async function save() {
  formError.value = ''
  saving.value = true
  try {
    const saved = await putNoiseFilter({
      enabled: enabled.value,
      keywords: keywords.value,
      regex: regex.value,
      keepRegex: keepRegex.value,
    })
    dirty.value = false
    defaults.value = saved
    enabled.value = saved.enabled
    keywords.value = saved.keywords ?? ''
    regex.value = saved.regex ?? ''
    keepRegex.value = saved.keepRegex ?? ''
    toast.success('噪音过滤已保存')
  } catch (e) {
    formError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    saving.value = false
  }
}

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
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      title="噪音过滤"
      sub="没信息量的话不进模型。原「拦截规则」已下线，这个位置现在归它。"
    />

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="() => load()" />

    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else>
      <p class="intro">
        消息进来时先判一次：命中就<strong>不再调用模型、不再自动回复</strong>，但原文照常写进消息日志。
        同一套规则也用在整理和夜间归纳之前，改一次三处同时生效。@我、问号、时间、作业这些一律保留。
      </p>

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

        <div class="field">
          <label for="noise-keywords">整句噪音词</label>
          <textarea
            id="noise-keywords"
            v-model="keywords"
            class="input textarea noise-textarea"
            rows="2"
            placeholder="666,哈哈哈,笑死我了"
            @input="dirty = true"
          ></textarea>
          <p class="field-hint">
            英文逗号分隔，只有「整句就是这个词」才算噪音。「好」「走」这种别放进来，可能是有效回复。
          </p>
        </div>

        <div class="field">
          <label for="noise-regex">噪音正则</label>
          <input
            id="noise-regex"
            v-model="regex"
            class="input mono-input"
            type="text"
            spellcheck="false"
            placeholder="^(.)\1{2,}$"
            @input="dirty = true"
          />
          <p class="field-hint">留空停用。默认匹配三个及以上相同字符。写坏了保存时会直接报错。</p>
        </div>

        <div class="field">
          <label for="noise-keep">强制保留正则</label>
          <input
            id="noise-keep"
            v-model="keepRegex"
            class="input mono-input"
            type="text"
            spellcheck="false"
            placeholder="@|？|作业|截止|明天"
            @input="dirty = true"
          />
          <p class="field-hint">
            命中就不丢，优先级高于上面两条。默认覆盖 @、问号、文件、时间与事务词。
          </p>
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
  margin-bottom: 16px;
}

/* ── 表单（骨架在 style.css，这里只补本页专属） ───────────── */
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

/* ── 试跑 ─────────────────────────────────────────────────── */
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
