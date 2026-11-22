<script setup>
import { ref, reactive, onMounted, watch, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Document } from '@element-plus/icons-vue'
import { patientsApi, glucoseApi } from '../api'
import { completeDayRange, analysisWindow, formatLocalDate } from '../utils/analysisDates'
import GlucoseTrend from '../components/GlucoseTrend.vue'
import AgpProfile from '../components/AgpProfile.vue'
import { getToken } from '../utils/request'

const loading = ref(false)
const errorMsg = ref('')
let requestVersion = 0
const patients = ref([])
const patientId = ref('')
const agp = ref(null), agpLoading = ref(false), agpError = ref('')
let agpVersion = 0
const filters = reactive({
  dataSource: 'CGM',
  dateRange: completeDayRange(14),
  summary: null, daily: [], records: [], recordsTotal: 0,
  recordsPage: 1, trend: null
})

const params = extra => {
  const window = analysisWindow(filters.dateRange)
  return window ? { patientId: patientId.value, ...window, dataSource: filters.dataSource, ...extra } : null
}
const disableIncompleteDay = date => formatLocalDate(date) >= formatLocalDate(new Date())

const statusMap = {
  QUALIFIED: ['st-success', '达到充分性门槛'],
  DESCRIPTIVE_ONLY: ['st-info', '仅供描述性统计'],
  INSUFFICIENT_CGM: ['st-warning', 'CGM 数据量不足'],
  NO_DATA: ['st-muted', '暂无有效记录']
}
const reasonText = {
  SMBG_NOT_TIME_BASED: 'SMBG 为稀疏测量点,不适用时间占比',
  TARGET_NOT_CONFIGURED: '部分测量类型未配置目标',
  SHORT_WINDOW: '窗口不足 14 个完整日',
  LOW_COVERAGE: '覆盖率不足 70%',
  INSUFFICIENT_SAMPLES: '样本量不足',
  INSUFFICIENT_SLOT_DAYS: '部分半小时时段覆盖不足7个不同日期'
}

