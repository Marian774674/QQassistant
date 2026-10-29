<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { Check, Pencil, Plus, Trash2, X } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import {
  createFact,
  deleteFact,
  getStyleProfile,
  listFacts,
  updateFact,
  updateStyleProfile,
} from '@/api/memory'
import type { ProfileFact, StyleProfile } from '@/api/types'
import { useOwner } from '@/composables/useOwner'
import { useToast } from '@/composables/useToast'
import { formatDateTime } from '@/utils/datetime'

const owner = useOwner()
const toast = useToast()

const facts = ref<ProfileFact[]>([])
const loadingFacts = ref(true)
const factsError = ref('')

const draft = reactive({ factKey: '', factValue: '' })
const adding = ref(false)
const editingId = ref<number | null>(null)
const editDraft = reactive({ factKey: '', factValue: '' })
const confirmingId = ref<number | null>(null)

const profile = ref<StyleProfile | null>(null)
const profileText = ref('')
const profileError = ref('')
const savingProfile = ref(false)

const senderId = computed(() => owner.senderId)

async function loadFacts() {
  if (!senderId.value) {
    loadingFacts.value = false
    return
  }
  loadingFacts.value = true
  factsError.value = ''
  try {
    facts.value = await listFacts(senderId.value)
  } catch (e) {
    factsError.value = e instanceof Error ? e.message : '事实读取失败'
  } finally {
    loadingFacts.value = false
  }
}

async function loadProfile() {
  if (!senderId.value) return
  profileError.value = ''
  try {
    profile.value = await getStyleProfile(senderId.value)
    profileText.value = profile.value?.profilePrompt ?? ''
  } catch {
    // 没生成过画像时后端会报错，这里不是异常，只是还没有内容
    profile.value = null
    profileText.value = ''
  }
}

async function loadAll() {
  if (!senderId.value) {
    loadingFacts.value = false
    return
  }
  await Promise.all([loadFacts(), loadProfile()])
}

onMounted(loadAll)
watch(senderId, loadAll)

async function addFact() {
  if (!senderId.value) return
  if (!draft.factKey.trim() || !draft.factValue.trim()) {
    toast.error('键和值都要填')
    return
  }
  adding.value = true
  try {
    const created = await createFact({
      senderId: senderId.value,
      factKey: draft.factKey.trim(),
      factValue: draft.factValue.trim(),
    })
    facts.value = [...facts.value, created]
    draft.factKey = ''
    draft.factValue = ''
    toast.success('已记下这条事实')
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '保存失败')
  } finally {
    adding.value = false
  }
}

function startEdit(fact: ProfileFact) {
  editingId.value = fact.id
  editDraft.factKey = fact.factKey
  editDraft.factValue = fact.factValue
  confirmingId.value = null
}

async function saveEdit(fact: ProfileFact) {
  if (!editDraft.factKey.trim() || !editDraft.factValue.trim()) {
    toast.error('键和值都要填')
    return
  }
  try {
    const updated = await updateFact(fact.id, {
      factKey: editDraft.factKey.trim(),
      factValue: editDraft.factValue.trim(),
    })
    facts.value = facts.value.map((item) => (item.id === updated.id ? updated : item))
    editingId.value = null
    toast.success('已更新')
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '保存失败')
  }
}

async function removeFact(fact: ProfileFact) {
  try {
    await deleteFact(fact.id)
    facts.value = facts.value.filter((item) => item.id !== fact.id)
    confirmingId.value = null
    toast.push('已删除')
  } catch (e) {
    toast.error(e instanceof Error ? e.message : '删除失败')
  }
}

async function saveProfile() {
  if (!senderId.value) return
  savingProfile.value = true
  profileError.value = ''
  try {
    profile.value = await updateStyleProfile({
      senderId: senderId.value,
      profilePrompt: profileText.value,
    })
    toast.success('风格画像已保存')
  } catch (e) {
    profileError.value = e instanceof Error ? e.message : '保存失败'
  } finally {
    savingProfile.value = false
  }
}
</script>

