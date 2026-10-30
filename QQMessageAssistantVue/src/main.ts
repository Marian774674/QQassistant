import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'

createApp(App).use(router).mount('#app')

/**
 * 首屏只下载当前页。等浏览器空闲下来，再把其余路由的 chunk 预热掉，
 * 之后点底部 tab 就是本地命中，不用再等一次网络往返。
 */
const warmRoutes = [
  () => import('./views/DraftsView.vue'),
  () => import('./views/MemoryView.vue'),
  () => import('./views/MoreView.vue'),
  () => import('./views/SettingsView.vue'),
  () => import('./views/FilesView.vue'),
  () => import('./views/LogsView.vue'),
  () => import('./views/RulesView.vue'),
]

function warm() {
  for (const load of warmRoutes) void load().catch(() => {})
}

if (typeof window.requestIdleCallback === 'function') {
  window.requestIdleCallback(warm, { timeout: 2500 })
} else {
  window.setTimeout(warm, 1200)
}
