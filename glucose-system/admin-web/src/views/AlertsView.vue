<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { alertsApi, patientsApi } from '../api'
import { useAuthStore } from '../stores/auth'
import AlertHandleDrawer from '../components/AlertHandleDrawer.vue'

const auth = useAuthStore()
const loading = ref(false)
const loadError = ref('')
let loadVersion = 0
const list = ref([])
const total = ref(0)
const patients = ref([])
const query = reactive({
  pageNum: 1, pageSize: 20, patientId: '', alertType: '', level: '',
  handleStatus: 'PENDING', isHistorical: ''
})
const dialog = reactive({ visible: false, alert: null, note: '', submitting: false })

const levelText = { 2: '警告', 3: '严重' }

async function load() {
  const version = ++loadVersion
  loading.value = true
  loadError.value = ''
  try {
    const data = await alertsApi.list({
      pageNum: query.pageNum, pageSize: query.pageSize,
      patientId: query.patientId || undefined,
      alertType: query.alertType || undefined,
      level: query.level || undefined,
      handleStatus: query.handleStatus || undefined,
      isHistorical: query.isHistorical || undefined
    })
    if (version !== loadVersion) return
    list.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    if (version === loadVersion) { loadError.value = e.message; list.value = []; total.value = 0 }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function loadPatients() {
  try {
    const data = await patientsApi.list({ pageNum: 1, pageSize: 100 })
    patients.value = data.records || []
  } catch { /* 医生无权时忽略 */ }
}

function openHandle(row) {
  const patient = patients.value.find(p => String(p.userId) === String(row.patientId))
  dialog.alert = { ...row, patientName: patient?.realName || patient?.username || String(row.patientId) }
  dialog.note = ''
  dialog.visible = true
}

onMounted(() => { load(); loadPatients() })
</script>

<template>
  <div class="panel">
    <div class="toolbar">
      <el-select v-model="query.patientId" clearable filterable placeholder="患者" style="width: 180px"
                 @change="query.pageNum = 1; load()">
        <el-option v-for="p in patients" :key="p.userId" :value="p.userId"
                   :label="p.realName || p.username" />
      </el-select>
      <el-select v-model="query.alertType" clearable placeholder="方向" style="width: 120px"
                 @change="query.pageNum = 1; load()">
        <el-option value="LOW" label="低血糖" />
        <el-option value="HIGH" label="高血糖" />
      </el-select>
      <el-select v-model="query.level" clearable placeholder="级别" style="width: 120px"
                 @change="query.pageNum = 1; load()">
        <el-option :value="2" label="警告" />
        <el-option :value="3" label="严重" />
      </el-select>
      <el-select v-model="query.handleStatus" clearable placeholder="处理状态" style="width: 130px"
                 @change="query.pageNum = 1; load()">
        <el-option value="PENDING" label="待处理" />
        <el-option value="HANDLED" label="已处理" />
      </el-select>
      <el-select v-model="query.isHistorical" clearable placeholder="历史" style="width: 120px"
                 @change="query.pageNum = 1; load()">
        <el-option :value="1" label="历史数据" />
        <el-option :value="0" label="实时数据" />
      </el-select>
      <div class="spacer" />
      <span class="muted" style="font-size: 13px">患者已读 ≠ 医生已处理;升级会重新置为待处理</span>
    </div>

    <div v-if="loadError" class="state-error" role="alert"><span>{{ loadError }}</span><el-button @click="load">重新加载</el-button></div>
    <el-table :empty-text="loading ? '正在加载…' : (loadError ? '加载失败，请重试' : '暂无数据')" :data="list" v-loading="loading">
      <el-table-column label="方向/级别" width="120">
        <template #default="{ row }">
          <span class="status-tag" :class="row.alertType === 'LOW' ? 'st-danger' : 'st-warning'">
            {{ row.alertType === 'LOW' ? '低' : '高' }}·{{ levelText[row.level] }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="患者" min-width="110">
        <template #default="{ row }">
          <div>{{ patients.find(p => String(p.userId) === String(row.patientId))?.realName || patients.find(p => String(p.userId) === String(row.patientId))?.username || row.patientId }}</div>
          <span class="status-tag" :class="row.dataKind === 'DEMO' ? 'st-demo' : 'st-muted'">{{ row.dataKind === 'DEMO' ? '演示数据' : row.dataKind === 'REAL' ? '真实数据' : '性质待确认' }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="dataSource" label="来源" width="80" />
      <el-table-column label="发生区间" min-width="240">
        <template #default="{ row }">
          {{ row.startAt }}<template v-if="row.endAt"> 至 {{ row.endAt }}</template>
          <template v-else> 起 · {{ row.eventStatus === 'OPEN' ? '近期仍有异常读数' : '结束时间未知' }}</template>
        </template>
      </el-table-column>
      <el-table-column label="极值 mmol/L" width="110">
        <template #default="{ row }">
          <span class="mono-num">{{ row.alertType === 'LOW' ? row.minGlucose : row.maxGlucose }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="sampleCount" label="样本数" width="80" />
      <el-table-column label="历史" width="80">
        <template #default="{ row }">
          <span v-if="row.isHistorical === 1" class="status-tag st-demo">历史</span>
          <span v-else class="muted">—</span>
        </template>
      </el-table-column>
      <el-table-column label="处理" width="100">
        <template #default="{ row }">
          <span class="status-tag" :class="row.handleStatus === 'PENDING' ? 'st-warning' : 'st-success'">
            {{ row.handleStatus === 'PENDING' ? '待处理' : '已处理' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.handleStatus === 'PENDING'" link type="primary" @click="openHandle(row)">处理</el-button>
          <el-tooltip v-else :content="`处理备注:${row.handleNote || '(无)'}`" placement="top">
            <span class="muted">已处理</span>
          </el-tooltip>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 16px; justify-content: flex-end"
                   @current-change="load" />

    <AlertHandleDrawer v-model="dialog.visible" :alert="dialog.alert" @handled="load" @refresh="load" />
  </div>
</template>
