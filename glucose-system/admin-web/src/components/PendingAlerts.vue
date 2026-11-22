<script setup>
import { onMounted, ref } from 'vue'
import { alertsApi, patientsApi } from '../api'
import AlertHandleDrawer from './AlertHandleDrawer.vue'
import { Warning, ArrowRight } from '@element-plus/icons-vue'

const emit = defineEmits(['handled'])
const rows = ref([]), loading = ref(true), error = ref(''), selected = ref(null), drawer = ref(false)

async function load() {
  loading.value = true; error.value = ''
  try {
    const [alerts, patients] = await Promise.all([
      alertsApi.list({ handleStatus: 'PENDING', pageNum: 1, pageSize: 5 }),
      patientsApi.list({ pageNum: 1, pageSize: 100 })
    ])
    rows.value = (alerts.records || []).map(a => ({
      ...a,
      patientName: patients.records?.find(p => String(p.userId) === String(a.patientId))?.realName ||
                   patients.records?.find(p => String(p.userId) === String(a.patientId))?.username ||
                   String(a.patientId)
    }))
  } catch (e) {
    error.value = e.message || '预警加载失败'
  } finally {
    loading.value = false
  }
}

function handled() { load(); emit('handled') }
onMounted(load)
</script>

<template>
  <section class="panel pending-panel">
    <div class="pending-heading">
      <div class="title-with-icon">
        <el-icon class="panel-icon"><Warning /></el-icon>
        <h2>待处理预警</h2>
      </div>
      <router-link to="/alerts" class="view-all-link">
        查看全部预警 <el-icon><ArrowRight /></el-icon>
      </router-link>
    </div>
    <p class="muted pending-intro">按临床重要性排序，及时核实异常读数并记录随访处理。</p>
    
    <div v-if="error" role="alert" class="preview-error">
      <span>{{ error }}</span>
      <el-button link type="primary" @click="load">重新加载</el-button>
    </div>
    
    <div v-else class="table-container">
      <el-table :data="rows" v-loading="loading" empty-text="当前暂无待处理预警，情况良好" class="modern-table">
        <el-table-column label="患者" min-width="150">
          <template #default="{ row }">
            <div class="patient-cell">
              <span class="patient-name">{{ row.patientName }}</span>
              <span class="status-tag" :class="row.dataKind === 'DEMO' ? 'st-demo' : 'st-muted'">
                {{ row.dataKind === 'DEMO' ? '演示' : row.dataKind === 'REAL' ? '真实' : '待定' }}
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="事件 / 级别" min-width="160">
          <template #default="{ row }">
            <span class="status-tag" :class="row.level === 3 ? 'st-danger' : 'st-warning'">
              {{ row.alertType === 'LOW' ? '低血糖' : '高血糖' }} · {{ row.level === 3 ? '严重' : '警告' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="来源" prop="dataSource" width="85">
          <template #default="{ row }">
            <span class="source-badge">{{ row.dataSource }}</span>
          </template>
        </el-table-column>
        <el-table-column label="发生时间" prop="startAt" min-width="166">
          <template #default="{ row }">
            <span class="mono-num time-text">{{ row.startAt }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" size="small" @click="selected = row; drawer = true">处理</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <AlertHandleDrawer v-model="drawer" :alert="selected" @handled="handled" @refresh="load" />
  </section>
</template>

<style scoped>
.pending-panel { min-width: 0; }
.pending-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; }
.title-with-icon { display: flex; align-items: center; gap: 8px; }
.panel-icon { font-size: 20px; color: var(--color-warning); }
.pending-heading h2 { font-size: 17px; font-weight: 700; margin: 0; color: var(--color-ink); }
.view-all-link {
  display: inline-flex; align-items: center; gap: 4px; font-size: 13px; font-weight: 500;
  text-decoration: none; color: var(--color-primary); transition: gap 0.2s ease;
}
.view-all-link:hover { gap: 6px; }
.pending-intro { font-size: 13px; margin: 6px 0 16px; }
.preview-error {
  padding: 24px; color: var(--color-danger); background: var(--color-danger-subtle);
  border-radius: var(--radius-sm); border: 1px solid #FECACA; display: flex; gap: 12px; align-items: center;
}
.table-container { border-radius: var(--radius-sm); overflow: hidden; }
.patient-cell { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.patient-name { font-weight: 600; color: var(--color-ink); font-size: 13.5px; }
.source-badge {
  font-size: 12px; font-weight: 600; color: var(--color-ink-muted);
  background: var(--color-surface-subtle); padding: 2px 6px; border-radius: 4px;
}
.time-text { font-size: 12.5px; color: var(--color-ink-muted); }
</style>
