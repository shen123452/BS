import { createRouter, createWebHistory } from 'vue-router'
import { nextTick } from 'vue'
import { auth, homes } from './lib/auth.js'
const layout = () => import('./layouts/RoleLayout.vue')
const routes = [
  { path: '/login', component: () => import('./pages/AuthPage.vue'), meta: { title: '登录' } },
  { path: '/register', component: () => import('./pages/AuthPage.vue'), meta: { title: '患者注册' } },
  { path: '/patient', component: layout, meta: { role: 'PATIENT' }, children: [
    { path: 'home', component: () => import('./pages/HomePage.vue'), meta: { title: '患者首页' } },
    { path: 'profile', component: () => import('./pages/ProfilePage.vue'), meta: { title: '我的档案' } },
    { path: 'consultations', component: () => import('./pages/ConsultationPage.vue'), meta: { title: 'AI问诊' } },
    { path: 'consultations/:sessionId/summary', component: () => import('./pages/TriagePage.vue'), meta: { title: '核对症状并导诊' } },
    { path: 'triage/:runId', component: () => import('./pages/TriagePage.vue'), meta: { title: '导诊结果' } },
    { path: 'appointments', component: () => import('./pages/BookingPage.vue'), meta: { title: '预约挂号' } },
    { path: 'visits', component: () => import('./pages/VisitsPage.vue'), meta: { title: '我的就诊' } },
    { path: 'visits/:id', component: () => import('./pages/VisitDetail.vue'), meta: { title: '就诊详情' } },
    { path: 'visits/:id/record', component: () => import('./pages/RecordPage.vue'), meta: { title: '就诊病历' } },
  ] },
  { path: '/doctor', component: layout, meta: { role: 'DOCTOR' }, children: [
    { path: 'workbench', component: () => import('./pages/VisitsPage.vue'), meta: { title: '接诊工作台' } },
    { path: 'schedules', component: () => import('./pages/SchedulesPage.vue'), meta: { title: '我的排班' } },
    { path: 'visits/:id', component: () => import('./pages/VisitDetail.vue'), meta: { title: '接诊详情' } },
    { path: 'visits/:id/record', component: () => import('./pages/RecordPage.vue'), meta: { title: '病历填写' } },
  ] },
  { path: '/admin', component: layout, meta: { role: 'ADMIN' }, children: [
    { path: 'home', component: () => import('./pages/HomePage.vue'), meta: { title: '管理首页' } },
    ...['users', 'departments', 'doctors'].map(kind => ({ path: kind, component: () => import('./pages/AdminDataPage.vue'), props: { kind }, meta: { title: { users:'用户管理', departments:'科室管理', doctors:'医生管理' }[kind] } })),
    { path: 'schedules', component: () => import('./pages/SchedulesPage.vue'), meta: { title: '排班管理' } },
    { path: 'knowledge', component: () => import('./pages/AdminKnowledgePage.vue'), meta: { title: '知识库' } },
    { path: 'ai-settings', component: () => import('./pages/AdminAiSettingsPage.vue'), meta: { title: 'AI 配置' } },
    { path: 'tools', component: () => import('./pages/AdminToolsPage.vue'), meta: { title: '工具中心' } },
    { path: 'prompts', component: () => import('./pages/AdminPromptsPage.vue'), meta: { title: 'Prompt 模板' } },
    { path: 'symptoms', component: () => import('./pages/AdminSymptomTagsPage.vue'), meta: { title: '症状标签' } },
    { path: 'agent-runs', component: () => import('./pages/AdminActivityPage.vue'), meta: { title: '导诊运行记录' } },
    { path: 'statistics', component: () => import('./pages/AdminActivityPage.vue'), props:{ statistics:true }, meta: { title: '业务统计' } },
  ] },
  { path: '/:pathMatch(.*)*', redirect: () => homes[auth.user?.role] || '/login' },
]
const router = createRouter({ history: createWebHistory(), routes, scrollBehavior: () => ({ top: 0 }) })
router.beforeEach(async to => {
  if (!to.meta.role) return true
  if (!sessionStorage.getItem('hospital.token')) return { path: '/login', query: { returnTo: to.fullPath } }
  try { await auth.refresh() } catch (e) {
    if (e.status === 401) auth.clear()
    return { path: '/login', query: { expired: e.status === 401 ? '1' : undefined, unavailable: e.status !== 401 ? '1' : undefined } }
  }
  if (auth.user.role !== to.meta.role) return homes[auth.user.role]
})
router.afterEach(async to => { document.title = `${to.meta.title || '首页'} · 医院智能导诊系统`; await nextTick(); document.querySelector('main h1')?.focus() })
export default router
