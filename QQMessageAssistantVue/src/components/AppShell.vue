<script setup lang="ts">
import { CalendarClock, Inbox, SlidersHorizontal, Sparkles } from 'lucide-vue-next'

const navItems = [
  { to: '/memos', label: '备忘', icon: CalendarClock },
  { to: '/drafts', label: '草稿', icon: Inbox },
  { to: '/memory', label: '记忆', icon: Sparkles },
  { to: '/settings', label: '设置', icon: SlidersHorizontal },
]
</script>

<template>
  <div class="app">
    <a class="skip-link" href="#main">跳到主要内容</a>

    <aside class="rail">
      <RouterLink to="/memos" class="brand">
        <span class="display brand-name">值守台</span>
        <span class="brand-sub">QQ 助手</span>
      </RouterLink>

      <nav class="rail-nav" aria-label="主导航">
        <RouterLink
          v-for="item in navItems"
          :key="item.to"
          :to="item.to"
          class="nav-item"
          active-class="is-active"
        >
          <component :is="item.icon" :size="18" :stroke-width="1.75" aria-hidden="true" />
          <span>{{ item.label }}</span>
        </RouterLink>
      </nav>
    </aside>

    <main id="main" class="main">
      <RouterView />
    </main>

    <nav class="tabbar" aria-label="主导航（移动端）">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="tab-item"
        active-class="is-active"
      >
        <component :is="item.icon" :size="20" :stroke-width="1.75" aria-hidden="true" />
        <span>{{ item.label }}</span>
      </RouterLink>
    </nav>
  </div>
</template>

<style scoped>
.app {
  min-height: 100dvh;
}

.skip-link {
  position: absolute;
  left: 12px;
  top: -60px;
  z-index: 1100;
  padding: 8px 14px;
  background: var(--sheet);
  border: 1px solid var(--signal);
  border-radius: 2px;
  font-size: 14px;
  transition: top 0.2s var(--ease);
}

.skip-link:focus {
  top: 12px;
}

/* ── 左侧导航 ─────────────────────────────────────────── */

.rail {
  position: fixed;
  inset: 0 auto 0 0;
  width: var(--rail-w);
  display: flex;
  flex-direction: column;
  padding: 26px 16px 24px;
  border-right: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.62);
  backdrop-filter: blur(6px);
}

.brand {
  display: grid;
  gap: 2px;
  padding: 0 10px 26px;
  color: var(--ink);
  text-decoration: none;
}

.brand-name {
  font-size: 25px;
  line-height: 1.15;
}

.brand-sub {
  font-size: 12.5px;
  color: var(--ink-soft);
}

.rail-nav {
  display: grid;
  gap: 2px;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 11px;
  min-height: 44px;
  padding: 0 10px;
  border-left: 2px solid transparent;
  border-radius: 0 2px 2px 0;
  color: var(--ink-soft);
  font-size: 14.5px;
  text-decoration: none;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.nav-item:hover {
  background: rgba(22, 35, 44, 0.045);
  color: var(--ink);
}

.nav-item.is-active {
  border-left-color: var(--signal);
  background: var(--signal-wash);
  color: var(--signal-deep);
  font-weight: 600;
}

/* ── 主区域 ───────────────────────────────────────────── */

.main {
  margin-left: var(--rail-w);
  padding: 34px clamp(20px, 3.2vw, 44px) 72px;
}

.main > :deep(*) {
  max-width: 1180px;
  margin: 0 auto;
}

/* ── 移动端导航 ───────────────────────────────────────── */

.tabbar {
  display: none;
}

@media (max-width: 900px) {
  .rail {
    display: none;
  }

  .main {
    margin-left: 0;
    padding: 22px 16px calc(84px + env(safe-area-inset-bottom, 0px));
  }

  .tabbar {
    position: fixed;
    inset: auto 0 0 0;
    z-index: 800;
    display: grid;
    grid-template-columns: repeat(4, 1fr);
    padding: 6px 6px calc(6px + env(safe-area-inset-bottom, 0px));
    border-top: 1px solid var(--line);
    background: rgba(255, 255, 255, 0.94);
    backdrop-filter: blur(8px);
  }

  .tab-item {
    display: flex;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    min-height: 52px;
    border-radius: 3px;
    color: var(--ink-soft);
    font-size: 11.5px;
    text-decoration: none;
  }

  .tab-item.is-active {
    color: var(--signal-deep);
    background: var(--signal-wash);
    font-weight: 600;
  }
}
</style>
