<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  ArrowDown,
  CalendarDays,
  Home,
  Inbox,
  LayoutGrid,
  MessageCircleQuestion,
  Plus,
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
  { to: '/ask', label: '问一句', icon: MessageCircleQuestion },
  { to: '/more', label: '更多', icon: LayoutGrid },
]

const pendingCount = computed(() => draftStore.pendingCount.value)
const badgeText = computed(() => (pendingCount.value > 99 ? '99+' : String(pendingCount.value)))

const activeIndex = computed(() => {
  const path = route.path
  if (path.startsWith('/home')) return 0
  if (path.startsWith('/memos')) return 1
  if (path.startsWith('/drafts')) return 2
  if (path.startsWith('/ask')) return 3
  // 记忆、群设置、日志这些都收在「更多」下面
  return 4
})

/* ── 滚动：页头收起 + 位置记忆 ────────────────────────────── */

/*
 * 收起与展开用两个不同的阈值（滞回）：只用一个阈值时，
 * 顶部回弹（iOS 橡皮筋）或手指微抖会让 scrollTop 在阈值上下反复穿过，
 * 页头就跟着一亮一暗地闪。两个阈值把这段来回抖动挡在外面。
 */
const COLLAPSE_AT = 28
const EXPAND_AT = 2
/** 收起后页头大致的高度，用来估算「收起会让内容上移多少」 */
const COLLAPSED_HEAD = 38

const scrolled = ref(false)
const positions = new Map<string, number>()
let lastPath = route.path
let ticking = false

function onScroll() {
  if (ticking) return
  ticking = true
  requestAnimationFrame(() => {
    const el = screenEl.value
    if (!el) {
      ticking = false
      return
    }
    const top = el.scrollTop

    if (scrolled.value) {
      if (top < EXPAND_AT) scrolled.value = false
      ticking = false
      return
    }

    if (top <= COLLAPSE_AT) {
      ticking = false
      return
    }

    /*
     * 内容只比视口高一点点时别收起：收起会让内容整体上移，
     * 滚动位置被浏览器夹回顶部，看起来像「刚滚就弹回去」。
     * 所以先算一下还剩多少可滚距离，够抵消这次收缩才收。
     */
    const head = el.querySelector<HTMLElement>('.page-head')
    const shrink = head ? Math.max(0, head.offsetHeight - COLLAPSED_HEAD) : 0
    const range = el.scrollHeight - el.clientHeight
    if (range > shrink + EXPAND_AT) scrolled.value = true

    ticking = false
  })
}

onMounted(() => {
  const el = screenEl.value
  el?.addEventListener('scroll', onScroll, { passive: true })
  // touchmove 必须非 passive，否则 preventDefault 不生效，下拉会被浏览器当成橡皮筋
  el?.addEventListener('touchstart', onTouchStart, { passive: true })
  el?.addEventListener('touchmove', onTouchMove, { passive: false })
  el?.addEventListener('touchend', onTouchEnd)
  el?.addEventListener('touchcancel', onTouchEnd)
  void draftStore.refreshPendingDrafts()
})

onBeforeUnmount(() => {
  const el = screenEl.value
  el?.removeEventListener('scroll', onScroll)
  el?.removeEventListener('touchstart', onTouchStart)
  el?.removeEventListener('touchmove', onTouchMove)
  el?.removeEventListener('touchend', onTouchEnd)
  el?.removeEventListener('touchcancel', onTouchEnd)
  document.body.classList.remove('pulling')
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
    scrolled.value = target > COLLAPSE_AT

    if (path === '/memos' || path === '/drafts') void draftStore.refreshPendingDrafts()
  },
)

/* ── 下拉刷新 ─────────────────────────────────────────────── */

/*
 * 触摸设备上 pointer 事件在浏览器接管滚动时会被 cancel 掉，
 * 所以触摸走 touch（touchmove 必须 passive:false 才能 preventDefault），
 * 鼠标才走 pointer。两条路最后都落到 drag() / end()。
 */
const PULL_MAX = 96
const PULL_TRIGGER = 56

