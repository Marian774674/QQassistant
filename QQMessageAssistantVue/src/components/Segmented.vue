<script setup lang="ts" generic="T extends string">
import { computed } from 'vue'

const props = defineProps<{
  items: Array<{ key: T; label: string; count?: number }>
  modelValue: T
  label?: string
}>()

const emit = defineEmits<{ 'update:modelValue': [value: T] }>()

const index = computed(() =>
  Math.max(
    0,
    props.items.findIndex((item) => item.key === props.modelValue),
  ),
)
</script>

<template>
  <div
    class="seg"
    role="group"
    :aria-label="label ?? '筛选'"
    :style="{ '--seg-n': items.length }"
  >
    <span
      class="seg-indicator"
      :style="{ transform: `translateX(${index * 100}%)` }"
      aria-hidden="true"
    />
    <button
      v-for="item in items"
      :key="item.key"
      type="button"
      class="seg-item"
      :aria-pressed="modelValue === item.key"
      @click="emit('update:modelValue', item.key)"
    >
      <span>{{ item.label }}</span>
      <span v-if="item.count !== undefined" class="seg-count num">{{ item.count }}</span>
    </button>
  </div>
</template>
