<script setup>
import { computed } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { HomeFilled, Calendar, Tickets, User, OfficeBuilding, FirstAidKit, SwitchButton, Reading, DataAnalysis, List } from '@element-plus/icons-vue'
import { auth, roleNames } from '../lib/auth.js'
const router = useRouter(), route = useRoute()
const patient = computed(() => auth.user?.role === 'PATIENT')
const menus = computed(() => ({
  PATIENT: [['/patient/home','首页',HomeFilled],['/patient/consultations','AI问诊',FirstAidKit],['/patient/appointments','预约挂号',Calendar],['/patient/visits','我的就诊',Tickets],['/patient/profile','我的档案',User]],
  DOCTOR: [['/doctor/workbench','接诊工作台',Tickets],['/doctor/schedules','我的排班',Calendar]],
  ADMIN: [['/admin/home','管理首页',HomeFilled],['/admin/users','用户管理',User],['/admin/departments','科室管理',OfficeBuilding],['/admin/doctors','医生管理',FirstAidKit],['/admin/schedules','排班管理',Calendar],['/admin/knowledge','知识库',Reading],['/admin/agent-runs','导诊运行记录',List],['/admin/statistics','业务统计',DataAnalysis]],
}[auth.user?.role] || []))
async function logout() { await router.replace('/login'); if (router.currentRoute.value.path === '/login') auth.clear() }
</script>
<template>
  <div class="app-shell" :class="{ 'patient-shell': patient, 'doctor-workspace': auth.user?.role === 'DOCTOR' }">
    <aside v-if="!patient" class="sidebar">
      <router-link :to="menus[0]?.[0] || '/'" class="brand"><el-icon><FirstAidKit /></el-icon><span>医院智能导诊<small>{{ roleNames[auth.user?.role] }}工作空间</small></span></router-link>
      <nav aria-label="主导航"><router-link v-for="[path,label,icon] in menus" :key="path" :to="path" :aria-current="route.path.startsWith(path) ? 'page' : undefined" :class="{ current: route.path.startsWith(path) }"><el-icon><component :is="icon" /></el-icon>{{ label }}</router-link></nav>
      <div class="sidebar-note">普通门诊<br>预约 · 接诊 · 病历</div>
    </aside>
    <div class="shell-body">
      <header class="topbar">
        <router-link v-if="patient" to="/patient/home" class="brand"><el-icon><FirstAidKit /></el-icon><span>医院智能导诊</span></router-link>
        <span v-else class="workspace-title">{{ route.meta.title }}</span>
        <nav v-if="patient" class="patient-nav" aria-label="主导航"><router-link v-for="[path,label] in menus" :key="path" :to="path" :aria-current="route.path.startsWith(path) ? 'page' : undefined" :class="{ current: route.path.startsWith(path) }">{{ label }}</router-link></nav>
        <div class="account"><span>{{ auth.user?.displayName }}<small>{{ roleNames[auth.user?.role] }}</small></span><el-button text :icon="SwitchButton" aria-label="退出登录" @click="logout">退出</el-button></div>
      </header>
      <main :class="patient ? 'patient-main' : 'workspace-main'"><router-view :key="route.path" /></main>
      <footer v-if="patient" class="patient-footer">预约信息请以实际就诊安排为准。如遇危急情况，请及时就近急诊或拨打 120。</footer>
    </div>
  </div>
</template>
