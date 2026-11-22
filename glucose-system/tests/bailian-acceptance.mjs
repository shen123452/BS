// Explicit local integration check. Only --prepare-knowledge adds a sourced base document.
// Uses the backend's in-memory provider settings; never reads or saves a provider API key.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { randomUUID } from 'node:crypto'
const base = process.env.RUNTIME_BASE || 'http://127.0.0.1:8083'
assert.ok(['127.0.0.1','localhost'].includes(new URL(base).hostname), '此脚本只允许本机测试环境')
const out = new URL('./.artifacts/bailian-acceptance-2026-10-24/', import.meta.url)
await fs.mkdir(out, { recursive: true })
const checks = []
let patient, report, cleanupPassed = false
async function api(method, path, token, body) {
  const response = await fetch(base + path, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(25000) })
  const result = await response.json()
  assert.equal(result.code, 0, `${method} ${path}: ${result.message}`)
  return result.data
}
function pass(name, detail) { checks.push({ name, passed: true, detail }); console.log('PASS ' + name) }
try {
  const captcha = await api('GET', '/api/common/captcha')
  const redisPath = process.env.REDIS_CLI || 'D:/Redis-x64-5.0.14.1/redis-cli.exe'
  const redisEnv = { ...process.env }
  if (process.env.REDIS_PASSWORD) redisEnv.REDISCLI_AUTH = process.env.REDIS_PASSWORD
  const redis = spawnSync(redisPath, ['-h','127.0.0.1','-p',process.env.REDIS_PORT || '6379','--raw','GET','auth:cap:' + captcha.captchaId],
    { env: redisEnv, encoding: 'utf8', windowsHide: true, timeout: 5000 })
  assert.equal(redis.status, 0, '无法读取本地验证码；请通过 REDIS_CLI 指定本地 redis-cli 路径')
  const admin = await api('POST', '/api/admin/auth/login', '', { username: process.env.GLUCOSE_ADMIN_USERNAME || 'admin',
    password: process.env.GLUCOSE_ADMIN_PASSWORD || 'admin123', captchaId: captcha.captchaId, captchaCode: redis.stdout.trim() })
  const consumed = await fetch(base + '/api/admin/auth/login', { method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username: process.env.GLUCOSE_ADMIN_USERNAME || 'admin', password: process.env.GLUCOSE_ADMIN_PASSWORD || 'admin123', captchaId: captcha.captchaId, captchaCode: redis.stdout.trim() }) })
  assert.equal((await consumed.json()).code, 40001)
  pass('本地 Redis 5 管理员登录及一次性验证码兼容')
  const draft = JSON.parse(await fs.readFile(new URL('./fixtures/blood-glucose-basics.json', import.meta.url), 'utf8'))
  const existing = await api('GET', '/api/admin/kb/documents?keyword=' + encodeURIComponent(draft.title), admin.token)
  let document = existing.records.find(d => d.title === draft.title && d.sourceUrl === draft.sourceUrl)
  if (process.argv.includes('--prepare-knowledge')) {
    if (!document) document = await api('POST', '/api/admin/kb/documents', admin.token, draft)
    const detail = await api('GET', '/api/admin/kb/documents/' + document.id, admin.token)
    assert.equal(detail.content, draft.content, '已有同名资料内容不同，未覆盖或启用')
    if (detail.indexStatus !== 'READY') await api('POST', `/api/admin/kb/documents/${document.id}/reindex`, admin.token)
    if (detail.status !== 1) await api('PUT', `/api/admin/kb/documents/${document.id}/status`, admin.token, { status: 1 })
  }
  assert.ok(document, '尚未准备基础资料；确认来源后可显式传 --prepare-knowledge')
  const indexed = await api('GET', '/api/admin/kb/documents/' + document.id, admin.token)
  assert.equal(indexed.indexStatus, 'READY'); assert.equal(indexed.status, 1); assert.ok(indexed.chunkCount >= 1)
  pass('百炼 text-embedding-v3 真实索引就绪', { model: 'text-embedding-v3', dimension: 1024, sourceUrl: draft.sourceUrl })
  patient = await api('POST', '/api/app/auth/wx-login', '', { code: 'provider-check-' + randomUUID() })
  const targets = await api('GET', '/api/app/profile/targets', patient.token)
  await api('PUT', '/api/app/profile/targets', patient.token, { targetRevision: targets.targetRevision, privacyVersion: 'privacy-v1' })
  const availability = await api('GET', '/api/app/qa/availability', patient.token)
  assert.equal(availability.modelReady, true); assert.equal(availability.knowledgeReady, true)
  const session = await api('POST', '/api/app/qa/sessions', patient.token, { title: '百炼本地联调（自动清理）' })
  const ask = (requestId, question) => api('POST', '/api/app/qa/ask', patient.token, { sessionId: session.sessionId, requestId, question })
  const hello = await ask('hello', '你好')
  assert.equal(hello.answerMode, 'FIXED_NOTICE'); assert.equal(hello.sources.length, 0); assert.match(hello.answer, /你好/)
  pass('问候直接返回使用提示，不伪造引用')
  const answer = await ask('basics', '血糖是什么')
  assert.equal(answer.answerMode, 'RAG', '应由真实百炼模型基于资料生成，而非降级')
  assert.match(answer.answer, /葡萄糖/); assert.ok(answer.sources.length >= 1)
  const sources = await api('GET', `/api/app/qa/messages/${answer.messageId}/sources`, patient.token)
  assert.ok(sources.some(s => s.sourceUrl === draft.sourceUrl && s.excerpt.includes('葡萄糖')))
  pass('百炼 qwen-plus 回答血糖定义并展示真实出处', { answer: answer.answer, mode: answer.answerMode, latencyMs: answer.latencyMs, sources: sources.map(s => ({ title: s.title, sourceUrl: s.sourceUrl })) })
  const replay = await ask('basics', '血糖是什么'); assert.equal(replay.messageId, answer.messageId)
  const unknown = await ask('uncovered', '如何维修汽车变速箱')
  assert.equal(unknown.answerMode, 'NO_COVERAGE'); assert.equal(unknown.sources.length, 0)
  const messages = await api('GET', `/api/app/qa/sessions/${session.sessionId}/messages`, patient.token)
  assert.equal(messages.find(m => m.messageId === hello.messageId).hasSources, false)
  assert.equal(messages.find(m => m.messageId === answer.messageId).hasSources, true)
  assert.equal(messages.find(m => m.messageId === unknown.messageId).hasSources, false)
  pass('重复请求复用结果；未覆盖及空引用消息不显示资料入口')
  report = { passed: true, scope: '本机 MySQL 5.7 / Redis；真实阿里百炼 API；仅合成测试账号', checks }
} catch (error) {
  report = { passed: false, checks, error: error.message }; console.error(error.message); process.exitCode = 1
} finally {
  if (patient) {
    try { await api('DELETE', '/api/app/account', patient.token); cleanupPassed = true }
    catch { process.exitCode = 1; report.passed = false; report.cleanupError = '临时测试账号清理失败' }
  }
  await fs.writeFile(new URL('results.json', out), JSON.stringify({ ...report, testAccountDeleted: cleanupPassed }, null, 2))
}
