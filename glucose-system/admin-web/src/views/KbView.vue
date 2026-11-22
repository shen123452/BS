<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { kbApi } from '../api'

const loading = ref(false)
const loadError = ref('')
let loadVersion = 0
const list = ref([])
const total = ref(0)
const query = reactive({ pageNum: 1, pageSize: 20, keyword: '' })
const dialog = reactive({ visible: false, mode: 'create', id: null, submitting: false, loading: false, error: '', form: blankForm() })
const chunksDrawer = reactive({ visible: false, docId: '', chunks: [], loading: false, error: '' })
const indexing = ref('')
let detailVersion = 0, chunksVersion = 0

function blankForm() {
  return {
    title: '', category: '', content: '',
    sourceTitle: '', sourceUrl: '', sourceVersion: '', publishedOn: ''
  }
}

async function load() {
  const version = ++loadVersion
  loading.value = true
  loadError.value = ''
  try {
    const data = await kbApi.list({ pageNum: query.pageNum, pageSize: query.pageSize, keyword: query.keyword })
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
  ++detailVersion
  dialog.loading = false; dialog.error = ''
  dialog.mode = 'create'
  dialog.id = null
  dialog.form = blankForm()
  dialog.visible = true
}

async function openEdit(row) {
  const version = ++detailVersion
  dialog.mode = 'edit'; dialog.id = row.id; dialog.visible = true; dialog.loading = true; dialog.error = ''; dialog.form = blankForm()
  try {
    const detail = await kbApi.detail(row.id)
    if (version !== detailVersion) return
    dialog.mode = 'edit'
    dialog.id = row.id
    dialog.form = {
      title: detail.title, category: detail.category, content: detail.content,
      sourceTitle: detail.sourceTitle, sourceUrl: detail.sourceUrl,
      sourceVersion: detail.sourceVersion, publishedOn: detail.publishedOn
    }
    dialog.visible = true
  } catch (e) {
    if (version === detailVersion) dialog.error = e.message
  } finally { if (version === detailVersion) dialog.loading = false }
}

async function submit() {
  if (dialog.submitting || dialog.loading) return
  const f = dialog.form
  dialog.error = ''
  if (!f.title?.trim() || !f.sourceTitle?.trim() || !f.content?.trim()) { dialog.error = '请填写标题、来源标题和正文。'; return }
  if (f.sourceUrl && !/^https?:\/\/[^\s]+$/i.test(f.sourceUrl)) { dialog.error = '来源链接须使用 http:// 或 https://。'; return }
  dialog.submitting = true
  try {
    if (dialog.mode === 'create') {
      await kbApi.create(dialog.form)
      ElMessage.success('已创建,默认停用;请核验来源后启用并重建索引')
    } else {
      await kbApi.update(dialog.id, dialog.form)
      ElMessage.success('已保存;内容变更后索引置为待重建')
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
    await kbApi.toggle(row.id, next)
    ElMessage.success(next === 1 ? '已启用,立即参与检索' : '已停用,立即退出检索')
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function reindex(row) {
  if (indexing.value) return
  indexing.value = String(row.id)
  try {
    const data = await kbApi.reindex(row.id)
    ElMessage.success(`索引完成:${data.indexStatus},切片 ${data.chunkCount} 个`)
    load()
  } catch (e) {
    ElMessage.error(`索引失败:${e.message}`)
    load()
  } finally { indexing.value = '' }
}

async function remove(row) {
  try {
    await ElMessageBox.confirm(
      `确认删除文档「${row.title}」?删除后立即退出检索。`, '删除确认', { type: 'warning' }
    )
  } catch { return }
  try {
    await kbApi.remove(row.id)
    ElMessage.success('已删除')
    load()
  } catch (e) {
    ElMessage.error(e.message)
  }
}

async function viewChunks(row) {
  const version = ++chunksVersion
  chunksDrawer.visible = true; chunksDrawer.docId = row.id; chunksDrawer.chunks = []; chunksDrawer.loading = true; chunksDrawer.error = ''
  try { const data = await kbApi.chunks(row.id); if (version === chunksVersion) chunksDrawer.chunks = data }
  catch (e) { if (version === chunksVersion) chunksDrawer.error = e.message }
  finally { if (version === chunksVersion) chunksDrawer.loading = false }
}

const indexMap = {
  PENDING: ['st-muted', '待重建'],
  BUILDING: ['st-warning', '重建中'],
  READY: ['st-success', '索引就绪'],
  FAILED: ['st-danger', '索引失败']
}

onMounted(load)
</script>

<template>
  <div class="panel">
    <div class="toolbar">
      <el-input v-model="query.keyword" placeholder="搜索标题/分类" clearable style="width: 240px"
                @keyup.enter="query.pageNum = 1; load()" @clear="query.pageNum = 1; load()" />
      <el-button @click="query.pageNum = 1; load()">搜索</el-button>
      <div class="spacer" />
      <el-button type="primary" @click="openCreate">新增文档</el-button>
    </div>

    <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px"
              title="知识入库要求:来源字段必填;内容经人工审核后启用并重建索引。启用文档立即参与问答检索,停用立即退出。" />

    <div v-if="loadError" class="state-error" role="alert"><span>{{ loadError }}</span><el-button @click="load">重新加载</el-button></div>
    <el-table :empty-text="loading ? '正在加载…' : (loadError ? '加载失败，请重试' : '暂无数据')" :data="list" v-loading="loading">
      <el-table-column prop="title" label="标题" min-width="220" show-overflow-tooltip />
      <el-table-column prop="category" label="分类" width="110" />
      <el-table-column prop="sourceTitle" label="来源" min-width="150" show-overflow-tooltip />
      <el-table-column label="版本" width="80">
        <template #default="{ row }">v{{ row.documentVersion }}</template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <span class="status-tag" :class="row.status === 1 ? 'st-success' : 'st-muted'">
            {{ row.status === 1 ? '已启用' : '已停用' }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="索引" width="110">
        <template #default="{ row }">
          <span class="status-tag" :class="indexMap[row.indexStatus]?.[0]">
            {{ indexMap[row.indexStatus]?.[1] || row.indexStatus }}
          </span>
        </template>
      </el-table-column>
      <el-table-column prop="chunkCount" label="切片数" width="80" />
      <el-table-column label="操作" width="260" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          <el-button link :type="row.status === 1 ? 'warning' : 'success'" @click="toggle(row)">
            {{ row.status === 1 ? '停用' : '启用' }}
          </el-button>
          <el-button link :disabled="!!indexing || row.indexStatus === 'BUILDING'" :loading="indexing === String(row.id)" @click="reindex(row)">重建索引</el-button>
          <el-button link @click="viewChunks(row)">切片</el-button>
          <el-button link type="danger" @click="remove(row)">删除</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 16px; justify-content: flex-end"
                   @current-change="load" />

    <el-dialog v-model="dialog.visible" :title="dialog.mode === 'create' ? '新增知识文档' : '编辑知识文档'"
               width="680px" top="5vh" :close-on-click-modal="!dialog.submitting" :close-on-press-escape="!dialog.submitting" :show-close="!dialog.submitting">
      <div v-if="dialog.loading" class="detail-loading" role="status">正在加载文档…</div>
      <div v-if="dialog.error" class="state-error" role="alert">{{ dialog.error }}<el-button v-if="!dialog.form.title && dialog.mode === 'edit'" @click="openEdit({ id: dialog.id })">重新加载文档</el-button></div>
      <el-form v-if="!dialog.loading" label-position="top" :disabled="dialog.submitting">
        <el-form-item label="标题" required>
          <el-input v-model="dialog.form.title" maxlength="200" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :xs="24" :sm="8">
            <el-form-item label="分类">
              <el-input v-model="dialog.form.category" placeholder="如:基础概念" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="来源标题" required>
              <el-input v-model="dialog.form.sourceTitle" placeholder="指南/资料题名" />
            </el-form-item>
          </el-col>
          <el-col :xs="24" :sm="8">
            <el-form-item label="来源版本/日期">
              <el-input v-model="dialog.form.sourceVersion" placeholder="如 2024 版" />
            </el-form-item>
          </el-col>
        </el-row>
        <el-form-item label="来源链接(仅 http/https,可空)">
          <el-input v-model="dialog.form.sourceUrl" />
        </el-form-item>
        <el-form-item label="正文内容(纯文本,入库后由系统切片)" required>
          <el-input v-model="dialog.form.content" type="textarea" :rows="12" maxlength="20000" show-word-limit />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button :disabled="dialog.submitting" @click="dialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="dialog.submitting" :disabled="dialog.loading || !dialog.form.content" @click="submit">保存</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="chunksDrawer.visible" :title="`切片预览 · 文档 ${chunksDrawer.docId}`" size="520px">
      <div v-if="chunksDrawer.loading" class="detail-loading">正在加载切片…</div>
      <div v-if="chunksDrawer.error" class="state-error">{{ chunksDrawer.error }}<el-button @click="viewChunks({ id: chunksDrawer.docId })">重试</el-button></div>
      <div v-for="c in chunksDrawer.chunks" :key="c.chunkNo" class="chunk-item">
        <div class="muted" style="font-size:12px; margin-bottom:4px">
          #{{ c.chunkNo }} · {{ c.embeddingModel }} · {{ c.dim }} 维 · {{ c.indexVersion }}
        </div>
        <div style="white-space: pre-wrap">{{ c.content }}</div>
      </div>
      <el-empty v-if="!chunksDrawer.loading && !chunksDrawer.error && !chunksDrawer.chunks.length" description="尚无切片,请先重建索引" />
    </el-drawer>
  </div>
</template>

<style scoped>
.chunk-item {
  border-top: 1px solid var(--color-border-strong); border-bottom: 1px solid var(--color-border);
  padding: 14px 0; margin-bottom: 0; font-size: 13px; line-height: 1.8;
}
</style>
