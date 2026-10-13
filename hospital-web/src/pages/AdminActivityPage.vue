<script setup>
import { onMounted, reactive, ref } from 'vue'
import { get } from '../lib/http.js'
import { useLoad } from '../lib/useLoad.js'
import { chinaDate } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import Pager from '../components/Pager.vue'
const props = defineProps({ statistics:Boolean })
const filters = reactive({ startDate:chinaDate(-6),endDate:chinaDate(),status:'',page:1,pageSize:20 })
const tools = ref([]), detail = ref(null), visible = ref(false), actionError = ref(null)
const names = { WAITING:'待接诊',IN_PROGRESS:'接诊中',COMPLETED:'已完成',CANCELLED:'已取消',QUEUED:'等待处理',RUNNING:'运行中',SUCCEEDED:'成功',DEGRADED:'信息或证据有限',FAILED:'失败',TIMEOUT:'超时' }
const {data,loading,error,load} = useLoad(async () => props.statistics
  ? get('/admin/statistics',{startDate:filters.startDate,endDate:filters.endDate})
  : get('/admin/agent-runs',{status:filters.status,page:filters.page,pageSize:20}))
async function directory() { if(!props.statistics){ try { tools.value=await get('/admin/agent-tools') } catch(e){actionError.value=e} } }
async function show(row) { actionError.value=null;try{detail.value=await get(`/admin/agent-runs/${row.id}`);visible.value=true}catch(e){actionError.value=e} }
async function search(page=1) {filters.page=page;await load()}
onMounted(() => Promise.all([load(),directory()]))
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">{{ statistics ? '业务统计' : '导诊运行记录' }}</h1><p>{{ statistics ? '预约按就诊日期统计，导诊按任务创建日期统计。统计值来自实际业务记录。' : '查看固定只读工具和导诊任务的实际步骤，不展示患者症状正文。' }}</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
  <ErrorPanel :error="error" @retry="load" /><ErrorPanel :error="actionError" />
  <template v-if="statistics">
    <form class="panel filter-bar" @submit.prevent="search()"><div><label for="statistics-start">开始日期</label><el-date-picker id="statistics-start" v-model="filters.startDate" value-format="YYYY-MM-DD" :clearable="false" /></div><div><label for="statistics-end">结束日期</label><el-date-picker id="statistics-end" v-model="filters.endDate" value-format="YYYY-MM-DD" :clearable="false" /></div><el-button type="primary" native-type="submit">查询统计</el-button></form>
    <div class="activity-columns" v-loading="loading"><section v-for="[key,title] in [['registrations','预约状态'],['agents','导诊状态']]" :key="key" class="panel"><h2>{{ title }}</h2><dl class="statistic-list"><div v-for="row in data?.[key] || []" :key="row.status"><dt>{{ names[row.status] || row.status }}</dt><dd>{{ row.count }} 次</dd></div></dl><p v-if="!data?.[key]?.length" class="muted">此日期范围暂无记录。</p></section></div>
    <section class="panel"><h2>科室预约量</h2><div class="table-container"><el-table :data="data?.departments || []" empty-text="此日期范围暂无预约"><el-table-column prop="departmentName" label="科室" min-width="180" /><el-table-column prop="count" label="预约次数（含取消）" min-width="160" /></el-table></div></section>
    <section class="panel"><h2>每日预约量</h2><div class="table-container"><el-table :data="data?.daily || []" empty-text="此日期范围暂无预约"><el-table-column prop="date" label="就诊日期" min-width="180" /><el-table-column prop="count" label="预约次数（含取消）" min-width="160" /></el-table></div></section>
  </template>
  <template v-else>
    <section class="panel"><h2>三个固定只读工具</h2><div v-for="tool in tools" :key="tool.name" class="tool-description"><strong>{{ tool.name }}</strong><p>{{ tool.description }}</p><small class="muted">只读查询</small></div></section>
    <section class="panel"><div class="filter-bar"><div><label for="run-status">运行状态</label><el-select id="run-status" v-model="filters.status" clearable placeholder="全部状态" @change="search()"><el-option v-for="state in ['QUEUED','RUNNING','SUCCEEDED','DEGRADED','FAILED','CANCELLED']" :key="state" :value="state" :label="names[state]" /></el-select></div></div><div class="table-container"><el-table v-loading="loading" :data="data?.items || []" empty-text="暂无导诊运行"><el-table-column prop="id" label="运行编号" min-width="120" /><el-table-column label="状态" min-width="150"><template #default="{row}">{{ names[row.status] }}</template></el-table-column><el-table-column prop="actualRounds" label="模型轮数" width="110" /><el-table-column prop="actualToolCalls" label="工具次数" width="110" /><el-table-column prop="errorMessage" label="处理说明" min-width="220" /><el-table-column label="操作" width="120"><template #default="{row}"><el-button size="small" @click="show(row)">查看步骤</el-button></template></el-table-column></el-table></div><Pager :page="filters.page" :total="data?.total || 0" @change="search" /></section>
    <el-dialog v-model="visible" :title="`运行 ${detail?.id || ''} 的实际步骤`" width="680px"><ol class="agent-timeline"><li v-for="step in detail?.steps || []" :key="step.sequenceNo"><strong>{{ step.publicLabel }}</strong><span>{{ names[step.status] || step.status }}<small v-if="step.durationMs != null"> · {{ (step.durationMs / 1000).toFixed(1) }} 秒</small></span></li></ol><p v-if="!detail?.steps?.length" class="muted">暂无执行步骤。</p></el-dialog>
  </template>
</template>
