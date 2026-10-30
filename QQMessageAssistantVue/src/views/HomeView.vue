<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { RouterLink } from 'vue-router'
import { ArrowUpRight, BellRing, ChevronDown } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import DigestPopup from '@/components/DigestPopup.vue'
import Skeleton from '@/components/Skeleton.vue'
import { getLastSeen, listDigest, markSeen } from '@/api/digest'
import type { DigestItem } from '@/api/digest'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { formatHeadDate, formatTime } from '@/utils/datetime'

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

const important = computed(() => items.value.filter((item) => item.importance >= IMPORTANT))
const rest = computed(() => items.value.filter((item) => item.importance < IMPORTANT))
const visibleRest = computed(() =>
  expanded.value ? rest.value : rest.value.slice(0, Math.max(0, PREVIEW - important.value.length)),
)
const hiddenCount = computed(() => rest.value.length - visibleRest.value.length)

onMounted(() => {
  void load()
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    // 时间基准由后端给：第一次进来是空串（后端按 24 小时算），之后是上次点「都看过了」的时刻
    lastSeen.value = await getLastSeen()
    items.value = await listDigest(lastSeen.value || undefined)
    expanded.value = false
    now.value = new Date()
    maybePopup()
  } catch (e) {
    error.value = e instanceof Error ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

useRefresh(load)

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

function chatLabel(item: DigestItem) {
  return item.chatName || `群 ${item.chatId}`
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
    <header class="page-head">
      <p class="eyebrow">{{ formatHeadDate(now) }}</p>
      <h1 class="page-title">你不在的时候</h1>
      <p class="page-sub">这些是群里的新鲜事，要你拿主意的排在前面。</p>
    </header>

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
        title="这段时间群里没什么事"
        hint="勾选要整理的群之后，再离开一阵子回来看看。"
      >
        <RouterLink to="/memos" class="btn btn-sm btn-quiet">
          先去看看备忘
          <ArrowUpRight :size="13" :stroke-width="2.4" aria-hidden="true" />
        </RouterLink>
      </EmptyState>

      <template v-else>
        <section v-if="important.length" class="group">
          <h2 class="section-title">
            <BellRing :size="14" :stroke-width="2" aria-hidden="true" />
            要你拿主意 <span class="num">{{ important.length }}</span>
          </h2>
          <ul class="list">
            <li v-for="item in important" :key="item.id" class="item is-important">
              <p class="item-chat">
                {{ chatLabel(item) }}
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
                {{ chatLabel(item) }}
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

.section-title {
  display: flex;
  align-items: center;
  gap: 6px;
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
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.item.is-important {
  background: var(--card-inverse);
  color: #fff;
  box-shadow: var(--shadow-lift);
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

.item-time {
  margin-left: auto;
  font-weight: 400;
}

.mention {
  padding: 1px 6px;
  border-radius: var(--r-pill);
  background: rgba(229, 56, 127, 0.14);
  color: var(--accent);
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
