import { computed, reactive, toRefs, watch } from 'vue'
import { getNoiseFilter, putNoiseFilter, type NoiseFilterSettings } from '@/api/rules'
import {
  KEEP_GROUPS, REGEX_META, activeKeepGroups, countKeywords,
  escapeRegex, parseRepeat, reorderKeepTokens, repeatRegex, splitTokens,
} from '@/utils/noise'
import { useToast } from './useToast'

/**
 * 噪音过滤是「一整张表」：PUT 必须一次带上 enabled / keywords / regex / keepRegex 四个键。
 * 拆成三个页面之后，如果各页自己 GET + PUT 自己那一个字段，子页保存会把主页面
 * 还没保存的草稿冲掉。所以这里做一份模块级单例状态，三个页面读写同一份草稿，
 * 任意一处点「保存」都是把当前这份完整配置整体提交。
 */
const state = reactive({
  enabled: true,
  keywords: '',
  regex: '',
  keepRegex: '',
  defaults: null as NoiseFilterSettings | null,
  loading: true,
  loaded: false,
  error: '',
  dirty: false,
  saving: false,
  formError: '',
})

/**
 * 服务端回填 / 保存后回填不能算「用户改过」。watch 用 sync，
 * 赋值时把 hydrating 立起来，赋值完立刻放下即可，不必等 nextTick。
 */
let hydrating = false
watch(
  () => [state.enabled, state.keywords, state.regex, state.keepRegex],
  () => { if (!hydrating) state.dirty = true },
  { flush: 'sync' },
)

function hydrate(noise: NoiseFilterSettings) {
  hydrating = true
  state.enabled = noise.enabled
  state.keywords = noise.keywords ?? ''
  state.regex = noise.regex ?? ''
  state.keepRegex = noise.keepRegex ?? ''
  hydrating = false
}

/** silent = 下拉刷新：保留正在编辑的草稿，不闪骨架屏 */
async function load(silent = false) {
  if (!silent) {
    state.loading = true
    state.error = ''
  }
  try {
    const noise = await getNoiseFilter()
    state.defaults = noise
    if (!state.dirty) hydrate(noise)
    state.loaded = true
  } catch (e) {
    const message = e instanceof Error ? e.message : '规则读取失败'
    if (silent) useToast().error(message)
    else state.error = message
  } finally {
    state.loading = false
  }
}

async function save(): Promise<boolean> {
  state.formError = ''
  state.saving = true
  try {
    const saved = await putNoiseFilter({
      enabled: state.enabled,
      keywords: state.keywords,
      regex: state.regex,
      keepRegex: state.keepRegex,
    })
    state.defaults = saved
    hydrate(saved)
    state.dirty = false
    useToast().success('噪音过滤已保存')
    return true
  } catch (e) {
    state.formError = e instanceof Error ? e.message : '保存失败'
    return false
  } finally {
    state.saving = false
  }
}

function resetDefaults() {
  const d = state.defaults
  if (!d) return
  state.enabled = true
  state.keywords = d.defaultKeywords
  state.regex = d.defaultRegex
  state.keepRegex = d.defaultKeepRegex
  state.dirty = true
  state.formError = ''
}

/** 二级页只恢复自己那一项，不动用户可能在主页面改过的其它三项 */
function resetKeywords() {
  if (!state.defaults) return
  state.keywords = state.defaults.defaultKeywords
  state.formError = ''
}

function resetKeepRegex() {
  if (!state.defaults) return
  state.keepRegex = state.defaults.defaultKeepRegex
  state.formError = ''
}

function toggleEnabled() {
  state.enabled = !state.enabled
}

function setRepeat(value: string) {
  if (value === 'custom') return
  state.regex = repeatRegex(Number(value))
  state.formError = ''
}

const repeatValue = computed(() => {
  const n = parseRepeat(state.regex)
  return n < 0 ? 'custom' : String(n)
})
const repeatIsCustom = computed(() => parseRepeat(state.regex) < 0)

const keywordCount = computed(() => countKeywords(state.keywords))
const wordsSummary = computed(() =>
  keywordCount.value ? `已设置 ${keywordCount.value} 个词` : '还没设置噪音词',
)

const keepTokens = computed(() => splitTokens(state.keepRegex))
const activeGroups = computed(() => activeKeepGroups(keepTokens.value))
const activeGroupIds = computed<string[]>(() => activeGroups.value.map((group) => group.id))
/** 没被任何「完整分组」认领的片段：可能是用户加的词，也可能是自定义正则 */
const customTokens = computed(() => {
  const owned = new Set<string>(activeGroups.value.flatMap((group) => [...group.tokens]))
  return keepTokens.value.filter((token) => !owned.has(token))
})
const customWords = computed(() => customTokens.value.filter((token) => !REGEX_META.test(token)))
const customRegexTokens = computed(() => customTokens.value.filter((token) => REGEX_META.test(token)))
/** 认不出来的表达式要让「高级」自动摊开，别让人以为自己的设置丢了 */
const customCount = computed(() => (repeatIsCustom.value ? 1 : 0) + customRegexTokens.value.length)
const keepSummary = computed(() => {
  const parts = [`已开启 ${activeGroupIds.value.length}/${KEEP_GROUPS.length} 组`]
  if (customWords.value.length) parts.push(`${customWords.value.length} 个自定义词`)
  return parts.join(' · ')
})

function writeKeepTokens(tokens: string[]) {
  state.keepRegex = reorderKeepTokens(tokens)
  state.formError = ''
}

function toggleKeepGroup(id: string) {
  const group = KEEP_GROUPS.find((item) => item.id === id)
  if (!group) return
  const current = keepTokens.value
  const on = activeGroupIds.value.includes(id)
  const next = on
    ? current.filter((token) => !(group.tokens as readonly string[]).includes(token))
    : [...current, ...group.tokens.filter((token) => !current.includes(token))]
  writeKeepTokens(next)
}

/** 返回是否真的加进去了，页面据此决定要不要清空输入框 */
function addKeepWord(raw: string): boolean {
  const word = raw.trim()
  if (!word) return false
  const token = escapeRegex(word)
  if (keepTokens.value.includes(token)) return false
  writeKeepTokens([...keepTokens.value, token])
  return true
}

function removeCustomToken(token: string) {
  writeKeepTokens(keepTokens.value.filter((item) => item !== token))
}

/** 常用词标签点一下加、再点一下去掉；顺手把中英文逗号统一成半角 */
function toggleKeyword(word: string) {
  const clean = word.trim()
  if (!clean) return
  const words = state.keywords.split(/[,，]/).map((item) => item.trim()).filter(Boolean)
  state.keywords = (words.includes(clean) ? words.filter((item) => item !== clean) : [...words, clean]).join(',')
  state.formError = ''
}

export function useNoiseFilter() {
  return {
    ...toRefs(state),
    repeatValue,
    repeatIsCustom,
    keywordCount,
    wordsSummary,
    keepTokens,
    activeGroupIds,
    customWords,
    customRegexTokens,
    customCount,
    keepSummary,
    load,
    save,
    resetDefaults,
    resetKeywords,
    resetKeepRegex,
    toggleEnabled,
    setRepeat,
    toggleKeepGroup,
    addKeepWord,
    removeCustomToken,
    toggleKeyword,
  }
}
