<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowUpRight, ChevronDown } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import DigestPopup from '@/components/DigestPopup.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import { getLastSeen, listDigest, markSeen, refreshDigest } from '@/api/digest'
import type { DigestItem, DigestRefresh } from '@/api/digest'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { formatHeadDate, formatTime } from '@/utils/datetime'
import { firstSegment, fullNameHint } from '@/utils/name'

/** 重要度到这个数就值得单独拎出来放前面 */
const IMPORTANT = 4
/** 先给这么多，剩下的点一下再看 */
const PREVIEW = 5
/** 弹窗去过重：同一段「上次看到现在」只弹一次 */
const POPUP_KEY = 'digest-popup:'

const toast = useToast()

const items = ref<DigestItem[]>([])
const lastSeen = ref('')
const loading = ref(true)
const error = ref('')
const expanded = ref(false)
const seenConfirmed = ref(false)
const now = ref(new Date())
const popupItem = ref<DigestItem | null>(null)
const refreshInfo = ref<DigestRefresh | null>(null)

const important = computed(() => items.value.filter((item) => item.importance >= IMPORTANT))
const rest = computed(() => items.value.filter((item) => item.importance < IMPORTANT))
const visibleRest = computed(() =>
  expanded.value ? rest.value : rest.value.slice(0, Math.max(0, PREVIEW - important.value.length)),
)
const hiddenCount = computed(() => rest.value.length - visibleRest.value.length)

/**
 * 空屏不等于「群里没人说话」：也可能是没到整理阈值，或这次整理没产出。
 * 后端 refresh 已经告诉我们窗口里收了多少条，用这个数区分三种情况，
 * 别再一律显示「没什么事」。
 */
const emptyCopy = computed(() => {
  const info = refreshInfo.value
  if (!info || info.messageCount === 0) {
    return {
      title: '这段时间群里没什么事',
      hint: '勾选要整理的群之后，再离开一阵子回来看看。',
    }
  }
  if (!info.refreshed) {
    return {
      title: '消息先攒着，还没到整理的坎',
      hint: `上次整理之后已有 ${info.messageCount} 条消息，达到你设的条数或时长后一起整理。`,
    }
  }
  return {
    title: '这段时间有消息，但没整理出要点',
    hint: `收到了 ${info.messageCount} 条消息，这次没有可单独提醒的内容，可以到消息日志翻原文。`,
  }
})

onMounted(() => {
  void load()
})

/**
 * silent = 下拉刷新：不清空当前内容、不摆骨架屏，否则整页会闪一下白。
 * 失败也不把页面换成错误页——旧数据比错误页有用，弹个 toast 就够。
 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    // 每次进首页都问一次：距上次整理超过一段时间、或中间攒了足够多消息，后端才真的跑整理
    // （两个阈值在「群设置」里改）；没到阈值就是一次几十毫秒的计数，窗口继续累积
    refreshInfo.value = await refreshDigest()
    // 时间基准由后端给：第一次进来是空串（后端按 24 小时算），之后是上次点「都看过了」的时刻
    lastSeen.value = await getLastSeen()
    items.value = await listDigest(lastSeen.value || undefined)
    expanded.value = false
    now.value = new Date()
    // 刷新时不要再弹一次「要你拿主意」
    if (!silent) maybePopup()
  } catch (e) {
    const message = e instanceof Error ? e.message : '加载失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

useRefresh(() => load(true))

function expandAll() {
  expanded.value = true
  void markAllSeen()
}

/**
 * 「这一段看完了」只在这里发生。
 * 只挂到「还有 N 条」上是不够的：条数不超过一屏时那个按钮根本不出现，
 * last_seen 永远是空的，每次进来都按 24 小时重放。
 */
async function markAllSeen() {
  try {
    await markSeen()
    // 本地的锚点也跟着走，否则这次弹过的那条下次还会被当成「新的一段」
    lastSeen.value = localStamp()
    seenConfirmed.value = true
  } catch {
    // 记不上不算错：下次还会看到这些，最多重复一遍
    toast.push('没记住「已看过」，下次可能还会提醒你', 'info')
  }
}

/** 后端按 FlexibleLocalDateTime 解析，给本地时间串，别带 Z */
function localStamp(d = new Date()) {
  const p = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
}

/** 群名可能是「双创部学科竞赛交流群，交流群」这种逗号串，卡片上只留第一段 */
function chatLabel(item: DigestItem) {
  return firstSegment(item.chatName) || `群 ${item.chatId}`
}

/** 全名不进布局，只挂在 title 上，悬停/长按仍能看到 */
function chatTitle(item: DigestItem) {
  return fullNameHint(chatLabel(item), item.chatName)
}

/**
 * 只弹最要紧的一条，且「这一段」只弹一次。
 * 键里带 lastSeen：下次点过「都看过了」之后再进来，是新的一段，会重新弹。
 */
