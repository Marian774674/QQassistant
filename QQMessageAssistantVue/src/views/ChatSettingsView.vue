<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { Check } from 'lucide-vue-next'
import EmptyState from '@/components/EmptyState.vue'
import ErrorState from '@/components/ErrorState.vue'
import PageHead from '@/components/PageHead.vue'
import Skeleton from '@/components/Skeleton.vue'
import { listChatSettings, putChatSetting } from '@/api/chatSettings'
import type { ChatSetting } from '@/api/chatSettings'
import { getSettings, putSettings } from '@/api/settings'
import { useRefresh } from '@/composables/useRefresh'
import { useToast } from '@/composables/useToast'
import { firstSegment, fullNameHint } from '@/utils/name'

const toast = useToast()

const chats = ref<ChatSetting[]>([])
const loading = ref(true)
const error = ref('')

const digestEnabled = ref(false)
const aliases = ref('')
const focus = ref('')
/** 进首页时的整理门槛：消息条数、间隔分钟数，任一超过就跑一次。
 *  数字框的 v-model 给回来的是 number，清空是 ''，所以按 number | '' 收，
 *  存的时候再统一落回正整数——别拿字符串方法去处理它。 */
const minMessages = ref<number | ''>(100)
const minMinutes = ref<number | ''>(60)
const savingPrefs = ref(false)
const prefsSaved = ref(false)

/**
 * 只收「字段级」说明，一段一个 ?。
 *
 * 大分类（群列表 / 私聊）下面的说明是一整节的入口，读之前就得看见它在讲什么，
 * 按用户要求保持常驻；要收的是夹在输入框之间的解释文字和列表尾的补充注记。
 * 原来它们整段摊在页面上，说明和选项都是灰字，一路读下来就成了「一片字」。
 */
const hints = reactive({
  aliases: false, focus: false, threshold: false,
  autoReplyNote: false,
})
function toggleHint(key: keyof typeof hints) {
  hints[key] = !hints[key]
}

const groups = computed(() => chats.value.filter((chat) => chat.kind === 'GROUP'))
// 私聊没有群号，会话键是对方 QQ；私聊不参与整理，只有自动回复一个开关
const privates = computed(() => chats.value.filter((chat) => chat.kind === 'PRIVATE'))
const enabledCount = computed(() => groups.value.filter((chat) => chat.digestEnabled).length)

onMounted(() => {
  void load()
})

/** silent = 下拉刷新：保留当前内容，不闪骨架屏 */
async function load(silent = false) {
  if (!silent) {
    loading.value = true
    error.value = ''
  }
  try {
    const [list, settings] = await Promise.all([listChatSettings(), getSettings()])
    chats.value = list
    digestEnabled.value = settings.digest_enabled === '1'
    aliases.value = settings.owner_aliases ?? ''
    focus.value = settings.digest_focus_prompt ?? ''
    minMessages.value = toPositiveInt(settings.digest_refresh_min_messages, 100)
    minMinutes.value = toPositiveInt(settings.digest_refresh_min_minutes, 60)
  } catch (e) {
    const message = e instanceof Error ? e.message : '加载失败'
    if (silent) toast.error(message)
    else error.value = message
  } finally {
    loading.value = false
  }
}

useRefresh(() => load(true))

/** 先改界面再发请求：失败了滚回去，别让人以为已经生效 */
async function toggle(chat: ChatSetting, field: 'digestEnabled' | 'autoReplyEnabled') {
  const before = chat[field]
  chat[field] = !before
  prefsSaved.value = false
  try {
    const saved = await putChatSetting({
      chatId: chat.chatId,
      kind: chat.kind,
      chatName: chat.chatName,
      [field]: chat[field],
    })
    chat.id = saved.id ?? chat.id
  } catch (e) {
    chat[field] = before
    toast.push(e instanceof Error ? e.message : '没保存上', 'error')
  }
}