<template>
  <div class="page">
    <header class="head">
      <h1 class="display page-title">记忆</h1>
      <p class="page-sub">
        事实是会即时生效的硬信息；风格画像只在你手动保存或定时总结时更新，不会每句话都改动。
      </p>
    </header>

    <p v-if="!owner.loaded" class="faint loading">正在读取设置…</p>

    <EmptyState
      v-else-if="!senderId"
      title="还不知道你是谁"
      hint="先填上你的 QQ 号，才能查看和整理属于你的记忆。"
    >
      <RouterLink to="/settings" class="btn btn-sm">去设置里填 QQ 号</RouterLink>
    </EmptyState>

    <template v-else>
      <section class="block" aria-labelledby="facts-title">
        <div class="block-head">
          <h2 id="facts-title" class="block-title">事实</h2>
          <span class="block-sub">比如学号、导员电话、常用地址</span>
        </div>

        <form class="add" @submit.prevent="addFact">
          <label class="add-field">
            <span class="add-label">键</span>
            <input v-model="draft.factKey" type="text" placeholder="学号" maxlength="64" />
          </label>
          <label class="add-field">
            <span class="add-label">值</span>
            <input v-model="draft.factValue" type="text" placeholder="2023001" maxlength="255" />
          </label>
          <button type="submit" class="btn" :disabled="adding">
            <Plus :size="15" aria-hidden="true" />
            添加
          </button>
        </form>

        <p v-if="factsError" class="notice" role="alert">
          <span>{{ factsError }}</span>
          <button type="button" class="btn btn-sm" @click="loadFacts">重试</button>
        </p>
        <p v-else-if="loadingFacts" class="faint loading">正在读取…</p>
        <p v-else-if="!facts.length" class="faint none">
          还没有记录任何事实。发 QQ 说「记一下我的学号是 2023001」也会自动进来。
        </p>

        <ul v-else class="facts">
          <li v-for="fact in facts" :key="fact.id" class="fact">
            <template v-if="editingId === fact.id">
              <input v-model="editDraft.factKey" type="text" aria-label="事实的键" maxlength="64" />
              <input v-model="editDraft.factValue" type="text" aria-label="事实的值" maxlength="255" />
              <div class="fact-actions">
                <button type="button" class="icon-btn ok" aria-label="保存" @click="saveEdit(fact)">
                  <Check :size="16" aria-hidden="true" />
                </button>
                <button type="button" class="icon-btn" aria-label="放弃修改" @click="editingId = null">
                  <X :size="16" aria-hidden="true" />
                </button>
              </div>
            </template>

            <template v-else>
              <span class="fact-key">{{ fact.factKey }}</span>
              <span class="fact-value">{{ fact.factValue }}</span>
              <span class="fact-meta num">{{ formatDateTime(fact.updatedAt) }}</span>
              <div class="fact-actions">
                <template v-if="confirmingId === fact.id">
                  <button type="button" class="btn btn-sm btn-danger" @click="removeFact(fact)">
                    确定删除
                  </button>
                  <button type="button" class="icon-btn" aria-label="取消删除" @click="confirmingId = null">
                    <X :size="16" aria-hidden="true" />
                  </button>
                </template>
                <template v-else>
                  <button type="button" class="icon-btn" aria-label="编辑" @click="startEdit(fact)">
                    <Pencil :size="15" aria-hidden="true" />
                  </button>
                  <button
                    type="button"
                    class="icon-btn danger"
                    aria-label="删除"
                    @click="confirmingId = fact.id"
                  >
                    <Trash2 :size="15" aria-hidden="true" />
                  </button>
                </template>
              </div>
            </template>
          </li>
        </ul>
      </section>

      <section class="block" aria-labelledby="profile-title">
        <div class="block-head">
          <h2 id="profile-title" class="block-title">风格画像</h2>
          <span class="block-sub">
            {{
              profile?.lastSummarizedAt
                ? `上次总结于 ${formatDateTime(profile.lastSummarizedAt)}`
                : '还没有生成过画像'
            }}
          </span>
        </div>

        <p class="profile-note">
          这段提示词决定机器人模仿你说话的语气。改完点保存才会生效；聊天过程中它不会被自动改写。
        </p>

        <div class="field">
          <label for="profile-text">画像提示词</label>
          <textarea
            id="profile-text"
            v-model="profileText"
            rows="9"
            placeholder="例如：说话简短，常用「行」「好嘞」，少用感叹号……"
          />
        </div>

        <p v-if="profileError" class="notice" role="alert">{{ profileError }}</p>

        <div class="profile-actions">
          <button type="button" class="btn btn-primary" :disabled="savingProfile" @click="saveProfile">
            {{ savingProfile ? '保存中…' : '保存画像' }}
          </button>
        </div>
      </section>
    </template>
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
  max-width: 62ch;
  margin-top: 6px;
  color: var(--ink-soft);
  font-size: 13.5px;
  line-height: 1.65;
}

