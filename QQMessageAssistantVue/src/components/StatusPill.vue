<script setup lang="ts">
import type { Tone } from '@/utils/meta'

withDefaults(
  defineProps<{
    label: string
    tone?: Tone
    dot?: boolean
  }>(),
  { tone: 'neutral', dot: true },
)
</script>

<template>
  <span class="status" :class="`tone-${tone}`">
    <i v-if="dot" class="status-dot" aria-hidden="true" />
    {{ label }}
  </span>
</template>

<style scoped>
.status {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 8px;
  border-radius: var(--r-pill);
  font-size: 11.5px;
  font-weight: 500;
  line-height: 1.5;
  white-space: nowrap;
}

.status-dot {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  opacity: 0.85;
}

.tone-neutral {
  background: rgba(46, 38, 26, 0.055);
  color: var(--ink-2);
}

.tone-ok {
  background: rgba(47, 122, 85, 0.1);
  color: var(--ok);
}

.tone-warn {
  background: rgba(160, 106, 18, 0.11);
  color: var(--warn);
}

.tone-danger {
  background: var(--danger-wash);
  color: var(--danger);
}

.tone-quiet {
  background: transparent;
  color: var(--ink-3);
}
</style>
