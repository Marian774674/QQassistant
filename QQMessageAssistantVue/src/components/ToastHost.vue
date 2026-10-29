<script setup lang="ts">
import { Check, Info, TriangleAlert } from 'lucide-vue-next'
import { useToast } from '@/composables/useToast'

const toast = useToast()
</script>

<template>
  <div class="toast-host" aria-live="polite" aria-atomic="false">
    <TransitionGroup name="toast">
      <button
        v-for="item in toast.items"
        :key="item.id"
        type="button"
        class="toast"
        :class="`toast-${item.tone}`"
        @click="toast.dismiss(item.id)"
      >
        <span class="toast-icon" aria-hidden="true">
          <Check v-if="item.tone === 'success'" :size="13" :stroke-width="3" />
          <TriangleAlert v-else-if="item.tone === 'error'" :size="13" :stroke-width="2.4" />
          <Info v-else :size="13" :stroke-width="2.4" />
        </span>
        <span class="toast-text">{{ item.message }}</span>
      </button>
    </TransitionGroup>
  </div>
</template>

<style scoped>
.toast-host {
  position: absolute;
  top: 12px;
  left: 16px;
  right: 16px;
  z-index: 90;
  display: grid;
  gap: 8px;
  pointer-events: none;
}

.toast {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 11px 14px;
  border: 0;
  border-radius: var(--r-md);
  background: rgba(16, 16, 17, 0.94);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 12px 30px -14px rgba(0, 0, 0, 0.6);
  color: #fff;
  font-size: 13.5px;
  line-height: 1.45;
  text-align: left;
  pointer-events: auto;
  cursor: pointer;
}

.toast-icon {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.16);
  flex: none;
}

.toast-success .toast-icon {
  background: rgba(87, 190, 140, 0.9);
  color: #06251a;
}

.toast-error .toast-icon {
  background: #e2584a;
  color: #fff;
}

.toast-text {
  flex: 1;
}

.toast-enter-active,
.toast-leave-active {
  transition: opacity 0.3s var(--ease), transform 0.3s var(--ease);
}

.toast-enter-from,
.toast-leave-to {
  opacity: 0;
  transform: translateY(-10px) scale(0.98);
}
</style>
