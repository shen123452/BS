<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { doctorsApi } from '../api'

const loading = ref(false)
const loadError = ref('')
let loadVersion = 0
const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 20 })
const dialog = reactive({ visible: false, mode: 'create', id: null, submitting: false, error: '', form: blankForm() })

function blankForm() {
  return { username: '', realName: '', phone: '', password: '' }
}

async function load() {
  const version = ++loadVersion
  loading.value = true
  loadError.value = ''
  try {
    const data = await doctorsApi.list({ pageNum: query.pageNum, pageSize: query.pageSize })
    if (version !== loadVersion) return
    list.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    if (version === loadVersion) { loadError.value = e.message; list.value = []; total.value = 0 }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

function openCreate() {
  dialog.error = ''
  dialog.mode = 'create'
  dialog.id = null
  dialog.form = blankForm()
  dialog.visible = true
}

function openEdit(row) {
  dialog.error = ''
  dialog.mode = 'edit'
  dialog.id = row.id
  dialog.form = { username: row.username, realName: row.realName, phone: row.phone, password: '' }
  dialog.visible = true
}

async function submit() {
  if (dialog.submitting) return
  const f = dialog.form
  dialog.error = ''
  if (!f.username.trim()) { dialog.error = '请填写用户名。'; return }
  if ((dialog.mode === 'create' || f.password) && f.password.length < 6) { dialog.error = '密码至少 6 位；编辑时留空可保留原密码。'; return }
  if (f.phone && !/^1[3-9]\d{9}$/.test(f.phone)) { dialog.error = '请填写有效的 11 位手机号，或留空。'; return }
  dialog.submitting = true
  try {
    if (dialog.mode === 'create') {
      await doctorsApi.create(dialog.form)
      ElMessage.success('医生账号已创建')
    } else {
      const body = { realName: dialog.form.realName, phone: dialog.form.phone }
      if (dialog.form.password) body.password = dialog.form.password
      await doctorsApi.update(dialog.id, body)
      ElMessage.success('已保存(重置密码会使旧登录失效)')
    }
    dialog.visible = false
    load()
  } catch (e) {
    dialog.error = e.message
  } finally {
    dialog.submitting = false
  }
}

async function toggle(row) {
  const next = row.status === 1 ? 0 : 1
  try {
    await ElMessageBox.confirm(
      `确认${next === 1 ? '启用' : '停用'}医生 ${row.username}?停用后其登录凭证立即失效。`, '二次确认', { type: 'warning' }
    )
  } catch { return }
  try {
    await doctorsApi.toggleStatus(row.id, next)
    ElMessage.success('已更新')
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <div class="toolbar">
      <span class="muted" style="font-size:13px">管理医生账号与登录状态</span>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新建医生账号</el-button>
    </div>

    <div v-if="loadError" class="state-error" role="alert"><span>{{ loadError }}</span><el-button @click="load">重新加载</el-button></div>
    <el-table :empty-text="loading ? '正在加载…' : (loadError ? '加载失败，请重试' : '暂无数据')" :data="list" v-loading="loading">
      <el-table-column prop="username" label="用户名" min-width="140" />
      <el-table-column label="姓名" min-width="120">
        <template #default="{ row }">{{ row.realName || '—' }}</template>
      </el-table-column>
      <el-table-column label="手机号" min-width="130">
        <template #default="{ row }">{{ row.phone || '—' }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span class="status-tag" :class="row.status === 1 ? 'st-success' : 'st-muted'">
            {{ row.status === 1 ? '启用' : '停用' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column label="操作" width="140" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'danger' : 'success'" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 16px; justify-content: flex-end"
                   @current-change="load" />

    <el-dialog v-model="dialog.visible" :title="dialog.mode === 'create' ? '新建医生账号' : '编辑医生账号'"
               width="460px" :close-on-click-modal="!dialog.submitting" :close-on-press-escape="!dialog.submitting" :show-close="!dialog.submitting">
      <el-form label-position="top" :disabled="dialog.submitting">
        <el-form-item label="用户名" required>
          <el-input v-model="dialog.form.username" :disabled="dialog.mode === 'edit'" />
        </el-form-item>
        <el-form-item label="姓名">
          <el-input v-model="dialog.form.realName" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="dialog.form.phone" />
        </el-form-item>
        <el-form-item :label="dialog.mode === 'create' ? '初始密码(至少 6 位)' : '重置密码(留空不修改)'"
                      :required="dialog.mode === 'create'">
          <el-input v-model="dialog.form.password" type="password" show-password />
        </el-form-item>
      </el-form>
      <div v-if="dialog.error" class="state-error" role="alert">{{ dialog.error }}</div>
      <template #footer>
        <el-button :disabled="dialog.submitting" @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
