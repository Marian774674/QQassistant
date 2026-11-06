<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Coins } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Segmented from '@/components/Segmented.vue'
import Skeleton from '@/components/Skeleton.vue'
import {
  getRecentTokenUsage,
  getTokenUsageSummary,
  type TokenUsageChatBucket,
  type TokenUsageRow,
  type TokenUsageSummary,
} from '@/api/tokenUsage'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'
import { firstSegment } from '@/utils/name'

type WindowKey = '7' | '30' | '90'

const toast = useToast()
const days = ref<WindowKey>('7')
const tabs: Array<{ key: WindowKey; label: string }> = [
  { key: '7', label: '近 7 天' },
  { key: '30', label: '近 30 天' },
  { key: '90', label: '近 90 天' },
]

const summary = ref<TokenUsageSummary | null>(null)
const recent = ref<TokenUsageRow[]>([])
const loading = ref(true)
const error = ref('')

// 明细一次拉 50 条，但默认只铺 12 条：一进页面就压出五十行会把上面的图表挤没
const expanded = ref(false)
const VISIBLE_ROWS = 12
const shownRecent = computed(() =>
  expanded.value ? recent.value : recent.value.slice(0, VISIBLE_ROWS),
)
const hasMore = computed(() => recent.value.length > VISIBLE_ROWS)

/** 一次拉齐汇总和明细，避免两个请求回来时间不同导致数字对不上 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const n = Number(days.value)
    const [s, rows] = await Promise.all([
      getTokenUsageSummary(n),
      getRecentTokenUsage(n, 50),
    ])
    summary.value = s
    recent.value = rows ?? []
  } catch (e) {
    const message = e instanceof Error ? e.message : '用量读取失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

onMounted(() => void load())
watch(days, () => void load())
useRefresh(() => load(true))

const TASK_LABEL: Record<string, string> = {
  chat: '自动回复',
  digest: '消息整理',
  extract: '画像抽取',
  ask: '问一句',
  file: '文件任务',
  recap: '夜间归纳',
}
const SOURCE_LABEL: Record<string, string> = {
  spring: '后端工作流',
  plugin: '对话插件',
}

function taskLabel(task: string) {
  return TASK_LABEL[task] ?? task
}
function sourceLabel(source: string) {
  return SOURCE_LABEL[source] ?? source
}
/** 明细副行与 title 提示共用一段文案，避免窄屏截断后看不到完整信息 */
function rowMeta(row: TokenUsageRow) {
  return `${sourceLabel(row.source)} · ${row.model || '未标注'} · ${formatDateTime(row.createdAt)}`
}
function num(value: number | null | undefined) {
  return (value ?? 0).toLocaleString('zh-CN')
}
function compact(value: number | null | undefined) {
  const n = value ?? 0
  return n >= 10_000 ? `${(n / 10_000).toFixed(1)}万` : num(n)
}

const byTask = computed(() => summary.value?.byTask ?? [])
const bySource = computed(() => summary.value?.bySource ?? [])
const byDay = computed(() => summary.value?.byDay ?? [])
const maxTask = computed(() => Math.max(1, ...byTask.value.map((b) => b.tokens ?? 0)))
const maxDay = computed(() => Math.max(1, ...byDay.value.map((b) => b.tokens ?? 0)))
const avgPerCall = computed(() => {
  const s = summary.value
  return s && s.calls ? Math.round(s.totalTokens / s.calls) : 0
})
const avgPerDay = computed(() => {
  const s = summary.value
  return s && s.days ? Math.round(s.totalTokens / s.days) : 0
})
function dayLabel(day: string) {
  const parts = String(day).split('-')
  return parts.length === 3 ? `${Number(parts[1])}/${Number(parts[2])}` : String(day)
}

/* ── 「花在哪个会话」饼图 ──────────────────────────────────────
 * 不引图表库：一圈描边圆 + stroke-dasharray 就是饼图，体积为零、缩放不糊。
 * 服务端已经把 Top6 / 其它 / 未归属合并好，前端只算占比和弧长。
 */
const DONUT_R = 45
const DONUT_C = 2 * Math.PI * DONUT_R
/** 扇区之间留一点纸色，颜色才不会糊成一整圈 */
const DONUT_GAP = 2.2

