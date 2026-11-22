<script setup>
import { onMounted, ref } from 'vue'
import { dashboardApi } from '../api'
import { useAuthStore } from '../stores/auth'
import PendingAlerts from '../components/PendingAlerts.vue'
import { ArrowRight, User, DataAnalysis, WarningFilled } from '@element-plus/icons-vue'

const auth = useAuthStore()
const loading = ref(false)
const errorMessage = ref('')
const overview = ref(null)

async function loadOverview() {
  loading.value = true
  errorMessage.value = ''
  try {
    overview.value = await dashboardApi.overview()
  } catch (error) {
    errorMessage.value = error.message || '暂时无法加载工作台数据'
  } finally {
    loading.value = false
  }
}

function formatCount(value) {
  const number = Number(value)
  return Number.isFinite(number) ? new Intl.NumberFormat('zh-CN').format(number) : '0'
}

onMounted(loadOverview)
</script>

<template>
  <section class="dashboard" aria-label="工作台概览">
    <div v-if="loading" class="kpi-grid" aria-live="polite" aria-label="正在加载工作台概览">
      <div v-for="item in 3" :key="item" class="kpi-card kpi-loading"><span class="loading-line short"></span><span class="loading-number"></span><span class="loading-line"></span></div>
    </div>

    <div v-else-if="errorMessage" class="dashboard-error" role="alert">
      <div><strong>概览暂时不可用</strong><p>{{ errorMessage }}</p></div>
      <el-button type="primary" @click="loadOverview">重新加载</el-button>
    </div>

    <template v-else-if="overview">
      <div class="kpi-grid">
        <article class="kpi-card kpi-patient">
          <div class="kpi-header">
            <div class="kpi-label"><span class="kpi-dot patient-dot"></span>启用患者</div>
            <div class="kpi-icon-badge patient-badge"><el-icon><User /></el-icon></div>
          </div>
          <strong class="kpi-value">{{ formatCount(overview.activePatients) }}</strong>
          <span class="kpi-note">{{ auth.isDoctor ? '已绑定且账号启用' : '患者账号正常启用' }}</span>
        </article>
        <article class="kpi-card kpi-record">
          <div class="kpi-header">
            <div class="kpi-label"><span class="kpi-dot record-dot"></span>近 7 日血糖记录</div>
            <div class="kpi-icon-badge record-badge"><el-icon><DataAnalysis /></el-icon></div>
          </div>
          <strong class="kpi-value">{{ formatCount(overview.recordsLast7Days) }}</strong>
          <span class="kpi-note">按测量时间时序汇总</span>
        </article>
        <article class="kpi-card kpi-alert" :class="{ 'kpi-attention': Number(overview.pendingAlerts) > 0 }">
          <div class="kpi-header">
            <div class="kpi-label"><span class="kpi-dot alert-dot"></span>待处理预警</div>
            <div class="kpi-icon-badge alert-badge"><el-icon><WarningFilled /></el-icon></div>
          </div>
          <strong class="kpi-value">{{ formatCount(overview.pendingAlerts) }}</strong>
          <span class="kpi-note">等待医生或管理员处理随访</span>
        </article>
      </div>
      <p class="updated-note">
        <span>数据更新时间：<strong class="mono-num">{{ overview.updatedAt || '—' }}</strong></span>
        <span>按当前账号权限隔离范围统计</span>
      </p>

      <div class="dashboard-row">
        <PendingAlerts @handled="loadOverview" />
        <div class="dashboard-aside">
          <section class="panel quick-panel" aria-labelledby="quick-title">
            <div class="panel-head">
              <h2 id="quick-title">快捷入口</h2>
              <span class="muted">常用临床功能</span>
            </div>
            <div class="quick-list">
              <router-link class="quick-row" to="/patients">
                <div class="quick-info">
                  <span class="quick-name">患者管理</span>
                  <span class="quick-desc">档案信息、目标区间与医生绑定关系</span>
                </div>
                <el-icon class="quick-arrow"><ArrowRight /></el-icon>
              </router-link>
              <router-link class="quick-row" to="/glucose">
                <div class="quick-info">
                  <span class="quick-name">血糖与分析</span>
                  <span class="quick-desc">时序趋势图、TIR/GMI 及 AGP 日间分布</span>
                </div>
                <el-icon class="quick-arrow"><ArrowRight /></el-icon>
              </router-link>
              <router-link class="quick-row" to="/qa">
                <div class="quick-info">
                  <span class="quick-name">问答记录</span>
                  <span class="quick-desc">查看会话历史、权威引用与医生答复</span>
                </div>
                <el-icon class="quick-arrow"><ArrowRight /></el-icon>
              </router-link>
              <router-link v-if="auth.isAdmin" class="quick-row" to="/kb">
                <div class="quick-info">
                  <span class="quick-name">知识库管理</span>
                  <span class="quick-desc">维护糖尿病医学资料与向量切片索引</span>
                </div>
                <el-icon class="quick-arrow"><ArrowRight /></el-icon>
              </router-link>
            </div>
          </section>

          <section class="panel note-panel" aria-labelledby="note-title">
            <div class="panel-head">
              <h2 id="note-title">系统与口径说明</h2>
            </div>
            <ul class="note-list">
              <li><strong>数据隔离</strong>：医生仅查询其负责的绑定患者，全量管理操作留存审计日志。</li>
              <li><strong>连续性规则</strong>：动态监测缺测区间严格断线，达标率与 TIR 严格区分。</li>
              <li><strong>预警生命周期</strong>：连续同向读数合并为统一事件，处理留存随访记录。</li>
            </ul>
            <div class="note-footer">健康管理决策辅助系统 · 临床请结合化验单与实际症状综合评估</div>
          </section>
        </div>
      </div>
    </template>
  </section>