/** 输入框可能被清空成 ''，也可能被手输成小数：统一收成一个正整数再存 */
function toPositiveInt(value: string | number | '' | null | undefined, fallback: number) {
  const n = Math.trunc(Number(value))
  return Number.isFinite(n) && n > 0 ? n : fallback
}

async function savePrefs() {
  savingPrefs.value = true
  // 上一次成功留下的「已保存」先收回来：这次要是失败，按钮不能还挂着已保存
  prefsSaved.value = false
  try {
    await putSettings({
      digest_enabled: digestEnabled.value ? '1' : '0',
      owner_aliases: aliases.value.trim(),
      digest_focus_prompt: focus.value.trim(),
      digest_refresh_min_messages: String(toPositiveInt(minMessages.value, 100)),
      digest_refresh_min_minutes: String(toPositiveInt(minMinutes.value, 60)),
    })
    prefsSaved.value = true
    toast.success('整理偏好已保存')
  } catch (e) {
    toast.push(e instanceof Error ? e.message : '保存失败', 'error')
  } finally {
    savingPrefs.value = false
  }
}

/** 群名里常带「，低代码」这类后缀，界面上只留第一段；别名同理，逗号分隔只取第一个 */
function chatLabel(chat: ChatSetting) {
  // 只展示第一个称呼；后面的是给匹配用的，别把整串抖在界面上
  return (
    firstSegment(chat.aliases) ||
    firstSegment(chat.chatName) ||
    (chat.kind === 'PRIVATE' ? `QQ ${chat.chatId}` : `群 ${chat.chatId}`)
  )
}

/** 悬停/长按时给完整名字：截断只是版面上的取舍，不该让人永远看不到全名 */
function chatTitle(chat: ChatSetting) {
  const full = (chat.aliases ?? '').trim() || (chat.chatName ?? '').trim()
  return fullNameHint(chatLabel(chat), full)
}
</script>

