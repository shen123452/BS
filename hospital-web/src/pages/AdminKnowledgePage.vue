<script setup>
import { computed, onMounted, onBeforeUnmount, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { get, patch, post } from '../lib/http.js'
import ErrorPanel from '../components/ErrorPanel.vue'
import Pager from '../components/Pager.vue'

const page = ref(1)
const pageSize = 20
const data = ref({ items: [], total: 0 })
const status = ref(null)
const loading = ref(false)
const error = ref(null)
const actionError = ref(null)
const uploading = ref(false)
const selectedFile = ref(null)
const form = reactive({ title: '', source: '', version: '' })
const searchQuery = ref('')
const searchLoading = ref(false)
const searchError = ref(null)
const hits = ref([])
const detail = ref(null)
const detailLoading = ref(false)
const chunkDialog = ref(false)
const retrying = ref(null)
const toggling = ref(null)
const chunkPage = ref(1), chunkTotal = ref(0), chunkError = ref(null)
let refreshTimer, disposed = false

const statusNames = { QUEUED: '待处理', PARSING: '解析中', CHUNKING: '切片中', EMBEDDING: '生成向量', READY: '已就绪', FAILED: '处理失败' }
const statusClass = value => ({ READY: 'ready', FAILED: 'failed', QUEUED: 'queued', PARSING: 'processing', CHUNKING: 'processing', EMBEDDING: 'processing' }[value] || '')
const searchAvailable = computed(() => status.value?.status === 'AVAILABLE')
const chooseFile = event => { selectedFile.value = event.target.files?.[0] || null }
const formatSize = bytes => bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024)} KB` : `${(bytes / 1024 / 1024).toFixed(1)} MB`
const formatTime = value => value ? value.replace('T', ' ').slice(0, 16) : '—'

async function load() {
  clearTimeout(refreshTimer)
  loading.value = true; error.value = null
  try { data.value = await get('/admin/knowledge/documents', { page: page.value, pageSize }); status.value = await get('/admin/knowledge/status') }
  catch (e) { error.value = e }
  finally { loading.value = false
    if (!disposed && !error.value && data.value.items.some(d => ['QUEUED','PARSING','CHUNKING','EMBEDDING'].includes(d.status))) refreshTimer = setTimeout(load,4000)
  }
}
async function upload() {
  if (uploading.value) return
  if (!selectedFile.value || !form.title.trim() || !form.source.trim() || !form.version.trim()) { actionError.value = new Error('请填写资料信息并选择 TXT 或 PDF 文件。'); return }
  const name = selectedFile.value.name.toLowerCase()
  if (selectedFile.value.size > 10 * 1024 * 1024 || selectedFile.value.size === 0) { actionError.value = new Error('文件须为非空文件且不超过10 MB。'); return }
  if (!name.endsWith('.txt') && !name.endsWith('.pdf')) { actionError.value = new Error('仅支持 UTF-8 TXT 或文本型 PDF。'); return }
  uploading.value = true; actionError.value = null
  try {
    const body = new FormData()
    body.append('file', selectedFile.value); body.append('title', form.title.trim()); body.append('source', form.source.trim()); body.append('version', form.version.trim())
    const uploaded = await post('/admin/knowledge/documents',body)
    ElMessage.success(uploaded.duplicate ? '资料已存在，未重复上传。' : '资料已上传，后台开始处理。')
    form.title = ''; form.source = ''; form.version = ''; selectedFile.value = null
    const input = document.querySelector('#knowledge-file'); if (input) input.value = ''
    await load()
  } catch (e) { actionError.value = e }
  finally { uploading.value = false }
}
async function retry(row) {
  retrying.value = row.id; actionError.value = null
  try { await post(`/admin/knowledge/documents/${row.id}/retry`); ElMessage.success('已重新加入处理队列。'); await load() }
  catch (e) { actionError.value = e }
  finally { retrying.value = null }
}
async function toggle(row) {
  const enabled = !row.enabled
  if (enabled) {
    try { await ElMessageBox.confirm('启用后，导诊检索会使用这份资料。确认启用？', '启用知识资料', { confirmButtonText: '确认启用', cancelButtonText: '返回' }) }
    catch { return }
  }
  toggling.value = row.id; actionError.value = null
  try { await patch(`/admin/knowledge/documents/${row.id}/enabled`, { enabled }); ElMessage.success(enabled ? '资料已启用。' : '资料已停用。'); await load() }
  catch (e) { actionError.value = e }
  finally { toggling.value = null }
}
async function showChunks(row) {
  detail.value = row; chunkDialog.value = true; chunkPage.value = 1; await loadChunks()
}
async function loadChunks(page = chunkPage.value) {
  chunkPage.value = page; detailLoading.value = true; chunkError.value = null
  const id = detail.value.id
  try { const chunks = await get(`/admin/knowledge/documents/${id}/chunks`, { page, pageSize:20 }); if (detail.value.id === id) { detail.value = { ...detail.value, chunks:chunks.items }; chunkTotal.value = chunks.total } }
  catch (e) { chunkError.value = e }
  finally { detailLoading.value = false }
}
async function search() {
  if (!searchQuery.value.trim() || searchLoading.value) return
  searchLoading.value = true; searchError.value = null; hits.value = []
  try { hits.value = (await post('/admin/knowledge/search', { query: searchQuery.value.trim(), topK: 5 })).hits }
  catch (e) { searchError.value = e }
  finally { searchLoading.value = false }
}
async function changePage(value) { page.value = value; await load() }
onMounted(load)
onBeforeUnmount(() => { disposed = true; clearTimeout(refreshTimer) })
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">知识库</h1><p>维护导诊资料。上传后会自动解析、切片并生成向量，启用前请先确认处理完成。</p></div><el-button :loading="loading" @click="load">刷新状态</el-button></div>
  <ErrorPanel :error="error" @retry="load" /><ErrorPanel :error="actionError" />
  <section class="knowledge-grid">
    <form class="panel knowledge-upload" @submit.prevent="upload">
      <div class="section-heading"><div><h2>上传资料</h2><p class="muted">支持 UTF-8 TXT 和文本型 PDF，单文件不超过 10 MB。</p></div></div>
      <div class="form-grid"><div><label for="knowledge-title">资料标题</label><el-input id="knowledge-title" v-model="form.title" :disabled="uploading" maxlength="200" show-word-limit placeholder="例如：呼吸科常见疾病指南" /></div><div><label for="knowledge-version">资料版本</label><el-input id="knowledge-version" v-model="form.version" :disabled="uploading" maxlength="100" placeholder="例如：2026-01" /></div></div>
      <label for="knowledge-source">来源说明</label><el-input id="knowledge-source" v-model="form.source" :disabled="uploading" maxlength="1000" placeholder="例如：医院呼吸科整理，供内部导诊参考" />
      <label for="knowledge-file">文件</label><input id="knowledge-file" class="knowledge-file" type="file" :disabled="uploading" accept=".txt,.pdf,text/plain,application/pdf" @change="chooseFile" /><small class="muted">{{ selectedFile ? `${selectedFile.name} · ${formatSize(selectedFile.size)}` : '请选择一个 TXT 或 PDF 文件' }}</small>
      <div class="form-actions"><el-button type="primary" native-type="submit" :loading="uploading">上传并处理</el-button><span class="muted">资料内容会保存在服务器本地目录。</span></div>
    </form>
    <section class="panel knowledge-search">
      <div class="section-heading"><div><h2>检索测试</h2><p class="muted">用实际问题检查已启用资料是否能返回相关片段。</p></div><span class="knowledge-availability" :class="searchAvailable ? 'ready' : 'muted'">{{ searchAvailable ? '可检索' : '暂无可用资料' }}</span></div>
      <form class="knowledge-search-form" @submit.prevent="search"><el-input v-model="searchQuery" maxlength="1000" placeholder="例如：咳嗽伴发热应该先挂哪个科室？" :disabled="!searchAvailable || searchLoading" /><el-button type="primary" native-type="submit" :loading="searchLoading" :disabled="!searchAvailable || !searchQuery.trim()">检索</el-button></form>
      <ErrorPanel :error="searchError" />
      <div v-if="hits.length" class="knowledge-hits"><article v-for="hit in hits" :key="hit.chunkId"><div><strong>{{ hit.title }}</strong><small>{{ hit.sourceDescription }} · {{ hit.documentVersion }} · 检索相似度 {{ hit.score.toFixed(3) }}（非医学概率）</small></div><p>{{ hit.excerpt }}</p></article></div><p v-else-if="!searchLoading" class="empty-state muted">输入问题后查看检索片段。</p>
    </section>
  </section>
  <section class="panel"><div class="section-heading"><div><h2>资料列表</h2><p class="muted">{{ data.total }} 份资料 · {{ searchAvailable ? '已启用资料可用于导诊检索' : '请启用已就绪资料' }}</p></div></div>
    <div class="table-container"><el-table v-loading="loading" :data="data.items" row-key="id" empty-text="暂无知识资料"><el-table-column prop="title" label="资料" min-width="210"><template #default="{ row }"><strong>{{ row.title }}</strong><small class="knowledge-meta">{{ row.sourceDescription }}</small></template></el-table-column><el-table-column prop="documentVersion" label="版本" width="120" /><el-table-column label="处理状态" width="140"><template #default="{ row }"><span class="knowledge-status" :class="statusClass(row.status)">{{ statusNames[row.status] || row.status }}</span><small v-if="row.status === 'READY'" class="knowledge-meta">{{ row.enabled ? '已启用' : '未启用' }}</small><small v-if="row.errorMessage" class="danger-text knowledge-meta">{{ row.errorMessage }}</small><small class="knowledge-meta">{{ row.embeddedChunks }}/{{ row.totalChunks }} 片段</small></template></el-table-column><el-table-column label="更新时间" width="165"><template #default="{ row }">{{ formatTime(row.updatedAt || row.createdAt) }}</template></el-table-column><el-table-column label="操作" min-width="250"><template #default="{ row }"><div class="table-actions"><el-button size="small" @click="showChunks(row)">查看片段</el-button><el-button v-if="row.status === 'FAILED'" size="small" type="warning" :loading="retrying === row.id" @click="retry(row)">重试</el-button><el-button v-if="row.status === 'READY'" size="small" :type="row.enabled ? 'danger' : 'primary'" plain :loading="toggling === row.id" @click="toggle(row)">{{ row.enabled ? '停用' : '启用' }}</el-button></div></template></el-table-column></el-table></div><Pager :page="page" :total="data.total" :page-size="pageSize" @change="changePage" />
  </section>
  <el-dialog v-model="chunkDialog" :title="detail?.title || '资料片段'" width="760px"><div v-loading="detailLoading" class="chunk-dialog"><ErrorPanel :error="chunkError" @retry="loadChunks()" /><p v-if="detail?.errorMessage" class="danger-text">{{ detail.errorMessage }}</p><article v-for="chunk in detail?.chunks || []" :key="chunk.id" class="knowledge-chunk"><header><strong>片段 {{ chunk.chunkNo }}</strong><small>{{ chunk.charCount }} 字</small></header><p>{{ chunk.content }}</p></article><p v-if="!detailLoading && !detail?.chunks?.length" class="empty-state muted">暂无可查看片段。</p></div><Pager :page="chunkPage" :total="chunkTotal" :page-size="20" @change="loadChunks" /></el-dialog>
</template>
