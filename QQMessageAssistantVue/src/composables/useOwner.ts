import { reactive } from 'vue'
import { getSettings, putSettings } from '@/api/settings'

/**
 * 谁是"主人"：创建备忘/事实、读风格画像都要 senderId。
 * 存在 app_setting.owner_sender_id 里，前端只做读写，不写死。
 */
const state = reactive<{ loaded: boolean; senderId: number | null }>({
  loaded: false,
  senderId: null,
})

export async function loadOwner() {
  try {
    const kv = await getSettings()
    const raw = kv['owner_sender_id']
    const parsed = raw ? Number(raw) : NaN
    state.senderId = Number.isFinite(parsed) && parsed > 0 ? parsed : null
  } catch {
    state.senderId = null
  } finally {
    state.loaded = true
  }
}

export async function saveOwner(senderId: number) {
  await putSettings({ owner_sender_id: String(senderId) })
  state.senderId = senderId
}

export function useOwner() {
  return state
}
