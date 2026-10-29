<script setup lang="ts">
withDefaults(defineProps<{ rows?: number }>(), { rows: 3 })
</script>

<template>
  <div class="skeleton-list" aria-hidden="true">
    <div v-for="n in rows" :key="n" class="skeleton-card">
      <div class="skeleton skeleton-chip" />
      <div class="skeleton-lines">
        <div class="skeleton skeleton-line" style="width: 62%" />
        <div class="skeleton skeleton-line" style="width: 38%" />
      </div>
    </div>
  </div>
</template>

<style scoped>
.skeleton-list {
  display: grid;
  gap: 10px;
}

.skeleton-card {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 14px;
  border-radius: var(--r-lg);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

.skeleton-chip {
  width: 34px;
  height: 34px;
  border-radius: 10px;
  flex: none;
}

.skeleton-lines {
  flex: 1;
  display: grid;
  gap: 8px;
}

.skeleton-line {
  height: 11px;
}
</style>
