<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { getSettings } from '@/api/settings'
import { loadOwner, saveOwner, useOwner } from '@/composables/useOwner'
import { useToast } from '@/composables/useToast'

const owner = useOwner()
const toast = useToast()

const qqInput = ref('')
const savingOwner = ref(false)
const ownerError = ref('')

const settings = ref<Record<string, string>>({})
const loadingSettings = ref(true)
const settingsError = ref('')

const ownerSaved = computed(() => owner.senderId !== null && String(owner.senderId) === qqInput.value.trim())

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
  } catch (e) {
    settingsError.value = e instanceof Error ? e.message : '设置读取失败'
  } finally {
    loadingSettings.value = false
  }
}

onMounted(async () => {
  if (!owner.loaded) await loadOwner()
  await loadSettings()
})

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

const otherSettings = computed(() =>
  Object.entries(settings.value)
    .filter(([key]) => key !== 'owner_sender_id')
    .sort(([a], [b]) => a.localeCompare(b)),
)
</script>

<template>
  <div class="page">
    <header class="head">
      <h1 class="display page-title">设置</h1>
      <p class="page-sub">这里只放前端需要用到的身份信息。其余配置在后端和 LangBot 里改。</p>
    </header>

    <section class="block" aria-labelledby="me-title">
      <div class="block-head">
        <h2 id="me-title" class="block-title">我的 QQ 号</h2>
        <span class="block-sub">用来判断哪些备忘和事实是「你的」</span>
      </div>

      <form class="owner-form" @submit.prevent="saveMine">
        <div class="field">
          <label for="owner-qq">QQ 号</label>
          <input
            id="owner-qq"
            v-model="qqInput"
            type="text"
            inputmode="numeric"
            autocomplete="off"
            placeholder="例如：123456789"
            :aria-invalid="Boolean(ownerError)"
            aria-describedby="owner-help"
          />
          <p id="owner-help" class="hint">
            机器人从 QQ 收到消息时会带上发送者号码；填成同一个，前台和 QQ 两边看到的是同一份备忘。
          </p>
          <p v-if="ownerError" class="error" role="alert">{{ ownerError }}</p>
        </div>

        <button type="submit" class="btn btn-primary" :disabled="savingOwner || ownerSaved">
          {{ savingOwner ? '保存中…' : ownerSaved ? '已保存' : '保存' }}
        </button>
      </form>
    </section>

    <section class="block" aria-labelledby="reminder-title">
      <div class="block-head">
        <h2 id="reminder-title" class="block-title">提醒怎么走</h2>
      </div>
      <dl class="facts">
        <div class="fact">
          <dt>提醒方式</dt>
          <dd>后端每分钟扫一次到点的提醒，经 LangBot 发送桥推到你 QQ。提醒不拟人延迟。</dd>
        </div>
        <div class="fact">
          <dt>提醒档位</dt>
          <dd>5 级：提前 24 小时、2 小时、30 分钟；4 级：24 小时、1 小时；3 级：2 小时；2 级：当天 09:00；1 级：只存档。</dd>
        </div>
        <div class="fact">
          <dt>改计划</dt>
          <dd>改重要性或截止时间会作废旧提醒、按新档位重排。作废的记录保留，方便追溯。</dd>
        </div>
      </dl>
    </section>

    <section class="block" aria-labelledby="kv-title">
      <div class="block-head">
        <h2 id="kv-title" class="block-title">后端的设置项</h2>
        <span class="block-sub">只读，改动请走后端配置</span>
      </div>

      <p v-if="settingsError" class="notice" role="alert">
        <span>{{ settingsError }}</span>
        <button type="button" class="btn btn-sm" @click="loadSettings">重试</button>
      </p>
      <p v-else-if="loadingSettings" class="faint loading">正在读取…</p>
      <p v-else-if="!otherSettings.length" class="faint none">没有其他设置项。</p>

      <dl v-else class="facts kv">
        <div v-for="[key, value] in otherSettings" :key="key" class="fact">
          <dt>{{ key }}</dt>
          <dd>{{ value }}</dd>
        </div>
      </dl>
    </section>
  </div>
</template>

<style scoped>
.head {
  margin-bottom: 24px;
}

.page-title {
  font-size: 34px;
  line-height: 1.15;
}

.page-sub {
  max-width: 60ch;
  margin-top: 6px;
  color: var(--ink-soft);
  font-size: 13.5px;
  line-height: 1.65;
}

.block + .block {
  margin-top: 32px;
  padding-top: 24px;
  border-top: 1px solid var(--line);
}

.block-head {
  display: flex;
  align-items: baseline;
  flex-wrap: wrap;
  gap: 10px;
  margin-bottom: 14px;
}

.block-title {
  font-family: var(--font-display);
  font-size: 22px;
  font-weight: 400;
}

.block-sub {
  color: var(--ink-soft);
  font-size: 12.5px;
}

.owner-form {
  display: flex;
  align-items: flex-start;
  gap: 12px;
}

.owner-form .field {
  flex: 1;
  max-width: 380px;
}

/* 让按钮和输入框对齐，而不是和标签对齐 */
.owner-form > .btn {
  margin-top: 26px;
}

.facts {
  display: grid;
  gap: 0;
  margin: 0;
  border: 1px solid var(--line);
  border-radius: var(--radius-sheet);
  background: var(--sheet);
}

.fact {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  gap: 14px;
  padding: 11px 14px;
  font-size: 14px;
}

.facts .fact + .fact {
  border-top: 1px dashed var(--line-soft);
}

.fact dt {
  color: var(--ink-soft);
  font-size: 13px;
}

.fact dd {
  margin: 0;
  line-height: 1.65;
}

.kv .fact dd {
  overflow-wrap: anywhere;
}

.loading,
.none {
  padding: 8px 0;
}

.notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 14px;
  border-left: 2px solid var(--danger);
  border-radius: 0 2px 2px 0;
  background: var(--danger-wash);
  color: var(--danger);
  font-size: 13.5px;
}

@media (max-width: 760px) {
  .page-title {
    font-size: 29px;
  }

  .owner-form {
    flex-direction: column;
    align-items: stretch;
  }

  .owner-form .field {
    max-width: none;
  }

  .fact {
    grid-template-columns: 1fr;
    gap: 3px;
  }
}
</style>
