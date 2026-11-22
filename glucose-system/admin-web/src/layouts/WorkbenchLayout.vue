<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import { authApi } from '../api'
import { DataLine, User, Bell, ChatDotRound, Reading, UserFilled, SwitchButton, Odometer } from '@element-plus/icons-vue'

const router = useRouter()
const route = useRoute()
const auth = useAuthStore()

const menus = computed(() => [
  { path: '/dashboard', title: '工作台', icon: Odometer, show: true },
  { path: '/patients', title: '患者管理', icon: User, show: true },
  { path: '/glucose', title: '血糖与分析', icon: DataLine, show: true },
  { path: '/alerts', title: '预警中心', icon: Bell, show: true },
  { path: '/qa', title: '问答记录', icon: ChatDotRound, show: true },
  { path: '/kb', title: '知识库', icon: Reading, show: auth.isAdmin },
  { path: '/doctors', title: '医生账号', icon: UserFilled, show: auth.isAdmin }
])
const descriptions = {
  '/dashboard': '当前患者范围内的近期记录与待处理事项',
  '/patients': '患者档案、医生绑定与账号状态',
  '/glucose': '按来源查看记录、趋势与完整日统计',
  '/alerts': '异常事件区间、处理结果与随访备注',
  '/qa': '问答内容、引用依据与回答模式',
  '/kb': '问答资料、来源版本与检索索引',
  '/doctors': '医生账号与使用状态'
}

async function handleLogout() {
  try { await authApi.logout() } catch { /* 已失效也继续 */ }
  auth.logout()
  router.replace('/login')
}
</script>

<template>
  <div class="layout">
    <aside class="layout-sidebar">
      <div class="layout-brand">
        <svg class="brand-symbol" viewBox="0 0 64 88" fill="none" aria-hidden="true">
          <path d="M32 2C22 15 1 36 1 56C1 74 14 87 32 87S63 74 63 56C63 36 42 15 32 2Z" fill="currentColor" />
          <path d="M11 56h9l7-14 8 23 7-13h11" stroke="white" stroke-width="3.2" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <div>血糖管理<span>时序监测与智能问答</span></div>
      </div>
      <nav class="layout-nav" aria-label="工作台导航">
        <router-link v-for="m in menus.filter(x => x.show)" :key="m.path" :to="m.path"
                     class="layout-menu-item" :class="{ active: route.path === m.path }" :aria-current="route.path === m.path ? 'page' : undefined">
          <el-icon><component :is="m.icon" /></el-icon>
          <span>{{ m.title }}</span>
        </router-link>
      </nav>
      <div class="sidebar-footer">健康管理参考<br />不替代诊断与治疗</div>
    </aside>
    <div class="layout-main">
    <header class="layout-header">
      <span class="workspace-title">健康管理工作台</span>
      <div class="layout-account">
        <span class="workspace-scope">{{ auth.isDoctor ? '医生 · 绑定患者范围' : '管理员 · 全量范围' }}</span>
        <span class="account-name">{{ auth.username }}</span>
        <el-button text :icon="SwitchButton" @click="handleLogout">退出</el-button>
      </div>
    </header>
      <main class="layout-content">
        <div class="page-heading">
          <div>
            <h1 class="page-title">{{ route.meta.title || '工作台' }}</h1>
            <p class="page-subtitle">{{ descriptions[route.path] }}</p>
          </div>
        </div>
        <router-view />
      </main>
    </div>
  </div>
</template>
