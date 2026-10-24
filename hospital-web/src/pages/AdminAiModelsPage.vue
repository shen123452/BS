<script setup>
import { onMounted, reactive, ref } from 'vue'
import { get, post, patch } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'

const overview = ref(null), loading = ref(false), busy = ref(false)
const error = ref(null), actionError = ref(null), statusText = ref('')
const createVisible = ref(false), createType = ref('CHAT'), creating = ref(false)
const form = reactive({ name: '', baseUrl: '', modelName: '', apiKey: '', timeoutMs: 30000, temperature: '', maxOutputTokens: '', embeddingDimension: '' })
const testStates = { UNTESTED: '未测试', PASSED: '已通过', FAILED: '未通过' }
const statusNames = { ENABLED: '已启用', DISABLED: '已停用' }

async function load() {
  loading.value = true; error.value = null
  try { overview.value = await get('/admin/ai-model-configs') }
  catch (e) { error.value = e } finally { loading.value = false }
}
function openCreate(type) {
  createType.value = type; createVisible.value = true; statusText.value = ''
  Object.assign(form, { name: '', baseUrl: overview.value?.effectiveBaseUrl || '', modelName: '', apiKey: '', timeoutMs: 30000, temperature: '', maxOutputTokens: '', embeddingDimension: type === 'EMBEDDING' ? '1024' : '' })
}
async function create() {
  if (creating.value) return
  creating.value = true; actionError.value = null
  try {
    await post('/admin/ai-model-configs', {
      modelType: createType.value, name: form.name, baseUrl: form.baseUrl, modelName: form.modelName,
      apiKey: form.apiKey || undefined, timeoutMs: Number(form.timeoutMs),
      temperature: form.temperature === '' ? undefined : Number(form.temperature),
      maxOutputTokens: form.maxOutputTokens === '' ? undefined : Number(form.maxOutputTokens),
      embeddingDimension: form.embeddingDimension === '' ? undefined : Number(form.embeddingDimension),
    })
    createVisible.value = false; statusText.value = '配置已创建，请先测试修订，测试通过后才能启用。'
    await load()
  } catch (e) { actionError.value = e } finally { creating.value = false }
}
async function test(config, revisionId) {
  if (busy.value) return
  busy.value = true; actionError.value = null; statusText.value = ''
  try {
    const result = await post(`/admin/ai-model-configs/${config.id}/test`, revisionId ? { revisionId } : {})
    statusText.value = `修订 ${result.revision} 测试${result.testStatus === 'PASSED' ? '通过' : '未通过'}${result.testDetail ? `：${result.testDetail}` : ''}。测试产生了真实供应商调用。`
    await load()
  } catch (e) { actionError.value = e } finally { busy.value = false }
}
async function activate(config, revisionId) {
  if (busy.value) return
  busy.value = true; actionError.value = null
  try {
    await post(`/admin/ai-model-configs/${config.id}/activate`, { revisionId })
    statusText.value = '已启用为默认配置；下一次新问诊/新导诊/新入库任务生效，运行中的任务沿用开始时版本。'
    await load()
  } catch (e) { actionError.value = e } finally { busy.value = false }
}
async function disable(config) {
  if (busy.value) return
  busy.value = true; actionError.value = null
  try { await post(`/admin/ai-model-configs/${config.id}/disable`); statusText.value = '已停用；系统回退到环境默认配置。'; await load() }
  catch (e) { actionError.value = e } finally { busy.value = false }
}
async function saveRevision(config) {
  if (busy.value) return
  busy.value = true; actionError.value = null
  try {
    await patch(`/admin/ai-model-configs/${config.id}`, {
      baseUrl: form.baseUrl || config.current?.baseUrl || config.revisions[0]?.baseUrl,
      modelName: form.modelName,
      apiKey: form.apiKey || undefined,
      timeoutMs: Number(form.timeoutMs),
      temperature: form.temperature === '' ? undefined : Number(form.temperature),
      maxOutputTokens: form.maxOutputTokens === '' ? undefined : Number(form.maxOutputTokens),
      embeddingDimension: config.modelType === 'EMBEDDING' ? Number(form.embeddingDimension) : undefined,
    })
    statusText.value = '已生成新修订（未测试），测试通过后才能启用。'
    form.apiKey = ''
    await load()
  } catch (e) { actionError.value = e } finally { busy.value = false }
}
function configsOf(type) { return (overview.value?.configs || []).filter(c => c.modelType === type) }
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">AI 模型配置</h1><p>聊天与向量模型分别管理：保存修订 → 测试 → 启用。密钥不回显；测试会产生真实供应商调用。</p></div><el-button :loading="loading" :disabled="busy" @click="load">刷新</el-button></div>
  <ErrorPanel :error="error" @retry="load" /><ErrorPanel :error="actionError" />
  <p v-if="statusText" class="panel status-line" role="status">{{ statusText }}</p>
  <section v-if="overview" class="panel">
    <h2>当前生效配置（来源：{{ overview.effectiveSource === 'DATABASE' ? '数据库修订' : '环境默认' }}）</h2>
    <dl class="detail-grid">
      <div><dt>接口地址</dt><dd class="wrap">{{ overview.effectiveBaseUrl }}</dd></div>
      <div><dt>聊天模型</dt><dd>{{ overview.effectiveChatModel }}</dd></div>
      <div><dt>向量模型</dt><dd>{{ overview.effectiveEmbeddingModel }} · {{ overview.effectiveEmbeddingDimension }} 维</dd></div>
    </dl>
    <p class="muted">未启用数据库修订时使用环境默认 + AI 配置页保存的 Key；启用修订后以修订内容为准。</p>
  </section>
  <section v-for="type in ['CHAT','EMBEDDING']" :key="type" class="panel">
    <div class="section-head">
      <h2>{{ type === 'CHAT' ? '聊天模型（问诊 / 导诊）' : '向量模型（知识检索与入库）' }}</h2>
      <el-button type="primary" @click="openCreate(type)">新增配置</el-button>
    </div>
    <p v-if="!configsOf(type).length" class="muted">尚未登记{{ type === 'CHAT' ? '聊天' : '向量' }}模型配置，当前使用环境默认。</p>
    <div v-for="config in configsOf(type)" :key="config.id" class="config-block">
      <div class="config-head">
        <strong>{{ config.name }}</strong>
        <el-tag :type="config.status === 'ENABLED' ? 'success' : 'info'" size="small">{{ statusNames[config.status] }}</el-tag>
        <span class="muted">{{ config.currentRevision ? `当前修订 ${config.current.current?.revision || '—'}` : '未启用任何修订' }}</span>
        <el-button v-if="config.status === 'ENABLED'" size="small" :disabled="busy" @click="disable(config)">停用</el-button>
      </div>
      <div class="table-container">
        <el-table :data="config.revisions" empty-text="尚无修订">
          <el-table-column prop="revision" label="修订" width="70" />
          <el-table-column prop="baseUrl" label="接口地址" min-width="220" show-overflow-tooltip />
          <el-table-column prop="modelName" label="模型" min-width="150" />
          <el-table-column label="密钥" width="90"><template #default="{row}">{{ row.apiKeySet ? '已保存' : '未保存' }}</template></el-table-column>
          <el-table-column label="测试" width="100"><template #default="{row}"><el-tag :type="row.testStatus === 'PASSED' ? 'success' : row.testStatus === 'FAILED' ? 'danger' : 'info'" size="small">{{ testStates[row.testStatus] }}</el-tag></template></el-table-column>
          <el-table-column label="操作" width="200">
            <template #default="{row}">
              <el-button size="small" :loading="busy" @click="test(config, row.id)">测试</el-button>
              <el-button size="small" type="primary" :disabled="busy || row.testStatus !== 'PASSED'" @click="activate(config, row.id)">启用</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>
      <details class="revision-form">
        <summary>保存新修订（apiKey 留空沿用上一次）</summary>
        <form class="inline-form" @submit.prevent="null">
          <label :for="`base-${config.id}`">接口地址</label><el-input :id="`base-${config.id}`" v-model="form.baseUrl" disabled placeholder="先在下方填写" />
          <label :for="`model-${config.id}`">模型名</label><el-input :id="`model-${config.id}`" v-model="form.modelName" :maxlength="100" />
          <label :for="`key-${config.id}`">API Key（留空沿用）</label><el-input :id="`key-${config.id}`" v-model="form.apiKey" type="password" show-password autocomplete="off" />
          <label :for="`timeout-${config.id}`">超时（毫秒）</label><el-input :id="`timeout-${config.id}`" v-model.number="form.timeoutMs" type="number" min="1000" max="120000" />
          <template v-if="config.modelType === 'EMBEDDING'">
            <label :for="`dim-${config.id}`">向量维度</label><el-input :id="`dim-${config.id}`" v-model.number="form.embeddingDimension" type="number" min="1" max="16000" />
          </template>
          <el-button type="primary" :loading="busy" @click="saveRevision(config)">保存修订</el-button>
        </form>
        <p class="muted">接口地址默认沿用当前修订；需要更换时先在此填写，再保存修订并重新测试。</p>
      </details>
    </div>
  </section>

  <el-dialog v-model="createVisible" :title="`新增${createType === 'CHAT' ? '聊天' : '向量'}模型配置`" width="560px">
    <form @submit.prevent="create">
      <label for="cfg-name">配置名称</label><el-input id="cfg-name" v-model="form.name" :maxlength="100" />
      <label for="cfg-base">接口地址（OpenAI 兼容根路径）</label><el-input id="cfg-base" v-model="form.baseUrl" :maxlength="500" placeholder="https://.../compatible-mode/v1" />
      <label for="cfg-model">模型名</label><el-input id="cfg-model" v-model="form.modelName" :maxlength="100" />
      <label for="cfg-key">API Key</label><el-input id="cfg-key" v-model="form.apiKey" type="password" show-password autocomplete="off" />
      <label for="cfg-timeout">超时（毫秒）</label><el-input id="cfg-timeout" v-model.number="form.timeoutMs" type="number" min="1000" max="120000" />
      <template v-if="createType === 'EMBEDDING'">
        <label for="cfg-dim">实测向量维度</label><el-input id="cfg-dim" v-model.number="form.embeddingDimension" type="number" min="1" max="16000" />
      </template>
      <p class="muted">创建后修订为“未测试”，测试通过才能启用；测试会向该地址发起真实调用。</p>
      <div class="form-actions"><el-button type="primary" native-type="submit" :loading="creating">创建配置</el-button></div>
    </form>
  </el-dialog>
</template>

<style scoped>
.status-line { color: #15803d; }
.section-head { display: flex; justify-content: space-between; align-items: center; gap: 16px; flex-wrap: wrap; }
.config-block { border-top: 1px solid var(--color-border, #d7dfea); padding-top: 16px; margin-top: 16px; }
.config-head { display: flex; gap: 12px; align-items: center; flex-wrap: wrap; margin-bottom: 12px; }
.revision-form { margin-top: 12px; }
.revision-form summary { cursor: pointer; color: #475569; }
.inline-form { display: grid; gap: 6px; margin-top: 12px; max-width: 480px; }
.wrap { overflow-wrap: anywhere; }
.form-actions { margin-top: 12px; }
</style>
