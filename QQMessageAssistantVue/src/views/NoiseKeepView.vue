<script setup lang="ts">
import { onMounted, ref, watch } from 'vue'
import { Check } from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import { useNoiseFilter } from '@/composables/useNoiseFilter'
import { useRefresh } from '@/composables/useRefresh'
import { KEEP_GROUPS } from '@/utils/noise'

/**
 * 「一定要保留的内容」从 /rules 里分出来的独立页。
 * 标签、自定义词、原始表达式都在这一页，主页面不再被七颗粉色标签挤满。
 */
const {
  error, loading, loaded, dirty, saving, formError,
  keepRegex, activeGroupIds, customWords, customRegexTokens, defaults,
  load, save, resetKeepRegex, toggleKeepGroup, addKeepWord, removeCustomToken,
} = useNoiseFilter()

const newWord = ref('')
function onAddWord() {
  if (addKeepWord(newWord.value)) newWord.value = ''
}

/** 认不出来的自定义表达式直接把「高级」摊开，避免用户以为自己的设置丢了 */
const advancedOpen = ref(false)
watch(customRegexTokens, (tokens) => {
  if (tokens.length) advancedOpen.value = true
}, { immediate: true })

onMounted(() => void load(loaded.value))
useRefresh(() => load(true))
</script>

<template>
  <div class="page">
    <PageHead :back="{ to: '/rules', label: '噪音过滤' }" title="一定要保留" />

    <p class="intro">
      消息里出现这些内容就<strong>一律不当噪音丢掉</strong>，哪怕它同时命中了刷屏词。
      优先级高于「整句噪音词」和「重复的刷屏话」，不用写正则，点一下就能开或关。
    </p>

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="() => load()" />
    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else>
      <section class="block">
        <h2 class="block-title">按类开启</h2>
        <p class="block-hint">一组就是一类消息，亮着表示这一类里的词全部生效。</p>
        <div class="chip-row" role="group" aria-label="按类保留">
          <button
            v-for="group in KEEP_GROUPS"
            :key="group.id"
            type="button"
            class="chip"
            :aria-pressed="activeGroupIds.includes(group.id)"
            @click="toggleKeepGroup(group.id)"
          >
            <Check v-if="activeGroupIds.includes(group.id)" :size="12" :stroke-width="2.4" aria-hidden="true" />
            {{ group.label }}
          </button>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">自己加的词</h2>
        <p class="block-hint">按普通文字匹配，符号会自动转义；点已有的词可以删掉。</p>

        <div v-if="customWords.length" class="chip-row custom-row">
          <button
            v-for="token in customWords"
            :key="token"
            type="button"
            class="chip chip-custom"
            :aria-label="`移除「${token}」`"
            @click="removeCustomToken(token)"
          >{{ token }}<span class="chip-x" aria-hidden="true">×</span></button>
        </div>

        <div class="chip-add">
          <input
            v-model="newWord"
            class="input chip-input"
            type="text"
            maxlength="20"
            placeholder="加一个词，比如：值日"
            aria-label="添加保留词"
            @keydown.enter.prevent="onAddWord"
          />
          <button
            type="button"
            class="btn btn-quiet chip-add-btn"
            :disabled="!newWord.trim()"
            @click="onAddWord"
          >添加</button>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">原始表达式</h2>
        <p class="block-hint">
          会写正则的人可以直接改这一串；上面的标签和它读写同一份配置，改任意一边另一边会跟着变。
        </p>
        <div class="card card-pad">
          <p v-if="customRegexTokens.length" class="custom-note">
            当前有 {{ customRegexTokens.length }} 段自定义表达式，标签只管内置分组；要改它请用下面这一框。
          </p>
          <button
            type="button"
            class="advanced-toggle"
            :aria-expanded="advancedOpen"
            aria-controls="keep-advanced"
            @click="advancedOpen = !advancedOpen"
          >
            <span>高级：直接编辑表达式</span>
            <span class="advanced-state">{{ advancedOpen ? '收起' : '展开' }}</span>
          </button>
          <Transition name="fold">
            <div v-if="advancedOpen" id="keep-advanced" class="advanced-body">
              <label for="noise-keep">强制保留正则</label>
              <input
                id="noise-keep"
                v-model="keepRegex"
                class="input mono-input"
                type="text"
                spellcheck="false"
                placeholder="@|？|作业|截止|明天"
              />
              <p class="field-hint">写坏了保存时会直接报错，改回出厂值也能救回来。</p>
            </div>
          </Transition>
        </div>
      </section>

      <p v-if="formError" class="error" role="alert">{{ formError }}</p>

      <div class="actions">
        <button
          type="button"
          class="btn btn-quiet"
          :disabled="!defaults || saving"
          @click="resetKeepRegex"
        >恢复默认</button>
        <button
          type="button"
          class="btn"
          :class="dirty ? 'btn-primary' : 'btn-quiet'"
          :disabled="!loaded || saving || !dirty"
          @click="save"
        >{{ saving ? '保存中…' : dirty ? '保存' : '已保存' }}</button>
      </div>
      <p class="save-hint">保存会把噪音过滤的四项一起提交；切回上一页也还能继续改。</p>
    </template>
  </div>
