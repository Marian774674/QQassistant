<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { X } from 'lucide-vue-next'

const props = withDefaults(
  defineProps<{
    open: boolean
    title: string
    description?: string
    width?: string
  }>(),
  { width: '560px', description: '' },
)

const emit = defineEmits<{ close: [] }>()

const panel = ref<HTMLElement | null>(null)
let lastFocused: HTMLElement | null = null

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

  // 焦点锁在面板里，别让 Tab 跑到背后页面上
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

watch(
  () => props.open,
  async (open) => {
    if (open) {
      lastFocused = document.activeElement as HTMLElement | null
      document.body.style.overflow = 'hidden'
      window.addEventListener('keydown', onKeydown)
      await nextTick()
      panel.value?.querySelector<HTMLElement>('input, textarea, select, button')?.focus()
    } else {
      cleanup()
    }
  },
)

function cleanup() {
  document.body.style.overflow = ''
  window.removeEventListener('keydown', onKeydown)
  lastFocused?.focus?.()
  lastFocused = null
}

onBeforeUnmount(cleanup)
</script>

<template>
  <Teleport to="body">
    <Transition name="modal">
      <div v-if="open" class="scrim" @click.self="close">
        <section
          ref="panel"
          class="panel"
          role="dialog"
          aria-modal="true"
          aria-labelledby="modal-title"
          :style="{ '--panel-w': width }"
        >
          <header class="panel-head">
            <div>
              <h2 id="modal-title" class="display panel-title">{{ title }}</h2>
              <p v-if="description" class="panel-desc">{{ description }}</p>
            </div>
            <button class="btn btn-ghost panel-close" type="button" aria-label="关闭" @click="close">
              <X :size="18" aria-hidden="true" />
            </button>
          </header>
          <div class="panel-body">
            <slot />
          </div>
          <footer v-if="$slots.footer" class="panel-foot">
            <slot name="footer" />
          </footer>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.scrim {
  position: fixed;
  inset: 0;
  z-index: 900;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(16, 26, 32, 0.48);
  backdrop-filter: blur(2px);
}

.panel {
  width: min(var(--panel-w), 100%);
  max-height: min(86dvh, 780px);
  display: flex;
  flex-direction: column;
  border-radius: var(--radius-float);
  background: var(--sheet);
  box-shadow: var(--shadow-float);
  overflow: hidden;
}

.panel-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 20px 22px 14px;
  border-bottom: 1px solid var(--line-soft);
}

.panel-title {
  font-size: 21px;
  line-height: 1.3;
}

.panel-desc {
  margin-top: 4px;
  font-size: 13px;
  color: var(--ink-soft);
}

.panel-close {
  width: 44px;
  min-height: 44px;
  padding: 0;
  flex: none;
}

.panel-body {
  padding: 20px 22px;
  overflow-y: auto;
}

.panel-foot {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding: 14px 22px 20px;
  border-top: 1px solid var(--line-soft);
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.2s var(--ease);
}
.modal-enter-active .panel,
.modal-leave-active .panel {
  transition: transform 0.24s var(--ease), opacity 0.2s var(--ease);
}
.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
.modal-enter-from .panel,
.modal-leave-to .panel {
  opacity: 0;
  transform: translateY(10px) scale(0.985);
}

@media (max-width: 640px) {
  .scrim {
    padding: 12px;
    align-items: end;
  }
  .panel {
    max-height: 92dvh;
    border-radius: 16px 16px 0 0;
  }
}
</style>
