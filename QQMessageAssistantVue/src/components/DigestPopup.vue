<script setup lang="ts">
import Sheet from '@/components/Sheet.vue'
import type { DigestItem } from '@/api/digest'
import { formatTime } from '@/utils/datetime'

defineProps<{
  item: DigestItem | null
}>()

const emit = defineEmits<{ close: [] }>()
</script>

<template>
  <Sheet
    :open="item !== null"
    :title="item?.chatName || '群消息'"
    description="这条可能得你亲自看一眼"
    @close="emit('close')"
  >
    <div v-if="item" class="popup">
      <p class="summary">{{ item.summary }}</p>
      <p class="meta num">
        {{ formatTime(item.createdAt) }}
        <span v-if="item.mentionsMe" class="mention">@你</span>
      </p>
    </div>

    <template #footer>
      <button type="button" class="btn btn-primary confirm" @click="emit('close')">知道了</button>
    </template>
  </Sheet>
</template>

<style scoped>
.popup {
  display: grid;
  gap: 10px;
}

.summary {
  margin: 0;
  font-size: 16px;
  line-height: 1.6;
  letter-spacing: -0.005em;
}

.meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--ink-3);
  font-size: 12px;
}

.mention {
  padding: 1px 7px;
  border-radius: var(--r-pill);
  background: rgba(229, 56, 127, 0.12);
  color: var(--accent-ink);
  font-weight: 600;
}

.confirm {
  width: 100%;
  justify-content: center;
}
</style>
