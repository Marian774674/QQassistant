<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import Segmented from '@/components/Segmented.vue'
import { getCaptcha, getQqStatus, login, sendQqCode, verifyQqCode } from '@/api/auth'
import { setToken } from '@/auth/session'

type Mode = 'qq' | 'password'

const router = useRouter()
const route = useRoute()

// 改完密码被送回登录页时，直接摆在密码栏，省得用户再切一次
const changed = computed(() => route.query.changed === '1')
const mode = ref<Mode>(changed.value ? 'password' : 'qq')
const modes: Array<{ key: Mode; label: string }> = [
  { key: 'qq', label: 'QQ 登录' },
  { key: 'password', label: '密码登录' },
]
const loading = ref(false)
const error = ref('')

// ── QQ 登录 ──────────────────────────────────────────────────────────────
const QQ_KEY = 'qq-assistant.login.qq'
const qq = ref(localStorage.getItem(QQ_KEY) ?? '')
const qqCode = ref('')
const qqBound = ref(true)
const qqMasked = ref('')
const statusLoaded = ref(false)
const sending = ref(false)
const cooldown = ref(0)
const codeSent = ref(false)
let timer: ReturnType<typeof setInterval> | null = null

const qqValid = computed(() => /^\d{5,12}$/.test(qq.value.trim()))

// ── 密码登录 ──────────────────────────────────────────────────────────────
const username = ref('admin')
const password = ref('')
const captchaCode = ref('')
const captchaId = ref('')
const captchaImage = ref('')
const captchaLoading = ref(false)

onMounted(async () => {
  try {
    const status = await getQqStatus()
    qqBound.value = status.bound
    qqMasked.value = status.maskedQq
    // 没绑定主人号时 QQ 登录必然失败，直接把密码登录摆在前面，少一次白跑
    if (!status.bound) mode.value = 'password'
  } catch {
    // 状态拿不到不影响密码登录，QQ 栏会自己给出提示
    qqBound.value = false
  } finally {
    statusLoaded.value = true
  }
})

// 切到密码登录才去取图形验证码：默认停在 QQ 栏，没必要先打一次接口
watch(
  mode,
  (next) => {
    if (next === 'password' && !captchaImage.value) void refreshCaptcha()
    error.value = ''
  },
  { immediate: true },
)

onUnmounted(stopTimer)

async function refreshCaptcha() {
  captchaLoading.value = true
  try {
    const captcha = await getCaptcha()
    captchaId.value = captcha.captchaId
    captchaImage.value = captcha.image
    captchaCode.value = ''
  } catch (e) {
    error.value = e instanceof Error ? e.message : '验证码加载失败'
  } finally {
    captchaLoading.value = false
  }
}

function startCooldown(seconds: number) {
  cooldown.value = Math.max(0, Math.floor(seconds))
  stopTimer()
  if (cooldown.value === 0) return
  timer = setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0) stopTimer()
  }, 1000)
}

function stopTimer() {
  if (timer !== null) {
    clearInterval(timer)
    timer = null
  }
}

async function requestCode() {
  if (sending.value || cooldown.value > 0) return
  error.value = ''
  if (!qqValid.value) {
    error.value = 'QQ 号应该是 5–12 位数字'
    return
  }

  sending.value = true
  try {
    const result = await sendQqCode(qq.value.trim())
    localStorage.setItem(QQ_KEY, qq.value.trim())
    startCooldown(result.retryAfterSeconds)
    if (result.sent) {
      codeSent.value = true
      qqCode.value = ''
    } else {
      error.value = `验证码刚发过，${result.retryAfterSeconds} 秒后可重发`
    }
  } catch (e) {
    error.value = e instanceof Error ? e.message : '发送失败'
  } finally {
    sending.value = false
  }
}

/** 六位输满就自动提交：少点一次按钮；输错还能改，码不会因为一次失败就作废 */
function onCodeInput(event: Event) {
  const input = event.target as HTMLInputElement
  const digits = input.value.replace(/\D/g, '').slice(0, 6)
  qqCode.value = digits
  input.value = digits
  if (digits.length === 6) void submitQq()
}

