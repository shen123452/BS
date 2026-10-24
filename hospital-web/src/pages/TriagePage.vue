<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router'
import { ElMessageBox } from 'element-plus'
import { get, post, put } from '../lib/http.js'
import { definiteFailure } from '../lib/bookingAttempt.js'
import { dateTime, slots, money } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
const route = useRoute(), router = useRouter()
const session = ref(null), run = ref(null), result = ref(null), error = ref(null)
const loading = ref(false), starting = ref(false), cancelling = ref(false), saved = ref(false)
const attempt = ref(null), active = computed(() => ['QUEUED','RUNNING'].includes(run.value?.status))
const form = reactive({ chiefComplaint:'', duration:'', severity:'', associated:'', negatives:'',
  riskAnswers:{ chestPain:'UNKNOWN', severeBreathing:'UNKNOWN', unconscious:'UNKNOWN', majorBleeding:'UNKNOWN', paralysis:'UNKNOWN' } })
const risks = [['chestPain','胸痛'],['severeBreathing','严重呼吸困难'],['unconscious','意识不清'],['majorBleeding','大出血'],['paralysis','突发偏瘫']]
const anyDanger = computed(() => Object.values(form.riskAnswers).includes('YES'))
const names = { QUEUED:'等待处理', RUNNING:'导诊中', SUCCEEDED:'已完成', DEGRADED:'信息或证据有限', FAILED:'处理失败', CANCELLED:'已停止' }
const eventNames = { run_started:'任务已创建', step_started:'开始处理', step_finished:'处理完成', validation:'已校验', result_ready:'结果已生成', run_failed:'未完成', run_cancelled:'已停止' }
const eventLog = ref([]); let eventCursor = 0, lastRunId = null
let timer, disposed = false
const storageKey = computed(() => `hospital.triage.${session.value?.id || route.params.sessionId}`)
function snapshot() { return { chiefComplaint:form.chiefComplaint.trim(), duration:form.duration.trim(), severity:form.severity.trim(),
  associatedSymptoms:form.associated.split(/[，,\n]/).map(s => s.trim()).filter(Boolean),
  pertinentNegatives:form.negatives.split(/[，,\n]/).map(s => s.trim()).filter(Boolean), riskAnswers:{...form.riskAnswers} } }
