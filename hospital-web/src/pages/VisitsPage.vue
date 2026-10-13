<script setup>
import { reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '../lib/auth.js'
import { get } from '../lib/http.js'
import { useLoad } from '../lib/useLoad.js'
import { chinaDate, slots, money } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Pager from '../components/Pager.vue'
const route = useRoute(), router = useRouter(), doctor = computed(() => auth.user.role === 'DOCTOR')
const base = doctor.value ? '/doctor' : '/patient'
const filters = reactive({ status: route.query.status || '', date: route.query.date || chinaDate(), page: Number(route.query.page) || 1, pageSize: 20 })
const { data, loading, error, load } = useLoad(() => get(`${base}/registrations`, { ...filters, date: doctor.value ? filters.date : undefined }))
const { data:counts, error:countsError, load:loadCounts } = useLoad(async () => {
  const states = ['WAITING','IN_PROGRESS','COMPLETED']
  const pages = await Promise.all(states.map(status => get('/doctor/registrations',{date:filters.date,status,pageSize:1})))
  return Object.fromEntries(states.map((status,i)=>[status,pages[i].total]))
})
async function refresh() { await Promise.all([load(), ...(doctor.value ? [loadCounts()] : [])]) }
async function search(page = 1) { filters.page = page; router.replace({ query: { status: filters.status || undefined, date: doctor.value ? filters.date : undefined, page: page > 1 ? page : undefined } }); await refresh() }
onMounted(refresh)
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">{{ doctor ? '接诊工作台' : '我的就诊' }}</h1><p>{{ doctor ? '查看名下预约；仅可在实际就诊当天开始接诊。' : '查看预约进度与历史就诊，已完成就诊可查看医生提交的病历。' }}</p></div><router-link v-if="!doctor" to="/patient/appointments" class="el-button el-button--primary">预约挂号</router-link></div>
  <section class="panel"><div class="filter-bar"><div v-if="doctor"><label for="queueDate">就诊日期</label><el-date-picker id="queueDate" v-model="filters.date" type="date" value-format="YYYY-MM-DD" :clearable="false" @change="search()" /></div><div><label for="visitStatus">就诊状态</label><el-select id="visitStatus" v-model="filters.status" placeholder="全部状态" @change="search()"><el-option label="全部状态" value="" /><el-option label="待接诊" value="WAITING" /><el-option label="接诊中" value="IN_PROGRESS" /><el-option label="已完成" value="COMPLETED" /><el-option label="已取消" value="CANCELLED" /></el-select></div><el-button :loading="loading" @click="refresh">刷新列表</el-button></div>
  <template v-if="doctor"><ErrorPanel :error="countsError" @retry="loadCounts" /><div v-if="counts" class="queue-summary" aria-live="polite"><span>{{ filters.date }} 本人接诊</span><strong>待接诊 {{ counts.WAITING }}</strong><strong>接诊中 {{ counts.IN_PROGRESS }}</strong><strong>已完成 {{ counts.COMPLETED }}</strong></div></template>
  <ErrorPanel :error="error" @retry="load" />
  <div v-loading="loading" class="visit-list"><div v-if="data && !data.items.length" class="empty-state"><h2>{{ doctor ? '该日期暂无匹配的预约' : '暂无匹配的就诊记录' }}</h2><p>{{ doctor ? '可调整日期或状态后查看。' : '可以调整筛选，或先预约一个可用场次。' }}</p></div><article v-for="v in data?.items || []" :key="v.id" class="visit-row"><div class="visit-identity"><strong>{{ doctor ? v.patientName : v.departmentName }}</strong><span>{{ doctor ? v.departmentName : v.doctorName }}</span><small>挂号号 {{ v.registrationNo }}</small><template v-if="doctor"><span class="queue-symptom">就诊说明：{{ v.symptomDescription || '未提供' }}</span><small>年龄与健康信息：查看接诊详情中的患者档案</small></template></div><div class="visit-date"><strong>{{ v.visitDate }}</strong><span>{{ slots[v.timeSlot] }}</span></div><StatusBadge :status="v.status" /><span>{{ money(v.fee) }}</span><router-link :to="`${base}/visits/${v.id}`" class="el-button">{{ doctor && v.status === 'IN_PROGRESS' ? '继续接诊' : '查看详情' }}</router-link></article></div>
  <Pager :page="filters.page" :total="data?.total || 0" @change="search" /></section>
</template>
