<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { patientsApi, doctorsApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const loadError = ref('')
let loadVersion = 0
const list = ref([])
const doctors = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })

const drawer = reactive({ visible: false, data: null, loading: false, error: '', id: '' })
let detailVersion = 0
const doctorsError = ref('')
const toggling = ref('')
const binding = reactive({ visible: false, row: null, doctorId: null, saving: false, error: '' })

async function load() {
  const version = ++loadVersion
  loading.value = true
  loadError.value = ''
  try {
    const data = await patientsApi.list({ pageNum: query.pageNum, pageSize: query.pageSize, keyword: query.keyword })
    if (version !== loadVersion) return
    list.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    if (version === loadVersion) { loadError.value = e.message; list.value = []; total.value = 0 }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function openDetail(row) {
  const version = ++detailVersion
  drawer.visible = true; drawer.data = null; drawer.loading = true; drawer.error = ''; drawer.id = row.userId
  try { const data = await patientsApi.detail(row.userId); if (version === detailVersion) drawer.data = data }
  catch (e) { if (version === detailVersion) drawer.error = e.message }
  finally { if (version === detailVersion) drawer.loading = false }
}

async function loadDoctors() {
  if (!auth.isAdmin) return
  doctorsError.value = ''
  try {
    const data = await doctorsApi.list({ pageNum: 1, pageSize: 100 })
    doctors.value = (data.records || []).filter(d => d.status === 1)
  } catch (e) { doctorsError.value = e.message }
}

async function handleBind(row) {
  if (binding.saving) return
  binding.error = ''
  binding.row = row
  binding.doctorId = row.doctorId || null
  binding.visible = true
}

async function saveBinding() {
  if (binding.saving || !binding.row || doctorsError.value) return
  binding.error = ''
  binding.saving = true
  try {
    await patientsApi.bindDoctor(binding.row.userId, binding.doctorId || 0)
    ElMessage.success(binding.doctorId ? '已绑定' : '已解绑')
    binding.visible = false
    load()
  } catch (e) {
    binding.error = e.message
  } finally { binding.saving = false }
}

async function handleToggle(row) {
  if (toggling.value) return
  toggling.value = String(row.userId)
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      `确认${next === 1 ? '启用' : '停用'}患者 ${row.username}?停用后其登录凭证立即失效。`, '二次确认', { type: 'warning' }
    )
  } catch { toggling.value = ''; return }
  try {
    await patientsApi.toggleStatus(row.userId, next)
    ElMessage.success('已更新')
    load()
  } catch (e) {
    ElMessage.error(e.message)
  } finally { toggling.value = '' }
}

onMounted(() => { load(); loadDoctors() })
</script>

<template>
  <div class="panel">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="按用户名/姓名搜索" clearable style="width: 240px"
                aria-label="按用户名或姓名搜索患者" @keyup.enter="query.pageNum = 1; load()" @clear="query.pageNum = 1; load()" />
      <el-button type="primary" @click="query.pageNum = 1; load()">搜索患者</el-button>
      <div class="spacer" />
      <span class="muted" style="font-size:13px">
        共 {{ total }} 名患者{{ auth.isDoctor ? '(已绑定)' : '' }}
      </span>
    </div>

    <div v-if="loadError" class="state-error" role="alert"><span>{{ loadError }}</span><el-button @click="load">重新加载</el-button></div>
    <el-table :empty-text="loading ? '正在加载…' : (loadError ? '加载失败，请重试' : '暂无数据')" :data="list" v-loading="loading">
      <template #empty><el-empty :description="loading ? '正在加载…' : (loadError ? '加载失败，请重试' : '没有找到患者，请调整搜索条件')" :image-size="72" /></template>
      <el-table-column label="患者" min-width="170">
        <template #default="{ row }"><div class="person-name">{{ row.realName || '未填写姓名' }}</div><div class="person-account">{{ row.username }}</div></template>
      </el-table-column>
      <el-table-column label="数据性质" width="120">
        <template #default="{ row }">
          <span class="status-tag" :class="row.dataMode === 'DEMO' ? 'st-demo' : 'st-info'">
            {{ row.dataMode === 'DEMO' ? '演示数据' : (row.dataMode === 'REAL' ? '真实数据' : '性质未标注') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="绑定医生" min-width="120">
        <template #default="{ row }">{{ row.doctorName || '未绑定' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span class="status-tag" :class="row.status === 1 ? 'st-success' : 'st-muted'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="注册时间" width="170" />
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">查看</el-button>
          <el-button v-if="auth.isAdmin" link @click="handleBind(row)">绑定医生</el-button>
          <el-button v-if="auth.isAdmin" link :type="row.status === 1 ? 'danger' : 'success'"
                     :disabled="!!toggling" @click="handleToggle(row)">{{ row.status === 1 ? '停用' : '启用' }}</el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 16px; justify-content: flex-end"
                   @current-change="load" />

    <el-drawer v-model="drawer.visible" title="患者档案" size="480px">
      <div v-if="drawer.loading" class="detail-loading" role="status">正在加载患者档案…</div>
      <div v-else-if="drawer.error" class="state-error" role="alert">{{ drawer.error }}<el-button @click="openDetail({ userId: drawer.id })">重试</el-button></div>
      <template v-else-if="drawer.data">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="用户名">{{ drawer.data.username }}</el-descriptions-item>
          <el-descriptions-item label="姓名">{{ drawer.data.realName || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="手机号">{{ drawer.data.phone || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="糖尿病类型">{{ drawer.data.diabetesType || '未填写' }}</el-descriptions-item>
          <el-descriptions-item label="CGM 目标范围">
            {{ drawer.data.cgmTargetLow }} – {{ drawer.data.cgmTargetHigh }} mmol/L
            <span class="muted">(当前设置,非统一诊断标准)</span>
          </el-descriptions-item>
          <el-descriptions-item label="绑定医生">{{ drawer.data.doctorName || '未绑定' }}</el-descriptions-item>
          <el-descriptions-item label="数据版本">
            data {{ drawer.data.dataRevision }} / target {{ drawer.data.targetRevision }}
          </el-descriptions-item>
        </el-descriptions>
        <p class="muted" style="font-size:13px">
          血糖记录与指标请前往「血糖与分析」页面,按该患者查询。
        </p>
      </template>
    </el-drawer>
    <el-dialog v-model="binding.visible" title="绑定医生" width="440px" :close-on-click-modal="!binding.saving" :show-close="!binding.saving">
      <p>为 {{ binding.row?.realName || binding.row?.username }} 选择医生，清空选择可解除绑定。</p>
      <div v-if="doctorsError" class="state-error">{{ doctorsError }}<el-button @click="loadDoctors">重试加载医生</el-button></div>
      <el-select :disabled="binding.saving || !!doctorsError" v-model="binding.doctorId" clearable filterable placeholder="选择医生或清空解绑" style="width:100%" aria-label="绑定医生">
        <el-option v-for="doctor in doctors" :key="doctor.id" :value="doctor.id" :label="`${doctor.realName || doctor.username}（${doctor.username}）`" />
      </el-select>
      <div v-if="binding.error" class="state-error" role="alert">{{ binding.error }}</div>
      <template #footer><el-button :disabled="binding.saving" @click="binding.visible = false">取消</el-button><el-button type="primary" :loading="binding.saving" @click="saveBinding">保存绑定</el-button></template>
    </el-dialog>
  </div>
</template>
