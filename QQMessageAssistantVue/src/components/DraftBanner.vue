<script setup lang="ts">
import { computed } from 'vue'
import { ChevronRight, Inbox } from 'lucide-vue-next'
import { useDraftStore } from '@/composables/useDraftStore'

const store = useDraftStore()
const count = computed(() => store.pendingCount.value)
</script>

<template>
  <RouterLink v-if="count" to="/drafts" class="banner">
    <span class="banner-icon" aria-hidden="true">
      <Inbox :size="17" :stroke-width="1.9" />
    </span>
    <span class="banner-text">
      <span class="banner-title">
        {{ count }} 条等你过目
      </span>
      <span class="banner-sub">AI 拿不准的回复、文件与记录</span>
    </span>
    <ChevronRight :size="17" :stroke-width="2" class="banner-arrow" aria-hidden="true" />
  </RouterLink>
</template>

<style scoped>
.banner {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
  padding: 13px 14px;
  border-radius: var(--r-lg);
  background: var(--card-inverse);
  color: var(--on-dark);
  box-shadow: var(--shadow-card);
  transition: transform var(--dur) var(--ease);
}

.banner:active {
  transform: scale(0.985);
}

.banner-icon {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.11);
  flex: none;
}

.banner-text {
  flex: 1;
  display: grid;
  gap: 2px;
  min-width: 0;
}

.banner-title {
  font-size: 14.5px;
  font-weight: 600;
  letter-spacing: -0.01em;
}

.banner-sub {
  color: var(--on-dark-3);
  font-size: 11.5px;
}

.banner-arrow {
  color: var(--on-dark-2);
  flex: none;
}
</style>
