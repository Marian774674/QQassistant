<script setup lang="ts">
import { computed, onMounted } from 'vue'
import { Check } from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import { useNoiseFilter } from '@/composables/useNoiseFilter'
import { useRefresh } from '@/composables/useRefresh'

/**
 * 「整句噪音词」从 /rules 里分出来的独立页。
 * 单独一页之后可以把常用词摊开成可点的标签，主页面只留一行入口，不再塞一整框逗号串。
 */
const {
  error, loading, loaded, dirty, saving, formError,
  keywords, keywordCount, defaults,
  load, save, resetKeywords, toggleKeyword,
} = useNoiseFilter()

/** 和出厂词库对齐的一组快捷词：点一下加、再点一下去掉 */
const QUICK_WORDS = ['666', '哈哈哈', '笑死我了', '233', '收到', '好的', '嗯嗯', '哦哦', '额', '绝了', '牛逼']
const currentWords = computed(() =>
  new Set(keywords.value.split(/[,，]/).map((word) => word.trim()).filter(Boolean)),
)

onMounted(() => void load(loaded.value))
useRefresh(() => load(true))
</script>

<template>
  <div class="page">
    <PageHead :back="{ to: '/rules', label: '噪音过滤' }" title="整句噪音词" />

    <p class="intro">
      消息<strong>整句就是这个词</strong>时，直接判成噪音：不调模型、不自动回复，原文照常写进消息日志。
      只有完整匹配才算，所以「好」「走」这种可能是有效回复的词别放进来。
    </p>

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="() => load()" />
    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else>
      <section class="block">
        <h2 class="block-title">词表</h2>
        <p class="block-hint">英文逗号分隔，写多写少都行，留空就是不用这一条。</p>

        <div class="card card-pad">
          <div class="field">
            <div class="field-head">
              <label for="noise-words">噪音词</label>
              <span class="count num">{{ keywordCount }} 个词</span>
            </div>
            <textarea
              id="noise-words"
              v-model="keywords"
              class="input textarea words-input"
              rows="8"
              spellcheck="false"
              placeholder="666,哈哈哈,笑死我了"
            ></textarea>
          </div>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">常用词</h2>
        <p class="block-hint">点一下加进词表，再点一下去掉；最后记得保存。</p>
        <div class="chip-row" role="group" aria-label="常用噪音词">
          <button
            v-for="word in QUICK_WORDS"
            :key="word"
            type="button"
            class="chip"
            :aria-pressed="currentWords.has(word)"
            @click="toggleKeyword(word)"
          >
            <Check v-if="currentWords.has(word)" :size="12" :stroke-width="2.4" aria-hidden="true" />
            {{ word }}
          </button>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">出厂词表</h2>
        <p class="block-hint">刚装好时的那一串。改乱了可以一键回到这里。</p>
        <div class="card card-pad">
          <p class="factory num">{{ defaults?.defaultKeywords || '（还没读到）' }}</p>
          <button
            type="button"
            class="btn btn-quiet btn-block"
            :disabled="!defaults"
            @click="resetKeywords"
          >
            恢复这一项的默认值
          </button>
        </div>
      </section>

      <p v-if="formError" class="error" role="alert">{{ formError }}</p>

      <button
        type="button"
        class="btn btn-block btn-primary save"
        :disabled="!loaded || saving || !dirty"
        @click="save"
      >
        {{ saving ? '保存中…' : dirty ? '保存' : '已保存' }}
      </button>
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

.field {
  display: grid;
  gap: 8px;
}

.field-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.field-head label {
  flex: 1;
  color: var(--ink);
  font-size: 14px;
  font-weight: 500;
}

.count {
  color: var(--ink-3);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
}

.words-input {
  min-height: 172px;
  font-size: 13.5px;
  line-height: 1.75;
  resize: vertical;
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

.chip[aria-pressed='true'] {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-ink);
}

.factory {
  margin: 0 0 14px;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.7;
  overflow-wrap: anywhere;
}

.save {
  margin-top: 24px;
}

.save-hint {
  margin: 9px 0 0;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: center;
}
</style>