const pull = ref(0)
const pulling = ref(false)
const refreshing = ref(false)
let startY = 0
let startX = 0
let armed = false

const pullReady = computed(() => pull.value >= PULL_TRIGGER)

/** 内容跟着手指走，指示器落在顶部露出来的那道缝里 */
const screenStyle = computed(() => ({
  transform: pull.value ? `translateY(${pull.value}px)` : undefined,
  transition: pulling.value ? 'none' : 'transform 380ms var(--ease-spring)',
}))

const pullStyle = computed(() => ({
  opacity: String(Math.min(1, pull.value / (PULL_TRIGGER * 0.7))),
  transform: `translateY(${Math.max(-24, pull.value / 2 - 24)}px)`,
}))

function begin(y: number, x: number) {
  if (refreshing.value || !refreshState.handler) return false
  if ((screenEl.value?.scrollTop ?? 0) > 0) return false
  startY = y
  startX = x
  armed = true
  return true
}

function drag(y: number) {
  if (!armed || refreshing.value) return
  const dy = y - startY
  if (dy <= 0) {
    if (pull.value !== 0) pull.value = 0
    return
  }
  if (!pulling.value) {
    if (dy < 8) return
    pulling.value = true
    document.body.classList.add('pulling')
  }
  // 阻尼：越往下拉越沉，手感接近橡皮筋
  pull.value = Math.min(PULL_MAX, dy * 0.55)
}

