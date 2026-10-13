<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth } from '../lib/auth.js'
import { get, put, post } from '../lib/http.js'
import { recordFields, recordPayload, slots, dateTime } from '../lib/helpers.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
const route = useRoute(), doctor = auth.user.role === 'DOCTOR', base = doctor ? '/doctor' : '/patient', url = `${base}/registrations/${route.params.id}`
const record = ref(null), visit = ref(null), form = reactive({}), snapshot = ref(''), loading = ref(false), saving = ref(false), submitting = ref(false), error = ref(null), actionError = ref(null), preview = ref(false), conflict = ref(false), savedAt = ref('')
const editable = computed(() => doctor && record.value?.status === 'DRAFT')
const dirty = computed(() => editable.value && JSON.stringify(recordPayload(form)) !== snapshot.value)
function apply(result) { record.value = result; Object.assign(form, recordPayload(result)); snapshot.value = JSON.stringify(recordPayload(form)); conflict.value = false }
async function load() {
  loading.value = true; error.value = null
  try { const detail = await get(url); visit.value = doctor ? detail.registration : detail; apply(await get(`${url}/medical-record`)) } catch(e) { error.value = e } finally { loading.value = false }
}
async function save() {
  if (saving.value || conflict.value) return false
  saving.value = true; actionError.value = null
  try { apply(await put(`${url}/medical-record`, recordPayload(form))); savedAt.value = new Date().toLocaleTimeString('zh-CN', { hour:'2-digit', minute:'2-digit' }); return true }
  catch(e) { actionError.value = e; conflict.value = e.code === 'RECORD_VERSION_CONFLICT'; return false } finally { saving.value = false }
}
async function saveDraft() { if (await save()) ElMessage.success('病历草稿已保存') }
function showPreview() {
  actionError.value = null
  const missing = recordFields.filter(([key]) => !form[key]?.trim())
  if (missing.length) { actionError.value = new Error(`请补充：${missing.map(([,label]) => label).join('、')}。未查项目请填写实际说明。`); document.getElementById(missing[0][0])?.focus(); return }
  preview.value = true
}
async function complete() {
  if (submitting.value) return
  submitting.value = true; actionError.value = null
  try {
    if (dirty.value && !(await save())) return
    apply(await post(`${url}/complete`, { version:form.version })); preview.value = false; ElMessage.success('病历已提交，本次就诊已完成')
  } catch(e) { actionError.value = e; conflict.value = e.code === 'RECORD_VERSION_CONFLICT' } finally { submitting.value = false }
}
async function discardReload() {
  try { await ElMessageBox.confirm('重新载入会替换当前未保存正文。可以先复制当前内容，再加载服务端最新病历。', '重新载入病历', { confirmButtonText:'重新载入', cancelButtonText:'保留当前正文', type:'warning' }) } catch { return }
  await load(); actionError.value = null; preview.value = false
}
async function copy() {
  try { await navigator.clipboard.writeText(recordFields.map(([k,label]) => `${label}\n${form[k] || ''}`).join('\n\n')); ElMessage.success('当前正文已复制') } catch { ElMessage.warning('无法自动复制，请选中正文手动复制') }
}
function beforeUnload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(async () => {
  if (saving.value || submitting.value) return false
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('病历有未保存的修改。离开后本次修改会丢失。', '离开病历编辑', { confirmButtonText:'放弃修改并离开', cancelButtonText:'继续编辑', type:'warning' }); return true } catch { return false }
})
onMounted(() => { window.addEventListener('beforeunload', beforeUnload); load() })
onUnmounted(() => window.removeEventListener('beforeunload', beforeUnload))
</script>
<template>
  <div class="page-heading"><div><router-link :to="`${base}/visits/${route.params.id}`" class="back-link">返回就诊详情</router-link><h1 tabindex="-1">{{ editable ? '填写门诊病历' : '门诊病历' }}</h1><p v-if="visit">{{ visit.patientName }} · {{ visit.departmentName }} · {{ visit.doctorName }} · {{ visit.visitDate }} {{ slots[visit.timeSlot] }}</p></div><StatusBadge v-if="record" :status="record.status" /></div>
  <div v-if="editable && visit" class="record-identity"><strong>当前患者：{{ visit.patientName }}</strong><span>挂号号 {{ visit.registrationNo }}</span><span>{{ visit.visitDate }} · {{ slots[visit.timeSlot] }}</span></div>
  <ErrorPanel :error="error" @retry="load"><p v-if="error?.status === 404">病历尚未提交、接诊尚未开始，或你无权访问此记录。</p></ErrorPanel>
  <ErrorPanel :error="actionError"><div v-if="conflict" class="conflict-actions"><p>病历已在其他窗口更新。当前正文已保留，请复制后重新载入，核对后再保存。</p><el-button @click="copy">复制当前正文</el-button><el-button @click="discardReload">重新载入最新病历</el-button></div></ErrorPanel>
  <div v-loading="loading" class="record-layout"><template v-if="record"><nav class="record-nav" aria-label="病历章节"><a v-for="[key,label] in recordFields" :key="key" :href="`#section-${key}`">{{ label }}</a></nav><section class="panel record-content">
    <div v-if="editable" class="record-save-state" role="status"><strong>{{ dirty ? '有未保存的修改' : '当前正文已保存' }}</strong><span>{{ savedAt ? `上次保存 ${savedAt}` : '请按实际接诊情况填写，使用「保存草稿」手动保存。' }}</span></div><p v-else class="muted">已提交于 {{ dateTime(record.submittedAt) }}，病历正文只读。</p>
    <section v-for="[key,label] in recordFields" :id="`section-${key}`" :key="key" class="record-section"><h2><label v-if="editable" :for="key">{{ label }} <span class="required">*</span></label><template v-else>{{ label }}</template></h2><el-input v-if="editable" :id="key" v-model="form[key]" type="textarea" :rows="key === 'presentIllness' || key === 'treatmentPlan' ? 5 : 4" :maxlength="10000" show-word-limit :disabled="saving || submitting || conflict" :placeholder="`填写${label}，未查项目请说明`" /><p v-else class="preserve-text">{{ record[key] || '未填写' }}</p></section>
    <div v-if="editable" class="record-actions"><span class="muted">提交后就诊完成，正文不能再修改。</span><el-button :loading="saving" :disabled="submitting || conflict" @click="saveDraft">保存草稿</el-button><el-button type="primary" :disabled="saving || submitting || conflict" @click="showPreview">预览并提交完成</el-button></div>
  </section></template></div>
  <el-dialog v-model="preview" title="核对并提交病历" width="760px" :close-on-click-modal="false" :close-on-press-escape="!submitting" :show-close="!submitting" :before-close="done => { if (!submitting) done() }"><div class="preview-identity"><strong>{{ visit?.patientName }} · {{ visit?.departmentName }} · {{ visit?.doctorName }}</strong><p>挂号号 {{ visit?.registrationNo }} · {{ visit?.visitDate }}</p></div><ErrorPanel :error="actionError" /><section v-for="[key,label] in recordFields" :key="key" class="record-section"><h3>{{ label }}</h3><p class="preserve-text">{{ form[key] }}</p></section><template #footer><el-button :disabled="submitting" @click="preview = false">返回编辑</el-button><el-button type="primary" :loading="submitting" :disabled="conflict" @click="complete">确认提交并完成就诊</el-button></template></el-dialog>
</template>
