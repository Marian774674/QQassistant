<script setup lang="ts">
withDefaults(
  defineProps<{
    rows?: number
    /** 骨架形状跟着真实布局走，避免加载完「跳一下」 */
    variant?: 'card' | 'timeline' | 'log'
  }>(),
  { rows: 3, variant: 'card' },
)
</script>

<template>
  <div class="skeleton-list" :class="variant" aria-hidden="true">
    <div v-for="n in rows" :key="n" class="row">
      <template v-if="variant === 'timeline'">
        <span class="dot" />
        <div class="skeleton card">
          <span class="skeleton-chip" />
          <div class="lines">
            <span class="skeleton-line" style="width: 58%" />
            <span class="skeleton-line" style="width: 34%" />
          </div>
        </div>
      </template>

      <template v-else-if="variant === 'log'">
        <span class="stamp" />
        <div class="skeleton bubble">
          <span class="skeleton-line" style="width: 22%" />
          <span class="skeleton-line" style="width: 76%" />
        </div>
      </template>

      <template v-else>
        <div class="skeleton card full">
          <span class="skeleton-chip" />
          <div class="lines">
            <span class="skeleton-line" style="width: 62%" />
            <span class="skeleton-line" style="width: 38%" />
          </div>
        </div>
      </template>
    </div>
  </div>
</template>

<style scoped>
.skeleton-list {
  display: grid;
  gap: 10px;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.dot {
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1.5px solid var(--dot);
  flex: none;
}

.stamp {
  width: 40px;
  height: 9px;
  border-radius: var(--r-pill);
  background: rgba(17, 18, 20, 0.07);
  flex: none;
}

.card {
  flex: 1;
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 14px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.bubble {
  flex: 1;
  display: grid;
  gap: 7px;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.skeleton-chip {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(17, 18, 20, 0.06);
  flex: none;
  animation: pulse 1.4s var(--ease) infinite;
}

.lines {
  flex: 1;
  display: grid;
  gap: 8px;
}

.skeleton-line {
  display: block;
  height: 11px;
  border-radius: var(--r-pill);
  background: rgba(17, 18, 20, 0.06);
  animation: pulse 1.4s var(--ease) infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 0.55;
  }
  50% {
    opacity: 1;
  }
}
</style>
