import { createRouter, createWebHistory } from 'vue-router'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/memos' },
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
      path: '/more',
      name: 'more',
      component: () => import('@/views/MoreView.vue'),
      meta: { title: '更多' },
    },
    {
      path: '/settings',
      name: 'settings',
      component: () => import('@/views/SettingsView.vue'),
      meta: { title: '设置' },
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
      path: '/rules',
      name: 'rules',
      component: () => import('@/views/RulesView.vue'),
      meta: { title: '拦截规则' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/memos' },
  ],
  scrollBehavior() {
    // 滚动容器是 .screen 而不是 window，位置由 AppShell 自己记
    return false
  },
})

router.afterEach((to) => {
  const title = (to.meta.title as string | undefined) ?? ''
  document.title = title ? `${title} · 值守台` : '值守台'
})

export default router
