<script setup>
import { onMounted, reactive, ref } from 'vue'
import { get, post, patch } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import Pager from '../components/Pager.vue'

const tools = ref([]), executors = ref([]), loading = ref(false), actionError = ref(null)
const logs = reactive({ items: [], total: 0, page: 1, pageSize: 20, code: '', status: '' })
const createVisible = ref(false), creating = ref(false)
const form = reactive({ code: '', name: '', description: '', executorCode: '', inputSchema: '{"properties":{},"required":[]}', timeoutMs: 3000, maxResultChars: 4000 })
const testing = ref(null), testVisible = ref(false), testArgs = ref('{}'), testResult = ref(null), testRunning = ref(false)
const stateNames = { ENABLED: '启用', DISABLED: '停用' }

async function load() {
  loading.value = true; actionError.value = null
  try {
    const [toolList, executorList] = await Promise.all([get('/admin/tools'), get('/admin/tool-executors')])
    tools.value = toolList; executors.value = executorList.executors || []
  } catch (e) { actionError.value = e } finally { loading.value = false }
}
async function loadLogs(page = 1) {
  logs.page = page
  try { Object.assign(logs, await get('/admin/tool-call-logs', { code: logs.code, status: logs.status, page, pageSize: logs.pageSize })) }
  catch (e) { actionError.value = e }
}
async function flip(tool) {
  actionError.value = null
  try { await post(`/admin/tools/${tool.id}/status`, { status: tool.status === 'ENABLED' ? 'DISABLED' : 'ENABLED' }); await Promise.all([load(), loadLogs()]) }
  catch (e) { actionError.value = e }
}
async function create() {
  if (creating.value) return
  actionError.value = null
  creating.value = true
  try { await post('/admin/tools', { ...form, timeoutMs: Number(form.timeoutMs), maxResultChars: Number(form.maxResultChars) }); createVisible.value = false; Object.assign(form, { code: '', name: '', description: '', executorCode: '', inputSchema: '{"properties":{},"required":[]}', timeoutMs: 3000, maxResultChars: 4000 }); await load() }
  catch (e) { actionError.value = e } finally { creating.value = false }
}
async function openTest(tool) {
  testing.value = tool; testVisible.value = true; testArgs.value = '{}'; testResult.value = null
}
async function runTest() {
  if (!testing.value || testRunning.value) return
  testRunning.value = true; actionError.value = null
  try {
    let parsed
    try { parsed = JSON.parse(testArgs.value || '{}') } catch { testResult.value = { status: 'FAILED', output: { message: '测试入参必须是合法 JSON' } }; return }
    testResult.value = await post(`/admin/tools/${testing.value.id}/test`, { arguments: JSON.stringify(parsed) })
  } catch (e) { actionError.value = e } finally { testRunning.value = false }
}
onMounted(() => Promise.all([load(), loadLogs()]))
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">工具中心</h1><p>登记数据库驱动的只读工具，启停从下一次新导诊生效；仅可选择服务端已实现的执行器。</p></div><el-button :loading="loading" @click="load">刷新</el-button></div>
  <ErrorPanel :error="actionError" @retry="load" />
  <section class="panel">
    <div class="section-head"><h2>已登记工具（{{ tools.length }}）</h2><el-button type="primary" @click="createVisible = true">登记新工具</el-button></div>
    <div class="table-container">
      <el-table v-loading="loading" :data="tools" empty-text="暂无登记工具">
        <el-table-column prop="code" label="工具编码" min-width="200" />
        <el-table-column prop="name" label="名称" min-width="140" />
        <el-table-column prop="executorCode" label="执行器" min-width="200" />
        <el-table-column prop="revision" label="修订" width="80" />
        <el-table-column label="状态" width="150">
          <template #default="{row}">
            <el-switch :model-value="row.status === 'ENABLED'" :aria-label="`${row.code} 启用开关`" @change="flip(row)" />
            <span class="status-text">{{ stateNames[row.status] }}</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180">
          <template #default="{row}">
            <el-button size="small" @click="openTest(row)">测试</el-button>
            <el-button size="small" @click="logs.code = row.code; loadLogs()">日志</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>
  </section>
  <section class="panel">
    <div class="filter-bar">
      <div><label for="tool-log-code">工具编码</label><el-input id="tool-log-code" v-model="logs.code" clearable placeholder="全部工具" @change="loadLogs()" /></div>
      <div><label for="tool-log-status">状态</label><el-select id="tool-log-status" v-model="logs.status" clearable placeholder="全部状态" @change="loadLogs()"><el-option value="SUCCEEDED" label="成功" /><el-option value="FAILED" label="失败" /><el-option value="RUNNING" label="执行中" /></el-select></div>
      <el-button @click="loadLogs()">查询日志</el-button>
    </div>
    <div class="table-container">
      <el-table :data="logs.items" empty-text="暂无工具调用记录">
        <el-table-column prop="toolCode" label="工具" min-width="180" />
        <el-table-column label="来源" width="110"><template #default="{row}">{{ row.callSource === 'AGENT' ? '导诊运行' : '管理测试' }}</template></el-table-column>
        <el-table-column label="状态" width="90"><template #default="{row}">{{ stateNames[row.status] || row.status }}</template></el-table-column>
        <el-table-column label="耗时" width="100"><template #default="{row}">{{ row.durationMs != null ? `${row.durationMs} ms` : '—' }}</template></el-table-column>
        <el-table-column prop="errorCode" label="错误码" min-width="140" />
        <el-table-column prop="createdAt" label="时间" min-width="170" />
      </el-table>
    </div>
    <Pager :page="logs.page" :total="logs.total" @change="loadLogs" />
  </section>

  <el-dialog v-model="createVisible" title="登记新工具" width="640px">
    <form @submit.prevent="create">
      <label for="tool-code">工具编码（模型可见名称）</label>
      <el-input id="tool-code" v-model="form.code" :maxlength="100" placeholder="如 department_search" />
      <label for="tool-name">名称</label><el-input id="tool-name" v-model="form.name" :maxlength="100" />
      <label for="tool-desc">用途说明</label><el-input id="tool-desc" v-model="form.description" :maxlength="2000" type="textarea" :rows="2" />
      <label for="tool-executor">执行器（仅服务端已实现）</label>
      <el-select id="tool-executor" v-model="form.executorCode" placeholder="选择执行器"><el-option v-for="e in executors" :key="e" :value="e" :label="e" /></el-select>
      <label for="tool-schema">参数 Schema（JSON）</label>
      <el-input id="tool-schema" v-model="form.inputSchema" type="textarea" :rows="4" />
      <label for="tool-timeout">超时（毫秒）</label><el-input id="tool-timeout" v-model.number="form.timeoutMs" type="number" min="1000" max="60000" />
      <div class="form-actions"><el-button type="primary" native-type="submit" :loading="creating">登记</el-button></div>
    </form>
  </el-dialog>

  <el-dialog v-model="testVisible" :title="`测试 ${testing?.code || ''}`" width="640px">
    <label for="tool-test-args">测试入参（JSON）</label>
    <el-input id="tool-test-args" v-model="testArgs" type="textarea" :rows="3" />
    <div class="form-actions"><el-button type="primary" :loading="testRunning" @click="runTest">执行测试</el-button></div>
    <template v-if="testResult">
      <p><strong>{{ testResult.status === 'SUCCEEDED' ? '执行成功' : '执行失败' }}</strong><span class="muted"> · {{ testResult.durationMs }} ms</span></p>
      <pre class="test-output">{{ JSON.stringify(testResult.output, null, 2) }}</pre>
    </template>
    <p class="muted">测试使用管理端授权入参，调用日志标记为“管理测试”；患者上下文工具可携带测试患者ID。</p>
  </el-dialog>
</template>

<style scoped>
.section-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.status-text { margin-left: 8px; font-size: 13px; }
.form-actions { margin: 12px 0; }
.test-output { background: #f5f7fb; border-radius: 8px; padding: 12px; max-height: 320px; overflow: auto; font-size: 12px; }
</style>
