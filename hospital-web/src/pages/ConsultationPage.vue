<script setup>
import { computed, nextTick, onMounted, onBeforeUnmount, ref } from 'vue'
import { get, post } from '../lib/http.js'
import { readSse } from '../lib/sse.js'
import ErrorPanel from '../components/ErrorPanel.vue'

const sessions = ref([]), selectedId = ref(''), messages = ref([]), draft = ref('')
const loading = ref(false), busy = ref(false), error = ref(null), operationId = ref('')
const controller = ref(null), messageBox = ref(null)
const selected = computed(() => sessions.value.find(item => item.id === selectedId.value))

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
  if (busy.value) return
  selectedId.value = id; error.value = null
  try { messages.value = await get(`/patient/consultations/${id}/messages`); await scrollBottom() }
  catch (e) { error.value = e }
}
async function createSession() {
  if (busy.value) return
  error.value = null
  try {
    const created = await post('/patient/consultations', {})
    sessions.value.unshift(created); selectedId.value = created.id; messages.value = []
    await nextTick(); document.querySelector('#messageInput textarea')?.focus()
  } catch (e) { error.value = e }
}
async function ensureSession() {
  if (selectedId.value) return selectedId.value
  const created = await post('/patient/consultations', {})
  sessions.value.unshift(created); selectedId.value = created.id; messages.value = []
  return created.id
}
async function send() {
  const content = draft.value.trim()
  if (!content || busy.value) return
  busy.value = true; error.value = null; draft.value = ''
  const clientMessageId = crypto.randomUUID()
  let sessionId
  const userMessage = { id:`local-${clientMessageId}`, clientMessageId, role:'USER', content, status:'COMPLETED' }
  let assistant = { id:`pending-${clientMessageId}`, clientMessageId, role:'ASSISTANT', content:'', status:'STREAMING' }
  try {
    sessionId = await ensureSession()
    messages.value.push(userMessage, assistant); assistant = messages.value.at(-1); await scrollBottom()
    controller.value = new AbortController()
    const response = await fetch(`/api/patient/consultations/${sessionId}/messages`, {
      method:'POST', signal:controller.value.signal,
      headers:{ 'Content-Type':'application/json', 'Accept':'text/event-stream', Authorization:`Bearer ${sessionStorage.getItem('hospital.token')}` },
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
      if (sessionId) { try { messages.value = await get(`/patient/consultations/${sessionId}/messages`) } catch {} }
    }
  } finally {
    busy.value = false; controller.value = null; operationId.value = ''; await scrollBottom()
  }
}
async function cancel() {
  if (!operationId.value || !selectedId.value) return
  const current = controller.value
  try { await post(`/patient/consultations/${selectedId.value}/cancel`, { operationId:operationId.value }) }
  catch (e) { error.value = e }
  current?.abort()
  if (selectedId.value) { try { messages.value = await get(`/patient/consultations/${selectedId.value}/messages`) } catch {} }
  busy.value = false; controller.value = null; operationId.value = ''
}
async function scrollBottom() {
  await nextTick()
  if (messageBox.value) messageBox.value.scrollTop = messageBox.value.scrollHeight
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
        <header class="consultation-title"><strong>{{ selected?.title || '新问诊' }}</strong><div><span>仅本人可见</span><router-link v-if="!busy" :to="`/patient/consultations/${selectedId}/summary`" class="el-button">核对症状并导诊</router-link></div></header>
        <div ref="messageBox" class="consultation-messages" aria-live="polite" aria-relevant="additions text">
          <div v-if="!messages.length" class="consultation-empty"><p>你可以描述主要不适、持续时间，以及希望了解的就医安排。</p></div>
          <article v-for="message in messages" :key="message.id" class="chat-message" :class="message.role === 'USER' ? 'from-patient' : 'from-assistant'"><strong>{{ message.role === 'USER' ? '我' : '就医助手' }}</strong><p>{{ message.content || (message.status === 'STREAMING' ? '正在整理回答…' : '') }}</p><small v-if="message.status === 'FAILED'">回答中断。你可以继续发送消息，先前内容仍会保留。</small><small v-else-if="message.status === 'CANCELLED'">已停止本次回答。</small></article>
        </div>
        <form class="consultation-composer" @submit.prevent="send"><el-input id="messageInput" v-model="draft" type="textarea" :rows="3" :maxlength="10000" show-word-limit :disabled="busy" placeholder="描述你的问题（请勿填写身份证号等敏感信息）" aria-label="输入问诊问题" /><div class="composer-footer"><span>AI提供一般信息，不能替代医生诊疗；紧急情况请拨打 120。</span><div><el-button v-if="busy" @click="cancel">停止回答</el-button><el-button v-else type="primary" native-type="submit" :disabled="!draft.trim()">发送</el-button></div></div></form>
      </template>
    </div>
  </section>
</template>
