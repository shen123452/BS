<script setup>
import { computed, ref, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth } from '../lib/auth.js'
import { get, post } from '../lib/http.js'
import { useLoad } from '../lib/useLoad.js'
import { chinaDate, slots, money, dateTime, histories } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
const route = useRoute(), router = useRouter(), doctor = auth.user.role === 'DOCTOR', base = doctor ? '/doctor' : '/patient'
const { data, loading, error, load } = useLoad(async () => {
  const result = await get(`${base}/registrations/${route.params.id}`)
  if (doctor) return result
  let schedule = null
  try { schedule = await get(`/schedules/${result.scheduleId}`) } catch(e) { if (e.status !== 404) throw e }
  return { registration:result, schedule }
})
const visit = computed(() => data.value?.registration), busy = ref(false), actionError = ref(null)
const canCancel = computed(() => visit.value?.status === 'WAITING' && (!data.value?.schedule || Date.now() < new Date(`${data.value.schedule.bookingDeadline}+08:00`).getTime()))
async function cancel() {
  let reason
  try { const answer = await ElMessageBox.prompt('取消后号源将释放，是否取消本次预约？原因可不填。', '取消预约', { confirmButtonText:'确认取消预约', cancelButtonText:'保留预约', inputValidator: value => !value || value.length <= 255 || '取消原因最多 255 字' }); reason = answer.value } catch { return }
  busy.value = true; actionError.value = null
  try { await post(`${base}/registrations/${route.params.id}/cancel`, { reason }); ElMessage.success('预约已取消'); await load() } catch(e) { actionError.value = e } finally { busy.value = false }
}
async function start() {
  busy.value = true; actionError.value = null
  try { await post(`${base}/registrations/${route.params.id}/start`); await router.push(`${base}/visits/${route.params.id}/record`) } catch(e) { actionError.value = e } finally { busy.value = false }
}
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><router-link :to="doctor ? '/doctor/workbench' : '/patient/visits'" class="back-link">返回{{ doctor ? '接诊工作台' : '我的就诊' }}</router-link><h1 tabindex="-1">{{ doctor ? '接诊详情' : '就诊详情' }}</h1></div><StatusBadge v-if="visit" :status="visit.status" /></div>
  <ErrorPanel :error="error" @retry="load" /><ErrorPanel :error="actionError" />
  <div v-loading="loading"><template v-if="visit"><section class="panel"><h2>{{ visit.patientName }} · {{ visit.departmentName }}</h2><dl class="detail-grid"><div><dt>挂号号</dt><dd>{{ visit.registrationNo }}</dd></div><div><dt>就诊时间</dt><dd>{{ visit.visitDate }} · {{ slots[visit.timeSlot] }}</dd></div><div><dt>接诊医生</dt><dd>{{ visit.doctorName }}</dd></div><div><dt>费用</dt><dd>{{ money(visit.fee) }}</dd></div><div><dt>预约时间</dt><dd>{{ dateTime(visit.createdAt) }}</dd></div><div v-if="data.schedule"><dt>取消截止</dt><dd>{{ dateTime(data.schedule.bookingDeadline) }}</dd></div><div v-if="visit.startedAt"><dt>接诊开始</dt><dd>{{ dateTime(visit.startedAt) }}</dd></div><div v-if="visit.completedAt"><dt>就诊完成</dt><dd>{{ dateTime(visit.completedAt) }}</dd></div><div v-if="visit.cancelledAt"><dt>取消时间</dt><dd>{{ dateTime(visit.cancelledAt) }}</dd></div></dl><h3>就诊说明</h3><p class="preserve-text">{{ visit.symptomDescription || '未提供' }}</p><p v-if="visit.cancelReason">取消原因：{{ visit.cancelReason }}</p></section>
  <section v-if="doctor && data.patient" class="panel"><h2>患者档案</h2><dl class="detail-grid"><div><dt>姓名 / 年龄</dt><dd>{{ data.patient.name || '未提供' }} / {{ data.patient.age ?? '未提供' }}</dd></div><div><dt>性别</dt><dd>{{ { MALE:'男', FEMALE:'女', UNKNOWN:'未提供' }[data.patient.gender] }}</dd></div><div><dt>慢病史</dt><dd>{{ histories[data.patient.chronicStatus] }}</dd></div><div><dt>过敏史</dt><dd>{{ histories[data.patient.allergyStatus] }}</dd></div></dl><p v-if="data.patient.chronicStatus === 'KNOWN'" class="preserve-text">慢病说明：{{ data.patient.chronicHistory }}</p><p v-if="data.patient.allergyStatus === 'KNOWN'" class="preserve-text">过敏说明：{{ data.patient.allergyHistory }}</p></section>
  <div class="form-actions"><el-button v-if="!doctor && visit.status === 'WAITING'" type="danger" plain :loading="busy" :disabled="!canCancel" @click="cancel">取消预约</el-button><p v-if="!doctor && visit.status === 'WAITING' && !canCancel" class="muted">已过取消截止时间。</p><p v-if="!doctor && visit.status === 'WAITING' && !data.schedule" class="muted">当前场次不可查询，取消是否成功以提交结果为准。</p><el-button v-if="doctor && visit.status === 'WAITING'" type="primary" :loading="busy" :disabled="visit.visitDate !== chinaDate()" @click="start">开始接诊</el-button><p v-if="doctor && visit.status === 'WAITING' && visit.visitDate !== chinaDate()" class="muted">请在 {{ visit.visitDate }} 当天开始接诊。</p><router-link v-if="doctor && visit.status === 'IN_PROGRESS'" :to="`${base}/visits/${visit.id}/record`" class="el-button el-button--primary">继续填写病历</router-link><router-link v-if="visit.status === 'COMPLETED'" :to="`${base}/visits/${visit.id}/record`" class="el-button el-button--primary">查看病历</router-link></div></template></div>
</template>
