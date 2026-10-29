<script setup lang="ts">
import { computed } from 'vue'
import { memoStatusMeta } from '@/utils/datetime'

const props = defineProps<{ status: string }>()
const meta = computed(() => memoStatusMeta(props.status))
</script>

<template>
  <span class="pill" :class="meta.cls">{{ meta.label }}</span>
</template>

<style scoped>
.status-open {
  background: var(--signal-wash);
  color: var(--signal-deep);
}
.status-wait {
  background: var(--u4-wash);
  color: var(--u4);
}
.status-late {
  background: var(--u5-wash);
  color: var(--u5);
}
.status-done {
  background: var(--u-low-wash);
  color: var(--ink-soft);
}
</style>
