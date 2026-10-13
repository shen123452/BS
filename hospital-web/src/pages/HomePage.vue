<script setup>
import { onMounted, computed } from 'vue'
import { auth } from '../lib/auth.js'
import { get } from '../lib/http.js'
import { useLoad } from '../lib/useLoad.js'
import { slots, money } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
const patient = computed(() => auth.user?.role === 'PATIENT')
const { data, loading, error, load } = useLoad(async () => {
  if (patient.value) {
    const [profile, visits] = await Promise.all([get('/patient/profile'), get('/patient/registrations', { status:'WAITING', pageSize: 5 })])
    return { profile, visits: visits.items }
  }
  const [departments, doctors] = await Promise.all([get('/admin/departments', { pageSize: 1 }), get('/admin/doctors', { pageSize: 1 })])
  return { departments: departments.total, doctors: doctors.total }
})
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">{{ patient ? `你好，${auth.user?.displayName}` : '门诊管理' }}</h1><p>{{ patient ? '在这里安排预约、查看就诊信息。' : '维护门诊资料与号源，让患者能够预约、医生能够接诊。' }}</p></div><router-link v-if="patient" to="/patient/appointments" class="el-button el-button--primary">预约挂号</router-link></div>
  <ErrorPanel :error="error" @retry="load" />
  <div v-loading="loading" class="home-grid" aria-live="polite">
    <template v-if="patient && data">
      <section class="panel"><div class="section-heading"><h2>接下来的就诊</h2><router-link to="/patient/visits">全部就诊</router-link></div>
        <div v-if="!data.visits.length" class="empty-state"><h3>暂无待接诊预约</h3><p>选择科室与日期，查看未来七天可预约场次。</p><router-link to="/patient/appointments" class="el-button el-button--primary">查看可预约场次</router-link></div>
        <router-link v-for="visit in data.visits" :key="visit.id" :to="`/patient/visits/${visit.id}`" class="upcoming-visit"><div><strong>{{ visit.visitDate }} · {{ slots[visit.timeSlot] }}</strong><p>{{ visit.departmentName }} · {{ visit.doctorName }}</p></div><StatusBadge :status="visit.status" /><span>{{ money(visit.fee) }}</span></router-link>
      </section>
      <section class="panel profile-summary"><h2>我的档案</h2><p>{{ data.profile.name ? `${data.profile.name} · ${data.profile.age ?? '年龄未提供'}` : '请先补充就诊姓名' }}</p><p class="muted">慢病与过敏信息可以随时更新，便于就诊时医生参考。</p><router-link to="/patient/profile" class="el-button">{{ data.profile.name ? '查看与更新档案' : '完善档案' }}</router-link></section>
    </template>
    <section v-else-if="!patient && data" class="panel setup-panel"><h2>门诊准备</h2><p class="muted">按顺序准备科室、医生与排班，患者即可查看真实号源。</p><div class="setup-row"><div><h3>科室资料</h3><p>现有 {{ data.departments }} 个科室，维护名称、职责与位置。</p></div><router-link to="/admin/departments" class="el-button">管理科室</router-link></div><div class="setup-row"><div><h3>医生账号</h3><p>现有 {{ data.doctors }} 位医生，开户并关联所属科室。</p></div><router-link to="/admin/doctors" class="el-button">管理医生</router-link></div><div class="setup-row"><div><h3>门诊排班</h3><p>配置就诊日期、上午或下午、号源数量与费用。</p></div><router-link to="/admin/schedules" class="el-button el-button--primary">管理排班</router-link></div></section>
  </div>
</template>