async function submitQq() {
  if (loading.value) return
  error.value = ''
  if (!qqValid.value) {
    error.value = 'QQ 号应该是 5–12 位数字'
    return
  }
  if (qqCode.value.length !== 6) {
    error.value = '请输入机器人私聊发给你的 6 位验证码'
    return
  }

  loading.value = true
  try {
    const result = await verifyQqCode(qq.value.trim(), qqCode.value)
    await finish(result.token)
  } catch (e) {
    error.value = e instanceof Error ? e.message : '登录失败'
    qqCode.value = ''
  } finally {
    loading.value = false
  }
}

async function submitPassword() {
  if (loading.value) return
  error.value = ''
  if (!username.value.trim() || !password.value || !captchaCode.value.trim()) {
    error.value = '请填写账号、密码和验证码'
    return
  }

  loading.value = true
  try {
    const result = await login({
      username: username.value.trim(),
      password: password.value,
      captchaId: captchaId.value,
      captchaCode: captchaCode.value.trim(),
    })
    await finish(result.token)
  } catch (e) {
    const message = e instanceof Error ? e.message : '登录失败'
    // 后端无论校验码对错都会消费掉这张验证码，失败后必须换一张
    await refreshCaptcha()
    error.value = message
  } finally {
    loading.value = false
  }
}

async function finish(token: string) {
  setToken(token)
  const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/home'
  await router.replace(redirect)
}
</script>

<template>
  <main class="login">
    <section class="panel">
      <header class="head">
        <p class="eyebrow">QQ MESSAGE ASSISTANT</p>
        <h1>值守台</h1>
        <p class="sub">用 QQ 收一条验证码就能进，不用记密码。</p>
      </header>

      <Segmented v-model="mode" class="tabs" label="登录方式" :items="modes" />

      <div v-if="changed" class="notice changed" role="status">
        密码已更新，请用新密码重新登录。QQ 验证码登录不受影响。
      </div>

      <form v-if="mode === 'qq'" class="form" @submit.prevent="submitQq">
        <div v-if="statusLoaded && !qqBound" class="notice" role="status">
          还没有绑定主人 QQ 号。先用密码登录，在「设置 → 我的 QQ 号」里填上，之后就能用 QQ 登录了。
        </div>

        <template v-else>
          <label class="field">
            <span class="label">QQ 号</span>
            <div class="code-row">
              <input
                v-model="qq"
                class="input"
                inputmode="numeric"
                autocomplete="username"
                placeholder="你的 QQ 号"
              />
              <button
                type="button"
                class="send"
                :disabled="sending || cooldown > 0 || !qqValid"
                @click="requestCode"
              >
                {{ cooldown > 0 ? `${cooldown}s` : sending ? '发送中' : '发验证码' }}
              </button>
            </div>
          </label>

          <label class="field">
            <span class="label">验证码</span>
            <input
              :value="qqCode"
              class="input code-input"
              inputmode="numeric"
              autocomplete="one-time-code"
              maxlength="6"
              placeholder="6 位数字"
              @input="onCodeInput"
            />
          </label>

          <p class="hint">
            <template v-if="codeSent">
              已发到 {{ qqMasked || '你的 QQ' }} 的私聊，5 分钟内有效。
            </template>
            <template v-else>验证码由机器人私聊发给你，不会发到群里。</template>
          </p>
        </template>

        <p v-if="error" class="error" role="alert">{{ error }}</p>

        <button class="btn btn-primary btn-block" type="submit" :disabled="loading || !qqBound">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>

      <form v-else class="form" @submit.prevent="submitPassword">
        <label class="field">
          <span class="label">账号</span>
          <input v-model="username" class="input" autocomplete="username" placeholder="admin" />
        </label>

        <label class="field">
          <span class="label">密码</span>
          <input
            v-model="password"
            class="input"
            type="password"
            autocomplete="current-password"
            placeholder="••••••"
          />
        </label>

        <label class="field">
          <span class="label">验证码</span>
          <div class="captcha-row">
            <input
              v-model="captchaCode"
              class="input"
              maxlength="4"
              autocomplete="off"
              placeholder="4 位验证码"
            />
            <button
              type="button"
              class="captcha"
              :disabled="captchaLoading"
              aria-label="刷新验证码"
              title="看不清？点一下换一张"
              @click="refreshCaptcha()"
            >
              <img v-if="captchaImage" :src="captchaImage" alt="验证码" />
              <span v-else>{{ captchaLoading ? '加载中' : '刷新' }}</span>
            </button>
          </div>
        </label>

        <p v-if="error" class="error" role="alert">{{ error }}</p>

        <button class="btn btn-primary btn-block" type="submit" :disabled="loading">
          {{ loading ? '登录中…' : '登录' }}
        </button>
      </form>

      <p class="foot">本地单实例 · 会话 30 分钟无操作后失效</p>
    </section>
  </main>
