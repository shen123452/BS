<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { qaApi, patientsApi } from '../api'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const loading = ref(false)
const loadError = ref('')
let loadVersion = 0
const sessions = ref([])
const total = ref(0)
const patients = ref([])
const query = reactive({ pageNum: 1, pageSize: 20, patientId: '' })
const drawer = reactive({ visible: false, sessionId: '', messages: [], patientId: '', loading: false, error: '' })
const reply = reactive({ content: '', requestId: '', saving: false, error: '' })
let detailVersion = 0

function patientLabel(id) {
  const p = patients.value.find(x => String(x.userId) === String(id))
  return p ? (p.realName || p.username) : id
}

async function load() {
  const version = ++loadVersion
  loading.value = true
  loadError.value = ''
  try {
    const data = await qaApi.sessions({
      pageNum: query.pageNum, pageSize: query.pageSize,
      patientId: query.patientId || undefined
    })
    if (version !== loadVersion) return
    sessions.value = data.records || []
    total.value = Number(data.total || 0)
  } catch (e) {
    if (version === loadVersion) { loadError.value = e.message; sessions.value = []; total.value = 0 }
  } finally {
    if (version === loadVersion) loading.value = false
  }
}

async function loadPatients() {
  try {
    const data = await patientsApi.list({ pageNum: 1, pageSize: 100 })
    patients.value = data.records || []
  } catch { /* ignore */ }
}

async function openSession(row) {
  if (reply.saving) return
  const version = ++detailVersion
  drawer.visible = true; drawer.sessionId = row.sessionId; drawer.patientId = row.patientId
  drawer.messages = []; drawer.loading = true; drawer.error = ''
  reply.content = ''; reply.requestId = ''; reply.error = ''
  await loadMessages(version)
}
async function loadMessages(version = detailVersion) {
  const sessionId = drawer.sessionId
  drawer.loading = true; drawer.error = ''
  try { const messages = await qaApi.messages(sessionId); if (version === detailVersion && sessionId === drawer.sessionId) drawer.messages = messages }
  catch (e) { if (version === detailVersion) drawer.error = e.message }
  finally { if (version === detailVersion) drawer.loading = false }
}

