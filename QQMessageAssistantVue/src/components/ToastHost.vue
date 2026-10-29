<script setup lang="ts">
import { CheckCircle2, Info, TriangleAlert, X } from 'lucide-vue-next'
import { useToast } from '@/composables/useToast'

const toast = useToast()
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <div
        v-for="item in toast.items"
        :key="item.id"
        class="toast"
        :class="`toast-${item.tone}`"
        role="status"
      >
        <CheckCircle2 v-if="item.tone === 'success'" :size="17" aria-hidden="true" />
        <TriangleAlert v-else-if="item.tone === 'error'" :size="17" aria-hidden="true" />
        <Info v-else :size="17" aria-hidden="true" />
        <span class="toast-text">{{ item.message }}</span>
        <button class="toast-close" type="button" aria-label="关闭提示" @click="toast.dismiss(item.id)">
          <X :size="15" aria-hidden="true" />
        </button>
      </div>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: fixed;
  right: 20px;
  bottom: 20px;
  z-index: 1000;
  display: grid;
  gap: 8px;
  max-width: min(380px, calc(100vw - 40px));
}

.toast {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 11px 12px 11px 14px;
  border: 1px solid var(--line);
  border-left-width: 3px;
  border-radius: 3px;
  background: var(--sheet);
  box-shadow: var(--shadow-float);
  font-size: 14px;
  animation: rise 0.24s var(--ease) both;
}

.toast-success {
  border-left-color: var(--u3);
}
.toast-error {
  border-left-color: var(--danger);
}
.toast-info {
  border-left-color: var(--signal);
}

.toast-text {
  flex: 1;
}

.toast-close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 2px;
  background: transparent;
  color: var(--ink-soft);
  cursor: pointer;
}

.toast-close:hover {
  background: rgba(22, 35, 44, 0.06);
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.2s var(--ease), transform 0.2s var(--ease);
}
.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(6px);
}

@media (max-width: 640px) {
  .toast-host {
    left: 12px;
    right: 12px;
    bottom: calc(72px + env(safe-area-inset-bottom, 0px));
    max-width: none;
  }
}
</style>
