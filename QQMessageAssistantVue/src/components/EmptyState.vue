<script setup lang="ts">
defineProps<{
  title: string
  hint?: string
}>()
</script>

<template>
  <div class="empty">
    <p class="display empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: grid;
  justify-items: start;
  gap: 6px;
  padding: 44px 28px 40px;
  border: 1px dashed var(--line);
  border-radius: var(--radius-sheet);
  background: rgba(255, 255, 255, 0.5);
}

.empty-title {
  font-size: 20px;
}

.empty-hint {
  max-width: 46ch;
  color: var(--ink-soft);
  font-size: 14px;
}

.empty-action {
  margin-top: 10px;
}
</style>
