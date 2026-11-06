import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import './style.css'
import { installPressRipple } from './utils/ripple'
import { installTapOrigin } from './utils/tapOrigin'

createApp(App).use(router).mount('#app')

// 全站按压波纹：一次委托覆盖所有 .btn / .row / .stat / .seg-item / .tab
installPressRipple()

// 记下每次按下的位置，切页时新页面就从那一点圆形扩散开
installTapOrigin()

/**
 * 首屏只下载当前页。等浏览器空闲下来，再把其余路由的 chunk 预热掉，
 * 之后点底部 tab 就是本地命中，不用再等一次网络往返。
 */
const warmRoutes = [
  () => import('./views/AskView.vue'),
  () => import('./views/DraftsView.vue'),
  () => import('./views/MoreView.vue'),
  () => import('./views/MemoryView.vue'),
  () => import('./views/SettingsView.vue'),
  () => import('./views/ChatSettingsView.vue'),
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