/** 纸墨色系：从强调色一路褪到暖褐灰，别用彩虹色把整页配色砸掉 */
const CHAT_COLORS = ['#d92e73', '#e8779f', '#b6537e', '#8f6478', '#b08a6f', '#88806f']
/**
 * 两块非真实会话要分开：未归属是「压根不是群」，用冷灰明确退到背景；
 * 其它会话仍是真实会话，只是被合并了，给最浅的一档暖褐，留在会话色系里。
 * 都比 `--surface` 轨道色深，小扇区才不会融进底纸。
 */
const UNASSIGNED_COLOR = '#a8a49d'
const OTHER_COLOR = '#c6b49c'

interface ChatSlice extends TokenUsageChatBucket {
  color: string
  length: number
  offset: number
  percent: string
  shareLabel: string
}

const byChat = computed(() => summary.value?.byChat ?? [])
const chatTotal = computed(() => byChat.value.reduce((sum, b) => sum + (b.tokens ?? 0), 0))
const hoveredSlice = ref<number | null>(null)

const chatSlices = computed<ChatSlice[]>(() => {
  const total = chatTotal.value
  if (!total) return []
  let offset = 0
  let chatIndex = 0
  return byChat.value.map((bucket) => {
    const share = (bucket.tokens ?? 0) / total
    const raw = share * DONUT_C
    const length = Math.max(0.8, raw - (byChat.value.length > 1 ? DONUT_GAP : 0))
    const pct = share * 100
    const color =
      bucket.kind === 'chat'
        ? CHAT_COLORS[chatIndex++ % CHAT_COLORS.length]
        : bucket.kind === 'unassigned'
          ? UNASSIGNED_COLOR
          : OTHER_COLOR
    const slice: ChatSlice = {
      ...bucket,
      color,
      length,
      offset,
      percent: pct >= 10 ? String(Math.round(pct)) : pct.toFixed(1),
      shareLabel: `${pct.toFixed(1)}%`,
    }
    offset += raw
    return slice
  })
})

const donutAria = computed(
  () => `各会话 token 占比，共 ${num(chatTotal.value)} tokens，${chatSlices.value.length} 块`,
)

