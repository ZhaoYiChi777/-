import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw } from 'vue-router'

const routes: Array<RouteRecordRaw> = [
  {
    path: '/',
    redirect: '/portal'
  },
  // 前台：使用 Tab 布局，所有内容在 PortalLayout 内部管理
  {
    path: '/portal',
    name: 'Portal',
    component: () => import('../views/portal/PortalLayout.vue')
  },
  // 后台管理
  {
    path: '/admin',
    component: () => import('../views/admin/AdminLayout.vue'),
    children: [
      {
        path: '',
        name: 'AdminDashboard',
        component: () => import('../views/admin/Dashboard.vue')
      },
      {
        path: 'info-dynamic',
        name: 'AdminInfoDynamic',
        component: () => import('../views/admin/InfoDynamic.vue')
      },
      {
        path: 'reports',
        name: 'AdminReports',
        component: () => import('../views/admin/Reports.vue')
      },
      {
        path: 'translations',
        name: 'AdminTranslations',
        component: () => import('../views/admin/Translations.vue')
      },
      {
        path: 'charts',
        name: 'AdminCharts',
        component: () => import('../views/admin/Charts.vue')
      },
      {
        path: 'projects',
        name: 'AdminProjects',
        component: () => import('../views/admin/Projects.vue')
      },
      {
        path: 'settings',
        name: 'AdminSettings',
        component: () => import('../views/admin/Settings.vue')
      },
      {
        path: 'kg',
        name: 'AdminKG',
        component: () => import('../views/admin/KG.vue')
      },
      {
        path: 'sources',
        name: 'AdminSources',
        component: () => import('../views/admin/Sources.vue')
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes
})

export default router