async function loadPatients() {
  try {
    const data = await patientsApi.list({ pageNum: 1, pageSize: 100 })
    patients.value = data.records || []
    if (patients.value.length && !patientId.value) patientId.value = patients.value[0].userId
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function loadAll() {
  const version = ++requestVersion
  agpVersion++; agp.value = null; agpError.value = ''; agpLoading.value = false
  if (!patientId.value) { loading.value = false; filters.summary = null; return }
  const query = params({})
  if (!query) {
    filters.summary = null
    filters.trend = null
    filters.daily = []
    filters.records = []
    filters.recordsTotal = 0
    loading.value = false
    return
  }
  loading.value = true
  errorMsg.value = ''
  filters.summary = null
  try {
    const [summary, daily, trend, records] = await Promise.all([
      glucoseApi.summary(query), glucoseApi.daily(query), glucoseApi.trend(query),
      glucoseApi.records({ ...query, pageNum: 1, pageSize: 20 })
    ])
    if (version !== requestVersion) return
    filters.summary = summary
    filters.daily = daily
    filters.trend = trend
    filters.records = records.records || []
    filters.recordsTotal = Number(records.total || 0)
    if (filters.dataSource === 'CGM') loadAgp(query)
  } catch (e) {
    if (version === requestVersion) errorMsg.value = e.message
  } finally {
    if (version === requestVersion) loading.value = false
  }
}

async function loadAgp(query = params({})) {
  if (!query || !glucoseApi.agp) return
  const version = ++agpVersion
  agpLoading.value = true; agpError.value = ''
  try { const data = await glucoseApi.agp(query); if (version === agpVersion) agp.value = data }
  catch (e) { if (version === agpVersion) agpError.value = e.message || '日间分布暂时不可用' }
  finally { if (version === agpVersion) agpLoading.value = false }
}

async function loadRecords() {
  const version = ++requestVersion
  const query = params({ pageNum: filters.recordsPage, pageSize: 20 })
  if (!patientId.value || !query) return
  try {
    const data = await glucoseApi.records(query)
    if (version !== requestVersion) return
    filters.records = data.records || []
    filters.recordsTotal = Number(data.total || 0)
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function exportFile(format = 'csv') {
  const query = params({})
  if (!query) return
  try {
    const xlsx = format === 'xlsx'
    const response = await fetch((xlsx ? glucoseApi.exportXlsxUrl : glucoseApi.exportUrl)(query), { headers: { Authorization: `Bearer ${getToken()}` } })
    if (response.status === 401) { localStorage.removeItem('accessToken'); location.href = '/login'; return }
    if (!response.ok) {
      let message = '导出失败'
      try { message = (await response.json()).message || message } catch { /* csv error */ }
      throw new Error(message)
    }
    const blob = await response.blob()
    const url = URL.createObjectURL(blob); const anchor = document.createElement('a')
    anchor.href = url; anchor.download = `glucose-${patientId.value}-${filters.dataSource}.${xlsx ? 'xlsx' : 'csv'}`; anchor.click(); URL.revokeObjectURL(url)
  } catch (e) { ElMessage.error(e.message) }
}

watch([patientId, () => filters.dataSource, () => filters.dateRange], () => {
  filters.recordsPage = 1
  loadAll()
}, { deep: true })

onMounted(loadPatients)
</script>

<template>
  <div class="analysis-page" v-loading="loading">
    <!-- 顶部现代化控制工具条 -->
    <div class="toolbar panel filter-toolbar">
      <div class="filter-group">
        <el-select v-model="patientId" filterable style="width: 220px" placeholder="选择患者" aria-label="选择患者">
          <el-option v-for="p in patients" :key="p.userId" :value="p.userId"
                     :label="`${p.realName || p.username} (${p.username})`" />
        </el-select>
        <span v-if="patients.find(p => p.userId === patientId)?.dataMode === 'DEMO'" class="status-tag st-demo">演示数据</span>
      </div>

      <div class="filter-group">
        <el-radio-group v-model="filters.dataSource">
          <el-radio-button value="CGM">动态监测 CGM</el-radio-button>
          <el-radio-button value="SMBG">指尖血 SMBG</el-radio-button>
        </el-radio-group>
      </div>

      <div class="filter-group">
        <el-date-picker v-model="filters.dateRange" type="daterange" value-format="YYYY-MM-DD"
                        :clearable="false" :disabled-date="disableIncompleteDay"
                        start-placeholder="开始日期" end-placeholder="结束日期" style="width: 260px" />
      </div>

      <div class="spacer" />

      <div class="export-group">
        <el-button :icon="Download" :disabled="!patientId || loading" @click="exportFile('csv')">导出 CSV</el-button>
        <el-button :icon="Document" :disabled="!patientId || loading" @click="exportFile('xlsx')">导出 Excel</el-button>
      </div>
      <span class="toolbar-hint">按已结束的完整自然日计算</span>
    </div>

    <template v-if="filters.summary">
      <!-- 充分性状态条 -->
      <div class="analysis-status" :class="{ 'analysis-qualified': filters.summary.metricStatus === 'QUALIFIED' }">
        <div class="status-main">
          <span class="status-tag" :class="statusMap[filters.summary.metricStatus]?.[0]">
            {{ statusMap[filters.summary.metricStatus]?.[1] || filters.summary.metricStatus }}
          </span>
          <span v-for="r in filters.summary.reasonCodes" :key="r" class="status-tag st-muted">
            {{ reasonText[r] || r }}
          </span>
        </div>
        <div class="status-metrics">
          <span class="metric-pill">有效记录 <strong class="mono-num">{{ filters.summary.pointCount }}</strong> 条</span>
          <span v-if="filters.summary.dataAdequacy != null" class="metric-pill">
            有效覆盖率 <strong class="mono-num">{{ Math.round(filters.summary.dataAdequacy * 1000) / 10 }}%</strong>
          </span>
          <span v-if="filters.summary.validMonitoringMinutes != null" class="metric-pill">
            有效监测时长 <strong class="mono-num">{{ Math.round(filters.summary.validMonitoringMinutes / 6) / 10 }}</strong> 小时
          </span>
          <span v-if="filters.summary.eligiblePointCount != null" class="metric-pill">
            目标覆盖: <strong class="mono-num">{{ filters.summary.eligiblePointCount }}</strong> 达标 / {{ filters.summary.unconfiguredTargetCount }} 未配置
          </span>
        </div>
      </div>

      <!-- 五项统计关键指标网格 -->
      <div class="metric-grid panel">
        <div class="metric-card">
          <div class="metric-label">平均血糖值</div>
          <div class="metric-value mono-num">{{ filters.summary.avgGlucose ?? '—' }} <small>mmol/L</small></div>
          <div class="metric-desc">全时段算术均值</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">最高读数</div>
          <div class="metric-value mono-num highlight-high">{{ filters.summary.maxGlucose ?? '—' }} <small>mmol/L</small></div>
          <div class="metric-desc mono-num">{{ filters.summary.maxAt || '—' }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">最低读数</div>
          <div class="metric-value mono-num highlight-low">{{ filters.summary.minGlucose ?? '—' }} <small>mmol/L</small></div>
          <div class="metric-desc mono-num">{{ filters.summary.minAt || '—' }}</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">血糖标准差 (SD)</div>
          <div class="metric-value mono-num">{{ filters.summary.sd ?? '—' }} <small>mmol/L</small></div>
          <div class="metric-desc">绝对波动离散程度</div>
        </div>
        <div class="metric-card">
          <div class="metric-label">变异系数 (CV)</div>
          <div class="metric-value mono-num" :class="{ 'highlight-cv': Number(filters.summary.cv) > 36 }">
            {{ filters.summary.cv != null ? filters.summary.cv + '%' : '—' }}
          </div>
          <div class="metric-desc">相对波动率 (≤36%稳定)</div>
        </div>
      </div>

      <!-- 主数据分析行：趋势图 + TIR/GMI 概览 -->
      <div class="analysis-main-row" :class="{ 'has-summary': filters.summary.tir != null || filters.summary.gmi != null || filters.summary.measurementInRangeRate != null }">
        <div class="panel trend-panel">
          <GlucoseTrend :trend="filters.trend" :low="filters.summary.targetLow" :high="filters.summary.targetHigh" />
        </div>
        
        <aside v-if="filters.summary.tir != null || filters.summary.gmi != null || filters.summary.measurementInRangeRate != null"
               class="panel summary-panel" aria-label="血糖核心质控指标">
          <section v-if="filters.summary.tir != null" class="summary-section">
            <div class="summary-title-row">
              <h2>目标范围内时间 (TIR)</h2>
              <span class="tir-pill">CGM 黄金标准</span>
            </div>
            <div class="metric-value tir-value mono-num">{{ filters.summary.tir }}%</div>
            
            <!-- 胶囊分段组成条 -->
            <div class="tir-bar-container">
              <div class="tir-bar" role="img" :aria-label="`高于目标 ${filters.summary.tar}%，目标内 ${filters.summary.tir}%，低于目标 ${filters.summary.tbr}%`">
                <span class="tar" :style="{ width: filters.summary.tar + '%' }" :title="`高于 ${filters.summary.tar}%`"></span>
                <span class="tir" :style="{ width: filters.summary.tir + '%' }" :title="`目标内 ${filters.summary.tir}%`"></span>
                <span class="tbr" :style="{ width: filters.summary.tbr + '%' }" :title="`低于 ${filters.summary.tbr}%`"></span>
              </div>
              <div class="tir-bar-labels">
                <span class="label-tar"><span class="dot-swatch sw-tar"></span>高于 {{ filters.summary.tar }}%</span>
                <span class="label-tir"><span class="dot-swatch sw-tir"></span>目标内 {{ filters.summary.tir }}%</span>
                <span class="label-tbr"><span class="dot-swatch sw-tbr"></span>低于 {{ filters.summary.tbr }}%</span>
              </div>
            </div>
            <p class="metric-note">参考范围：{{ filters.summary.targetLow }}–{{ filters.summary.targetHigh }} mmol/L（常规推荐 TIR > 70%）</p>
          </section>

          <section v-if="filters.summary.measurementInRangeRate != null" class="summary-section">
            <div class="summary-title-row">
              <div class="muted-title">指尖测量达标率</div>
              <span class="smbg-pill">分类型统计</span>
            </div>
            <div class="metric-value mono-num smbg-rate">{{ filters.summary.measurementInRangeRate }}%</div>
            <p class="metric-note">依据空腹/餐后各自目标计算，为离散测量点达标比例，非全天连续时间占比。</p>
          </section>

          <section v-if="filters.summary.gmi != null" class="summary-section gmi-section">
            <div class="summary-title-row">
              <div class="muted-title">管理指标预估 (GMI)</div>
              <span class="gmi-pill">推算参考</span>
            </div>
            <div class="metric-value mono-num gmi-value">{{ filters.summary.gmi }}%</div>
            <p class="metric-note">由 ≥14 天合格 CGM 均值推算，不等同于医院静脉血测定的实验室糖化血红蛋白 (HbA1c)。</p>
          </section>
        </aside>
      </div>

      <!-- 下方：AGP 日间分位分布 + 每日统计 -->
      <div class="analysis-lower-row" :class="{ 'with-agp': filters.dataSource === 'CGM' }">
        <AgpProfile v-if="filters.dataSource === 'CGM'" :profile="agp" :loading="agpLoading" :error="agpError" @retry="loadAgp()" />

        <section class="panel daily-panel">
          <div class="panel-header-clean">
            <h2 class="section-title">每日统计明细</h2>
            <span class="muted-caption">完整日聚合</span>
          </div>
          <el-table :data="filters.daily" size="small" max-height="340" class="modern-inner-table">
            <el-table-column prop="date" label="日期" width="115">
              <template #default="{ row }"><strong class="mono-num">{{ row.date }}</strong></template>
            </el-table-column>
            <el-table-column prop="count" label="采样数" width="80" align="center">
              <template #default="{ row }"><span class="mono-num">{{ row.count }}</span></template>
            </el-table-column>
            <el-table-column label="日均值 (mmol/L)" min-width="110">
              <template #default="{ row }">
                <span class="mono-num bold-num">{{ row.avg ?? '—' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最低" width="75">
              <template #default="{ row }"><span class="mono-num text-low">{{ row.min ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column label="最高" width="75">
              <template #default="{ row }"><span class="mono-num text-high">{{ row.max ?? '—' }}</span></template>
            </el-table-column>
            <el-table-column v-if="filters.dataSource === 'CGM'" label="日覆盖率" width="95" align="right">
              <template #default="{ row }">
                <span class="mono-num">{{ row.coverage != null ? row.coverage + '%' : '—' }}</span>
              </template>
            </el-table-column>
          </el-table>
        </section>
      </div>

      <!-- 原始测量明细 -->
      <section class="panel records-panel">
        <div class="panel-header-clean">
          <h2 class="section-title">原始记录明细</h2>
          <span class="muted-caption">共 {{ filters.recordsTotal }} 条记录</span>
        </div>
        <el-table :data="filters.records" v-loading="loading" size="small">
          <el-table-column prop="measuredAt" label="测量时间" width="180">
            <template #default="{ row }"><span class="mono-num">{{ row.measuredAt }}</span></template>
          </el-table-column>
          <el-table-column label="血糖读数" width="120">
            <template #default="{ row }">
              <strong class="mono-num record-val">{{ row.glucoseMmol }}</strong>
              <small class="unit-sub">mmol/L</small>
            </template>
          </el-table-column>
          <el-table-column prop="dataSource" label="数据源" width="85">
            <template #default="{ row }"><span class="source-tag">{{ row.dataSource }}</span></template>
          </el-table-column>
          <el-table-column label="测量场景/类型" width="120">
            <template #default="{ row }">
              <span class="type-tag">{{ row.measureTypeLabel || '连续监测采样' }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="riskFlag" label="提醒标记" width="110">
            <template #default="{ row }">
              <span v-if="row.riskFlag === 'LOW'" class="status-tag st-danger">偏低</span>
              <span v-else-if="row.riskFlag === 'HIGH'" class="status-tag st-warning">偏高</span>
              <span v-else class="status-tag st-muted">正常</span>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注说明" min-width="150" />
        </el-table>
        <el-pagination v-model:current-page="filters.recordsPage" :page-size="20"
                       :total="filters.recordsTotal" layout="total, prev, pager, next"
                       style="margin-top: 14px; justify-content: flex-end" @current-change="loadRecords" />
      </section>
    </template>

    <div v-if="errorMsg" role="alert" class="analysis-error">
      <p>{{ errorMsg }}</p>
      <el-button type="primary" @click="loadAll">重新加载数据</el-button>
    </div>
    <el-empty v-else-if="!loading && !filters.summary" description="请在上方面板选择患者以加载血糖分析报告" :image-size="80" />
  </div>
</template>

<style scoped>
.filter-toolbar { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; }
.filter-group { display: flex; align-items: center; gap: 10px; }
.export-group { display: flex; align-items: center; gap: 8px; }
.toolbar-hint { font-size: 12px; color: var(--color-ink-faint); margin-left: 4px; }

/* 充分性状态条 */
.analysis-status {
  display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;
  padding: 14px 20px; background: #FFFFFF; border: 1px solid var(--color-border);
  border-radius: var(--radius-md); box-shadow: var(--shadow-card); margin-bottom: 20px;
}
.analysis-qualified { border-left: 4px solid var(--color-success); }
.status-main { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.status-metrics { display: flex; align-items: center; gap: 12px; flex-wrap: wrap; }
.metric-pill { font-size: 12.5px; color: var(--color-ink-muted); }

/* 指标卡片网格 */
.metric-grid {
  display: grid; grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 16px; margin-bottom: 20px;
}
.metric-card {
  padding: 16px 18px; border-radius: var(--radius-sm); background: #F8FAFC;
  border: 1px solid #EDF2F7; display: flex; flex-direction: column;
  transition: all 0.2s ease;
}
.metric-card:hover { background: #FFFFFF; border-color: #CBD5E1; box-shadow: var(--shadow-card); transform: translateY(-1px); }
.metric-label { font-size: 12.5px; font-weight: 500; color: var(--color-ink-muted); margin-bottom: 6px; }
.metric-value { font-size: 26px; line-height: 1.1; font-weight: 700; color: var(--color-ink); letter-spacing: -0.02em; }
.metric-value small { font-size: 12px; font-weight: 400; color: var(--color-ink-muted); margin-left: 4px; }
.metric-desc { font-size: 11.5px; color: var(--color-ink-faint); margin-top: 6px; }
.highlight-high { color: var(--color-danger); }
.highlight-low { color: #2563EB; }
.highlight-cv { color: var(--color-warning); }

/* 主数据区 */
.analysis-main-row, .analysis-lower-row {
  display: grid; gap: 20px; align-items: stretch; margin-top: 20px; min-width: 0;
}
.analysis-main-row.has-summary { grid-template-columns: minmax(0, 2.5fr) minmax(280px, 1fr); }
.analysis-lower-row.with-agp { grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr); }
.trend-panel :deep(.trend-section) { margin-top: 0; padding-top: 0; border: 0; }

/* 质控右侧指标卡 */
.summary-panel { display: flex; flex-direction: column; gap: 22px; justify-content: center; }
.summary-section {
  padding: 16px; border-radius: var(--radius-sm); background: #F8FAFC; border: 1px solid #EDF2F7;
}
.summary-title-row { display: flex; align-items: center; justify-content: space-between; margin-bottom: 8px; }
.summary-section h2 { font-size: 15px; font-weight: 700; margin: 0; color: var(--color-ink); }
.muted-title { font-size: 13.5px; font-weight: 600; color: var(--color-ink); }
.tir-pill { font-size: 11px; font-weight: 600; background: #ECFDF5; color: #059669; padding: 2px 6px; border-radius: 4px; }
.smbg-pill { font-size: 11px; font-weight: 600; background: #EFF6FF; color: #2563EB; padding: 2px 6px; border-radius: 4px; }
.gmi-pill { font-size: 11px; font-weight: 600; background: #F3E8FF; color: #7C3AED; padding: 2px 6px; border-radius: 4px; }

.tir-value { color: #059669; font-size: 36px; font-weight: 800; letter-spacing: -0.03em; }
.smbg-rate { color: #2563EB; font-size: 32px; font-weight: 800; }
.gmi-value { color: #7C3AED; font-size: 32px; font-weight: 800; }

.tir-bar-container { margin: 12px 0 8px; }
.tir-bar {
  display: flex; width: 100%; height: 14px; border-radius: var(--radius-pill);
  overflow: hidden; box-shadow: inset 0 1px 3px rgba(0,0,0,0.1);
}
.tir-bar .tar { background: linear-gradient(90deg, #F59E0B 0%, #D97706 100%); }
.tir-bar .tir { background: linear-gradient(90deg, #10B981 0%, #059669 100%); }
.tir-bar .tbr { background: linear-gradient(90deg, #EF4444 0%, #DC2626 100%); }

.tir-bar-labels {
  display: flex; justify-content: space-between; font-size: 11px; color: var(--color-ink-muted);
  margin-top: 6px; font-weight: 500;
}
.dot-swatch { display: inline-block; width: 6px; height: 6px; border-radius: 50%; margin-right: 4px; }
.sw-tar { background: #D97706; }
.sw-tir { background: #059669; }
.sw-tbr { background: #DC2626; }
.metric-note { font-size: 11.5px; color: var(--color-ink-faint); line-height: 1.6; margin: 8px 0 0; }

/* 每日与原始记录表 */
.panel-header-clean {
  display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 12px;
}
.panel-header-clean .section-title { margin: 0; }
.muted-caption { font-size: 12px; color: var(--color-ink-faint); }
.bold-num { font-weight: 600; color: var(--color-ink); }
.text-low { color: #2563EB; font-weight: 500; }
.text-high { color: var(--color-danger); font-weight: 500; }
.record-val { font-size: 14px; font-weight: 700; color: var(--color-ink); margin-right: 4px; }
.unit-sub { font-size: 11px; color: var(--color-ink-faint); }
.source-tag { font-size: 11px; font-weight: 600; background: #F1F5F9; color: var(--color-ink-muted); padding: 2px 6px; border-radius: 4px; }
.type-tag { font-size: 12px; color: var(--color-ink-muted); }

.records-panel { margin-top: 20px; }
.analysis-error {
  padding: 24px; color: var(--color-danger); background: var(--color-danger-subtle);
  border: 1px solid #FECACA; border-radius: var(--radius-sm); margin-top: 20px;
}

@media (max-width: 1100px) {
  .metric-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); }
  .analysis-main-row.has-summary, .analysis-lower-row.with-agp { grid-template-columns: 1fr; }
}
@media (max-width: 767px) {
  .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .metric-value { font-size: 22px; }
  .tir-value { font-size: 30px; }
}
</style>