function chatLabel(bucket: TokenUsageChatBucket) {
  return (
    firstSegment(bucket.label) ||
    (bucket.chatId != null ? `群 ${bucket.chatId}` : '未归属会话')
  )
}
/** hover 时把完整名字和群号都摊开，方便确认「到底是哪个群」 */
function chatTitle(bucket: TokenUsageChatBucket) {
  const name = bucket.label || chatLabel(bucket)
  return bucket.kind === 'chat' && bucket.chatId != null
    ? `${name} · 群 ${bucket.chatId}`
    : name
}
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      eyebrow="只读账本"
      title="Usage"
      sub="每次调用 Dify 记一行。这里只统计，不改写，也不参与自动回复决策。"
    />

    <Segmented v-model="days" :items="tabs" label="选择统计时间窗" />

    <div class="body">
      <ErrorState v-if="error" :message="error" hint="下拉可重试。" @retry="load()" />
      <Skeleton v-else-if="loading" :rows="4" />

      <template v-else-if="summary">
        <EmptyState
          v-if="!summary.calls"
          title="这个时间窗还没有调用记录"
          hint="后端工作流和对话插件成功调用 Dify 之后，这里才会出现数字。"
          :icon="Coins"
        />

        <template v-else>
          <!-- 数字栏：和「更多」页同一套发丝线排版，不另做仪表盘卡片 -->
          <section class="totals" aria-label="总量">
            <div class="total">
              <span class="total-num num">{{ num(summary.calls) }}</span>
              <span class="total-label">次调用</span>
            </div>
            <div class="total">
              <span class="total-num num">{{ compact(summary.totalTokens) }}</span>
              <span class="total-label">tokens</span>
            </div>
            <div class="total">
              <span class="total-num num">{{ compact(avgPerCall) }}</span>
              <span class="total-label">每次均量</span>
            </div>
          </section>
          <p class="footnote">
            输入 {{ num(summary.promptTokens) }} · 输出 {{ num(summary.completionTokens) }} ·
            日均 {{ num(avgPerDay) }}。Dify 不回传模型名时记为「未标注」，不做猜测。
          </p>

          <section class="block" aria-label="按任务分账">
            <h2 class="block-title">花在哪儿</h2>
            <ul class="bars">
              <li v-for="bucket in byTask" :key="bucket.task ?? 'unknown'" class="bar-row">
                <span class="bar-label">{{ taskLabel(String(bucket.task ?? '未分类')) }}</span>
                <span class="bar-track" aria-hidden="true">
                  <span
                    class="bar-fill"
                    :style="{ width: `${Math.max(2, ((bucket.tokens ?? 0) / maxTask) * 100)}%` }"
                  />
                </span>
                <span class="bar-value num">{{ compact(bucket.tokens) }}</span>
                <span class="bar-calls num">{{ num(bucket.calls) }} 次</span>
              </li>
            </ul>
            <p v-if="!byTask.length" class="muted">暂无分账数据。</p>
          </section>

          <section class="block" aria-label="按调用方分账">
            <h2 class="block-title">谁调的</h2>
            <div class="chips">
              <span v-for="bucket in bySource" :key="bucket.source ?? 'unknown'" class="chip">
                {{ sourceLabel(String(bucket.source ?? '未知')) }}
                <b class="num">{{ compact(bucket.tokens) }}</b>
              </span>
            </div>
          </section>

          <section v-if="chatSlices.length" class="block" aria-label="按会话分账">
            <h2 class="block-title">花在哪个会话</h2>

            <div class="donut-wrap">
              <svg class="donut" viewBox="0 0 120 120" role="img" :aria-label="donutAria">
                <circle class="donut-track" cx="60" cy="60" :r="DONUT_R" />
                <circle
                  v-for="(slice, i) in chatSlices"
                  :key="`slice-${slice.kind}-${slice.chatId ?? slice.label}-${i}`"
                  class="donut-slice"
                  cx="60"
                  cy="60"
                  :r="DONUT_R"
                  :stroke="slice.color"
                  :stroke-dasharray="`${slice.length} ${DONUT_C - slice.length}`"
                  :stroke-dashoffset="`${-slice.offset}`"
                  :opacity="hoveredSlice === null || hoveredSlice === i ? 1 : 0.3"
                  transform="rotate(-90 60 60)"
                >
                  <title>
                    {{ slice.label }} · {{ slice.shareLabel }} · {{ num(slice.tokens) }} tokens
                  </title>
                </circle>
              </svg>
              <div class="donut-center" aria-hidden="true">
                <span class="donut-num num">{{ compact(chatTotal) }}</span>
                <span class="donut-label">tokens</span>
              </div>
            </div>

            <ul class="rows chat-legend">
              <li
                v-for="(slice, i) in chatSlices"
                :key="`legend-${slice.kind}-${slice.chatId ?? slice.label}-${i}`"
                class="row"
                @mouseenter="hoveredSlice = i"
                @mouseleave="hoveredSlice = null"
              >
                <span
                  class="legend-swatch"
                  :style="{ background: slice.color }"
                  aria-hidden="true"
                />
                <span class="row-main">
                  <span class="row-title" :title="chatTitle(slice)">{{ chatLabel(slice) }}</span>
                  <span class="row-meta">
                    {{ num(slice.tokens) }} tokens · {{ num(slice.calls) }} 次调用
                  </span>
                </span>
                <span class="row-tokens num">
                  <b>{{ slice.percent }}%</b>
                  <small>占比</small>
                </span>
              </li>
            </ul>
            <p class="muted">
              按账本里的群号分账，占比之和等于总量。灰色的「未归属会话」是问一句、画像抽取、
              夜间归纳和私聊自动回复（账本只记群号，暂时分不开私聊对象）；真实会话超过 6 个时，
              其余合并成「其它会话」。
            </p>
          </section>

          <section v-if="byDay.length" class="block" aria-label="按天分布">
            <h2 class="block-title">按天</h2>
            <div class="days">
              <div v-for="bucket in byDay" :key="bucket.day ?? 'unknown'" class="day">
                <span class="day-num num">{{ compact(bucket.tokens) }}</span>
                <span class="day-bar" aria-hidden="true">
                  <span
                    class="day-fill"
                    :style="{ height: `${Math.max(3, ((bucket.tokens ?? 0) / maxDay) * 100)}%` }"
                  />
                </span>
                <span class="day-label num">{{ dayLabel(String(bucket.day ?? '')) }}</span>
              </div>
            </div>
          </section>

          <section class="block" aria-label="最近调用">
            <h2 class="block-title">
              最近调用
              <span v-if="recent.length" class="num">{{ recent.length }} 条</span>
            </h2>
            <p v-if="!recent.length" class="muted">这个时间窗还没有调用明细。</p>
            <template v-else>
              <!--
                .rows 的卡片、.row 的 16px 横向内边距、li + li 的行间发丝线
                都由 style.css 统一提供。别在这里把 .row 的 padding 改成 `10px 0`：
                那会抹掉横向内边距，文字直接贴到卡片边框上（截图反馈）。
              -->
              <ul class="rows">
                <li v-for="row in shownRecent" :key="row.id" class="row">
                  <span class="row-main">
                    <span class="row-title">{{ taskLabel(row.task) }}</span>
                    <span class="row-meta" :title="rowMeta(row)">{{ rowMeta(row) }}</span>
                  </span>
                  <span class="row-tokens num">
                    <b>{{ num(row.totalTokens) }}</b>
                    <small>tokens</small>
                  </span>
                </li>
                <li v-if="hasMore" class="row-more">
                  <button type="button" @click="expanded = !expanded">
                    {{ expanded ? '收起' : `展开全部 ${recent.length} 条` }}
                  </button>
                </li>
              </ul>
              <p class="muted">只追加、只展示：账本里的数字不会被网页改写。</p>
            </template>
          </section>
        </template>
      </template>
    </div>
  </div>
