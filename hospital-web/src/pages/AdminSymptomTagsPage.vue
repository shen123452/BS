<script setup>
import { onMounted, reactive, ref } from 'vue'
import { get, post, patch, remove } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'

const tags = ref([]), loading = ref(false), saving = ref(false), actionError = ref(null)
const createVisible = ref(false), creating = ref(false)
const form = reactive({ code: '', name: '', aliases: '', description: '', redFlag: false, confirmationQuestion: '' })
const editing = ref(null), editForm = reactive({ name: '', aliases: '', description: '', confirmationQuestion: '' })
const ruleForm = reactive({ departmentId: '', weight: 1 })
const departments = ref([])

async function load() {
  loading.value = true; actionError.value = null
  try {
    tags.value = await get('/admin/symptom-tags')
    if (!departments.value.length) departments.value = await get('/departments')
  } catch (e) { actionError.value = e } finally { loading.value = false }
}
async function create() {
  if (creating.value) return
  creating.value = true; actionError.value = null
  try {
    let aliases = null
    if (form.aliases.trim()) {
      try { aliases = JSON.stringify(JSON.parse(form.aliases)) } catch { actionError.value = new Error('同义词必须是合法 JSON 数组，如 ["发烧"]'); return }
    }
    await post('/admin/symptom-tags', { ...form, aliases })
    createVisible.value = false; Object.assign(form, { code: '', name: '', aliases: '', description: '', redFlag: false, confirmationQuestion: '' })
    await load()
  } catch (e) { actionError.value = e } finally { creating.value = false }
}
function openEdit(tag) {
  editing.value = tag
  Object.assign(editForm, { name: tag.name, aliases: tag.aliases ? JSON.stringify(tag.aliases) : '', description: tag.description || '', confirmationQuestion: tag.confirmationQuestion || '' })
}
async function saveEdit() {
  if (!editing.value || saving.value) return
  saving.value = true; actionError.value = null
  try {
    let aliases = null
    if (editForm.aliases.trim()) {
      try { aliases = JSON.stringify(JSON.parse(editForm.aliases)) } catch { actionError.value = new Error('同义词必须是合法 JSON 数组'); return }
    }
    await patch(`/admin/symptom-tags/${editing.value.id}`, { ...editForm, aliases })
    editing.value = null; await load()
  } catch (e) { actionError.value = e } finally { saving.value = false }
}
async function flip(tag) {
  actionError.value = null
  try { await post(`/admin/symptom-tags/${tag.id}/status`, { status: tag.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' }); await load() }
  catch (e) { actionError.value = e }
}
async function addRule(tag) {
  if (!ruleForm.departmentId) { actionError.value = new Error('请选择科室'); return }
  actionError.value = null
  try { await post(`/admin/symptom-tags/${tag.id}/rules`, { departmentId: ruleForm.departmentId, weight: Number(ruleForm.weight) }); ruleForm.departmentId = ''; await load() }
  catch (e) { actionError.value = e }
}
async function removeRule(rule) {
  actionError.value = null
  try { await remove(`/admin/symptom-rules/${rule.id}`); await load() }
  catch (e) { actionError.value = e }
}
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">症状标签</h1><p>维护症状与科室映射，用于导诊检索提示。停用标签不会关闭系统内置的危急安全检查。</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
  <ErrorPanel :error="actionError" @retry="load" />
  <section class="panel">
    <div class="section-head"><h2>症状标签（{{ tags.length }}）</h2><el-button type="primary" @click="createVisible = true">新增标签</el-button></div>
    <div class="table-container">
      <el-table v-loading="loading" :data="tags" empty-text="暂无症状标签">
        <el-table-column prop="code" label="编码" min-width="150" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="危急提示" width="110"><template #default="{row}">{{ row.redFlag ? '红标' : '—' }}</template></el-table-column>
        <el-table-column label="科室映射" min-width="240">
          <template #default="{row}">
            <span v-if="!row.rules?.length" class="muted">未配置</span>
            <el-tag v-for="r in row.rules" :key="r.id" size="small" closable class="rule-tag" @close="removeRule(r)">{{ r.departmentName }} · {{ r.weight }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="160">
          <template #default="{row}">
            <el-switch :model-value="row.status === 'ENABLED'" :aria-label="`${row.name} 启用开关`" @change="flip(row)" />
            <span class="status-text">{{ row.status === 'ENABLED' ? '启用' : '停用' }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110"><template #default="{row}"><el-button size="small" @click="openEdit(row)">编辑</el-button></template></el-table-column>
      </el-table>
    </div>
  </section>

  <el-dialog v-model="createVisible" title="新增症状标签" width="560px">
    <form @submit.prevent="create">
      <label for="tag-code">编码</label><el-input id="tag-code" v-model="form.code" :maxlength="64" placeholder="如 nausea" />
      <label for="tag-name">名称</label><el-input id="tag-name" v-model="form.name" :maxlength="100" />
      <label for="tag-aliases">同义词（JSON 数组，可留空）</label><el-input id="tag-aliases" v-model="form.aliases" placeholder='["恶心","想吐"]' />
      <label for="tag-question">需确认问题</label><el-input id="tag-question" v-model="form.confirmationQuestion" :maxlength="500" />
      <label for="tag-desc">说明</label><el-input id="tag-desc" v-model="form.description" :maxlength="500" />
      <label class="check-line"><input id="tag-redflag" v-model="form.redFlag" type="checkbox" /> 可能对应危急征象（仅提示标记）</label>
      <div class="form-actions"><el-button type="primary" native-type="submit" :loading="creating">新增</el-button></div>
    </form>
  </el-dialog>

  <el-dialog :model-value="!!editing" :title="`编辑 ${editing?.name || ''}`" width="560px" @close="editing = null">
    <label for="edit-name">名称</label><el-input id="edit-name" v-model="editForm.name" :maxlength="100" />
    <label for="edit-aliases">同义词（JSON 数组）</label><el-input id="edit-aliases" v-model="editForm.aliases" />
    <label for="edit-question">需确认问题</label><el-input id="edit-question" v-model="editForm.confirmationQuestion" :maxlength="500" />
    <label for="edit-desc">说明</label><el-input id="edit-desc" v-model="editForm.description" :maxlength="500" />
    <h3>新增科室映射</h3>
    <div class="rule-add">
      <el-select v-model="ruleForm.departmentId" placeholder="选择科室"><el-option v-for="d in departments" :key="d.id" :value="d.id" :label="d.name" /></el-select>
      <el-input v-model.number="ruleForm.weight" type="number" step="0.1" min="0.001" max="9.999" style="max-width:120px" aria-label="权重" />
      <el-button type="primary" @click="addRule(editing)">添加映射</el-button>
    </div>
    <div class="form-actions"><el-button type="primary" :loading="saving" @click="saveEdit">保存修改</el-button></div>
  </el-dialog>
</template>

<style scoped>
.section-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.status-text { margin-left: 8px; font-size: 13px; }
.rule-tag { margin: 2px 6px 2px 0; }
.check-line { display: flex; gap: 8px; align-items: center; margin: 12px 0; }
.form-actions { margin-top: 12px; }
.rule-add { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; }
h3 { margin-top: 20px; }
</style>