</template>

<style scoped>
.login {
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px;
  background:
    radial-gradient(120% 80% at 50% 0%, var(--bg-top) 0%, transparent 60%),
    var(--bg);
}

.panel {
  width: min(100%, 380px);
  padding: 34px 30px 24px;
  background: var(--card);
  border: 1px solid var(--hairline);
  border-radius: var(--r-sheet);
  box-shadow: 0 18px 50px rgba(46, 38, 26, 0.08);
}

.head {
  margin-bottom: 22px;
}

.eyebrow {
  margin: 0 0 12px;
  color: var(--ink-3);
  font-size: 9.5px;
  font-weight: 600;
  letter-spacing: 0.2em;
}

h1 {
  margin: 0;
  font-family: var(--font-display);
  font-size: 30px;
  font-weight: 400;
  line-height: 1.1;
  letter-spacing: -0.02em;
}

.sub {
  margin: 8px 0 0;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.5;
}

.tabs {
  margin-bottom: 22px;
}

.form {
  display: grid;
  gap: 15px;
}

.field {
  display: grid;
  gap: 7px;
}

.label {
  color: var(--ink-2);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.08em;
}

.code-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 96px;
  gap: 10px;
}

/* 发码是「次要动作」：描边而不是实心，避免和底部登录抢注意力 */
.send {
  height: 42px;
  padding: 0 10px;
  color: var(--accent-ink);
  font-size: 12.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  background: var(--card);
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  cursor: pointer;
  transition: background 0.15s ease, border-color 0.15s ease;
}

.send:active:not(:disabled) {
  background: var(--accent-soft);
  border-color: var(--accent-soft);
}

.send:disabled {
  color: var(--ink-3);
  cursor: not-allowed;
  opacity: 0.7;
}

.code-input {
  font-family: var(--font-display);
  font-size: 20px;
  letter-spacing: 0.42em;
  text-indent: 0.42em;
  text-align: center;
}

.hint {
  margin: -4px 0 0;
  color: var(--ink-3);
  font-size: 11.5px;
  line-height: 1.55;
}

.notice {
  padding: 12px 14px;
  color: var(--ink-2);
  font-size: 12px;
  line-height: 1.6;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
}

.notice.changed {
  margin-bottom: 14px;
  color: var(--ok);
  border-color: var(--ok);
}

/* 后端 LineCaptcha 是 120×40；列宽给它留够，否则字符会被挤掉 */
.captcha-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 120px;
  gap: 10px;
}

.captcha {
  display: grid;
  place-items: center;
  height: 42px;
  padding: 0;
  overflow: hidden;
  color: var(--ink-3);
  font-size: 11px;
  background: var(--surface);
  border: 1px solid var(--hairline);
  border-radius: var(--r-sm);
  cursor: pointer;
}

/*
 * 必须 contain，不能 cover：cover 会按高度放大 120×40 的图再居中裁掉左右，
 * 4 位验证码的头尾字符就没了，用户根本认不出来。即使后端以后改了图片尺寸，
 * contain 也只会等比缩小留点白，不会吃掉字符。
 */
.captcha img {
  display: block;
  width: 100%;
  height: 100%;
  object-fit: contain;
}

.captcha:disabled {
  cursor: wait;
  opacity: 0.6;
}

.error {
  margin: 0;
}

.foot {
  margin: 22px 0 0;
  color: var(--ink-3);
  font-size: 9.5px;
  letter-spacing: 0.06em;
  text-align: center;
}
</style>
