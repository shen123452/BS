<script setup>
import { onMounted, reactive, ref } from 'vue'
import { get } from '../lib/http.js'
import { chinaDate } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import Pager from '../components/Pager.vue'

const filters = reactive({ startDate:chinaDate(-6), endDate:chinaDate(), purpose:'', status:'', page:1, pageSize:20 })
const summary = ref(null), logs = ref({ items: [], total: 0 }), loading = ref(false), error = ref(null)
const purposeNames = { PRECONSULT:'预问诊对话', AGENT:'导诊智能体', EMBED_QUERY:'查询向量', EMBED_DOCUMENT:'文档向量' }
const statusNames = { RUNNING:'运行中', SUCCEEDED:'成功', FAILED:'失败', TIMEOUT:'超时', CANCELLED:'已取消' }
const latency = value => value == null ? '—' : value < 1000 ? `${value} ms` : `${(value / 1000).toFixed(1)} s`

async function load(page = 1) {
  loading.value = true; error.value = null; filters.page = page
  try {
    const [summaryData, logData] = await Promise.all([
      get('/admin/ai-usage/summary', { startDate:filters.startDate, endDate:filters.endDate }),
      get('/admin/ai-usage/logs', { startDate:filters.startDate, endDate:filters.endDate, purpose:filters.purpose, status:filters.status, page, pageSize:filters.pageSize }),
    ])
    summary.value = summaryData; logs.value = logData
  } catch (e) { error.value = e } finally { loading.value = false }
}
onMounted(() => load())
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">AI 调用观测</h1><p>按用途统计真实模型调用：成功率只计已终结调用；供应商未返回 Token 时计入缺失率，不当作零消耗。</p></div><el-button :loading="loading" @click="load()">刷新</el-button></div>
  <ErrorPanel :error="error" @retry="load()" />
  <form class="panel statistics-filters" @submit.prevent="load()">
    <div class="statistics-date-field"><label for="usage-start">开始日期</label><el-date-picker id="usage-start" v-model="filters.startDate" value-format="YYYY-MM-DD" :clearable="false" /></div>
    <div class="statistics-date-field"><label for="usage-end">结束日期</label><el-date-picker id="usage-end" v-model="filters.endDate" value-format="YYYY-MM-DD" :clearable="false" /></div>
    <el-button class="statistics-query" type="primary" native-type="submit">查询</el-button>
  </form>
  <section class="panel">
    <h2>调用汇总（{{ summary ? `${summary.startDate} ~ ${summary.endDate}，共 ${summary.total} 次` : '—' }}）</h2>
    <div class="table-container">
      <el-table v-loading="loading" :data="summary?.purposes || []" empty-text="此日期范围暂无 AI 调用">
        <el-table-column label="用途" min-width="130"><template #default="{row}">{{ purposeNames[row.purpose] || row.purpose }}</template></el-table-column>
        <el-table-column prop="total" label="调用数" width="90" />
        <el-table-column label="成功 / 已终结" width="130"><template #default="{row}">{{ row.succeeded }} / {{ row.terminated }}</template></el-table-column>
        <el-table-column label="成功率" width="100"><template #default="{row}">{{ row.successRate == null ? '—' : `${row.successRate}%` }}</template></el-table-column>
        <el-table-column label="Token（可用）" width="130"><template #default="{row}">{{ row.tokenSum }}（{{ row.usageAvailable }}）</template></el-table-column>
        <el-table-column label="usage 缺失率" width="110"><template #default="{row}">{{ row.usageMissingRate == null ? '—' : `${row.usageMissingRate}%` }}</template></el-table-column>
        <el-table-column label="耗时 P50 / P95" width="150"><template #default="{row}">{{ latency(row.durationP50Ms) }} / {{ latency(row.durationP95Ms) }}</template></el-table-column>
        <el-table-column label="首字 P50 / P95" min-width="150"><template #default="{row}">{{ latency(row.firstTokenP50Ms) }} / {{ latency(row.firstTokenP95Ms) }}</template></el-table-column>
      </el-table>
    </div>
    <p class="muted">成功率分母为已终结调用（不含运行中）；Token 只汇总供应商返回的数据，缺失时显示缺失率。</p>
  </section>
  <section class="panel">
    <div class="filter-bar">
      <div><label for="usage-purpose">用途</label><el-select id="usage-purpose" v-model="filters.purpose" clearable placeholder="全部用途" @change="load()"><el-option v-for="[key,label] in Object.entries(purposeNames)" :key="key" :value="key" :label="label" /></el-select></div>
      <div><label for="usage-status">状态</label><el-select id="usage-status" v-model="filters.status" clearable placeholder="全部状态" @change="load()"><el-option v-for="[key,label] in Object.entries(statusNames)" :key="key" :value="key" :label="label" /></el-select></div>
      <el-button @click="load()">查询明细</el-button>
    </div>
    <div class="table-container">
      <el-table v-loading="loading" :data="logs.items" empty-text="暂无调用明细">
        <el-table-column label="用途" min-width="120"><template #default="{row}">{{ purposeNames[row.purpose] || row.purpose }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{row}">{{ statusNames[row.status] || row.status }}</template></el-table-column>
        <el-table-column prop="modelName" label="模型" min-width="140" />
        <el-table-column label="Token" width="150"><template #default="{row}">{{ row.totalTokens == null ? '未返回' : row.totalTokens }}</template></el-table-column>
        <el-table-column label="耗时" width="110"><template #default="{row}">{{ latency(row.durationMs) }}</template></el-table-column>
        <el-table-column label="首字" width="110"><template #default="{row}">{{ latency(row.firstTokenLatencyMs) }}</template></el-table-column>
        <el-table-column prop="errorCode" label="错误码" min-width="130" />
        <el-table-column prop="startedAt" label="开始时间" min-width="170" />
      </el-table>
    </div>
    <Pager :page="filters.page" :total="logs.total" @change="load" />
  </section>
</template>
