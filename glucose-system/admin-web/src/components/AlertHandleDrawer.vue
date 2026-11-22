<script setup>
import { ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { alertsApi } from '../api'
const props = defineProps({ modelValue: Boolean, alert: Object })
const emit = defineEmits(['update:modelValue', 'handled', 'refresh'])
const note = ref(''), submitting = ref(false), error = ref('')
watch(() => props.modelValue, visible => { if (visible) { note.value = ''; error.value = '' } })
async function submit() {
  if (!props.alert || submitting.value) return
  submitting.value = true; error.value = ''
  try {
    await alertsApi.handle(props.alert.id, { version: props.alert.version, handleNote: note.value })
    ElMessage.success('处理已保存'); emit('update:modelValue', false); emit('handled')
  } catch (e) {
    error.value = e.message || '保存失败，请重试'
    if (e.code === 40901) {
      try { await ElMessageBox.confirm('该事件已被更新，请刷新后核对最新情况。', '事件已更新', { confirmButtonText: '刷新事件', cancelButtonText: '稍后处理', type: 'warning' }); emit('update:modelValue', false); emit('refresh') } catch { /* 用户保留当前备注 */ }
    }
  } finally { submitting.value = false }
}
</script>
<template>
  <el-drawer :model-value="modelValue" title="处理预警事件" size="480px" :close-on-click-modal="!submitting" :close-on-press-escape="!submitting" :show-close="!submitting" @update:model-value="emit('update:modelValue', $event)">
    <template v-if="alert">
      <div class="event-summary" :class="alert.level === 3 ? 'event-danger' : 'event-warning'">
        <div class="event-badge-row">
          <span class="status-tag" :class="alert.level === 3 ? 'st-danger' : 'st-warning'">{{ alert.level === 3 ? '严重预警' : '关注警告' }}</span>
          <span class="event-time-tag">{{ alert.startAt }}</span>
        </div>
        <h2>{{ alert.alertType === 'LOW' ? '低血糖事件' : '高血糖事件' }}</h2>
        <p>{{ alert.message || '请核对异常读数与患者近期情况。' }}</p>
      </div>

      <div class="details-card">
        <el-descriptions :column="1" class="event-details" border>
          <el-descriptions-item label="关联患者">
            <strong>{{ alert.patientName || alert.patientId }}</strong>
            <span class="status-tag" :class="alert.dataKind === 'DEMO' ? 'st-demo' : 'st-muted'" style="margin-left: 8px">
              {{ alert.dataKind === 'DEMO' ? '演示数据' : alert.dataKind === 'REAL' ? '真实数据' : '性质待确认' }}
            </span>
          </el-descriptions-item>
          <el-descriptions-item label="数据来源">{{ alert.dataSource }}</el-descriptions-item>
          <el-descriptions-item label="持续区间">{{ alert.startAt }} 至 {{ alert.endAt || (alert.eventStatus === 'OPEN' ? '近期仍有异常读数' : '结束时间未知') }}</el-descriptions-item>
          <el-descriptions-item label="区间极值"><span class="extreme-value">{{ alert.alertType === 'LOW' ? alert.minGlucose : alert.maxGlucose }} mmol/L</span></el-descriptions-item>
          <el-descriptions-item label="采样样本数"><span class="mono-num">{{ alert.sampleCount ?? '—' }}</span> 次</el-descriptions-item>
          <el-descriptions-item label="生命周期 / 状态">
            {{ ({ OPEN: '近期仍有异常', ENDED: '已平稳结束', END_UNKNOWN: '结束未知' })[alert.eventStatus] || '未知' }} · 
            <span :class="alert.handleStatus === 'PENDING' ? 'text-warn' : 'text-success'">{{ ({ PENDING: '待医生处理', HANDLED: '已随访处理' })[alert.handleStatus] || '未知' }}</span>
          </el-descriptions-item>
        </el-descriptions>
      </div>

      <div class="form-section">
        <label class="handle-label" for="handle-note">随访处理备注 <span class="required-star">*</span></label>
        <el-input id="handle-note" v-model="note" type="textarea" :rows="4" maxlength="255" show-word-limit placeholder="详细记录已核实的情况、医嘱或随访安排…" :disabled="submitting" />
        <p class="handle-tip">提示：保存后事件标记为已处理。若后续读数发生升级，系统会重新自动置为待处理状态。</p>
        <div v-if="error" role="alert" class="handle-error">{{ error }}</div>
      </div>
    </template>
    <template #footer>
      <div class="drawer-actions">
        <el-button :disabled="submitting" @click="emit('update:modelValue', false)">取消</el-button>
        <el-button type="primary" :loading="submitting" @click="submit">确认保存处理</el-button>
      </div>
    </template>
  </el-drawer>
</template>
<style scoped>
.event-summary {
  padding: 18px 20px; border-radius: var(--radius-md);
  border: 1px solid var(--color-border);
  position: relative; overflow: hidden;
}
.event-warning {
  background: linear-gradient(135deg, #FFFBEB 0%, #FEF3C7 100%);
  border-color: #FDE68A;
}
.event-danger {
  background: linear-gradient(135deg, #FEF2F2 0%, #FEE2E2 100%);
  border-color: #FECACA;
}
.event-badge-row { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.event-time-tag { font-size: 12px; color: var(--color-ink-muted); font-variant-numeric: tabular-nums; }
.event-summary h2 { font-size: 18px; margin: 10px 0 6px; font-weight: 700; color: var(--color-ink); }
.event-summary p { margin: 0; font-size: 13.5px; color: var(--color-ink-muted); line-height: 1.5; }

.details-card { margin: 20px 0; }
.extreme-value { font-weight: 700; color: var(--color-danger); font-size: 14px; font-variant-numeric: tabular-nums; }
.text-warn { color: var(--color-warning); font-weight: 600; }
.text-success { color: var(--color-success); font-weight: 600; }

.form-section { margin-top: 18px; }
.handle-label { display: block; margin-bottom: 8px; font-weight: 600; font-size: 13.5px; color: var(--color-ink); }
.required-star { color: var(--color-danger); }
.handle-tip { color: var(--color-ink-faint); font-size: 12px; line-height: 1.6; margin-top: 10px; }
.handle-error {
  margin-top: 12px; padding: 10px 14px; border-radius: var(--radius-sm);
  background: var(--color-danger-subtle); color: var(--color-danger);
  border: 1px solid #FECACA; font-size: 13px;
}
.drawer-actions { display: flex; justify-content: flex-end; gap: 10px; }
</style>
