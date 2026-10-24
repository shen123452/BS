<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
import { get, post, remove } from '../lib/http.js'
import { ElMessageBox } from 'element-plus'
import Pager from '../components/Pager.vue'
import { readSse } from '../lib/sse.js'
import ErrorPanel from '../components/ErrorPanel.vue'

const sessions = ref([]), selectedId = ref(''), messages = ref([]), draft = ref('')
const loading = ref(false), busy = ref(false), error = ref(null), operationId = ref('')
const controller = ref(null), messageBox = ref(null)
const followingLatest = ref(true)
const history = ref({ items:[], total:0 }), historyPage = ref(1), deleting = ref(false)
const selected = computed(() => sessions.value.find(item => item.id === selectedId.value))
const remoteBusy = computed(() => !!selected.value?.activeOperationId)
const runNames = { QUEUED:'等待处理', RUNNING:'导诊中', SUCCEEDED:'已完成', DEGRADED:'信息或证据有限', FAILED:'处理失败', CANCELLED:'已停止' }

async function loadSessions() {
  loading.value = true; error.value = null
  try {
    const result = await get('/patient/consultations', { page:1, pageSize:100 })
    sessions.value = result.items
    if (!selectedId.value && sessions.value.length) await select(sessions.value[0].id)
    else if (selectedId.value && !sessions.value.some(item => item.id === selectedId.value)) selectedId.value = ''
  } catch (e) { error.value = e } finally { loading.value = false }
}
async function select(id) {
  if (busy.value || deleting.value) return
  selectedId.value = id; error.value = null; messages.value = []; history.value = { items:[], total:0 }; historyPage.value = 1
  try {
    const [storedMessages, storedHistory, current] = await Promise.all([
      get(`/patient/consultations/${id}/messages`),
      get(`/patient/consultations/${id}/triage-runs`, {page:1,pageSize:10}),
      get(`/patient/consultations/${id}`),
    ])
    if (selectedId.value !== id) return
    messages.value = storedMessages; history.value = storedHistory
    const index = sessions.value.findIndex(item => item.id === id)
    if (index >= 0) sessions.value[index] = current
    await scrollBottom(true)
  } catch (e) { if (selectedId.value === id) error.value = e }
}
async function loadHistory(page) {
  const id = selectedId.value
  try {
    const loaded = await get(`/patient/consultations/${id}/triage-runs`, {page,pageSize:10})
    if (selectedId.value === id) { history.value = loaded; historyPage.value = page }
  } catch (e) { if (selectedId.value === id) error.value = e }
}
async function deleteSession() {
  if (!selectedId.value || busy.value || deleting.value || remoteBusy.value) return
  const id = selectedId.value
  try { await ElMessageBox.confirm('删除后，此问诊将从记录列表隐藏。关联的预约、病历和已保存的导诊记录会保留。', '删除问诊记录', {confirmButtonText:'确认删除',cancelButtonText:'保留记录',type:'warning'}) }
  catch { return }
  deleting.value = true; error.value = null
  try {
    await remove(`/patient/consultations/${id}`)
    sessionStorage.removeItem(`hospital.triage.${id}`)
    selectedId.value = ''; messages.value = []; history.value = {items:[],total:0}; draft.value = ''
    deleting.value = false; await loadSessions()
  } catch (e) { error.value = e } finally { deleting.value = false }
}
async function createSession() {
  if (busy.value || deleting.value) return
  error.value = null
  try {
    const created = await post('/patient/consultations', {})
    sessions.value.unshift(created); selectedId.value = created.id; messages.value = []; followingLatest.value = true
    history.value = {items:[],total:0}; historyPage.value = 1
    await nextTick(); document.getElementById('messageInput')?.focus()
  } catch (e) { error.value = e }
}
async function ensureSession() {
  if (selectedId.value) return selectedId.value
  const created = await post('/patient/consultations', {})
  sessions.value.unshift(created); selectedId.value = created.id; messages.value = []
  history.value = {items:[],total:0}; historyPage.value = 1
  return created.id
}
async function send() {
  const content = draft.value.trim()
  if (!content || busy.value || remoteBusy.value || deleting.value) return
  busy.value = true; error.value = null; draft.value = ''
  const clientMessageId = crypto.randomUUID()
  let sessionId
  const userMessage = { id:`local-${clientMessageId}`, clientMessageId, role:'USER', content, status:'COMPLETED' }
  let assistant = { id:`pending-${clientMessageId}`, clientMessageId, role:'ASSISTANT', content:'', status:'STREAMING' }
  try {
    sessionId = await ensureSession()
    messages.value.push(userMessage, assistant); assistant = messages.value.at(-1); await scrollBottom(true)
    controller.value = new AbortController()
    const response = await fetch(`/api/patient/consultations/${sessionId}/messages`, {
      method:'POST', signal:controller.value.signal,
      headers:{ 'Content-Type':'application/json', 'Accept':'text/event-stream, application/json', Authorization:`Bearer ${sessionStorage.getItem('hospital.token')}` },
      body:JSON.stringify({ clientMessageId, content }),
    })
    let terminal = false
    await readSse(response, event => {
      let data
      try { data = JSON.parse(event.data) } catch { throw new Error('服务器返回了无法读取的流式消息。') }
      if (event.name === 'meta') { operationId.value = data.requestId; assistant.id = data.messageId || assistant.id }
      if (event.name === 'delta') { assistant.content += data.text || ''; void scrollBottom() }
      if (event.name === 'done') { assistant.content = data.content ?? assistant.content; assistant.status = data.status || 'COMPLETED'; terminal = true }
      if (event.name === 'error') { assistant.status = 'FAILED'; throw Object.assign(new Error(data.message || '问诊暂时中断。'), { code:data.code }) }
    })
    if (!terminal && assistant.status === 'STREAMING') assistant.status = 'FAILED'
    await loadSessions()
  } catch (e) {
    if (e.name !== 'AbortError') {
      error.value = e; assistant.status = 'FAILED'
      if (!sessionId) draft.value = content
      if (sessionId && e.status !== 401) {
        try {
          messages.value = await get(`/patient/consultations/${sessionId}/messages`)
          if (!messages.value.some(message => message.clientMessageId === clientMessageId && message.role === 'USER')) draft.value = content
        } catch {}
      }
    }
  } finally {
    busy.value = false; controller.value = null; operationId.value = ''; await scrollBottom()
  }
}
async function cancel() {
  const operation = operationId.value || (selected.value?.activeOperationType === 'CHAT' ? selected.value.activeOperationId : null)
  if (!operation || !selectedId.value) return
  const current = controller.value
  try { await post(`/patient/consultations/${selectedId.value}/cancel`, { operationId:operation }) }
  catch (e) { error.value = e; return }
  current?.abort()
  if (selectedId.value) { try { messages.value = await get(`/patient/consultations/${selectedId.value}/messages`) } catch {} }
  busy.value = false; controller.value = null; operationId.value = ''
  await loadSessions()
}
function updateScrollPosition() {
  const box = messageBox.value
  if (box) followingLatest.value = box.scrollHeight - box.scrollTop - box.clientHeight <= 48
}
async function scrollBottom(force = false) {
  if (force) followingLatest.value = true
  await nextTick()
  if (messageBox.value && (force || followingLatest.value)) messageBox.value.scrollTop = messageBox.value.scrollHeight
}
async function returnToLatest() {
  await scrollBottom(true)
  messageBox.value?.focus({ preventScroll:true })
}
onMounted(loadSessions)
onBeforeUnmount(() => { controller.value?.abort() })
</script>