async function load() {
  loading.value = true; error.value = null
  try {
    if (route.params.runId) { await poll(route.params.runId); return }
    session.value = await get(`/patient/consultations/${route.params.sessionId}`)
    if (session.value.summaryJson) {
      const summary = JSON.parse(session.value.summaryJson)
      Object.assign(form, summary, { associated:(summary.associatedSymptoms || []).join('，'), negatives:(summary.pertinentNegatives || []).join('，') })
    } else {
      const messages = await get(`/patient/consultations/${session.value.id}/messages`)
      form.chiefComplaint = messages.filter(m => m.role === 'USER').at(-1)?.content?.slice(0,1000) || ''
    }
    saved.value = false
    const raw = sessionStorage.getItem(storageKey.value)
    if (raw) {
      attempt.value = JSON.parse(raw)
      if (attempt.value.runId) { await router.replace(`/patient/triage/${attempt.value.runId}`); return }
    }
  } catch(e) { error.value = e } finally { loading.value = false }
}
async function start() {
  if (starting.value || active.value) return
  if (!form.chiefComplaint.trim() || !form.duration.trim() || !form.severity.trim()) { error.value = new Error('请填写主要不适、持续时间和严重程度。'); return }
  starting.value = true; error.value = null
  try {
    if (!attempt.value) {
      session.value = await put(`/patient/consultations/${session.value.id}/summary`, { summary:snapshot(), expectedSummaryVersion:session.value.summaryVersion, confirm:true })
      saved.value = true
      attempt.value = { payload:{ sessionId:session.value.id, summaryVersion:session.value.summaryVersion, clientRequestId:crypto.randomUUID() } }
      sessionStorage.setItem(storageKey.value,JSON.stringify(attempt.value))
    }
    const created = await post('/patient/triage-runs',attempt.value.payload)
    attempt.value.runId = created.run.id
    sessionStorage.setItem(storageKey.value,JSON.stringify(attempt.value))
    await router.replace(`/patient/triage/${created.run.id}`)
  } catch(e) {
    error.value = e
    if (definiteFailure(e)) { sessionStorage.removeItem(storageKey.value); attempt.value = null }
  } finally { starting.value = false }
}
async function poll(id = route.params.runId) {
  clearTimeout(timer); error.value = null
  try {
    if (id !== lastRunId) { eventLog.value = []; eventCursor = 0; lastRunId = id }
    run.value = await get(`/patient/triage-runs/${id}`)
    // 持久事件按 cursor 增量补齐：页面重开从 0 重放公开事件，前端按 seq 去重
    try {
      const feed = await get(`/patient/triage-runs/${id}/events`, { cursor:eventCursor })
      if (feed.items?.length) eventLog.value.push(...feed.items.filter(e => !eventLog.value.some(x => x.seq === e.seq)))
      eventCursor = Math.max(eventCursor, feed.latestSeq || 0)
    } catch { eventLog.value = []; eventCursor = 0 }
    result.value = null
    if (!active.value) sessionStorage.removeItem(`hospital.triage.${run.value.sessionId}`)
    if (run.value.resultId) result.value = await get(`/patient/triage-results/${run.value.resultId}`)
    if (!disposed && active.value) timer = setTimeout(() => poll(id),2000)
  } catch(e) { error.value = e }
}
async function cancel() {
  cancelling.value = true
  try { await post(`/patient/triage-runs/${run.value.id}/cancel`); await poll(run.value.id) }
  catch(e) { error.value = e } finally { cancelling.value = false }
}
onBeforeRouteLeave(async to => {
  if (!route.params.sessionId || saved.value || !form.chiefComplaint || to.path.startsWith('/patient/triage/')) return true
  try { await ElMessageBox.confirm('当前症状摘要尚未确认，离开会丢失本页修改。', '离开摘要核对', { confirmButtonText:'确认离开', cancelButtonText:'继续填写' }); return true } catch { return false }
})
onBeforeUnmount(() => { disposed = true; clearTimeout(timer) })
onMounted(load)
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">{{ route.params.runId ? '导诊结果' : '核对症状并导诊' }}</h1><p>建议用于就医参考；症状有变化时，请重新确认摘要。</p></div><router-link to="/patient/consultations" class="el-button">返回问诊</router-link></div>
  <ErrorPanel v-if="route.params.runId || !session" :error="error" @retry="route.params.runId ? poll() : load()" />
  <ErrorPanel v-else :error="error" />
  <form v-if="!route.params.runId" class="panel form-panel triage-form" v-loading="loading" @submit.prevent="start">
    <fieldset class="profile-fields" :disabled="starting || !!attempt"><h2>症状摘要</h2><p class="muted">请核对自己的描述，不确定的信息保留“不确定”。</p>
      <label for="triage-complaint">主要不适</label><el-input id="triage-complaint" v-model="form.chiefComplaint" type="textarea" :rows="3" maxlength="1000" :disabled="starting || !!attempt" />
      <div class="form-grid"><div><label for="triage-duration">持续时间</label><el-input id="triage-duration" v-model="form.duration" maxlength="100" placeholder="例如：三天" :disabled="starting || !!attempt" /></div><div><label for="triage-severity">严重程度</label><el-input id="triage-severity" v-model="form.severity" maxlength="100" placeholder="例如：轻微，不影响日常活动" :disabled="starting || !!attempt" /></div></div>
      <label for="triage-associated">伴随症状（可不填）</label><el-input id="triage-associated" v-model="form.associated" maxlength="1000" placeholder="多项用逗号分隔" :disabled="starting || !!attempt" />
      <label for="triage-negatives">明确没有的相关症状（可不填）</label><el-input id="triage-negatives" v-model="form.negatives" maxlength="1000" :disabled="starting || !!attempt" />
      <h2>危险信号核对</h2><p class="muted">以下任一情况存在时，应及时急诊，不要等待普通门诊预约。</p>
      <div class="risk-fields"><div v-for="[key,label] in risks" :key="key" class="risk-row"><span :id="`risk-${key}`">{{ label }}</span><el-radio-group v-model="form.riskAnswers[key]" :aria-labelledby="`risk-${key}`" :disabled="starting || !!attempt"><el-radio value="YES">有</el-radio><el-radio value="NO">无</el-radio><el-radio value="UNKNOWN">不确定</el-radio></el-radio-group></div></div>
    </fieldset>
    <p v-if="anyDanger" class="triage-emergency" role="alert">你选择了危险信号，请立即拨打120或就近急诊。</p>
    <p v-if="attempt" class="muted" role="status">上次请求结果尚未确认，继续提交会复用同一请求号。</p>
    <div class="form-actions"><el-button type="primary" native-type="submit" :loading="starting" :disabled="loading || !session">{{ attempt ? '继续确认原导诊请求' : '确认并开始导诊' }}</el-button><router-link to="/patient/appointments" class="el-button">普通预约</router-link></div>
  </form>
  <template v-else-if="run">
    <section class="panel"><div class="section-heading"><h2>{{ names[run.status] || run.status }}</h2><el-button v-if="active" :loading="cancelling" @click="cancel">停止导诊</el-button><el-button v-else @click="poll()">刷新结果</el-button></div>
      <p v-if="run.errorMessage" class="danger-text">{{ run.errorMessage }}</p>
      <ol v-if="eventLog.length" class="agent-timeline" aria-live="polite"><li v-for="event in eventLog" :key="event.seq"><strong>{{ event.label || eventNames[event.type] || event.type }}</strong><span v-if="event.type === 'step_finished'">{{ {SUCCEEDED:'已完成',FAILED:'未完成',TIMEOUT:'查询超时'}[event.status] || event.status }}<small v-if="event.durationMs != null"> · {{ (event.durationMs / 1000).toFixed(1) }} 秒</small></span><span v-else-if="event.type === 'result_ready'">就绪</span></li></ol>
      <p v-else-if="!run.steps?.length" class="muted">任务已接收，等待处理。</p>
      <div v-if="!active && !result" class="form-actions"><router-link :to="`/patient/consultations/${run.sessionId}/summary`" class="el-button el-button--primary">重新核对症状</router-link><router-link to="/patient/appointments" class="el-button">普通预约</router-link></div>
    </section>
    <section v-if="result" class="panel triage-result"><h2>{{ result.riskLevel === 'EMERGENCY' ? '请及时急诊' : result.departmentName || '请补充信息后再导诊' }}</h2><p :class="result.riskLevel === 'EMERGENCY' ? 'triage-emergency' : 'preserve-text'">{{ result.reason }}</p>
      <p v-if="!result.canBook" class="muted">{{ result.unavailableReason }}</p>
      <ul v-if="result.missingInformation?.length"><li v-for="question in result.missingInformation" :key="question">{{ question }}</li></ul>
      <div class="form-actions"><router-link v-if="result.canBook" :to="{ path:'/patient/appointments', query:{departmentId:result.departmentId,triageResultId:result.id} }" class="el-button el-button--primary">查看推荐科室号源</router-link><router-link v-if="result.riskLevel !== 'EMERGENCY'" :to="`/patient/consultations/${result.sessionId}/summary`" class="el-button">补充症状</router-link></div>
      <h3 v-if="result.evidenceRefs?.length" class="evidence-heading">资料来源</h3><article v-for="ref in result.evidenceRefs" :key="ref.chunkId" class="evidence-source"><strong>{{ ref.title }}</strong><small>{{ ref.sourceDescription }} · {{ ref.documentVersion }}<template v-if="ref.pageStart"> · 第{{ ref.pageStart }}页</template><template v-else-if="ref.paragraphStart"> · 第{{ ref.paragraphStart }}段</template></small><p>{{ ref.excerpt }}</p></article>
      <h3 v-if="result.doctorOptions?.length" class="evidence-heading">查询时的可预约场次</h3><div v-for="option in result.doctorOptions" :key="option.id" class="triage-schedule"><strong>{{ option.doctorName }}</strong><span>{{ option.visitDate }} · {{ slots[option.timeSlot] }} · 余{{ option.remainingCount }}号 · {{ money(option.fee) }}</span></div><p class="muted">号源以预约页面实时查询为准。结果有效期至 {{ dateTime(result.expiresAt) }}。</p>
    </section>
  </template>
</template>