function maybePopup() {
  const lead = items.value.find((item) => item.importance >= IMPORTANT)
  if (!lead) return
  const anchor = lastSeen.value || items.value[0]?.createdAt || ''
  const key = POPUP_KEY + anchor
  try {
    if (localStorage.getItem(key)) return
    localStorage.setItem(key, '1')
  } catch {
    // 隐私模式写不了：下次还会弹，能接受
  }
  popupItem.value = lead
}
</script>

<template>
  <div class="page">
    <PageHead
      :eyebrow="formatHeadDate(now)"
      title="Digest"
      sub="这些是群里的新鲜事，要你拿主意的排在前面。"
    />

    <div class="body">
      <ErrorState
        v-if="error"
        :message="error"
        hint="确认后端已在 8080 运行，也可以下拉重试。"
        @retry="load"
      />

      <Skeleton v-else-if="loading" :rows="4" variant="card" />

      <EmptyState
        v-else-if="!items.length"
        :title="emptyCopy.title"
        :hint="emptyCopy.hint"
      >
        <RouterLink to="/memos" class="btn btn-sm btn-quiet">
          先去看看备忘
          <ArrowUpRight :size="13" :stroke-width="2.4" aria-hidden="true" />
        </RouterLink>
      </EmptyState>

      <template v-else>
        <section v-if="important.length" class="group">
          <h2 class="section-title">
            要你拿主意 <span class="num">{{ important.length }}</span>
          </h2>
          <ul class="list">
            <li v-for="item in important" :key="item.id" class="item is-important">
              <p class="item-chat">
                <span class="item-chat-name" :title="chatTitle(item)">{{ chatLabel(item) }}</span>
                <span v-if="item.mentionsMe" class="mention">@你</span>
                <span class="item-time num">{{ formatTime(item.createdAt) }}</span>
              </p>
              <p class="item-summary">{{ item.summary }}</p>
            </li>
          </ul>
        </section>

        <section v-if="visibleRest.length" class="group">
          <h2 class="section-title">其它</h2>
          <ul class="list">
            <li v-for="item in visibleRest" :key="item.id" class="item">
              <p class="item-chat">
                <span class="item-chat-name" :title="chatTitle(item)">{{ chatLabel(item) }}</span>
                <span class="item-time num">{{ formatTime(item.createdAt) }}</span>
              </p>
              <p class="item-summary">{{ item.summary }}</p>
            </li>
          </ul>
        </section>

        <button v-if="hiddenCount > 0" type="button" class="btn btn-quiet more" @click="expandAll">
          还有 <span class="num">{{ hiddenCount }}</span> 条
          <ChevronDown :size="14" :stroke-width="2.2" aria-hidden="true" />
        </button>

        <p v-else-if="expanded || seenConfirmed" class="done-note" role="status">都在这里了。</p>

        <button v-else type="button" class="btn btn-quiet more" @click="markAllSeen">都看过了</button>
      </template>
    </div>

    <DigestPopup :item="popupItem" @close="popupItem = null" />
  </div>
</template>

<style scoped>
.body {
  display: block;
}

.group {
  margin-bottom: 22px;
}

.list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.item {
  padding: 13px 15px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

/* 深色面统一走渐变 + 顶部高光，和卡片、抽屉是同一块「纸」的背面 */
.item.is-important {
  background: linear-gradient(158deg, #221f26 0%, #16151a 62%, #121116 100%);
  border-color: transparent;
  color: var(--on-dark);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.07), var(--shadow-lift);
}

.item-chat {
  display: flex;
  align-items: center;
  gap: 6px;
  margin: 0 0 5px;
  font-size: 11.5px;
  font-weight: 500;
  color: var(--ink-3);
}

.item.is-important .item-chat {
  color: rgba(255, 255, 255, 0.66);
}

/*
 * 名字是 flex 子项：不写 min-width:0 的话默认 min-width:auto，长群名会把
 * 后面的时间和 @你 挤出去。省略号只是版面取舍，悬停/长按仍可横向滚动看全名。
 */
.item-chat-name {
  flex: 1 1 auto;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 长按拖动是「滚动」，不该同时选中文字 */
  user-select: none;
}

.item-chat-name:hover,
.item-chat-name:active {
  overflow-x: auto;
  text-overflow: clip;
  scrollbar-width: none;
}

.item-chat-name::-webkit-scrollbar {
  display: none;
}

.item-time {
  flex: 0 0 auto;
  margin-left: auto;
  font-weight: 400;
}

.mention {
  flex: 0 0 auto;
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: rgba(229, 56, 127, 0.14);
  color: var(--accent-ink);
  font-weight: 600;
}

.item.is-important .mention {
  background: rgba(255, 255, 255, 0.18);
  color: #fff;
}

.item-summary {
  margin: 0;
  font-size: 14.5px;
  line-height: 1.55;
  letter-spacing: -0.005em;
}

.more {
  width: 100%;
  justify-content: center;
}

.done-note {
  margin: 14px 0 0;
  color: var(--ink-3);
  font-size: 12px;
  text-align: center;
}
</style>
