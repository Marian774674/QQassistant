<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { changePassword, logout } from '@/api/auth'
import { clearToken } from '@/auth/session'
import PageHead from '@/components/PageHead.vue'
import { useToast } from '@/composables/useToast'

const router = useRouter()
const toast = useToast()

type Field = 'old' | 'new' | 'confirm' | ''

const oldPassword = ref('')
const newPassword = ref('')
const confirmPassword = ref('')
const error = ref('')
const errorField = ref<Field>('')
const saving = ref(false)

function fail(message: string, field: Field) {
  error.value = message
  errorField.value = field
}

function clearError() {
  error.value = ''
  errorField.value = ''
}

/**
 * 改的是「密码登录」这个兜底入口的口令，不是 QQ 密码，也不影响 QQ 验证码登录。
 * 成功后必须把当前会话一并作废：只清浏览器里的 token，Redis 那把还留着 30 分钟。
 */
async function submit() {
  if (saving.value) return
  clearError()

  const oldPwd = oldPassword.value
  const newPwd = newPassword.value
  if (!oldPwd) return fail('请先输入当前密码', 'old')
  if (newPwd.length < 6) return fail('新密码至少 6 位', 'new')
  if (newPwd !== confirmPassword.value) return fail('两次输入的新密码不一致', 'confirm')
  if (newPwd === oldPwd) return fail('新密码不能和当前密码一样', 'new')

  saving.value = true
  try {
    await changePassword(oldPwd, newPwd)
    // 让服务端这把会话也失效；会话本来就过期了也无所谓，本地照样清
    try {
      await logout()
    } catch {
      // 忽略：不影响「本地退出 + 回登录页」
    }
    clearToken()
    toast.success('密码已更新，请用新密码重新登录')
    await router.replace({ name: 'login', query: { changed: '1' } })
  } catch (e) {
    // 后端失败多半是「原密码错误」，把焦点暗示留在第一栏
    fail(e instanceof Error ? e.message : '修改失败，请重试', 'old')
    // 保留旧密码让用户改错字；新密码清空避免将错就错
    newPassword.value = ''
    confirmPassword.value = ''
  } finally {
    saving.value = false
  }
}
</script>

<template>
  <div class="page">
    <PageHead :back="{ to: '/more', label: '更多' }" title="Password" />

    <section class="block">
      <h2 class="block-title">修改登录密码</h2>
      <p class="block-hint">
        这是机器人掉线时用来兜底的「账号 + 密码 + 图形验证码」口令，不是 QQ 密码。
        平时用 QQ 验证码登录，改这里不影响它。
      </p>

      <form class="card card-pad" @submit.prevent="submit">
        <div class="field">
          <label for="old-password">当前密码</label>
          <input
            id="old-password"
            v-model="oldPassword"
            class="input"
            type="password"
            autocomplete="current-password"
            maxlength="128"
            placeholder="现在用的登录密码"
            :aria-invalid="errorField === 'old'"
            :disabled="saving"
            @input="clearError"
          />
        </div>

        <div class="field">
          <label for="new-password">新密码</label>
          <input
            id="new-password"
            v-model="newPassword"
            class="input"
            type="password"
            autocomplete="new-password"
            maxlength="128"
            placeholder="至少 6 位"
            :aria-invalid="errorField === 'new'"
            :disabled="saving"
            @input="clearError"
          />
        </div>

        <div class="field">
          <label for="confirm-password">再输一次新密码</label>
          <input
            id="confirm-password"
            v-model="confirmPassword"
            class="input"
            type="password"
            autocomplete="new-password"
            maxlength="128"
            placeholder="确认没有打错"
            :aria-invalid="errorField === 'confirm'"
            :disabled="saving"
            @input="clearError"
          />
          <p v-if="error" class="error" role="alert">{{ error }}</p>
          <p v-else class="field-hint">
            改完会用新密码重新登录一次。其它已打开的会话会在 30 分钟内自然过期。
          </p>
        </div>

        <button type="submit" class="btn btn-block btn-primary save" :disabled="saving">
          {{ saving ? '修改中…' : '确认修改' }}
        </button>
      </form>
    </section>

    <section class="block">
      <h2 class="block-title">关于初始密码</h2>
      <p class="block-hint">
        首次部署如果没设 <span class="mono">AUTH_INITIAL_PASSWORD</span>，后端会生成一串随机临时密码，
        只在第一次启动日志里打印一次。哈希写进数据库之后，再改这个环境变量就不生效了——
        在这里改才是正路。
      </p>
    </section>

    <p class="version">值守台 · 本地单实例 · 数据只存在你自己的机器上</p>
  </div>
</template>

<style scoped>
.block + .block {
  margin-top: 28px;
}

.block-title {
  margin-bottom: 8px;
}

.block-hint {
  margin-bottom: 10px;
  color: var(--ink-3);
  font-size: 12.5px;
  line-height: 1.6;
}

.field + .field {
  margin-top: 16px;
}

.save {
  margin-top: 18px;
}

.version {
  margin-top: 30px;
  color: var(--ink-3);
  font-size: 11.5px;
  text-align: center;
}
</style>
