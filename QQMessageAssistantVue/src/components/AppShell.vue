<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowDown,
  CalendarDays,
  Home,
  Inbox,
  LayoutGrid,
  Plus,
  Sparkles,
} from 'lucide-vue-next'
import { useDraftStore } from '@/composables/useDraftStore'
import { useFabState } from '@/composables/useFab'
import { useRefreshState } from '@/composables/useRefresh'

const route = useRoute()
const draftStore = useDraftStore()
const fab = useFabState()
const refreshState = useRefreshState()

const screenEl = ref<HTMLElement | null>(null)

const navItems = [
  { to: '/home', label: '首页', icon: Home },
  { to: '/memos', label: '备忘', icon: CalendarDays },
  { to: '/drafts', label: '草稿', icon: Inbox, badge: true },
  { to: '/memory', label: '记忆', icon: Sparkles },
  { to: '/more', label: '更多', icon: LayoutGrid },
]

const pendingCount = computed(() => draftStore.pendingCount.value)
const badgeText = computed(() => (pendingCount.value > 99 ? '99+' : String(pendingCount.value)))

const activeIndex = computed(() => {
  const path = route.path
  if (path.startsWith('/home')) return 0
  if (path.startsWith('/memos')) return 1
  if (path.startsWith('/drafts')) return 2
  if (path.startsWith('/memory')) return 3
  return 4
})

/* ── 滚动：页头收起 + 位置记忆 ────────────────────────────── */

const scrolled = ref(false)
const positions = new Map<string, number>()
let lastPath = route.path
let ticking = false

function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    scrolled.value = (screenEl.value?.scrollTop ?? 0) > 12
    ticking = false
  })
}

onMounted(() => {
  screenEl.value?.addEventListener('scroll', onScroll, { passive: true })
  void draftStore.refreshPendingDrafts()
})

onBeforeUnmount(() => {
  screenEl.value?.removeEventListener('scroll', onScroll)
})

watch(
  () => route.path,
  async (path) => {
    const el = screenEl.value
    if (el) positions.set(lastPath, el.scrollTop)
    lastPath = path

    await nextTick()
    await nextTick()

    const target = positions.get(path) ?? 0
    el?.scrollTo({ top: target })
    scrolled.value = target > 12

    if (path === '/memos' || path === '/drafts') void draftStore.refreshPendingDrafts()
  },
)

/* ── 下拉刷新 ─────────────────────────────────────────────── */

const PULL_MAX = 88
const PULL_TRIGGER = 52

const pull = ref(0)
const pulling = ref(false)
const refreshing = ref(false)
let startY = 0
let armed = false

const pullStyle = computed(() => ({
  transform: `translateY(${-42 + pull.value}px)`,
  opacity: String(Math.min(1, pull.value / PULL_TRIGGER)),
  transition: pulling.value
    ? 'none'
    : 'transform 320ms var(--ease-spring), opacity 240ms var(--ease-out)',
}))

const pullReady = computed(() => pull.value >= PULL_TRIGGER)

function onPointerDown(event: PointerEvent) {
  if (refreshing.value || !refreshState.handler) return
  if ((screenEl.value?.scrollTop ?? 0) > 0) return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  startY = event.clientY
  armed = true
}

function onPointerMove(event: PointerEvent) {
  if (!armed || refreshing.value) return
  const dy = event.clientY - startY
  if (dy <= 0) {
    if (pull.value !== 0) pull.value = 0
    return
  }
  if (!pulling.value && dy > 10) pulling.value = true
  if (!pulling.value) return
  pull.value = Math.min(PULL_MAX, dy * 0.45)
}

async function onPointerUp() {
  if (!armed) return
  armed = false
  const distance = pull.value
  pulling.value = false

  if (distance < PULL_TRIGGER) {
    pull.value = 0
    return
  }

  pull.value = 56
  refreshing.value = true
  try {
    await refreshState.handler?.()
  } catch {
    // 页面自己会 toast，这里只负责收起指示器
  } finally {
    refreshing.value = false
    pull.value = 0
  }
}
</script>

