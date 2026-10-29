<script setup lang="ts">
import {
  ChevronRight,
  FileSpreadsheet,
  MessagesSquare,
  Settings,
  ShieldCheck,
} from 'lucide-vue-next'
import { useOwner } from '@/composables/useOwner'

const owner = useOwner()

const items = [
  {
    to: '/files',
    label: '文件任务',
    hint: '表格填写进度与产物',
    icon: FileSpreadsheet,
  },
  {
    to: '/logs',
    label: '消息日志',
    hint: '收发流水，排查用',
    icon: MessagesSquare,
  },
  {
    to: '/rules',
    label: '拦截规则',
    hint: '关键词与正则，热更新',
    icon: ShieldCheck,
  },
  {
    to: '/settings',
    label: '设置',
    hint: '身份、提醒策略与后端配置',
    icon: Settings,
  },
]
</script>

<template>
  <div class="page">
    <header class="page-head">
      <p class="eyebrow">更多</p>
      <h1 class="page-title">{{ owner.senderId ? `QQ ${owner.senderId}` : '还没绑定身份' }}</h1>
      <p class="page-sub">
        {{
          owner.senderId
            ? '这些是排查与配置入口，日常用不到。'
            : '先去设置里填上你的 QQ 号，备忘和记忆才会归到你名下。'
        }}
      </p>
    </header>

    <nav class="rows" aria-label="更多功能">
      <RouterLink v-for="item in items" :key="item.to" :to="item.to" class="row">
        <span class="row-icon" aria-hidden="true">
          <component :is="item.icon" :size="17" :stroke-width="1.8" />
        </span>
        <span class="row-text">
          <span class="row-label">{{ item.label }}</span>
          <span class="row-hint">{{ item.hint }}</span>
        </span>
        <ChevronRight :size="16" :stroke-width="2" class="row-arrow" aria-hidden="true" />
      </RouterLink>
    </nav>

    <p class="version">值守台 · 本地单实例</p>
  </div>
</template>

<style scoped>
.rows {
  display: block;
}

.row {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 62px;
  padding: 12px 14px;
  color: var(--ink);
  text-decoration: none;
}

.row:active {
  background: var(--surface);
}

.row-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border-radius: 11px;
  background: var(--surface);
  color: var(--ink-2);
  flex: none;
}

.row-text {
  flex: 1;
  display: grid;
  gap: 2px;
  min-width: 0;
}

.row-label {
  font-size: 15px;
  font-weight: 500;
}

.row-hint {
  color: var(--ink-3);
  font-size: 11.5px;
}

.row-arrow {
  color: var(--ink-3);
  flex: none;
}

.version {
  margin-top: 26px;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: center;
}
</style>
