import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue')
  },
  {
    path: '/',
    component: () => import('../layout/AdminLayout.vue'),
    redirect: '/dashboard',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('../views/Dashboard.vue'),
        meta: { title: '数据概览' }
      },
      {
        path: 'galleries',
        name: 'Galleries',
        component: () => import('../views/Galleries.vue'),
        meta: { title: '画廊列表' }
      },
      {
        path: 'dedupe-reviews',
        name: 'DedupeReviews',
        component: () => import('../views/DedupeReviews.vue'),
        meta: { title: '去重审核' }
      },
      {
        path: 'visual-dedup',
        name: 'VisualDeduplication',
        component: () => import('../views/VisualDeduplication.vue'),
        meta: { title: '视觉指纹' }
      },
      {
        path: 'komga-import-reviews',
        name: 'KomgaImportReviews',
        component: () => import('../views/KomgaImportReviews.vue'),
        meta: { title: 'Komga 入库复核' }
      },
      {
        path: 'operations',
        name: 'Operations',
        component: () => import('../views/Operations.vue'),
        meta: { title: '工作流操作' }
      },
      {
        path: 'monitoring',
        name: 'Monitoring',
        component: () => import('../views/Monitoring.vue'),
        meta: { title: '监控大盘' }
      },
      {
        path: 'workflows',
        name: 'Workflows',
        component: () => import('../views/Workflows.vue'),
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