<template>
  <div class="page">
    <PageHead
      :back="{ to: '/more', label: '更多' }"
      title="Chats"
      sub="选哪些群参与整理；偏好开关决定后端要不要每小时真的去整理。"
    />

    <ErrorState v-if="error" :message="error" hint="也可以下拉重试。" @retry="load" />
    <Skeleton v-else-if="loading" :rows="3" />

    <template v-else>
      <section class="block">
        <h2 class="block-title">整理偏好</h2>

        <div class="card card-pad prefs-card">
          <label class="switch-row">
            <span class="switch-text">
              <span class="switch-label">开启消息整理</span>
              <span class="switch-hint">
                关着的时候整段流程不跑，群里的开关也暂时不生效。
              </span>
            </span>
            <input
              v-model="digestEnabled"
              type="checkbox"
              class="switch"
              role="switch"
              :aria-checked="digestEnabled"
            />
          </label>

          <div class="field">
            <div class="field-head">
              <label for="owner-aliases">我在群里叫什么</label>
              <button
                type="button"
                class="icon-btn hint-dot"
                :aria-expanded="hints.aliases"
                aria-controls="hint-aliases"
                aria-label="「我在群里叫什么」的说明"
                @click="toggleHint('aliases')"
              >?</button>
            </div>
            <input
              id="owner-aliases"
              v-model="aliases"
              class="input"
              type="text"
              autocomplete="off"
              placeholder="小明,明哥"
            />
            <Transition name="hint">
              <p v-if="hints.aliases" id="hint-aliases" class="field-hint">
                逗号分隔。这些名字出现在消息里，那条就会被标成「在叫你」，整理时不会漏掉。
              </p>
            </Transition>
          </div>

          <div class="field">
            <div class="field-head">
              <label for="digest-focus">我想留意什么</label>
              <button
                type="button"
                class="icon-btn hint-dot"
                :aria-expanded="hints.focus"
                aria-controls="hint-focus"
                aria-label="「我想留意什么」的说明"
                @click="toggleHint('focus')"
              >?</button>
            </div>
            <textarea
              id="digest-focus"
              v-model="focus"
              class="input textarea"
              rows="3"
              placeholder="导师和实习相关的通知优先；游戏群里的活动可以忽略"
            />
            <Transition name="hint">
              <p v-if="hints.focus" id="hint-focus" class="field-hint">
                写进整理提示词。留空就按「有时间地点的通知 &gt; 要我做的事 &gt; 提到我」取舍。
              </p>
            </Transition>
          </div>

          <div class="field-pair">
            <div class="field">
              <label for="digest-min-messages">多少条消息算一段</label>
              <input
                id="digest-min-messages"
                v-model.number="minMessages"
                class="input"
                type="number"
                min="1"
                inputmode="numeric"
              />
            </div>
            <div class="field">
              <label for="digest-min-minutes">隔多久算一段（分钟）</label>
              <input
                id="digest-min-minutes"
                v-model.number="minMinutes"
                class="input"
                type="number"
                min="1"
                inputmode="numeric"
              />
            </div>
          </div>
          <!-- 这条同时解释两个数字框，所以挂在整组右下角，不塞进任何一列 -->
          <div class="pair-hint">
            <Transition name="hint">
              <p v-if="hints.threshold" id="hint-threshold" class="field-hint pair-hint-text">
                每次进首页都会看一眼：这段时间收到超过 {{ minMessages || '?' }} 条消息，或距上次整理超过
                {{ minMinutes || '?' }} 分钟，任一满足就整理一次；都没到就攒着，下次一起算。
              </p>
            </Transition>
            <button
              type="button"
              class="icon-btn hint-dot"
              :aria-expanded="hints.threshold"
              aria-controls="hint-threshold"
              aria-label="整理门槛的说明"
              @click="toggleHint('threshold')"
            >?</button>
          </div>

          <button
            type="button"
            class="btn btn-block"
            :class="prefsSaved ? 'btn-quiet' : 'btn-primary'"
            :disabled="savingPrefs"
            @click="savePrefs"
          >
            <Check v-if="prefsSaved" :size="15" :stroke-width="2.6" aria-hidden="true" />
            {{ savingPrefs ? '保存中…' : prefsSaved ? '已保存' : '保存偏好' }}
          </button>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">
          群列表
          <span class="num">{{ enabledCount }}/{{ groups.length }} 已开启整理</span>
        </h2>
        <p class="block-hint">
          列表来自机器人见过的群消息；还没配置过的群默认两个开关都是关的。
          自动回复还有一个总开关，在「设置 → 自动回复」——它关着时这里怎么开都不会说话。
        </p>

        <EmptyState
          v-if="!groups.length"
          title="机器人还没在任何群里说过话"
          hint="先把它拉进群，收到几条消息后这里就会出现。"
        />

        <ul v-else class="chats">
          <li v-for="chat in groups" :key="`${chat.kind}:${chat.chatId}`" class="chat">
            <p class="chat-name" :title="chatTitle(chat)">{{ chatLabel(chat) }}</p>
            <div class="chat-switches">
              <label class="mini">
                <input
                  type="checkbox"
                  class="switch switch-sm"
                  role="switch"
                  :checked="chat.digestEnabled"
                  :aria-checked="chat.digestEnabled"
                  @change="toggle(chat, 'digestEnabled')"
                />
                <span>整理</span>
              </label>
              <label class="mini">
                <input
                  type="checkbox"
                  class="switch switch-sm"
                  role="switch"
                  :checked="chat.autoReplyEnabled"
                  :aria-checked="chat.autoReplyEnabled"
                  @change="toggle(chat, 'autoReplyEnabled')"
                />
                <span>自动回复</span>
              </label>
            </div>
          </li>
        </ul>
        <div v-if="groups.length" class="note-row">
          <Transition name="hint">
            <p v-if="hints.autoReplyNote" id="hint-auto-reply-note" class="faint note note-text">
              自动回复管的是<span>替你说日常话</span>；备忘和查资料照常。
              别人发来的文件要这个开关开着才会处理，你自己发文件不受影响。
            </p>
          </Transition>
          <button
            type="button"
            class="icon-btn hint-dot"
            :aria-expanded="hints.autoReplyNote"
            aria-controls="hint-auto-reply-note"
            aria-label="自动回复开关的说明"
            @click="toggleHint('autoReplyNote')"
          >?</button>
        </div>
      </section>

      <section class="block">
        <h2 class="block-title">私聊</h2>
        <p class="block-hint">
          私聊只有自动回复这一个开关，不做整理。开着才替你说日常话，关闭时你说的事照记不误。
        </p>

        <EmptyState
          v-if="!privates.length"
          title="还没人私聊过机器人"
          hint="有人私聊它一句，这里就会出现。"
        />

        <ul v-else class="chats">
          <li v-for="chat in privates" :key="`PRIVATE:${chat.chatId}`" class="chat">
            <p class="chat-name" :title="chatTitle(chat)">{{ chatLabel(chat) }}</p>
            <div class="chat-switches">
              <label class="mini">
                <input
                  type="checkbox"
                  class="switch switch-sm"
                  role="switch"
                  :checked="chat.autoReplyEnabled"
                  :aria-checked="chat.autoReplyEnabled"
                  @change="toggle(chat, 'autoReplyEnabled')"
                />
                <span>自动回复</span>
              </label>
            </div>
          </li>
        </ul>
      </section>
    </template>
  </div>