function newRequestId() {
  if (!reply.requestId) reply.requestId = (globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`).slice(0, 36)
  return reply.requestId
}

async function sendReply() {
  const content = reply.content.trim()
  if (reply.saving || drawer.loading || drawer.error || !drawer.sessionId || !content) return
  const sessionId = drawer.sessionId, version = detailVersion
  reply.saving = true; reply.error = ''
  try {
    await qaApi.reply(sessionId, { requestId: newRequestId(), content })
    if (sessionId === drawer.sessionId) { reply.content = ''; reply.requestId = ''; ElMessage.success('已发送人工回复'); await loadMessages(version) }
  } catch (e) { reply.error = e.message }
  finally { reply.saving = false }
}

const modeText = {
  RAG: '有据回答', RETRIEVAL_ONLY: '资料降级', NO_COVERAGE: '未覆盖', FIXED_NOTICE: '固定提示', HUMAN: '人工回复'
}

onMounted(() => { load(); loadPatients() })
</script>

<template>
  <div class="panel">
    <div class="toolbar">
      <el-select v-model="query.patientId" clearable filterable placeholder="患者" style="width: 200px"
                 @change="query.pageNum = 1; load()">
        <el-option v-for="p in patients" :key="p.userId" :value="p.userId"
                   :label="p.realName || p.username" />
      </el-select>
      <div class="spacer" />
      <span class="muted" style="font-size: 13px">
        {{ auth.isDoctor ? '仅显示已绑定患者的会话' : '管理员查看留有审计' }}
      </span>
    </div>

    <div v-if="loadError" class="state-error" role="alert"><span>{{ loadError }}</span><el-button @click="load">重新加载</el-button></div>
    <el-table :empty-text="loading ? '正在加载…' : (loadError ? '加载失败，请重试' : '暂无数据')" :data="sessions" v-loading="loading">
      <el-table-column label="患者" min-width="120">
        <template #default="{ row }">{{ patientLabel(row.patientId) }}</template>
      </el-table-column>
      <el-table-column prop="title" label="会话标题" min-width="200" />
      <el-table-column prop="createdAt" label="创建时间" width="170" />
      <el-table-column prop="updatedAt" label="最近更新" width="170" />
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openSession(row)">查看消息</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.pageNum" :page-size="query.pageSize" :total="total"
                   layout="total, prev, pager, next" style="margin-top: 16px; justify-content: flex-end"
                   @current-change="load" />

    <el-drawer v-model="drawer.visible" :title="`会话消息(${drawer.sessionId})`" size="560px" :close-on-click-modal="!reply.saving" :close-on-press-escape="!reply.saving" :show-close="!reply.saving">
      <div v-if="drawer.loading" class="detail-loading" role="status">正在加载会话消息…</div>
      <div v-if="drawer.error" class="state-error" role="alert">{{ drawer.error }}<el-button @click="loadMessages()">重新加载消息</el-button></div>
      <div class="msg-list">
        <div v-for="m in drawer.messages" :key="m.messageId" class="msg-item" :class="m.role">
          <div class="msg-meta muted">
            {{ m.role === 'user' ? '患者' : (m.answerMode === 'HUMAN' ? '医生' : '助手') }} · {{ m.createdAt }}
            <template v-if="m.role === 'assistant' && m.answerMode">
              · {{ modeText[m.answerMode] || m.answerMode }}
              <span v-if="m.degraded" class="status-tag st-warning">降级</span>
            </template>

          </div>
          <div class="msg-bubble">{{ m.content || (m.status === 'PENDING' ? '正在生成回答…' : '回答未完成') }}</div>
          <div v-if="m.failureReason" class="state-error">{{ m.failureReason }}</div>
          <div v-if="m.hasSources" class="source-note">此回答附有资料引用，可在患者端打开来源面板核对原文。</div>
        </div>
        <el-empty v-if="!drawer.loading && !drawer.error && !drawer.messages.length" description="暂无消息" />
      </div>
      <div class="reply-box">
        <div class="reply-label">人工回复患者</div>
        <el-input v-model="reply.content" type="textarea" :rows="4" maxlength="2000" show-word-limit
                  placeholder="仅发送健康管理建议和就医提示；不要替代诊断、处方或剂量调整" :disabled="reply.saving || drawer.loading || !!drawer.error"
                  @keydown.ctrl.enter="sendReply" />
        <div v-if="reply.error" class="state-error" role="alert">{{ reply.error }}；正文与提交编号已保留，可重试。</div>
        <div class="reply-actions"><span class="muted">发送后患者端会标记为“医生回复”</span><el-button type="primary" :loading="reply.saving" :disabled="!reply.content.trim() || drawer.loading || !!drawer.error" @click="sendReply">发送回复</el-button></div>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.msg-list { display: flex; flex-direction: column; border-top: 1px solid var(--color-border); }
.msg-item { padding: 18px 0; border-bottom: 1px solid var(--color-border); }
.msg-item .msg-meta { font-family: var(--font-data); font-size: 12px; flex-wrap: wrap; margin-bottom: 8px; display: flex; gap: 8px; align-items: center; color: var(--color-ink-muted); }
.msg-bubble { white-space: pre-wrap; word-break: break-word; font-size: 14px; line-height: 1.8; }
.msg-item.user { display: flex; flex-direction: column; align-items: flex-end; }
.msg-item.user .msg-bubble { max-width: 88%; background: var(--color-primary); color: #FFFFFF; padding: 10px 14px; border-radius: 6px; }
.msg-item.assistant .msg-bubble { padding-top: 10px; border-top: 1px solid var(--color-border-strong); }
.reply-box { border-top: 1px solid var(--color-border); margin-top: 24px; padding-top: 16px; }
.reply-label { font-size: 14px; font-weight: 600; color: var(--color-ink-muted); margin-bottom: 8px; }
.reply-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 10px; }
@media (max-width: 560px) { .reply-actions { align-items: stretch; flex-direction: column; } }
.source-note { margin-top: 12px; padding: 10px 12px; background: var(--color-info-subtle); border-radius: 8px; font-size: 12px; color: var(--color-ink-muted); }
</style>
