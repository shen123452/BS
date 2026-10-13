<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { auth } from '../lib/auth.js'
import { get, post } from '../lib/http.js'
import { chinaDate, slots, money, dateTime } from '../lib/helpers.js'
import { attemptKey, makeAttempt, definiteFailure } from '../lib/bookingAttempt.js'
import { useLoad } from '../lib/useLoad.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import Pager from '../components/Pager.vue'
const route = useRoute(), router = useRouter()
const filters = reactive({ departmentId: route.query.departmentId || '', date: route.query.date || '', page: Number(route.query.page) || 1, pageSize: 12 })
const days = Array.from({ length: 7 }, (_,i) => chinaDate(i)), departments = ref([]), profile = ref(null), setupError = ref(null)
const { data, loading, error, load } = useLoad(() => get('/schedules', filters))
const visible = ref(false), selected = ref(null), symptom = ref(''), confirming = ref(false), posting = ref(false), submitError = ref(null), attempt = ref(null), refreshing = ref(false)
const selectionError = ref(null), failedSelection = ref(null)
const triage = ref(null), triageError = ref(null), triageId = ref(route.query.triageResultId || '')
const storageKey = attemptKey(auth.user.patientId)
function clearAttempt() { sessionStorage.removeItem(storageKey); attempt.value = null }
async function setup() { setupError.value = null; try { [departments.value, profile.value] = await Promise.all([get('/departments'), get('/patient/profile')]) } catch(e) { setupError.value = e }
  if (triageId.value) { try { triage.value = await get(`/patient/triage-results/${triageId.value}`); if (!triage.value.canBook) { triageError.value = new Error(triage.value.unavailableReason); triageId.value = '' } } catch(e) { triageError.value = e; triageId.value = '' } }
}
function clearTriage() { triageId.value = ''; triage.value = null; triageError.value = null }
async function search(page = 1) {
  if (triage.value && filters.departmentId !== triage.value.departmentId && !triage.value.alternativeDepartmentIds.includes(filters.departmentId)) clearTriage()
  filters.page = page; router.replace({ query: { departmentId: filters.departmentId || undefined, triageResultId:triageId.value || undefined, date: filters.date || undefined, page: page > 1 ? page : undefined } }); await load()
}
async function choose(schedule) {
  if (!profile.value?.name) { ElMessage.warning('请先完善档案中的就诊姓名'); router.push('/patient/profile'); return }
  if (attempt.value) { resume(); return }
  confirming.value = true; submitError.value = null; selectionError.value = null; failedSelection.value = schedule
  try { selected.value = await get(`/schedules/${schedule.id}`); symptom.value = ''; visible.value = true; failedSelection.value = null } catch(e) { selectionError.value = e; await load() } finally { confirming.value = false }
}
function resume() { selected.value = attempt.value.schedule; symptom.value = attempt.value.payload.symptomDescription; submitError.value = new Error('上次请求结果尚未确认。继续确认将复用原请求，不会重复扣号。'); visible.value = true }
async function refreshSelection() {
  refreshing.value = true; submitError.value = null
  try { selected.value = await get(`/schedules/${selected.value.id}`) } catch(e) { submitError.value = e; selected.value = { ...selected.value, bookable:false } } finally { refreshing.value = false }
}
async function book() {
  if (posting.value) return
  posting.value = true; submitError.value = null
  try {
    if (!attempt.value) {
      await refreshSelection()
      if (submitError.value || !selected.value.bookable) { submitError.value ||= new Error('此场次当前不可预约，请选择其他场次。'); return }
      const association = triage.value?.canBook && [triage.value.departmentId, ...triage.value.alternativeDepartmentIds].includes(selected.value.departmentId) ? triageId.value : null
      attempt.value = makeAttempt(selected.value, symptom.value, profile.value.name, association)
      sessionStorage.setItem(storageKey, JSON.stringify(attempt.value))
    }
    const visit = await post('/patient/registrations', attempt.value.payload)
    clearAttempt(); visible.value = false; ElMessage.success(`预约成功，挂号号 ${visit.registrationNo}`); await router.push(`/patient/visits/${visit.id}`)
  } catch(e) { submitError.value = e; if (definiteFailure(e)) { clearAttempt(); selected.value = { ...selected.value, bookable: false }; load() } }
  finally { posting.value = false }
}
onMounted(async () => {
  try { const raw = sessionStorage.getItem(storageKey); if (raw) attempt.value = JSON.parse(raw) } catch { sessionStorage.removeItem(storageKey) }
  await Promise.all([setup(), load()])
})
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">预约挂号</h1><p>选择未来七天的可预约场次，确认就诊安排。</p></div><router-link to="/patient/visits" class="el-button">查看我的就诊</router-link></div>
  <ErrorPanel :error="setupError" @retry="setup" />
  <ErrorPanel :error="triageError" />
  <div v-if="triageId && triage?.canBook" class="pending-banner" role="status"><span>来自导诊建议：{{ triage.departmentName }}。选择其他科室会清除导诊关联。</span><el-button :disabled="!!attempt" @click="clearTriage(); search()">改为普通预约</el-button></div>
  <ErrorPanel :error="selectionError" @retry="choose(failedSelection)" />
  <div v-if="attempt" class="pending-banner" role="status"><span>有一笔预约请求的结果尚未确认。</span><el-button @click="resume">继续确认原预约</el-button></div>
  <section class="panel booking-filters"><div><label for="bookingDepartment">就诊科室</label><el-select id="bookingDepartment" v-model="filters.departmentId" clearable placeholder="全部科室" @change="search()"><el-option v-for="d in departments" :key="d.id" :label="d.name" :value="d.id" /></el-select></div><div class="date-filter"><span class="field-label">就诊日期</span><div class="date-options"><button :class="{ selected: !filters.date }" :aria-pressed="!filters.date" @click="filters.date = ''; search()">全部日期</button><button v-for="(day,i) in days" :key="day" :class="{ selected: filters.date === day }" :aria-pressed="filters.date === day" @click="filters.date = day; search()"><strong>{{ i === 0 ? '今天' : day.slice(5).replace('-', '/') }}</strong><small>{{ new Date(`${day}T00:00:00+08:00`).toLocaleDateString('zh-CN', { weekday:'short', timeZone:'Asia/Shanghai' }) }}</small></button></div></div></section>
  <ErrorPanel :error="error" @retry="load" />
  <section v-loading="loading" class="schedule-list" aria-live="polite"><div v-if="data && !data.items.length" class="panel empty-state"><h2>暂无可预约场次</h2><p>当前科室或日期没有开放号源，可尝试其他日期或科室。</p><el-button @click="filters.departmentId = ''; filters.date = ''; search()">查看全部可预约场次</el-button></div><article v-for="s in data?.items || []" :key="s.id" class="panel schedule-card"><div class="schedule-doctor"><h2>{{ s.doctorName }}</h2><p>{{ s.departmentName }}</p></div><div class="schedule-time"><strong>{{ s.visitDate }}</strong><span>{{ slots[s.timeSlot] }}</span><small>预约截止 {{ dateTime(s.bookingDeadline) }}</small></div><div class="schedule-stock"><strong>剩余 {{ s.remainingCount }} 号</strong><span>总号源 {{ s.totalCount }} · {{ money(s.fee) }}</span></div><el-button type="primary" :disabled="confirming || !!setupError || !!attempt" @click="choose(s)">选择场次</el-button></article></section>
  <Pager :page="filters.page" :page-size="12" :total="data?.total || 0" @change="search" />
  <el-dialog v-model="visible" title="确认预约信息" width="560px" :close-on-click-modal="false" :close-on-press-escape="!posting" :show-close="!posting" :before-close="done => { if (!posting) done() }">
    <template v-if="selected"><dl class="detail-grid"><div><dt>就诊人</dt><dd>{{ attempt?.patientName || profile?.name }}</dd></div><div><dt>科室 / 医生</dt><dd>{{ selected.departmentName }} / {{ selected.doctorName }}</dd></div><div><dt>就诊时间</dt><dd>{{ selected.visitDate }} · {{ slots[selected.timeSlot] }}</dd></div><div><dt>挂号费用</dt><dd>{{ money(selected.fee) }}</dd></div><div><dt>取消截止</dt><dd>{{ dateTime(selected.bookingDeadline) }}</dd></div><div><dt>剩余号源</dt><dd>{{ selected.remainingCount }} 号</dd></div></dl><label for="symptoms">就诊说明（可不填）</label><el-input id="symptoms" v-model="symptom" type="textarea" :rows="3" :maxlength="10000" :disabled="!!attempt || posting" placeholder="简述本次就诊原因" /><p class="muted">最终余号由提交时确认；预约后可在截止前取消。</p><ErrorPanel :error="submitError" /><p v-if="!selected.bookable && !attempt" class="danger-text">此场次目前不可预约，请刷新或选择其他场次。</p></template>
    <template #footer><el-button :disabled="posting" @click="visible = false">返回</el-button><el-button v-if="!attempt" :loading="refreshing" :disabled="posting" @click="refreshSelection">刷新号源</el-button><el-button type="primary" :loading="posting" :disabled="refreshing || (!selected?.bookable && !attempt)" @click="book">{{ attempt ? '确认原请求结果' : '确认预约' }}</el-button></template>
  </el-dialog>
</template>
