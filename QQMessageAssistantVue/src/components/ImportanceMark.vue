<script setup lang="ts">
import { computed } from 'vue'
import { importanceLabel } from '@/utils/meta'

const props = defineProps<{ importance: number; inverse?: boolean }>()

const label = computed(() => importanceLabel(props.importance))
</script>

<template>
  <span
    class="imp"
    :class="{ inverse }"
    :aria-label="`重要性 ${importance} 级：${label}`"
  >
    <span class="bars" aria-hidden="true">
      <i v-for="n in 5" :key="n" :class="{ on: n <= importance }" />
    </span>
    <span class="label">{{ label }}</span>
  </span>
</template>

<style scoped>
.imp {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 11.5px;
  white-space: nowrap;
}

.bars {
  display: inline-flex;
  align-items: flex-end;
  gap: 2px;
  height: 10px;
}

.bars i {
  display: block;
  width: 2.5px;
  height: 6px;
  border-radius: 1px;
  background: var(--hairline);
  transition: background-color var(--dur) var(--ease);
}

.bars i:nth-child(4),
.bars i:nth-child(5) {
  height: 10px;
}

.bars i.on {
  background: var(--ink);
}

.label {
  letter-spacing: 0.01em;
}

.imp.inverse {
  color: var(--on-dark-2);
}

.imp.inverse .bars i {
  background: rgba(255, 255, 255, 0.22);
}

.imp.inverse .bars i.on {
  background: #fff;
}
</style>