<template>
  <div class="page-heading"><div><h1 tabindex="-1">AI问诊</h1><p>先描述你希望了解的问题，助手会帮你整理就医信息。建议科室仅作参考。</p></div><el-button type="primary" :disabled="busy" @click="createSession">新建问诊</el-button></div>
  <ErrorPanel :error="error" @retry="selectedId ? select(selectedId) : loadSessions" />
  <section class="consultation-shell panel" v-loading="loading">
    <aside class="consultation-history" aria-label="问诊记录"><h2>问诊记录</h2><button v-for="item in sessions" :key="item.id" type="button" :class="{ selected:item.id === selectedId }" :aria-current="item.id === selectedId ? 'page' : undefined" :disabled="busy" @click="select(item.id)"><strong>{{ item.title }}</strong><small>{{ item.updatedAt?.replace('T',' ').slice(0,16) }}</small></button><p v-if="!sessions.length" class="muted">还没有问诊记录</p></aside>
    <div class="consultation-main">
      <div v-if="!selectedId" class="consultation-empty"><h2>描述你的就医问题</h2><p>例如：最近咳嗽几天，挂号前需要准备哪些信息？</p><el-button type="primary" @click="createSession">开始问诊</el-button></div>
      <template v-else>
        <div class="consultation-chat">
          <header class="consultation-title"><strong>{{ selected?.title || '新问诊' }}</strong><div><router-link v-if="!busy && !remoteBusy" :to="`/patient/consultations/${selectedId}/summary`" class="el-button">核对症状并导诊</router-link><el-button :disabled="busy" @click="select(selectedId)">刷新记录</el-button><el-button type="danger" plain :loading="deleting" :disabled="busy || remoteBusy" @click="deleteSession">删除问诊</el-button></div></header>
          <p v-if="remoteBusy && !busy" class="consultation-notice" role="status">{{ selected.activeOperationType === 'TRIAGE' ? '当前会话正在导诊，可从下方历史记录继续查看。' : '上次回答仍在处理中，可刷新记录或停止回答。' }}</p>
          <div class="consultation-message-area">
            <div ref="messageBox" class="consultation-messages" tabindex="0" role="region" aria-label="问诊对话" aria-live="polite" aria-relevant="additions text" @scroll="updateScrollPosition">
              <div v-if="!messages.length" class="consultation-empty"><p>你可以描述主要不适、持续时间，以及希望了解的就医安排。</p></div>
              <article v-for="message in messages" :key="message.id" class="chat-message" :class="message.role === 'USER' ? 'from-patient' : 'from-assistant'"><strong>{{ message.role === 'USER' ? '我' : '就医助手' }}</strong><p>{{ message.content || (message.status === 'STREAMING' ? '正在整理回答…' : '') }}</p><small v-if="message.status === 'FAILED'">回答中断。你可以继续发送消息，先前内容仍会保留。</small><small v-else-if="message.status === 'CANCELLED'">已停止本次回答。</small></article>
            </div>
            <el-button v-if="!followingLatest" class="consultation-scroll-latest" @click="returnToLatest">回到最新消息</el-button>
          </div>
          <form class="consultation-composer" @submit.prevent="send"><el-input id="messageInput" v-model="draft" type="textarea" :rows="3" :maxlength="10000" show-word-limit :disabled="busy || remoteBusy || deleting" placeholder="描述你的问题（请勿填写身份证号等敏感信息）" aria-label="输入问诊问题" /><div class="composer-footer"><span>AI提供一般信息，不能替代医生诊疗；紧急情况请拨打 120。</span><div><el-button v-if="busy || selected?.activeOperationType === 'CHAT'" @click="cancel">停止回答</el-button><el-button v-else type="primary" native-type="submit" :disabled="!draft.trim() || remoteBusy || deleting">发送</el-button></div></div></form>
        </div>
        <section class="consultation-results" aria-label="历史导诊记录"><h2>历史导诊记录</h2><p class="muted">历史建议保留原有来源。预约是否可用，以结果页面实时校验为准。</p><ul v-if="history.items.length"><li v-for="item in history.items" :key="item.id"><div><strong>{{ runNames[item.status] || item.status }}</strong><small>{{ item.createdAt?.replace('T',' ').slice(0,16) }}</small></div><router-link :to="`/patient/triage/${item.id}`" class="el-button">{{ ['QUEUED','RUNNING'].includes(item.status) ? '继续查看导诊' : '查看导诊记录' }}</router-link></li></ul><p v-else class="muted">该问诊还没有导诊记录。</p><Pager :page="historyPage" :total="history.total" :page-size="10" @change="loadHistory" /></section>
      </template>
    </div>
  </section>
</template>
