<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    tone?: 'dark' | 'light'
    /** 占屏高度上限，默认 88% */
    height?: string
  }>(),
  { description: '', tone: 'dark', height: '88%' },
)

const emit = defineEmits<{ close: [] }>()

const panel = ref<HTMLElement | null>(null)
let lastFocused: HTMLElement | null = null
let scroller: HTMLElement | null = null

function close() {
  emit('close')
}

function onKeydown(event: KeyboardEvent) {
  if (event.key === 'Escape') {
    event.stopPropagation()
    close()
    return
  }
  if (event.key !== 'Tab' || !panel.value) return

  const focusable = panel.value.querySelectorAll<HTMLElement>(
    'a[href], button:not([disabled]), input:not([disabled]), select:not([disabled]), textarea:not([disabled]), [tabindex]:not([tabindex="-1"])',
  )
  const items = Array.from(focusable).filter((el) => el.offsetParent !== null)
  if (!items.length) return

  const first = items[0]
  const last = items[items.length - 1]
  const active = document.activeElement as HTMLElement | null
  const outside = !panel.value.contains(active)

  if (event.shiftKey && (active === first || outside)) {
    event.preventDefault()
    last.focus()
  } else if (!event.shiftKey && (active === last || outside)) {
    event.preventDefault()
    first.focus()
  }
}

function lockScroll(lock: boolean) {
  if (!scroller) scroller = document.querySelector<HTMLElement>('.screen')
  if (scroller) scroller.style.overflowY = lock ? 'hidden' : ''
}

watch(
  () => props.open,
  async (open) => {
    if (open) {
      lastFocused = document.activeElement as HTMLElement | null
      lockScroll(true)
      window.addEventListener('keydown', onKeydown)
      await nextTick()
      panel.value
        ?.querySelector<HTMLElement>('input, textarea, select, button')
        ?.focus({ preventScroll: true })
    } else {
      cleanup()
    }
  },
)

function cleanup() {
  lockScroll(false)
  window.removeEventListener('keydown', onKeydown)
  lastFocused?.focus?.({ preventScroll: true })
  lastFocused = null
}

onBeforeUnmount(cleanup)
</script>

<template>
  <Teleport to="#app">
    <Transition name="sheet">
      <div v-if="open" class="layer">
        <div class="scrim" @click="close" />
        <section
          ref="panel"
          class="panel"
          :class="tone"
          role="dialog"
          aria-modal="true"
          :aria-label="title"
          :style="{ maxHeight: height }"
        >
          <button type="button" class="grip-close" aria-label="关闭" @click="close">
            <X :size="17" :stroke-width="2.2" aria-hidden="true" />
          </button>

          <header class="head">
            <h2 class="title">{{ title }}</h2>
            <p v-if="description" class="desc">{{ description }}</p>
          </header>

          <div class="body">
            <slot />
          </div>

          <footer v-if="$slots.footer" class="foot">
            <slot name="footer" />
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.layer {
  position: absolute;
  inset: 0;
  z-index: 80;
  display: flex;
  flex-direction: column;
  justify-content: flex-end;
}

.scrim {
  position: absolute;
  inset: 0;
  background: rgba(10, 10, 11, 0.42);
  backdrop-filter: blur(3px);
  -webkit-backdrop-filter: blur(3px);
}

.panel {
  position: relative;
  display: flex;
  flex-direction: column;
  width: 100%;
  border-radius: var(--r-sheet) var(--r-sheet) 0 0;
  box-shadow: 0 -18px 60px -30px rgba(0, 0, 0, 0.6);
  animation: sheet-up 0.3s var(--ease) both;
}

.panel.dark {
  background: #0f0f10;
  color: var(--on-dark);
}

.panel.light {
  background: var(--bg);
  color: var(--ink);
}

.grip-close {
  position: absolute;
  top: -20px;
  left: 50%;
  transform: translateX(-50%);
  display: grid;
  place-items: center;
  width: 40px;
  height: 40px;
  border: 0;
  border-radius: 50%;
  background: #2c2c2e;
  color: #fff;
  box-shadow: 0 6px 20px -8px rgba(0, 0, 0, 0.6);
  cursor: pointer;
}

.panel.light .grip-close {
  background: #fff;
  color: #111214;
}

.head {
  padding: 30px 22px 14px;
}

.title {
  font-size: 21px;
  font-weight: 600;
  letter-spacing: -0.02em;
  line-height: 1.3;
  overflow-wrap: anywhere;
}

.desc {
  margin-top: 6px;
  font-size: 13px;
  line-height: 1.55;
  opacity: 0.62;
}

.body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  padding: 0 22px 8px;
}

.foot {
  flex: none;
  display: flex;
  gap: 10px;
  padding: 14px 22px calc(18px + env(safe-area-inset-bottom, 0px));
}

.foot :deep(.btn) {
  flex: 1;
}

.panel.dark .foot :deep(.btn-ghost) {
  color: rgba(255, 255, 255, 0.78);
}

.panel.dark .foot :deep(.btn-ghost:active) {
  background: rgba(255, 255, 255, 0.08);
}

.sheet-enter-active,
.sheet-leave-active {
  transition: opacity 0.24s var(--ease);
}

.sheet-enter-active .panel,
.sheet-leave-active .panel {
  transition: transform 0.3s var(--ease);
}

.sheet-enter-from,
.sheet-leave-to {
  opacity: 0;
}

.sheet-enter-from .panel,
.sheet-leave-to .panel {
  transform: translateY(100%);
}
</style>
