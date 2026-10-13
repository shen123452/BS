<script setup>
import { reactive, ref, computed, onMounted, onUnmounted } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, put } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'
const form = reactive({ name:'', gender:'UNKNOWN', age:null, chronicStatus:'UNKNOWN', chronicHistory:'', allergyStatus:'UNKNOWN', allergyHistory:'' })
const loading = ref(true), saving = ref(false), error = ref(null), loaded = ref(false)
const snapshot = ref('')
const payload = () => Object.fromEntries(['name','gender','age','chronicStatus','chronicHistory','allergyStatus','allergyHistory'].map(key => [key,form[key]]))
const dirty = computed(() => loaded.value && JSON.stringify(payload()) !== snapshot.value)
function apply(value) { Object.assign(form,value); snapshot.value = JSON.stringify(payload()) }
async function load() {
  if (saving.value) return
  if (dirty.value) { try { await ElMessageBox.confirm('重新加载会替换当前未保存的档案。是否继续？','重新加载档案',{confirmButtonText:'重新加载',cancelButtonText:'保留当前输入',type:'warning'}) } catch { return } }
  loading.value = true; error.value = null; try { apply(await get('/patient/profile')); loaded.value = true } catch(e) { error.value = e } finally { loading.value = false }
}
async function save() {
  error.value = null
  if ((form.chronicStatus === 'KNOWN' && !form.chronicHistory?.trim()) || (form.allergyStatus === 'KNOWN' && !form.allergyHistory?.trim())) { error.value = new Error('请填写已知的慢病或过敏信息。'); return }
  saving.value = true
  try { apply(await put('/patient/profile', payload())); ElMessage.success('档案已保存') } catch(e) { error.value = e } finally { saving.value = false }
}
function beforeUnload(event) { if (dirty.value) { event.preventDefault(); event.returnValue = '' } }
onBeforeRouteLeave(async () => {
  if (saving.value) return false
  if (!dirty.value) return true
  try { await ElMessageBox.confirm('档案有未保存的修改，离开后会丢失。','离开档案编辑',{confirmButtonText:'放弃修改并离开',cancelButtonText:'继续编辑',type:'warning'}); return true } catch { return false }
})
onMounted(() => { window.addEventListener('beforeunload',beforeUnload); load() })
onUnmounted(() => window.removeEventListener('beforeunload',beforeUnload))
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">我的档案</h1><p>预约前请填写姓名。未提供病史与明确无病史会分别记录。</p></div></div>
  <ErrorPanel :error="error" @retry="load" />
  <section v-loading="loading" class="panel form-panel"><form v-if="loaded" @submit.prevent="save"><fieldset :disabled="saving" class="profile-fields"><p class="muted" role="status">{{ dirty ? '有未保存的修改' : '当前档案已保存' }}</p><h2>基本信息</h2><div class="form-grid"><div><label for="patientName">就诊姓名 <span class="required">*</span></label><el-input id="patientName" v-model="form.name" :disabled="saving" maxlength="64" autocomplete="name" required /></div><div><label for="patientAge">年龄（可不填）</label><el-input-number id="patientAge" v-model="form.age" :disabled="saving" :min="0" :max="120" controls-position="right" /></div><div><label for="patientGender">性别</label><el-select id="patientGender" v-model="form.gender" :disabled="saving"><el-option label="未提供" value="UNKNOWN" /><el-option label="男" value="MALE" /><el-option label="女" value="FEMALE" /></el-select></div></div>
    <h2>健康信息</h2><div v-for="[key,label] in [['chronic','慢病史'],['allergy','过敏史']]" :key="key" class="field-group"><label :for="`${key}Status`">{{ label }}</label><el-select :id="`${key}Status`" v-model="form[`${key}Status`]" :disabled="saving"><el-option label="未提供 / 不确定" value="UNKNOWN" /><el-option label="明确无" value="NONE" /><el-option label="有，填写说明" value="KNOWN" /></el-select><el-input v-if="form[`${key}Status`] === 'KNOWN'" v-model="form[`${key}History`]" :disabled="saving" type="textarea" :rows="4" :maxlength="10000" show-word-limit :aria-label="`${label}说明`" placeholder="请填写已知情况" /></div>
    <div class="form-actions"><el-button type="primary" native-type="submit" :loading="saving">保存档案</el-button></div>
  </fieldset></form></section>
</template>
