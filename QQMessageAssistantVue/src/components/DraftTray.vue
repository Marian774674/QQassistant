<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ArrowRight } from 'lucide-vue-next'
import DraftItem from '@/components/DraftItem.vue'
import { listDrafts } from '@/api/drafts'
import type { Draft } from '@/api/types'

const drafts = ref<Draft[]>([])
const loading = ref(true)
const error = ref('')

async function load() {
  loading.value = true
  error.value = ''
  try {
    drafts.value = await listDrafts({ status: 'PENDING' })
  } catch (e) {
    error.value = e instanceof Error ? e.message : '草稿读取失败'
  } finally {
    loading.value = false
  }
}

function onResolved(id: number) {
  drafts.value = drafts.value.filter((draft) => draft.id !== id)
}

onMounted(load)
</script>

<template>
  <aside class="tray" aria-labelledby="tray-title">
    <header class="tray-head">
      <h2 id="tray-title" class="tray-title">等你过目</h2>
      <span v-if="drafts.length" class="count num">{{ drafts.length }}</span>
    </header>

    <p class="tray-hint">AI 拿不准的回复、备忘和事实先停在这里，确认了才落到 QQ 和记录里。</p>

    <p v-if="loading" class="faint">正在读取…</p>
    <p v-else-if="error" class="tray-error" role="alert">
      {{ error }}
      <button type="button" class="link-btn" @click="load">重试</button>
    </p>
    <p v-else-if="!drafts.length" class="tray-empty">暂时没有待确认的草稿。</p>

    <ul v-else class="tray-list">
      <li v-for="draft in drafts.slice(0, 3)" :key="draft.id">
        <DraftItem :draft="draft" compact @resolved="onResolved" />
      </li>
    </ul>

    <RouterLink to="/drafts" class="tray-more">
      <span>
        {{ drafts.length > 3 ? `还有 ${drafts.length - 3} 条，去草稿中心` : '去草稿中心' }}
      </span>
      <ArrowRight :size="15" aria-hidden="true" />
    </RouterLink>
  </aside>
</template>

<style scoped>
.tray {
  position: sticky;
  top: 26px;
  padding: 18px 18px 16px;
  border: 1px solid var(--line);
  border-radius: var(--radius-float);
  background: var(--sheet);
  box-shadow: var(--shadow-float);
}

.tray-head {
  display: flex;
  align-items: baseline;
  gap: 9px;
}

.tray-title {
  font-family: var(--font-display);
  font-size: 19px;
  font-weight: 400;
}

.count {
  color: var(--signal-deep);
  font-size: 14px;
}

.tray-hint {
  margin: 6px 0 14px;
  color: var(--ink-soft);
  font-size: 12.5px;
  line-height: 1.6;
}

.tray-list {
  display: grid;
  gap: 9px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.tray-empty {
  padding: 12px 0;
  color: var(--ink-soft);
  font-size: 13.5px;
}

.tray-error {
  color: var(--danger);
  font-size: 13px;
}

.link-btn {
  padding: 0;
  border: 0;
  background: none;
  color: inherit;
  font-size: inherit;
  text-decoration: underline;
  cursor: pointer;
}

.tray-more {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: 14px;
  padding-top: 12px;
  border-top: 1px solid var(--line-soft);
  color: var(--signal-deep);
  font-size: 13.5px;
  text-decoration: none;
}

.tray-more:hover {
  text-decoration: underline;
  text-underline-offset: 3px;
}

@media (max-width: 1100px) {
  .tray {
    position: static;
  }
}
</style>