</template>

<style scoped>

.block + .block {
  margin-top: 28px;
}

/* 分节标题由 style.css 的「栏目线」统一实现，这里只管块间距 */
.block-title {
  margin-bottom: 8px;
}

/*
 * 栏目线右端默认放「衬线数字」。这一处放的是「1/1 已开启整理」这种短语，
 * 衬线 14px 会把它变成一个奇怪的标题，所以退回微标签的字号与字距。
 */
.block-title .num {
  font-family: var(--font-ui);
  font-size: 10.5px;
  font-weight: 500;
  letter-spacing: 0.1em;
  color: var(--ink-3);
}

.block-hint {
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.field {
  margin-top: 16px;
}

.field-pair {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

/* 保存按钮和收起态的 ? 之间留一点气口；不用线，靠间距分层 */
.prefs-card > .btn {
  margin-top: 16px;
}

/* 标题行：标签在左、? 在右端，三个 ? 排成一条可扫的竖线 */
.field-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}

/*
 * 这四个表单标签和上面的「开启消息整理」是同一层级的标题，不该比它小半号、淡一档。
 * 以前沿用全局 .field > label（12.5px / --ink-2），摆在 14px 主色的 switch-label
 * 下面，看着像提示文字，主次就反了。字号、字重、颜色全部对齐 .switch-label。
 */
.field label {
  display: block;
  margin-bottom: 6px;
  color: var(--ink);
  font-size: 14px;
  font-weight: 500;
}

/* 标题行里的 margin 交给 .field-head，label 自己别再顶一道 */
.field .field-head label {
  margin-bottom: 0;
}

/*
 * ? 小圆钮：套 .icon-btn 只为复用全站按压波纹（ripple.ts 的 HOSTS 里有它），
 * 这里把 40px 方块收成 18px 圆。overflow:hidden 让波纹裁在圆内，不会泼到标签上。
 */
.hint-dot {
  width: 18px;
  height: 18px;
  margin-left: auto;
  flex: none;
  border: 1px solid var(--hairline);
  border-radius: 50%;
  background: var(--card);
  color: var(--ink-3);
  font-family: var(--font-ui);
  font-size: 10.5px;
  font-weight: 600;
  line-height: 1;
  /* 栏目线标题带 0.16em 字距和 uppercase，? 不能跟着被拉歪 */
  letter-spacing: 0;
  text-transform: none;
  overflow: hidden;
}

.hint-dot[aria-expanded='true'] {
  border-color: var(--accent);
  background: var(--accent-soft);
  color: var(--accent-ink);
}

/*
 * 门槛那条说明属于两个数字框整体，挂在整组下方。
 * 用 grid 让说明占满整行、? 单独右对齐在它下面——之前两者并排时，
 * 圆点会从文字里挖掉 26px，长句末尾被挤成孤零零一行。
 */
.pair-hint {
  display: grid;
  justify-items: end;
  gap: 6px;
  margin-top: 8px;
}

.pair-hint .pair-hint-text {
  justify-self: stretch;
  margin: 0;
}

.textarea {
  min-height: 84px;
  resize: vertical;
}

.field-hint {
  margin: 6px 0 0;
  color: var(--ink-3);
  font-size: 12px;
  line-height: 1.55;
}

/* 展开时 max-height 够装三行说明；收起把 margin 一起收掉，别留一条空白 */
.hint-enter-active,
.hint-leave-active {
  overflow: hidden;
  transition: opacity var(--dur) var(--ease),
    max-height var(--dur-2) var(--ease),
    margin var(--dur-2) var(--ease);
}

.hint-enter-from,
.hint-leave-to {
  opacity: 0;
  max-height: 0;
  /* .field-hint 用 margin-top、.block-hint 用 margin-bottom，两边都得收掉，
     否则收起后会留一条空白，看着像没关干净 */
  margin-top: 0;
  margin-bottom: 0;
}

.hint-enter-to,
.hint-leave-from {
  opacity: 1;
  max-height: 120px;
}

@media (prefers-reduced-motion: reduce) {
  .hint-enter-active,
  .hint-leave-active {
    transition: none;
  }
}

/* 开关本体样式在全局 style.css（设置页也用同一套） */

.chats {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.chat {
  display: flex;
  align-items: center;
  gap: 12px;
  /* 行本身也要能收缩，否则子项的 min-width: 0 传不下去 */
  min-width: 0;
  padding: 12px 14px;
  border-radius: var(--r-md);
  background: var(--card);
  box-shadow: var(--shadow-card);
}

/*
 * 名字是唯一允许被压缩的东西。
 * flex: 1 + min-width: 0 缺一不可——flex 项默认 min-width: auto，
 * 少了那一行，overflow/text-overflow 根本不生效，长名字会把开关挤变形。
 */
.chat-name {
  flex: 1 1 auto;
  min-width: 0;
  margin: 0;
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  /* 长按拖动是「滚动」，不该同时选中文字 */
  user-select: none;
}

/* 想看全名时：省略号让位给横向滚动，滚动条不占位也不显示 */
.chat-name:hover,
.chat-name:active {
  overflow-x: auto;
  text-overflow: clip;
  scrollbar-width: none;
}

.chat-name::-webkit-scrollbar {
  display: none;
}

.chat-switches {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-left: auto;
  /* 开关组绝不参与压缩：名字再长，也不许把「自动回复」挤成两行 */
  flex: 0 0 auto;
}

.mini {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: var(--ink-3);
  font-size: 11.5px;
  /* 标签自己也不许折行 */
  white-space: nowrap;
  cursor: pointer;
}

.note {
  margin: 12px 0 0;
  line-height: 1.55;
}

/*
 * 列表尾部那条说明：文字占满整行，? 单独右对齐在下方，
 * 和上半部分「门槛」那组的排法一致——两者并排会把长句从右边挖掉一块。
 * 收起时这一行只剩一个 18px 圆点，不再夹在群列表和私聊之间糊成一片。
 */
.note-row {
  display: grid;
  justify-items: end;
  gap: 6px;
  margin-top: 10px;
}

.note-row .note-text {
  justify-self: stretch;
  margin: 0;
}
</style>
