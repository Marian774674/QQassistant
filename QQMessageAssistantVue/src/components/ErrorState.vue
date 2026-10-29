<script setup lang="ts">
import { RefreshCw, TriangleAlert } from 'lucide-vue-next'

defineProps<{
  message: string
  hint?: string
}>()

const emit = defineEmits<{ retry: [] }>()
</script>

<template>
  <div class="error-state" role="alert">
    <span class="mark" aria-hidden="true">
      <TriangleAlert :size="17" :stroke-width="2" />
    </span>
    <div class="text">
      <p class="message">{{ message }}</p>
      <p v-if="hint" class="hint">{{ hint }}</p>
    </div>
    <button type="button" class="btn btn-sm btn-quiet retry" @click="emit('retry')">
      <RefreshCw :size="13" :stroke-width="2.4" aria-hidden="true" />
      重试
    </button>
  </div>
</template>

<style scoped>
.error-state {
  display: flex;
  align-items: flex-start;
  gap: 11px;
  padding: 14px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.mark {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--danger-wash);
  color: var(--danger);
  flex: none;
}

.text {
  flex: 1;
  min-width: 0;
  display: grid;
  gap: 3px;
}

.message {
  font-size: 13.5px;
  line-height: 1.5;
  overflow-wrap: anywhere;
}

.hint {
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.5;
}

.retry {
  flex: none;
  min-height: 32px;
  padding: 0 11px;
  font-size: 12.5px;
}
</style>