<template>
  <div class="app" :data-scrolled="scrolled ? 'true' : 'false'">
    <a class="skip-link" href="#main">跳到主要内容</a>

    <div class="pull" :style="pullStyle" aria-hidden="true">
      <span class="pull-dot" :class="{ ready: pullReady, spinning: refreshing }">
        <ArrowDown :size="13" :stroke-width="2.6" />
      </span>
    </div>

    <main
      id="main"
      ref="screenEl"
      class="screen"
      :class="{ 'has-fab': fab.visible }"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
      @pointerleave="onPointerUp"
    >
      <RouterView v-slot="{ Component }">
        <Transition name="page" mode="out-in">
          <component :is="Component" />
        </Transition>
      </RouterView>
    </main>

    <button
      v-if="fab.visible"
      type="button"
      class="fab"
      :aria-label="fab.label"
      @click="fab.action?.()"
    >
      <Plus :size="24" :stroke-width="2" aria-hidden="true" />
    </button>

    <nav class="tabbar" aria-label="主导航">
      <span
        class="tab-indicator"
        :style="{ transform: `translateX(${activeIndex * 100}%)` }"
        aria-hidden="true"
      >
        <span class="tab-pill" />
      </span>

      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="tab"
        active-class="is-active"
      >
        <span class="tab-icon">
          <component :is="item.icon" :size="21" :stroke-width="1.9" aria-hidden="true" />
          <Transition name="badge">
            <span v-if="item.badge && pendingCount" class="badge num">{{ badgeText }}</span>
          </Transition>
        </span>
        <span class="tab-label">{{ item.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.app {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}

.skip-link {
  position: absolute;
  left: 12px;
  top: -60px;
  z-index: 120;
  padding: 10px 16px;
  border-radius: var(--r-pill);
  background: var(--card-inverse);
  color: #fff;
  font-size: 13px;
  transition: top 0.2s var(--ease);
}

.skip-link:focus {
  top: 12px;
}

.screen {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  overflow-x: hidden;
  -webkit-overflow-scrolling: touch;
  overscroll-behavior-y: contain;
  padding-bottom: calc(var(--tab-h) + 26px + env(safe-area-inset-bottom, 0px));
}

/* 有悬浮按钮时多留一截，别让按钮压住最后一条 */
.screen.has-fab {
  padding-bottom: calc(var(--tab-h) + 88px + env(safe-area-inset-bottom, 0px));
}

/* ── 下拉刷新指示器 ───────────────────────────────────────── */

.pull {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 15;
  display: flex;
  justify-content: center;
  pointer-events: none;
}

.pull-dot {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  background: var(--card);
  color: var(--ink-3);
  box-shadow: var(--shadow-card);
  transform: rotate(0deg);
  transition: transform 300ms var(--ease-out), color 200ms var(--ease-out);
}

.pull-dot.ready {
  transform: rotate(180deg);
  color: var(--ink);
}

.pull-dot.spinning {
  animation: spin 0.7s linear infinite;
  color: var(--ink);
}

/* ── 悬浮新建 ─────────────────────────────────────────────── */

.fab {
  position: absolute;
  right: var(--pad-x);
  bottom: calc(var(--tab-h) + 20px + env(safe-area-inset-bottom, 0px));
  z-index: 40;
  display: grid;
  place-items: center;
  width: 54px;
  height: 54px;
  border: 0;
  border-radius: 50%;
  background: var(--card-inverse);
  color: #fff;
  box-shadow: var(--shadow-lift);
  cursor: pointer;
  transition: transform var(--dur-1) var(--ease-spring);
  animation: rise 0.44s var(--ease) both;
}

.fab:active {
  transform: scale(0.92);
}

/* ── 底部导航 ─────────────────────────────────────────────── */

.tabbar {
  position: relative;
  z-index: 30;
  flex: none;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  height: calc(var(--tab-h) + env(safe-area-inset-bottom, 0px));
  padding: 8px 10px calc(8px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--hairline);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(1.6) blur(18px);
  -webkit-backdrop-filter: saturate(1.6) blur(18px);
}

/* 选中态是一块会滑动的药丸，跟着 tab 走 */
.tab-indicator {
  position: absolute;
  top: 6px;
  left: 10px;
  width: calc((100% - 20px) / 5);
  height: 27px;
  transition: transform var(--dur-3) var(--ease-spring);
  pointer-events: none;
}

.tab-pill {
  position: absolute;
  left: 50%;
  top: 0;
  width: 40px;
  height: 27px;
  margin-left: -20px;
  border-radius: var(--r-pill);
  background: rgba(17, 18, 20, 0.06);
}

.tab {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border-radius: var(--r-md);
  color: var(--ink-3);
  transition: color var(--dur-2) var(--ease-out);
}

.tab-icon {
  position: relative;
  display: grid;
  place-items: center;
  transition: transform var(--dur-1) var(--ease-spring);
}

.tab:active .tab-icon {
  transform: scale(0.9);
}

.tab-label {
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.01em;
}

.tab.is-active {
  color: var(--ink);
}

.badge {
  position: absolute;
  top: -5px;
  right: -10px;
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: var(--r-pill);
  background: var(--accent);
  color: #fff;
  font-size: 10px;
  font-weight: 600;
  line-height: 16px;
  text-align: center;
}

.badge-enter-active,
.badge-leave-active {
  transition: transform var(--dur-2) var(--ease-spring),
    opacity var(--dur-1) var(--ease-out);
}

.badge-enter-from,
.badge-leave-to {
  transform: scale(0.3);
  opacity: 0;
}
</style>
