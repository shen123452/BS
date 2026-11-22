import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../utils/request'
import { useAuthStore } from '../stores/auth'

// 菜单可见性不是权限校验;所有详情仍由后端 403/404(规范 §5.2)
const routes = [
  { path: '/login', name: 'login', component: () => import('../views/LoginView.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../layouts/WorkbenchLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/DashboardView.vue'), meta: { title: '工作台' } },
      { path: 'patients', name: 'patients', component: () => import('../views/PatientsView.vue'), meta: { title: '患者管理' } },
      { path: 'glucose', name: 'glucose', component: () => import('../views/GlucoseView.vue'), meta: { title: '血糖与分析' } },
      { path: 'alerts', name: 'alerts', component: () => import('../views/AlertsView.vue'), meta: { title: '预警中心' } },
      { path: 'qa', name: 'qa', component: () => import('../views/QaView.vue'), meta: { title: '问答记录' } },
      { path: 'kb', name: 'kb', component: () => import('../views/KbView.vue'), meta: { title: '知识库', adminOnly: true } },
      { path: 'access-denied', name: 'access-denied', component: () => import('../views/AccessDeniedView.vue'), meta: { title: '访问受限' } },
      { path: 'doctors', name: 'doctors', component: () => import('../views/DoctorsView.vue'), meta: { title: '医生账号', adminOnly: true } }
    ]
  }
]

const router = createRouter({ history: createWebHistory(), routes })

router.beforeEach(to => {
  if (to.meta.public) return true
  if (!getToken()) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.meta.adminOnly && !useAuthStore().isAdmin) return { name: 'access-denied' }
  return true
})

export default router
