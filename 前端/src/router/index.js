/* 集中定义页面地址和菜单信息；没有登录凭据时引导用户登录。 */
import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../features/auth/pages/Login.vue')
  },
  {
    path: '/',
    component: () => import('../layout/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../features/dashboard/pages/Dashboard.vue'),
        meta: { title: '数据概览' }
      },
      {
        path: 'galleries',
        name: 'Galleries',
        component: () => import('../features/galleries/pages/Galleries.vue'),
        meta: { title: '画廊列表' }
      },
      {
        path: 'collections',
        name: 'Collections',
        component: () => import('../features/collections/pages/Collections.vue'),
        meta: { title: '画廊合集' }
      },
      {
        path: 'dedupe-reviews',
        name: 'DedupeReviews',
        component: () => import('../features/deduplication/pages/DedupeReviews.vue'),
        meta: { title: '去重审核' }
      },
      {
        path: 'visual-dedup',
        name: 'VisualDeduplication',
        component: () => import('../features/deduplication/pages/VisualDeduplication.vue'),
        meta: { title: '视觉指纹' }
      },
      {
        path: 'tag-network',
        name: 'TagNetwork',
        component: () => import('../features/tags/pages/TagNetwork.vue'),
        meta: { title: '关系图谱' }
      },
      {
        path: 'ai-settings',
        name: 'AiSettings',
        component: () => import('../features/settings/pages/AiSettings.vue'),
        meta: { title: 'AI 设置' }
      },
      {
        path: 'archive-sync',
        name: 'ArchiveSync',
        component: () => import('../features/archive/pages/ArchiveSync.vue'),
        meta: { title: '群晖归档同步' }
      },
      {
        path: 'komga-import-reviews',
        name: 'KomgaImportReviews',
        component: () => import('../features/komga/pages/KomgaImportReviews.vue'),
        meta: { title: 'Komga 入库复核' }
      },
      {
        path: 'operations',
        name: 'Operations',
        component: () => import('../features/workflows/pages/Operations.vue'),
        meta: { title: '工作流操作' }
      },
      {
        path: 'rss-subscriptions',
        name: 'RssSubscriptions',
        component: () => import('../features/rss/pages/RssSubscriptions.vue'),
        meta: { title: 'RSS 订阅' }
      },
      {
        path: 'monitoring',
        name: 'Monitoring',
        component: () => import('../features/monitoring/pages/Monitoring.vue'),
        meta: { title: '监控大盘' }
      },
      {
        path: 'workflows',
        name: 'Workflows',
        component: () => import('../features/workflows/pages/Workflows.vue'),
        meta: { title: '流程监控' }
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.path !== '/login' && !token) {
    next('/login')
  } else {
    next()
  }
})

export default router