</template>

<style scoped>
.body {
  margin-top: 18px;
}

/* 总量：上下各一条发丝线，三列之间竖线分隔，靠排版而不是容器 */
.totals {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid var(--rule);
  border-bottom: 1px solid var(--rule);
}

.total {
  display: grid;
  justify-items: start;
  gap: 7px;
  padding: 17px 0 15px;
  min-width: 0;
}

.total + .total {
  padding-left: 14px;
  border-left: 1px solid var(--rule);
}

.total-num {
  font-family: var(--font-display);
  font-size: 30px;
  line-height: 0.95;
  letter-spacing: -0.02em;
  font-variant-numeric: lining-nums tabular-nums;
  white-space: nowrap;
}

.total-label,
.block-title {
  color: var(--ink-3);
  font-size: 10px;
  font-weight: 600;
  letter-spacing: 0.14em;
  white-space: nowrap;
}

.footnote,
.muted {
  margin: 10px 0 0;
  color: var(--ink-3);
  font-size: 11px;
  line-height: 1.6;
}

.block {
  margin-top: 26px;
}

.block-title {
  margin: 0 0 12px;
}

.bars,
.rows,
.chips {
  margin: 0;
  padding: 0;
  list-style: none;
}

.bar-row {
  display: grid;
  grid-template-columns: 72px minmax(0, 1fr) 52px 46px;
  align-items: center;
  gap: 9px;
  min-height: 32px;
}

.bar-label,
.row-title {
  font-size: 12.5px;
  color: var(--ink);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.bar-track {
  display: block;
  height: 6px;
  border-radius: var(--r-pill);
  background: var(--surface);
  overflow: hidden;
}

.bar-fill {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: var(--accent);
  opacity: 0.75;
}

.bar-value,
.bar-calls {
  font-size: 11px;
  color: var(--ink-2);
  text-align: right;
  white-space: nowrap;
}

.bar-calls {
  color: var(--ink-3);
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 11px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-pill);
  color: var(--ink-2);
  font-size: 11.5px;
}

.chip b {
  color: var(--ink);
  font-weight: 600;
}

/*
 * 只有一天有数据时，原来 `flex: 1 0 30px` 会让唯一的一根柱子 grow 到整宽，
 * 变成一块铺满卡片的粉色矩形（截图反馈）。给柱子设上限、并允许压缩：
 * 少天数最多 48px，天数多了靠 overflow-x 横滚，任何情况都不会再撑成色块。
 * 这里用 flex-start 而不是 space-between/center：内容溢出时后两者会把左侧裁掉。
 */
.days {
  display: flex;
  align-items: flex-end;
  justify-content: flex-start;
  gap: 6px;
  overflow-x: auto;
  padding-bottom: 4px;
}

