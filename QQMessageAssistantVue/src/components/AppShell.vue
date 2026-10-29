<script setup lang="ts">
import { computed, onMounted, watch } from 'vue'
import { useRoute } from 'vue-router'
import { CalendarDays, Inbox, LayoutGrid, Plus, Sparkles } from 'lucide-vue-next'
import { useDraftStore } from '@/composables/useDraftStore'
import { useFabState } from '@/composables/useFab'

const route = useRoute()
const draftStore = useDraftStore()
const fab = useFabState()

const navItems = [
  { to: '/memos', label: '备忘', icon: CalendarDays },
  { to: '/drafts', label: '草稿', icon: Inbox, badge: true },
  { to: '/memory', label: '记忆', icon: Sparkles },
  { to: '/more', label: '更多', icon: LayoutGrid },
]

const pendingCount = computed(() => draftStore.pendingCount.value)
const badgeText = computed(() => (pendingCount.value > 99 ? '99+' : String(pendingCount.value)))

onMounted(() => {
  void draftStore.refreshPendingDrafts()
})

// 回到首页时刷新一次，QQ 那边新产生的草稿能及时出现；顺带把滚动位置归零
watch(
  () => route.path,
  (path) => {
    document.querySelector('.screen')?.scrollTo({ top: 0 })
    if (path === '/memos' || path === '/drafts') void draftStore.refreshPendingDrafts()
  },
)
</script>

<template>
  <div class="app">
    <a class="skip-link" href="#main">跳到主要内容</a>

    <main id="main" class="screen" :class="{ 'has-fab': fab.visible }">
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
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="tab"
        active-class="is-active"
      >
        <span class="tab-icon">
          <component :is="item.icon" :size="21" :stroke-width="1.9" aria-hidden="true" />
          <span v-if="item.badge && pendingCount" class="badge num">{{ badgeText }}</span>
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
  padding-bottom: calc(var(--tab-h) + 26px + env(safe-area-inset-bottom, 0px));
}

/* 有悬浮按钮时多留一截，别让按钮压住最后一条 */
.screen.has-fab {
  padding-bottom: calc(var(--tab-h) + 88px + env(safe-area-inset-bottom, 0px));
}

.page-enter-active {
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}

.page-leave-active {
  transition: opacity 0.12s var(--ease);
}

.page-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.page-leave-to {
  opacity: 0;
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
  transition: transform var(--dur) var(--ease);
  animation: rise 0.3s var(--ease) both;
}

.fab:active {
  transform: scale(0.94);
}

/* ── 底部导航 ─────────────────────────────────────────────── */

.tabbar {
  position: relative;
  z-index: 30;
  flex: none;
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 4px;
  height: calc(var(--tab-h) + env(safe-area-inset-bottom, 0px));
  padding: 8px 10px calc(8px + env(safe-area-inset-bottom, 0px));
  border-top: 1px solid var(--hairline);
  background: rgba(255, 255, 255, 0.86);
  backdrop-filter: saturate(1.6) blur(18px);
  -webkit-backdrop-filter: saturate(1.6) blur(18px);
}

.tab {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 3px;
  border-radius: var(--r-md);
  color: var(--ink-3);
  transition: color var(--dur) var(--ease),
    background-color var(--dur) var(--ease);
}

.tab-icon {
  position: relative;
  display: grid;
  place-items: center;
}

.tab-label {
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.01em;
}

.tab.is-active {
  color: var(--ink);
}

.tab.is-active .tab-icon::before {
  content: "";
  position: absolute;
  inset: -7px -12px;
  border-radius: var(--r-pill);
  background: rgba(17, 18, 20, 0.055);
}

.tab-icon > svg {
  position: relative;
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
</style>
