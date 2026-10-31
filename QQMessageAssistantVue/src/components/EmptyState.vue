<script setup lang="ts">
import { CircleDashed } from 'lucide-vue-next'
import type { Component } from 'vue'

defineProps<{
  title: string
  hint?: string
  /** 换一个更贴题的图标；不传就用中性的虚线圈 */
  icon?: Component
}>()
</script>

<template>
  <div class="empty">
    <span class="empty-mark" aria-hidden="true">
      <component :is="icon ?? CircleDashed" :size="15" :stroke-width="1.5" />
    </span>
    <p class="empty-title">{{ title }}</p>
    <p v-if="hint" class="empty-hint">{{ hint }}</p>
    <div v-if="$slots.default" class="empty-action">
      <slot />
    </div>
  </div>
</template>

<style scoped>
.empty {
  display: grid;
  justify-items: center;
  gap: 10px;
  padding: 54px 26px 46px;
  text-align: center;
}

/*
 * 标记从「一块灰底圆形气泡」改成「一枚发丝线圈」。
 * 灰底气泡是 App 的通用形状；一圈 1px 的线更像印刷品的符号，
 * 也和卡片、图标框、输入框统一到同一套「压边」语言里。
 */
.empty-mark {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  margin-bottom: 6px;
  border: 1px solid var(--rule);
  border-radius: 50%;
  background: transparent;
  color: var(--ink-3);
}

/*
 * 空屏的标题用衬线。
 * 一屏没内容的时候，唯一能撑住版面的就是这行字——衬线让它像一句「引言」，
 * 而不是一句「提示」。
 */
.empty-title {
  font-family: var(--font-display);
  font-size: 21px;
  font-weight: 400;
  line-height: 1.3;
  letter-spacing: -0.005em;
  text-wrap: balance;
}

.empty-hint {
  max-width: 27ch;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.7;
  text-wrap: pretty;
}

.empty-action {
  margin-top: 10px;
}
</style>