</template>

<style scoped>
.dashboard { display: grid; gap: 24px; }
.kpi-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 20px; }
.kpi-card {
  background: var(--color-surface); border: 1px solid var(--color-border); border-radius: var(--radius-md);
  padding: 24px 26px; min-height: 154px; display: flex; flex-direction: column;
  box-shadow: var(--shadow-card);
  transition: all 0.25s cubic-bezier(0.4, 0, 0.2, 1);
  position: relative; overflow: hidden;
}
.kpi-card::after {
  content: ''; position: absolute; top: 0; left: 0; right: 0; height: 3px;
  background: transparent; transition: all 0.25s ease;
}
.kpi-card:hover {
  transform: translateY(-2px); box-shadow: var(--shadow-card-hover); border-color: #CBD5E1;
}
.kpi-patient::after { background: var(--grad-primary); }
.kpi-record::after { background: linear-gradient(90deg, #059669 0%, #10B981 100%); }
.kpi-alert::after { background: linear-gradient(90deg, #D97706 0%, #F59E0B 100%); }
.kpi-attention::after { background: linear-gradient(90deg, #DC2626 0%, #EF4444 100%); }

.kpi-attention { border-color: #FECACA; background: #FFFBFB; }
.kpi-header { display: flex; align-items: center; justify-content: space-between; }
.kpi-label { display: flex; align-items: center; gap: 8px; color: var(--color-ink-muted); font-size: 13.5px; font-weight: 500; }
.kpi-dot { width: 7px; height: 7px; border-radius: 50%; flex: 0 0 auto; box-shadow: 0 0 6px currentColor; }
.patient-dot { color: var(--color-primary); background: var(--color-primary); }
.record-dot { color: var(--color-success); background: var(--color-success); }
.alert-dot { color: var(--color-warning); background: var(--color-warning); }
.kpi-attention .alert-dot { color: var(--color-danger); background: var(--color-danger); }

.kpi-icon-badge {
  width: 36px; height: 36px; border-radius: 10px; display: flex; align-items: center; justify-content: center;
  font-size: 18px; transition: transform 0.2s ease;
}
.kpi-card:hover .kpi-icon-badge { transform: scale(1.08); }
.patient-badge { background: #EFF6FF; color: var(--color-primary); }
.record-badge { background: #ECFDF5; color: var(--color-success); }
.alert-badge { background: #FFFBEB; color: var(--color-warning); }
.kpi-attention .alert-badge { background: #FEF2F2; color: var(--color-danger); }

.kpi-value {
  margin-top: 14px; font-size: 34px; line-height: 1.1; font-weight: 700;
  letter-spacing: -0.02em; font-variant-numeric: tabular-nums; color: var(--color-ink);
}
.kpi-attention .kpi-value { color: var(--color-danger); }
.kpi-note { margin-top: auto; padding-top: 10px; color: var(--color-ink-muted); font-size: 12.5px; }

.updated-note {
  margin: -10px 0 0; font-size: 12px; color: var(--color-ink-faint);
  display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap; padding: 0 4px;
}
.dashboard-row {
  display: grid; grid-template-columns: minmax(0, 2.2fr) minmax(260px, 1fr); gap: 24px; align-items: start;
}
.dashboard-aside { display: grid; gap: 24px; min-width: 0; }

.panel-head {
  display: flex; align-items: baseline; justify-content: space-between; gap: 12px;
  padding-bottom: 14px; border-bottom: 1px solid var(--color-border); margin-bottom: 8px;
}
.panel-head h2 { margin: 0; font-size: 16px; font-weight: 600; color: var(--color-ink); }
.panel-head .muted { font-size: 12px; }

.quick-list { display: flex; flex-direction: column; gap: 4px; }
.quick-row {
  display: flex; align-items: center; justify-content: space-between; padding: 12px 14px;
  border-radius: var(--radius-sm); text-decoration: none; color: var(--color-ink);
  transition: all 0.2s cubic-bezier(0.4, 0, 0.2, 1);
}
.quick-row:hover { background: #F8FAFC; transform: translateX(3px); }
.quick-info { display: flex; flex-direction: column; gap: 3px; min-width: 0; }
.quick-name { font-size: 14px; font-weight: 600; color: var(--color-ink); }
.quick-desc { color: var(--color-ink-muted); font-size: 12px; }
.quick-arrow { color: var(--color-ink-faint); font-size: 16px; transition: transform 0.2s ease; }
.quick-row:hover .quick-arrow { color: var(--color-primary); transform: translateX(3px); }

.note-list { margin: 14px 0 0; padding-left: 18px; color: var(--color-ink-muted); font-size: 12.5px; line-height: 1.8; }
.note-list li { margin-top: 6px; }
.note-list strong { color: var(--color-ink); font-weight: 600; }
.note-footer {
  margin-top: 18px; padding-top: 14px; border-top: 1px solid var(--color-border);
  color: var(--color-ink-faint); font-size: 11.5px; line-height: 1.5;
}

.dashboard-error {
  background: var(--color-surface); border: 1px solid #FECACA; border-radius: var(--radius-md);
  padding: 20px 24px; display: flex; align-items: center; justify-content: space-between; gap: 18px;
  box-shadow: var(--shadow-card);
}
.dashboard-error strong { font-weight: 600; color: var(--color-danger); }
.dashboard-error p { margin: 4px 0 0; color: var(--color-ink-muted); font-size: 13px; }

.kpi-loading { justify-content: center; gap: 12px; }
.loading-line, .loading-number {
  display: block; border-radius: 4px;
  background: linear-gradient(90deg, #F1F5F9, #E2E8F0, #F1F5F9);
  background-size: 200% 100%; animation: shimmer 1.5s ease infinite;
}
.loading-line { width: 62%; height: 11px; }
.loading-line.short { width: 38%; }
.loading-number { width: 46%; height: 28px; }
@keyframes shimmer { to { background-position: -200% 0; } }

@media (max-width: 1000px) {
  .kpi-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .dashboard-row { grid-template-columns: 1fr; }
}
@media (max-width: 620px) {
  .kpi-grid { grid-template-columns: 1fr; gap: 12px; }
  .kpi-card { min-height: 120px; padding: 18px 20px; }
}
</style>
