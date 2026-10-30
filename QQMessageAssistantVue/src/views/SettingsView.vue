<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Check } from 'lucide-vue-next'
import ErrorState from '@/components/ErrorState.vue'
import Skeleton from '@/components/Skeleton.vue'
import { getSettings, putSettings } from '@/api/settings'
import { loadOwner, saveOwner, useOwner } from '@/composables/useOwner'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'

const owner = useOwner()
const toast = useToast()

const qqInput = ref('')
const savingOwner = ref(false)
const ownerError = ref('')

const settings = ref<Record<string, string>>({})
const loadingSettings = ref(true)
const settingsError = ref('')

// 自动回复：子提示词是「额外要求」，限流只约束替主人闲聊的条数
const REPLY_PROMPT_KEY = 'reply_prompt'
const REPLY_LIMIT_KEY = 'reply_rate_limit'
const REPLY_ENABLED_KEY = 'reply_enabled'
const DEFAULT_LIMIT = 20
const PROMPT_HINTS = ['语气随便点，别太客气', '别提我的工作', '群聊里别人 @ 我才回']

const replyPrompt = ref('')
const replyLimit = ref(DEFAULT_LIMIT)
const savingReply = ref(false)
const replyError = ref('')
const replyDirty = ref(false)

// 总开关：一键闭嘴。缺省是开——没配过时保持各会话自己的开关说了算
const replyEnabled = ref(true)
const savingGlobal = ref(false)

const ownerSaved = computed(
  () => owner.senderId !== null && String(owner.senderId) === qqInput.value.trim(),
)

watch(
  () => owner.senderId,
  (id) => {
    if (id !== null) qqInput.value = String(id)
  },
  { immediate: true },
)

async function loadSettings() {
  loadingSettings.value = true
  settingsError.value = ''
  try {
    settings.value = await getSettings()
    hydrateReply(settings.value)
  } catch (e) {
    settingsError.value = e instanceof Error ? e.message : '设置读取失败'
  } finally {
    loadingSettings.value = false
  }
}

function hydrateReply(snapshot: Record<string, string>) {
  // 总开关是点一下就存的，刷新时永远以服务端为准
  replyEnabled.value = (snapshot[REPLY_ENABLED_KEY] ?? 'true').trim().toLowerCase() !== 'false'
  // 下拉刷新别把正在敲的字冲掉：只有没动过这两个字段时才回填
  if (replyDirty.value) return
  replyPrompt.value = snapshot[REPLY_PROMPT_KEY] ?? ''
  const limit = Number(snapshot[REPLY_LIMIT_KEY])
  replyLimit.value = Number.isFinite(limit) && limit > 0 ? limit : DEFAULT_LIMIT
}

onMounted(async () => {
  if (!owner.loaded) await loadOwner()
  await loadSettings()
})

useRefresh(loadSettings)

async function saveMine() {
  const raw = qqInput.value.trim()
  if (!/^\d{5,12}$/.test(raw)) {
    ownerError.value = 'QQ 号应该是 5–12 位数字'
    return
  }
  ownerError.value = ''
  savingOwner.value = true
  try {
    await saveOwner(Number(raw))
    await loadSettings()
    toast.success('已记下你的 QQ 号')
  } catch (e) {
    ownerError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    savingOwner.value = false
  }
}

function onSave() {
  if (ownerSaved.value) return
  void saveMine()
}

async function toggleReplyEnabled() {
  const next = !replyEnabled.value
  // 先翻过去给反馈，写失败再翻回来——这个开关要能「立刻相信」
  replyEnabled.value = next
  savingGlobal.value = true
  try {
    await putSettings({ [REPLY_ENABLED_KEY]: next ? 'true' : 'false' })
    settings.value = { ...settings.value, [REPLY_ENABLED_KEY]: next ? 'true' : 'false' }
    toast.success(next ? '已打开自动回复' : '已关掉自动回复')
  } catch (e) {
    replyEnabled.value = !next
    toast.push(e instanceof Error ? e.message : '没保存上', 'error')
  } finally {
    savingGlobal.value = false
  }
}

