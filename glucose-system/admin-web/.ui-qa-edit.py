from pathlib import Path
p=Path('glucose-system/admin-web/src/views/QaView.vue');s=p.read_text(encoding='utf-8')
s=s.replace("messages: [], patientId: ''", "messages: [], patientId: '', loading: false, error: ''").replace("saving: false })", "saving: false, error: '' })\nlet detailVersion = 0")
s=s.replace('x.userId === id','String(x.userId) === String(id)')
a=s.index('async function openSession');b=s.index('function newRequestId',a)
s=s[:a]+'''async function openSession(row) {
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

'''+s[b:]
a=s.index('async function sendReply');b=s.index('const modeText',a)
s=s[:a]+'''async function sendReply() {
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

'''+s[b:]
s=s.replace('size="560px">','size="560px" :close-on-click-modal="!reply.saving" :close-on-press-escape="!reply.saving" :show-close="!reply.saving">\n      <div v-if="drawer.loading" class="detail-loading" role="status">正在加载会话消息…</div>\n      <div v-if="drawer.error" class="state-error" role="alert">{{ drawer.error }}<el-button @click="loadMessages()">重新加载消息</el-button></div>')
s=s.replace('            <template v-if="m.role === \'assistant\'"> · {{ m.latencyMs }}ms</template>','')
s=s.replace('<div class="msg-bubble">{{ m.content || `(${m.status})` }}</div>','<div class="msg-bubble">{{ m.content || (m.status === \'PENDING\' ? \'正在生成回答…\' : \'回答未完成\') }}</div>\n          <div v-if="m.failureReason" class="state-error">{{ m.failureReason }}</div>\n          <div v-if="m.hasSources" class="source-note">此回答附有资料引用，可在患者端打开来源面板核对原文。</div>')
s=s.replace('v-if="!drawer.messages.length"','v-if="!drawer.loading && !drawer.error && !drawer.messages.length"').replace(':disabled="reply.saving"',':disabled="reply.saving || drawer.loading || !!drawer.error"')
s=s.replace('<div class="reply-actions">','<div v-if="reply.error" class="state-error" role="alert">{{ reply.error }}；正文与提交编号已保留，可重试。</div>\n        <div class="reply-actions">').replace(':disabled="!reply.content.trim()"',':disabled="!reply.content.trim() || drawer.loading || !!drawer.error"')
s=s.replace('border-top: 2px solid var(--color-ink)','border-top: 1px solid var(--color-border)').replace('font-size: 10px; letter-spacing: .06em;','font-size: 12px; flex-wrap: wrap;').replace('font-size: 12px; font-weight: 650; letter-spacing: .08em;','font-size: 14px; font-weight: 600;')
s=s.replace('</style>', '.source-note { margin-top: 12px; padding: 10px 12px; background: var(--color-info-subtle); border-radius: 8px; font-size: 12px; color: var(--color-ink-muted); }\n</style>')
p.write_text(s,encoding='utf-8')
p=Path('glucose-system/admin-web/src/router/index.js');s=p.read_text(encoding='utf-8').replace("import { getToken } from '../utils/request'", "import { getToken } from '../utils/request'\nimport { useAuthStore } from '../stores/auth'")
s=s.replace("      { path: 'doctors'", "      { path: 'access-denied', name: 'access-denied', component: () => import('../views/AccessDeniedView.vue'), meta: { title: '访问受限' } },\n      { path: 'doctors'")
s=s.replace('  return true\n})', "  if (to.meta.adminOnly && !useAuthStore().isAdmin) return { name: 'access-denied' }\n  return true\n})")
p.write_text(s,encoding='utf-8')
Path('glucose-system/admin-web/src/views/AccessDeniedView.vue').write_text('''<template>
  <div class="panel permission-state"><h1 class="page-title">此页面仅限管理员使用</h1><p class="muted">当前账号可以查看已绑定患者、血糖分析、预警和问答记录。如需管理账号或知识库，请联系管理员。</p><router-link to="/dashboard">返回工作台</router-link></div>
</template>
<style scoped>.permission-state { max-width: 720px; padding: 32px; }.permission-state p { margin-top: 20px; line-height: 1.8; }</style>
''',encoding='utf-8')
p=Path('glucose-system/admin-web/src/styles/global.css');s=p.read_text(encoding='utf-8');s+='''
.state-error { display:flex; flex-wrap:wrap; align-items:center; gap:12px; padding:12px 14px; margin:12px 0; color:var(--color-danger); background:var(--color-danger-subtle); border-radius:8px; overflow-wrap:anywhere; }
.detail-loading { padding:24px 0; color:var(--color-ink-muted); }
.el-drawer__body, .el-dialog__body { overflow-wrap:anywhere; }
@media(max-width:767px) {
  .el-message-box { max-width:calc(100vw - 24px); }
  .el-drawer__header { padding:18px 16px 14px; }
  .el-drawer__body { padding:16px; }
  .el-table .el-button.is-link { min-height:44px; }
  .el-dialog__footer { display:flex; justify-content:flex-end; gap:8px; }
  .el-pagination { justify-content:center!important; }
}
''';p.write_text(s,encoding='utf-8')