.day {
  display: grid;
  justify-items: center;
  gap: 5px;
  flex: 1 1 34px;
  min-width: 30px;
  max-width: 48px;
}

.day-num {
  color: var(--ink-3);
  font-size: 9px;
  white-space: nowrap;
}

.day-bar {
  display: flex;
  align-items: flex-end;
  width: 100%;
  height: 62px;
  background: var(--surface);
  border-radius: 3px;
  overflow: hidden;
}

.day-fill {
  display: block;
  width: 100%;
  background: var(--accent);
  opacity: 0.62;
  border-radius: 3px 3px 0 0;
}

.day-label {
  color: var(--ink-3);
  font-size: 9px;
  white-space: nowrap;
}

/* ── 会话占比：甜甜圈 + 图例 ──────────────────────────────────
 * 不用实心饼：中间留白正好放总量，在 430px 的纸上比一整块色饼轻。
 * 图例沿用全局 .rows / .row，和下面的「最近调用」是同一套列表语言。
 */
.donut-wrap {
  position: relative;
  width: 184px;
  height: 184px;
  margin: 2px auto 18px;
}

.donut {
  display: block;
  width: 100%;
  height: 100%;
}

.donut-track,
.donut-slice {
  fill: none;
  stroke-width: 15;
}

.donut-track {
  stroke: var(--surface);
}

.donut-slice {
  transition: opacity var(--dur) var(--ease);
}

.donut-center {
  position: absolute;
  inset: 0;
  display: grid;
  place-content: center;
  justify-items: center;
  gap: 4px;
  pointer-events: none;
}

.donut-num {
  font-family: var(--font-display);
  font-size: 26px;
  line-height: 1;
  letter-spacing: -0.02em;
  color: var(--ink);
}

.donut-label {
  color: var(--ink-3);
  font-size: 9px;
  letter-spacing: 0.16em;
}

.legend-swatch {
  flex: 0 0 auto;
  width: 12px;
  height: 12px;
  border-radius: 3px;
  border: 1px solid rgba(46, 38, 26, 0.08);
}

/*
 * 明细列表沿用全站 .rows / .row：
 * - style.css 的 .rows 已经是纸片卡片（发丝线 + 圆角 + 纸底），不要再套一层 .card；
 * - style.css 的 .row 给了 `padding: 13px 16px`，横向 16px 就是「文字不贴边框」的关键，
 *   这里只把行高压到 54px、去掉不可点击行的手型光标，其余保持全局；
 * - 行间线由 `.rows > li + li` 的 border-top 统一画，别在 .row 上再叠 border-bottom：
 *   两条线叠一起会显粗，而且会顶到卡片边缘。
 */
.row {
  align-items: center;
  gap: 12px;
  min-height: 54px;
  padding: 10px 16px;
  cursor: default;
}

.row-main {
  display: grid;
  gap: 3px;
  flex: 1 1 auto;
  min-width: 0;
}

/* 标题比图表标签重半级，密集列表里才分得清「一条调用」和「一行分账」 */
.row-title {
  font-size: 13.5px;
  font-weight: 500;
}

.row-meta {
  color: var(--ink-3);
  font-size: 11px;
  line-height: 1.45;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

/*
 * 右侧做成「数字 + 单位」的小统计块，整体垂直居中。
 * 之前单个数字居中会飘在标题和副行之间，看起来像每行都错位；
 * 补上单位后它是完整的一列，居中就是刻意的排版而不是飘。
 */
.row-tokens {
  display: grid;
  justify-items: end;
  gap: 2px;
  flex: 0 0 auto;
  min-width: 58px;
  text-align: right;
  white-space: nowrap;
}

.row-tokens b {
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 600;
  line-height: 1.15;
}

.row-tokens small {
  color: var(--ink-3);
  font-size: 9px;
  letter-spacing: 0.08em;
}

/* 展开按钮收进卡片当最后一行，不再是一块悬在外面的描边方块 */
.row-more button {
  display: block;
  width: 100%;
  padding: 12px 16px;
  color: var(--ink-2);
  font-size: 11.5px;
  text-align: center;
  background: transparent;
  border: 0;
  cursor: pointer;
}

.row-more button:active {
  color: var(--ink);
  background: var(--surface);
}

.rows + .muted {
  margin-top: 10px;
}
</style>
