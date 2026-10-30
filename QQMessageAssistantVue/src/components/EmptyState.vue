<script setup lang="ts">
import { CircleDashed } from 'lucide-vue-next'
import type { Component } from 'vue'

defineProps<{
  title: string
  hint?: string
  /** 换一个更贴题的图标；不传就用中性的虚线圈 */
  icon?: Component
}>()
</script>

<template>
  <div class="empty">
    <span class="empty-mark" aria-hidden="true">
      <component :is="icon ?? CircleDashed" :size="22" :stroke-width="1.6" />
    </span>
    <p class="empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: grid;
  justify-items: center;
  gap: 8px;
  padding: 46px 24px 42px;
  text-align: center;
}

.empty-mark {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  margin-bottom: 4px;
  border-radius: 50%;
  background: rgba(17, 18, 20, 0.045);
  color: var(--ink-3);
}

.empty-title {
  font-size: 16.5px;
  font-weight: 600;
  letter-spacing: -0.015em;
}

.empty-hint {
  max-width: 30ch;
  color: var(--ink-3);
  font-size: 13px;
  line-height: 1.6;
}

.empty-action {
  margin-top: 8px;
}
</style>