</template>

<style scoped>
.intro {
  margin: 0 0 22px;
  color: var(--ink-2);
  font-size: 12.5px;
  line-height: 1.65;
}

.intro strong {
  color: var(--ink);
  font-weight: 600;
}

.block + .block {
  margin-top: 24px;
}

.block-title {
  margin-bottom: 8px;
}

.block-hint {
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.6;
}

.chip-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 7px 13px;
  border: 1px solid var(--hairline);
  border-radius: var(--r-pill);
  background: var(--card);
  color: var(--ink-2);
  font-family: var(--font-ui);
  font-size: 12.5px;
  line-height: 1.2;
  cursor: pointer;
  transition: border-color var(--dur) var(--ease),
    background-color var(--dur) var(--ease),
    color var(--dur) var(--ease);
}

.chip[aria-pressed='true'],
.chip-custom {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.chip-x {
  font-size: 14px;
  line-height: 1;
}

.custom-row {
  margin-bottom: 12px;
}

.chip-add {
  display: flex;
  gap: 8px;
}

.chip-input {
  flex: 1 1 auto;
  min-width: 0;
  min-height: 44px;
  height: 44px;
  padding: 8px 12px;
  font-size: 13.5px;
}

.chip-add-btn {
  flex: 0 0 auto;
  min-width: 68px;
}

.custom-note {
  margin-bottom: 12px;
  color: var(--accent-ink);
  font-size: 12px;
  line-height: 1.55;
}

.advanced-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--ink-3);
  font-family: var(--font-ui);
  font-size: 12px;
  cursor: pointer;
}

.advanced-state {
  color: var(--accent-ink);
}

.advanced-body {
  display: grid;
  gap: 8px;
  padding-top: 14px;
}

.advanced-body label {
  color: var(--ink);
  font-size: 12.5px;
  font-weight: 500;
}

.mono-input {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12.5px;
}

.field-hint {
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.5;
}

.fold-enter-active,
.fold-leave-active {
  overflow: hidden;
  transition: opacity var(--dur) var(--ease),
    max-height var(--dur-2) var(--ease),
    padding var(--dur-2) var(--ease);
}

.fold-enter-from,
.fold-leave-to {
  opacity: 0;
  max-height: 0;
  padding-top: 0;
}

.fold-enter-to,
.fold-leave-from {
  opacity: 1;
  max-height: 240px;
}

@media (prefers-reduced-motion: reduce) {
  .fold-enter-active,
  .fold-leave-active {
    transition: none;
  }
}

.actions {
  display: flex;
  gap: 10px;
  margin-top: 24px;
}

.actions .btn {
  flex: 1 1 0;
}

.save-hint {
  margin: 9px 0 0;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: center;
}
</style>
