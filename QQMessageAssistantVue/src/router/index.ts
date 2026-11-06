import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '@/auth/session'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/home' },
    {
      path: '/login',
      name: 'login',
      component: () => import('@/views/LoginView.vue'),
      meta: { title: '登录', public: true },
    },
    {
      path: '/home',
      name: 'home',
      component: () => import('@/views/HomeView.vue'),
      meta: { title: '首页' },
    },
    {
      path: '/memos',
      name: 'memos',
      component: () => import('@/views/MemosView.vue'),
      meta: { title: '备忘' },
    },
    {
      path: '/drafts',
      name: 'drafts',
      component: () => import('@/views/DraftsView.vue'),
      meta: { title: '草稿' },
    },
    {
      path: '/memory',
      name: 'memory',
      component: () => import('@/views/MemoryView.vue'),
      meta: { title: '记忆' },
    },
    {
      path: '/ask',
      name: 'ask',
      component: () => import('@/views/AskView.vue'),
      meta: { title: '问一句' },
    },
    {
      path: '/more',
      name: 'more',
      component: () => import('@/views/MoreView.vue'),
      meta: { title: '更多' },
    },
    {
      path: '/chat-settings',
      name: 'chat-settings',
      component: () => import('@/views/ChatSettingsView.vue'),
      meta: { title: '群设置' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { title: '设置' },
    },
    {
      path: '/password',
      name: 'password',
      component: () => import('@/views/PasswordView.vue'),
      meta: { title: '修改密码' },
    },
    {
      path: '/files',
      name: 'files',
      component: () => import('@/views/FilesView.vue'),
      meta: { title: '文件任务' },
    },
    {
      path: '/logs',
      name: 'logs',
      component: () => import('@/views/LogsView.vue'),
      meta: { title: '消息日志' },
    },
    {
      path: '/usage',
      name: 'usage',
      component: () => import('@/views/UsageView.vue'),
      meta: { title: 'Token 用量' },
    },
    {
      path: '/rules',
      name: 'rules',
      component: () => import('@/views/RulesView.vue'),
      meta: { title: '噪音过滤' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/home' },
  ],
  scrollBehavior() {
    // 滚动容器是 .screen 而不是 window，位置由 AppShell 自己记
    return false
  },
})

router.beforeEach((to) => {
  const loggedIn = Boolean(getToken())
  if (to.meta.public === true) {
    return loggedIn && to.name === 'login' ? { name: 'home' } : true
  }
  if (!loggedIn) {
    return { name: 'login', query: { redirect: to.fullPath } }
  }
  return true
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 值守台` : '值守台'
})

export default router