async function saveReply() {
  const limit = Number(replyLimit.value)
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) {
    replyError.value = '每小时上限要是 1–200 的整数'
    return
  }
  replyError.value = ''
  savingReply.value = true
  try {
    await putSettings({
      [REPLY_PROMPT_KEY]: replyPrompt.value.trim(),
      [REPLY_LIMIT_KEY]: String(limit),
    })
    replyDirty.value = false
    await loadSettings()
    toast.success('自动回复设置已保存')
  } catch (e) {
    replyError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    savingReply.value = false
  }
}

const otherSettings = computed(() =>
  Object.entries(settings.value)
    // 这几项在上面有专属编辑区，别再当成只读的「后端配置」重复列一遍
    .filter(
      ([key]) =>
        !['owner_sender_id', REPLY_PROMPT_KEY, REPLY_LIMIT_KEY, REPLY_ENABLED_KEY].includes(key),
    )
    .sort(([a], [b]) => a.localeCompare(b)),
)
</script>

<template>
  <div class="page">
    <header class="page-head">
      <RouterLink to="/more" class="back">
        <ArrowLeft :size="16" :stroke-width="2" aria-hidden="true" />
        更多
      </RouterLink>
      <h1 class="page-title">设置</h1>
    </header>

    <section class="block">
      <h2 class="block-title">我的 QQ 号</h2>
      <p class="block-hint">用来判断哪些备忘、事实和草稿是「你的」。</p>

      <div class="card card-pad">
        <div class="field">
          <label for="owner-qq">QQ 号</label>
          <input
            id="owner-qq"
            v-model="qqInput"
            class="input"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            placeholder="例如：123456789"
            :aria-invalid="Boolean(ownerError)"
          />
          <p v-if="ownerError" class="error" role="alert">{{ ownerError }}</p>
          <p v-else class="field-hint">
            机器人从 QQ 收到消息时会带上发送者号码；填成同一个，两边看到的是同一份数据。
          </p>
        </div>

        <button
          type="button"
          class="btn btn-block save"
          :class="ownerSaved ? 'btn-quiet' : 'btn-primary'"
          :disabled="savingOwner"
          @click="onSave"
        >
          <Check v-if="ownerSaved" :size="15" :stroke-width="2.6" aria-hidden="true" />
          {{ savingOwner ? '保存中…' : ownerSaved ? '已保存' : '保存' }}
        </button>
      </div>
    </section>

    <section class="block">
      <h2 class="block-title">自动回复</h2>
      <p class="block-hint">
        机器人替你说日常话时，会参考下面这段要求。哪些群、哪些私聊允许它开口，在「群设置」里按会话开关。
        备忘、查资料、文件填写不受这里影响，照常工作。
      </p>

      <div class="card card-pad">
        <label class="switch-row">
          <span class="switch-text">
            <span class="switch-label">总开关</span>
            <span class="switch-hint">
              关掉后所有群和私聊都立刻闭嘴，「群设置」里那些开关全部暂停。
              备忘、查资料、文件填写照常，不受影响。
            </span>
          </span>
          <input
            type="checkbox"
            class="switch"
            role="switch"
            :checked="replyEnabled"
            :aria-checked="replyEnabled"
            :disabled="savingGlobal"
            @change="toggleReplyEnabled"
          />
        </label>

        <div class="field">
          <label for="reply-prompt">额外要求</label>
          <textarea
            id="reply-prompt"
            v-model="replyPrompt"
            class="input textarea"
            rows="4"
            maxlength="500"
            placeholder="例如：语气随便点；别提我的工作；群里被 @ 才回"
            @input="replyDirty = true"
          ></textarea>
          <p class="field-hint">
            大白话写就行，比如：
            <span v-for="(hint, i) in PROMPT_HINTS" :key="hint">
              {{ hint }}<span v-if="i < PROMPT_HINTS.length - 1">、</span>
            </span>。
            不替你承诺、不说自己是机器人这些底线，写在这里也改不掉。
            <span class="faint">{{ replyPrompt.length }}/500</span>
          </p>
        </div>

        <div class="field">
          <label for="reply-limit">每小时最多替你说几句</label>
          <input
            id="reply-limit"
            v-model.number="replyLimit"
            class="input limit"
            type="number"
            min="1"
            max="200"
            step="1"
            inputmode="numeric"
            :aria-invalid="Boolean(replyError)"
            @input="replyDirty = true"
          />
          <p v-if="replyError" class="error" role="alert">{{ replyError }}</p>
          <p v-else class="field-hint">说得太多容易露馅。超过这个数就只做事、不说话。默认 20。</p>
        </div>

        <button
          type="button"
          class="btn btn-block save"
          :class="replyDirty ? 'btn-primary' : 'btn-quiet'"
          :disabled="savingReply || !replyDirty"
          @click="saveReply"
        >
          <Check v-if="!replyDirty" :size="15" :stroke-width="2.6" aria-hidden="true" />
          {{ savingReply ? '保存中…' : replyDirty ? '保存' : '已保存' }}
        </button>
      </div>
    </section>

    <section class="block">
      <h2 class="block-title">提醒怎么走</h2>
      <dl class="rows">
        <div class="row static">
          <dt class="row-label">提醒方式</dt>
          <dd class="row-value wide">后端每分钟扫一次到点提醒，经发送桥推到 QQ，不拟人延迟</dd>
        </div>
        <div class="row static">
          <dt class="row-label">提醒档位</dt>
          <dd class="row-value wide">5 级 24h/2h/30min；4 级 24h/1h；3 级 2h；2 级当天 09:00；1 级只存档</dd>
        </div>
        <div class="row static">
          <dt class="row-label">改计划</dt>
          <dd class="row-value wide">改重要性或截止时间会作废旧提醒，按新档位重排</dd>
        </div>
      </dl>
    </section>

    <section class="block">
      <h2 class="block-title">后端配置</h2>
      <p class="block-hint">只读，改动请走后端配置。</p>

      <ErrorState
        v-if="settingsError"
        :message="settingsError"
        hint="也可以下拉重试。"
        @retry="loadSettings"
      />
      <Skeleton v-else-if="loadingSettings" :rows="2" />
      <p v-else-if="!otherSettings.length" class="faint">没有其他设置项。</p>

      <dl v-else class="rows">
        <div v-for="[key, value] in otherSettings" :key="key" class="row static">
          <dt class="row-label mono">{{ key }}</dt>
          <dd class="row-value">{{ value }}</dd>
        </div>
      </dl>
    </section>

    <p class="version">值守台 · 本地单实例 · 数据只存在你自己的机器上</p>
  </div>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-bottom: 10px;
  color: var(--ink-2);
  font-size: 13px;
}

.block + .block {
  margin-top: 28px;
}

.block-title {
  margin-bottom: 6px;
  font-size: 17px;
  font-weight: 600;
  letter-spacing: -0.02em;
}

.block-hint {
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.save {
  margin-top: 16px;
}

/* 总开关与下面的输入框拉开距离，别挤成一行 */
.switch-row {
  padding-bottom: 16px;
  border-bottom: 1px solid var(--hairline);
}

.field + .field {
  margin-top: 16px;
}

.textarea {
  min-height: 96px;
  resize: vertical;
}

.limit {
  max-width: 140px;
}

.rows .row-value {
  max-width: none;
  overflow: visible;
  white-space: normal;
  text-align: left;
  color: var(--ink-2);
  font-size: 12.5px;
  line-height: 1.55;
  overflow-wrap: anywhere;
}

.row.static {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 3px;
  cursor: default;
}

.row-value.wide {
  max-width: none;
  white-space: normal;
  line-height: 1.55;
  text-align: left;
  color: var(--ink-2);
  font-size: 12.5px;
}

.version {
  margin-top: 30px;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: center;
}

</style>
