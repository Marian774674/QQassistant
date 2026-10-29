<script setup lang="ts">
import { computed } from 'vue'
import { importanceTone } from '@/utils/datetime'

const props = defineProps<{ importance: number }>()

const tone = computed(() => importanceTone(props.importance))
</script>

<template>
  <span
    class="importance"
    :class="tone.cls"
    :aria-label="`重要性 ${importance} 级：${tone.label}`"
  >
    <span class="bars" aria-hidden="true">
      <i v-for="n in 5" :key="n" :class="{ on: n <= importance }" />
    </span>
    <span class="label">{{ tone.label }}</span>
  </span>
</template>

<style scoped>
.importance {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: var(--u-low);
  font-size: 12px;
  white-space: nowrap;
}

.bars {
  display: inline-flex;
  align-items: flex-end;
  gap: 2px;
  height: 11px;
}

.bars i {
  display: block;
  width: 3px;
  height: 7px;
  border-radius: 1px;
  background: var(--line);
}

.bars i:nth-child(4),
.bars i:nth-child(5) {
  height: 11px;
}

.bars i.on {
  background: currentColor;
}

.label {
  letter-spacing: 0.02em;
}

.tone-u5 {
  color: var(--u5);
}
.tone-u4 {
  color: var(--u4);
}
.tone-u3 {
  color: var(--u3);
}
.tone-low {
  color: var(--u-low);
}
</style>
