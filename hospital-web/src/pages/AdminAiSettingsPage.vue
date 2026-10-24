<script setup>
import { onMounted, ref } from 'vue'
import { get, put } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'

const config = ref(null), apiKey = ref(''), loading = ref(false), saving = ref(false)
const error = ref(null), actionError = ref(null), saved = ref(false)
async function load() {
  if (saving.value) return
  loading.value = true; error.value = null
  try { config.value = await get('/admin/ai/config') }
  catch (e) { error.value = e }
  finally { loading.value = false }
}
async function save() {
  if (saving.value || loading.value) return
  actionError.value = null; saved.value = false
  const key = apiKey.value.trim()
  if (key.length < 8 || key.length > 512 || !/^[\x21-\x7e]+$/.test(key)) {
    actionError.value = new Error('请填写完整的 API Key，检查是否混入了空格或换行。')
    document.getElementById('ai-api-key')?.focus(); return
  }
  saving.value = true
  try {
    config.value = await put('/admin/ai/api-key', { apiKey: key })
    apiKey.value = ''; saved.value = true
  } catch (e) { actionError.value = e }
  finally { saving.value = false }
}
onMounted(load)
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">AI 配置</h1><p>保存百炼 API Key，用于问诊、导诊和知识检索。</p></div><el-button :loading="loading" :disabled="saving" @click="load">刷新状态</el-button></div>
  <ErrorPanel :error="error" @retry="load" />
  <section v-if="loading && !config" class="panel form-panel ai-loading" aria-live="polite" aria-busy="true">
    <h2>正在读取 AI 配置</h2>
    <p class="muted">请稍候，正在确认数据库中的 Key 状态。</p>
    <el-skeleton :rows="5" animated />
  </section>
  <form v-else-if="config" class="panel form-panel" :aria-busy="saving" @submit.prevent="save">
    <h2>{{ config.configured ? 'API Key 已配置' : '尚未配置 API Key' }}</h2>
    <p class="muted">{{ config.configured ? '已保存的 Key 可继续使用。如需更换，请在下方填写新的 Key。' : '填写并保存后即可使用 AI 服务。' }}</p>
    <label for="ai-api-key">{{ config.configured ? '新的 API Key' : 'API Key' }}</label>
    <el-input id="ai-api-key" v-model="apiKey" type="password" show-password autocomplete="off" :maxlength="512" :disabled="saving" placeholder="粘贴百炼 API Key" aria-describedby="ai-key-help" @input="saved = false" />
    <p id="ai-key-help" class="muted">Key 存在数据库中，重启后仍可用。已保存内容不会再次显示。</p>
    <div class="form-actions"><el-button type="primary" native-type="submit" :loading="saving" :disabled="loading || !apiKey.trim()">{{ config.configured ? '保存新的 API Key' : '保存 API Key' }}</el-button><p v-if="saved" role="status">API Key 已保存，新请求将使用此配置。</p></div>
    <div class="field-group"><ErrorPanel :error="actionError" /></div>
    <h2>当前模型</h2>
    <dl class="detail-grid"><div><dt>接口地址</dt><dd class="ai-endpoint">{{ config.baseUrl }}</dd></div><div><dt>对话模型</dt><dd>{{ config.chatModel }}</dd></div><div><dt>向量模型</dt><dd>{{ config.embeddingModel }} · {{ config.embeddingDimension }} 维</dd></div><div><dt>Key 状态</dt><dd>{{ config.configured ? '已保存在数据库' : '未保存' }}</dd></div></dl>
    <p class="muted">保存只更新配置，不调用模型。实际使用结果以服务响应为准。</p>
  </form>
</template>

<style scoped>
.ai-endpoint { overflow-wrap: anywhere; }
.ai-loading :deep(.el-skeleton) { margin-top: 24px; }
</style>