.block + .block {
  margin-top: 34px;
  padding-top: 26px;
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

.add {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr) auto;
  align-items: end;
  gap: 10px;
  max-width: 900px;
  margin-bottom: 16px;
}

.add-field {
  display: grid;
  gap: 4px;
}

.add-label {
  color: var(--ink-soft);
  font-size: 12px;
}

.add input,
.fact input {
  min-height: 44px;
  padding: 8px 12px;
  border: 1px solid var(--line);
  border-radius: 2px;
  background: var(--sheet);
  font-size: 14.5px;
}

.add input:focus,
.fact input:focus {
  border-color: var(--signal);
}

.facts {
  max-width: 900px;
  margin: 0;
  padding: 0;
  list-style: none;
  border: 1px solid var(--line);
  border-radius: var(--radius-sheet);
  background: var(--sheet);
}

.fact {
  display: grid;
  grid-template-columns: 120px minmax(0, 1fr) auto auto;
  align-items: center;
  gap: 14px;
  padding: 10px 14px;
  font-size: 14.5px;
}

.facts li + li {
  border-top: 1px dashed var(--line-soft);
}

.fact-key {
  color: var(--ink-soft);
  font-size: 13.5px;
}

.fact-value {
  overflow-wrap: anywhere;
}

.fact-meta {
  color: var(--ink-faint);
  font-size: 12px;
  white-space: nowrap;
}

.fact-actions {
  display: flex;
  align-items: center;
  gap: 2px;
}

.icon-btn {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  border: 0;
  border-radius: 2px;
  background: transparent;
  color: var(--ink-faint);
  cursor: pointer;
  transition: background-color 0.15s var(--ease), color 0.15s var(--ease);
}

.icon-btn:hover {
  background: rgba(22, 35, 44, 0.06);
  color: var(--ink);
}

.icon-btn.ok:hover {
  background: var(--u3-wash);
  color: var(--u3);
}

.icon-btn.danger:hover {
  background: var(--danger-wash);
  color: var(--danger);
}

.none {
  padding: 10px 0;
}

.loading {
  padding: 10px 0;
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

.profile-note {
  max-width: 62ch;
  margin-bottom: 12px;
  color: var(--ink-soft);
  font-size: 13px;
  line-height: 1.65;
}

.block .field {
  max-width: 760px;
}

.profile-actions {
  margin-top: 14px;
}

@media (max-width: 760px) {
  .page-title {
    font-size: 29px;
  }

  .add {
    grid-template-columns: 1fr;
  }

  .fact {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 4px 12px;
  }

  .fact-key {
    grid-column: 1;
  }

  .fact-value {
    grid-column: 1;
  }

  .fact-meta {
    grid-column: 1;
  }

  .fact-actions {
    grid-column: 2;
    grid-row: 1 / span 3;
  }
}
</style>