async function end() {
  if (!armed) return
  armed = false
  document.body.classList.remove('pulling')
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

/* 鼠标 */
function onPointerDown(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  if (event.pointerType === 'mouse' && event.button !== 0) return
  if (!begin(event.clientY, event.clientX)) return
  ;(event.currentTarget as HTMLElement).setPointerCapture?.(event.pointerId)
}

function onPointerMove(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  drag(event.clientY)
}

function onPointerUp(event: PointerEvent) {
  if (event.pointerType === 'touch') return
  void end()
}

/* 触摸 */
function onTouchStart(event: TouchEvent) {
  if (event.touches.length !== 1) return
  const touch = event.touches[0]
  begin(touch.clientY, touch.clientX)
}

function onTouchMove(event: TouchEvent) {
  if (!armed || refreshing.value) return
  const touch = event.touches[0]
  const dy = touch.clientY - startY
  const dx = touch.clientX - startX

  // 横向滑动或往上推：交还给浏览器去滚动
  if (dy <= 0 || Math.abs(dx) > Math.abs(dy)) {
    if (pull.value) pull.value = 0
    if (Math.abs(dx) > 12) armed = false
    return
  }

  if (!pulling.value && dy < 8) return
  // 接管这次手势，否则浏览器会把它当成橡皮筋，并吞掉后续 touchmove
  if (event.cancelable) event.preventDefault()
  drag(touch.clientY)
}

function onTouchEnd() {
  void end()
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
      :style="screenStyle"
      @pointerdown="onPointerDown"
      @pointermove="onPointerMove"
      @pointerup="onPointerUp"
      @pointercancel="onPointerUp"
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

    <nav class="tabbar" :style="{ '--tab-n': navItems.length }" aria-label="主导航">
      <span
        class="tab-indicator"
        :style="{ transform: `translateX(${activeIndex * 100}%)` }"
        aria-hidden="true"
      >
        <span class="tab-pill" />
      </span>

      <RouterLink
        v-for="(item, index) in navItems"
        :key="item.to"
        :to="item.to"
        class="tab"
        :class="{ 'is-active': activeIndex === index }"
        :aria-current="activeIndex === index ? 'page' : undefined"
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
  /*
   * 整页底色不是一块死灰：顶部一层几乎察觉不到的暖白，往下沉到纸色，
   * 底部再压深一点点。卡片浮在上面才有「纸」的层次。
   */
  background: linear-gradient(
    180deg,
    var(--bg-top) 0%,
    var(--bg) 8%,
    var(--bg) 78%,
    var(--bg-bottom) 100%
  );
}

/*
 * 纸纹。
 *
 * 一层 3% 的噪点铺在整屏最上面，压在卡片、页头、导航之上——
 * 数字界面之所以「廉价」，很大程度是因为它太干净：每一块都是数学意义上的纯色，
 * 眼睛找不到任何随机性。噪点把它拉回到「有纤维的纸」。
 *
 * 它必须 pointer-events:none，否则整屏都点不动；
 * 也刻意不跟内容一起滚——纸是不动的，动的是纸上的东西。
 */
.app::after {
  content: '';
  position: absolute;
  inset: 0;
  z-index: 45;
  background-image: var(--grain);
  background-size: 140px 140px;
  opacity: 0.055;
  pointer-events: none;
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
  /*
   * 页头收起时自身高度会从 ~147px 压到 ~70px。默认的滚动锚定会为了「保持视野不动」
   * 逐帧补偿 scrollTop，而补偿又跨过收起阈值，状态于是在 60fps 下反复横跳——
   * 表现就是滚动时页头一直在抖。关掉锚定，高度变化就只发生一次。
   */
  overflow-anchor: none;
  padding-bottom: calc(var(--tab-h) + 40px + env(safe-area-inset-bottom, 0px));
}

/* 有悬浮按钮时多留一截，别让按钮压住最后一条 */
.screen.has-fab {
  padding-bottom: calc(var(--tab-h) + 96px + env(safe-area-inset-bottom, 0px));
}

/* ── 下拉刷新指示器 ───────────────────────────────────────── */

.pull {
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  z-index: 12;
  display: flex;
  justify-content: center;
  align-items: center;
  height: 48px;
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
  box-shadow: 0 0 0 1px rgba(46, 38, 26, 0.04),
    0 6px 18px -10px rgba(46, 38, 26, 0.4);
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
  bottom: calc(var(--tab-h) + 30px + env(safe-area-inset-bottom, 0px));
  z-index: 40;
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  border: 0;
  border-radius: var(--r-md);
  background: linear-gradient(158deg, #221f26 0%, #16151a 70%);
  color: var(--on-dark);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.09), var(--shadow-lift);
  cursor: pointer;
  transition: transform var(--dur-1) var(--ease-spring);
  animation: rise 0.44s var(--ease) both;
}

.fab:active {
  transform: scale(0.92);
}

/* ── 底部导航 ─────────────────────────────────────────────── */

/*
 * v3 把导航从「贴边的半透明白条」改成一条浮起来的书签条：
 * 离左右各 10px、离底 8px、22px 圆角、发丝线压边。
 * 贴边的条子是「浏览器工具栏」，浮起来的一条才是「这块界面的一部分」——
 * 它同时让底下那条纸色露出来，整屏因此有个收口，而不是被白条硬切掉。
 */
.tabbar {
  position: relative;
  z-index: 30;
  flex: none;
  display: grid;
  grid-template-columns: repeat(var(--tab-n, 5), 1fr);
  height: var(--tab-h);
  margin: 0 10px calc(8px + env(safe-area-inset-bottom, 0px));
  padding: 6px 8px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-xl);
  background: rgba(255, 253, 250, 0.84);
  backdrop-filter: saturate(1.5) blur(22px);
  -webkit-backdrop-filter: saturate(1.5) blur(22px);
  box-shadow: 0 1px 2px rgba(46, 38, 26, 0.04),
    0 18px 40px -28px rgba(46, 38, 26, 0.5);
}

/* 选中态是一块会滑动的纸片，跟着 tab 走 */
.tab-indicator {
  position: absolute;
  top: 6px;
  left: 8px;
  width: calc((100% - 16px) / var(--tab-n, 5));
  height: 28px;
  transition: transform var(--dur-3) var(--ease-spring);
  pointer-events: none;
}

.tab-pill {
  position: absolute;
  left: 50%;
  top: 0;
  width: 44px;
  height: 28px;
  margin-left: -22px;
  border-radius: var(--r-pill);
  background: var(--card);
  box-shadow: 0 0 0 1px var(--hairline), 0 1px 3px rgba(46, 38, 26, 0.07);
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
  font-size: 10px;
  font-weight: 500;
  letter-spacing: 0.02em;
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
