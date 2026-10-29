<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { ArrowLeft, Check } from 'lucide-vue-next'
import Skeleton from '@/components/Skeleton.vue'
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

function onSave() {
  if (ownerSaved.value) return
  void saveMine()
}

const otherSettings = computed(() =>
  Object.entries(settings.value)
    .filter(([key]) => key !== 'owner_sender_id')
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

      <p v-if="settingsError" class="notice" role="alert">
        <span>{{ settingsError }}</span>
        <button type="button" class="btn btn-sm btn-quiet" @click="loadSettings">重试</button>
      </p>
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
  margin-top: 26px;
}

.block-title {
  margin-bottom: 6px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.015em;
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

.notice {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 12px 14px;
  border-radius: var(--r-lg);
  background: var(--danger-wash);
  color: var(--danger);
  font-size: 13px;
}
</style>
