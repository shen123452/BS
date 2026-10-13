<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { auth, roleNames } from '../lib/auth.js'
import { get, post, put, allPages } from '../lib/http.js'
import { useLoad } from '../lib/useLoad.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import StatusBadge from '../components/StatusBadge.vue'
import Pager from '../components/Pager.vue'
const props = defineProps({ kind: String }), route = useRoute(), router = useRouter()
const name = computed(() => ({ users:'用户', departments:'科室', doctors:'医生' }[props.kind]))
const page = ref(Number(route.query.page) || 1), departments = ref([]), directoryError = ref(null)
const { data, loading, error, load } = useLoad(() => get(`/admin/${props.kind}`, { page:page.value, pageSize:20 }))
const modal = ref(false), editing = ref(null), form = reactive({}), saving = ref(false), formError = ref(null), actionError = ref(null), changing = ref(null)
const deptName = id => departments.value.find(d => d.id === id)?.name || '未知科室'
async function directory() { if (props.kind !== 'doctors') return; directoryError.value = null; try { departments.value = await allPages('/admin/departments') } catch(e) { directoryError.value = e } }
function open(item = null) {
  editing.value = item?.id || null; formError.value = null
  Object.keys(form).forEach(key => delete form[key])
  Object.assign(form, props.kind === 'departments' ? { code:'', name:'', description:'', location:'', isEmergency:false, status:'ENABLED' } : { username:'', password:'', departmentId:'', name:'', title:'', specialty:'', status:'ENABLED' }, item || {})
  modal.value = true
}
async function save() {
  if (saving.value) return
  formError.value = null
  if (props.kind === 'doctors' && !form.departmentId) { formError.value = new Error('请选择所属科室。'); return }
  saving.value = true
  try {
    const keys = props.kind === 'departments' ? ['code','name','description','location','isEmergency','status'] : editing.value ? ['departmentId','name','title','specialty','status'] : ['username','password','departmentId','name','title','specialty']
    const payload = Object.fromEntries(keys.map(key => [key,form[key]]))
    const url = `/admin/${props.kind}${editing.value ? `/${editing.value}` : ''}`
    await (editing.value ? put(url, payload) : post(url, payload)); form.password = ''; modal.value = false; ElMessage.success(`${name.value}已保存`); await load()
  }
  catch(e) { formError.value = e } finally { saving.value = false }
}
async function changeStatus(user) {
  const status = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
  try { await ElMessageBox.confirm(status === 'DISABLED' ? `停用「${user.displayName}」后，该账号当前登录也会失效。确认停用？` : `确认恢复「${user.displayName}」的登录权限？`, status === 'DISABLED' ? '停用账号' : '启用账号', { confirmButtonText:'确认', cancelButtonText:'返回', type:'warning' }) } catch { return }
  changing.value = user.id; actionError.value = null
  try { await put(`/admin/users/${user.id}/status`, { status }); ElMessage.success('账号状态已更新'); await load() } catch(e) { actionError.value = e } finally { changing.value = null }
}
async function changePage(value) { page.value = value; router.replace({ query: { page: value > 1 ? value : undefined } }); await load() }
onMounted(() => Promise.all([load(), directory()]))
</script>
<template>
  <div class="page-heading"><div><h1 tabindex="-1">{{ name }}管理</h1><p>{{ { users:'维护登录状态；患者自行注册，医生由管理员开户。', departments:'维护科室职责、位置与启用状态。', doctors:'为医生开户并关联科室，维护职称与专长。' }[kind] }}</p></div><el-button v-if="kind !== 'users'" type="primary" :disabled="!!directoryError" @click="open()">新增{{ name }}</el-button></div>
  <ErrorPanel :error="directoryError" @retry="directory" /><ErrorPanel :error="actionError" />
  <section class="panel"><div class="section-heading"><span class="muted">共 {{ data?.total ?? '—' }} {{ kind === 'doctors' ? '位医生' : kind === 'departments' ? '个科室' : '个账号' }}</span><el-button :loading="loading" @click="load">刷新列表</el-button></div><ErrorPanel :error="error" @retry="load" />
    <div class="table-container"><el-table v-loading="loading" :data="data?.items || []" empty-text="暂无资料" row-key="id">
      <template v-if="kind === 'users'"><el-table-column prop="username" label="账号" min-width="160" /><el-table-column prop="displayName" label="姓名" min-width="130" /><el-table-column label="角色" width="110"><template #default="{ row }">{{ roleNames[row.role] }}</template></el-table-column><el-table-column label="状态" width="110"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column><el-table-column label="操作" min-width="160"><template #default="{ row }"><el-button size="small" :type="row.status === 'ACTIVE' ? 'danger' : 'primary'" plain :disabled="row.id === auth.user.id" :loading="changing === row.id" @click="changeStatus(row)">{{ row.status === 'ACTIVE' ? '停用' : '启用' }}</el-button><small v-if="row.id === auth.user.id" class="muted"> 当前账号</small></template></el-table-column></template>
      <template v-else-if="kind === 'departments'"><el-table-column prop="code" label="编码" min-width="130" /><el-table-column prop="name" label="科室" min-width="140" /><el-table-column prop="location" label="位置" min-width="160" /><el-table-column prop="description" label="职责说明" min-width="240" show-overflow-tooltip /><el-table-column label="急诊" width="85"><template #default="{ row }">{{ row.isEmergency ? '是' : '否' }}</template></el-table-column></template>
      <template v-else><el-table-column prop="name" label="医生姓名" min-width="130" /><el-table-column label="科室" min-width="150"><template #default="{ row }">{{ deptName(row.departmentId) }}</template></el-table-column><el-table-column prop="title" label="职称" min-width="130" /><el-table-column prop="specialty" label="专长" min-width="220" show-overflow-tooltip /></template>
      <template v-if="kind !== 'users'"><el-table-column label="状态" width="110"><template #default="{ row }"><StatusBadge :status="row.status" /></template></el-table-column><el-table-column label="操作" width="100"><template #default="{ row }"><el-button size="small" :disabled="!!directoryError" @click="open(row)">编辑</el-button></template></el-table-column></template>
    </el-table></div><Pager :page="page" :total="data?.total || 0" @change="changePage" />
  </section>
  <el-dialog v-model="modal" :title="`${editing ? '编辑' : '新增'}${name}`" width="600px" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="done => { if (!saving) done() }">
    <ErrorPanel :error="formError" /><form id="masterdataForm" @submit.prevent="save">
      <template v-if="kind === 'departments'"><div class="form-grid"><div><label for="deptCode">科室编码 <span class="required">*</span></label><el-input id="deptCode" v-model="form.code" maxlength="32" pattern="[A-Za-z0-9_-]{1,32}" placeholder="例如 INTERNAL_MEDICINE" required /></div><div><label for="deptName">科室名称 <span class="required">*</span></label><el-input id="deptName" v-model="form.name" maxlength="80" required /></div></div><label for="deptLocation">科室位置</label><el-input id="deptLocation" v-model="form.location" maxlength="120" /><label for="deptDescription">职责说明</label><el-input id="deptDescription" v-model="form.description" type="textarea" :rows="3" maxlength="10000" /><div class="field-group"><el-checkbox v-model="form.isEmergency">急诊科室</el-checkbox></div></template>
      <template v-else><div v-if="!editing" class="form-grid"><div><label for="doctorUsername">医生账号 <span class="required">*</span></label><el-input id="doctorUsername" v-model="form.username" maxlength="64" pattern="[A-Za-z0-9_.-]{3,64}" autocomplete="off" required /></div><div><label for="doctorPassword">初始密码 <span class="required">*</span></label><el-input id="doctorPassword" v-model="form.password" type="password" show-password minlength="8" maxlength="72" autocomplete="new-password" required /></div></div><div class="form-grid"><div><label for="doctorName">医生姓名 <span class="required">*</span></label><el-input id="doctorName" v-model="form.name" maxlength="64" required /></div><div><label for="doctorDepartment">所属科室 <span class="required">*</span></label><el-select id="doctorDepartment" v-model="form.departmentId" placeholder="请选择科室"><el-option v-for="d in departments" :key="d.id" :label="`${d.name}${d.status === 'DISABLED' ? '（停用）' : ''}`" :value="d.id" :disabled="d.status === 'DISABLED' && d.id !== form.departmentId" /></el-select></div></div><label for="doctorTitle">职称</label><el-input id="doctorTitle" v-model="form.title" maxlength="40" /><label for="doctorSpecialty">专长</label><el-input id="doctorSpecialty" v-model="form.specialty" type="textarea" :rows="3" maxlength="10000" /><p v-if="editing" class="muted">转科前需处理未来活动预约，并关闭未来开放排班。</p></template>
      <template v-if="kind === 'departments' || editing"><label for="dataStatus">状态</label><el-select id="dataStatus" v-model="form.status"><el-option label="启用" value="ENABLED" /><el-option label="停用" value="DISABLED" /></el-select><p v-if="form.status === 'DISABLED'" class="muted">停用后不再提供新的预约，关联限制由服务检查。</p></template>
    </form><template #footer><el-button :disabled="saving" @click="modal = false; form.password = ''">返回</el-button><el-button type="primary" native-type="submit" form="masterdataForm" :loading="saving">保存{{ name }}</el-button></template>
  </el-dialog>
</template>
