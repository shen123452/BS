// Creates its own disposable MySQL/Redis containers; credentials exist only in process memory.
// Prerequisites: docker images mysql:8.4, redis:7 and a freshly packaged backend jar.
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import { randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'glucose-system/tests/.artifacts/local-acceptance-2026-10-24')
await fs.mkdir(out, { recursive: true })
const suffix = `${Date.now()}-${process.pid}`
const importDir = path.join(out, '.imports-' + suffix)
await fs.mkdir(importDir)
const mysql = 'glucose-test-mysql-' + suffix, redis = 'glucose-test-redis-' + suffix
const secret = randomUUID(), adminPassword = randomUUID(), jwt = randomUUID() + randomUUID()
const env = { ...process.env, MYSQL_ROOT_PASSWORD: secret, MYSQL_PWD: secret }
const created = []
let backend, backendLog = '', providerMode = 'ok', modelCalls = 0
const checks = []
const pause = ms => new Promise(r => setTimeout(r, ms))
function docker(args, input) {
  const p = spawnSync('docker', args, { env, input, encoding: 'utf8', windowsHide: true, timeout: 60000, maxBuffer: 4_000_000 })
  if (p.status !== 0) throw new Error(`docker ${args[0]} failed: ${(p.stderr || p.error?.message || '').replaceAll(secret, '[redacted]')}`)
  return p.stdout.trim()
}
function sql(query) { return docker(['exec', '-i', '--env', 'MYSQL_PWD', mysql, 'mysql', '-uroot', '--default-character-set=utf8mb4', '-N', '-B'], query) }
async function until(check, label, attempts = 60) {
  for (let i = 0; i < attempts; i++) { try { if (await check()) return } catch {} await pause(1000) }
  throw new Error('Timed out: ' + label)
}
const provider = createServer(async (req, res) => {
  for await (const chunk of req) { /* consume */ }
  res.setHeader('Content-Type', 'application/json')
  if (req.url.endsWith('/embeddings')) return res.end(JSON.stringify({ data: [{ embedding: [1, 0] }] }))
  modelCalls++
  if (providerMode === 'slow') await pause(1500)
  if (providerMode === 'fail') { res.statusCode = 503; return res.end('{"error":"controlled failure"}') }
  const content = providerMode === 'uncited' ? '这是没有引用的测试回答。' : '这是隔离验收资料的回答。[S1]'
  res.end(JSON.stringify({ choices: [{ message: { content } }] }))
})
await new Promise(r => provider.listen(0, '127.0.0.1', r))
const base = 'http://127.0.0.1:18083'
async function request(method, url, token, body) {
  const multipart = body instanceof FormData
  const res = await fetch(base + url, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(!multipart && body !== undefined ? { 'Content-Type': 'application/json' } : {}) }, body: body === undefined ? undefined : multipart ? body : JSON.stringify(body), signal: AbortSignal.timeout(20000) })
  const json = await res.json(); return { status: res.status, ...json }
}
async function ok(method, url, token, body) { const result = await request(method, url, token, body); assert.equal(result.code, 0, `${method} ${url}: ${result.code} ${result.message}`); return result.data }
async function check(name, test) { const start = performance.now(); const detail = await test(); checks.push({ name, passed: true, ms: Math.round(performance.now() - start), ...(detail ? { detail } : {}) }); console.log('PASS ' + name) }
const date = new Date(); date.setDate(date.getDate() - 1)
const day = date.getFullYear() + '-' + String(date.getMonth() + 1).padStart(2, '0') + '-' + String(date.getDate()).padStart(2, '0')
const at = time => day + ' ' + time
const localTime = minutesAgo => { const d = new Date(Date.now() - minutesAgo * 60000); return d.getFullYear() + '-' + String(d.getMonth()+1).padStart(2,'0') + '-' + String(d.getDate()).padStart(2,'0') + ' ' + String(d.getHours()).padStart(2,'0') + ':' + String(d.getMinutes()).padStart(2,'0') + ':00' }
try {
  console.log('Preparing disposable MySQL and Redis...')
  docker(['run', '-d', '--rm', '--name', mysql, '--mount', 'type=tmpfs,destination=/var/lib/mysql', '-p', '127.0.0.1:13316:3306', '-e', 'MYSQL_ROOT_PASSWORD', 'mysql:8.4']); created.push(mysql)
  docker(['run', '-d', '--rm', '--name', redis, '-p', '127.0.0.1:16386:6379', 'redis:7']); created.push(redis)
  await until(() => sql('SELECT 1') === '1', 'MySQL readiness')
  sql(await fs.readFile(path.join(root, 'sql/schema.sql'), 'utf8'))
  const providerBase = 'http://127.0.0.1:' + provider.address().port + '/v1'
  backend = spawn('java', ['-jar', path.join(root, 'glucose-system/backend/target/glucose-backend.jar'), '--server.port=18083', '--spring.profiles.active=dev', '--app.demo.enabled=true', '--app.import.temp-dir=' + importDir, '--logging.level.com.glucose=INFO'], { cwd: root, windowsHide: true, env: { ...process.env, DB_HOST: '127.0.0.1', DB_PORT: '13316', DB_NAME: 'glucose_db', DB_USER: 'root', DB_PASSWORD: secret, REDIS_HOST: '127.0.0.1', REDIS_PORT: '16386', REDIS_PASSWORD: '', JWT_SECRET: jwt, GLUCOSE_ADMIN_USERNAME: 'acceptance_admin', GLUCOSE_ADMIN_PASSWORD: adminPassword, WX_APPID: '', WX_SECRET: '', LLM_BASE_URL: providerBase, LLM_API_KEY: 'isolated-provider', LLM_MODEL: 'fixture', EMBEDDING_BASE_URL: providerBase, EMBEDDING_API_KEY: 'isolated-provider', EMBEDDING_MODEL: 'fixture', EMBEDDING_DIM: '2' } })
  backend.stdout.on('data', b => { backendLog += b.toString() }); backend.stderr.on('data', b => { backendLog += b.toString() })
  await until(async () => (await request('GET', '/api/common/captcha')).code === 0, 'backend startup')
  await until(() => sql("USE glucose_db; SELECT COUNT(*) FROM sys_user WHERE username='demo_patient'") === '1' && sql("USE glucose_db; SELECT COUNT(*) FROM sys_user WHERE username='acceptance_admin'") === '1', 'demo and admin initialization')
  sql('USE glucose_db; ALTER TABLE sys_user AUTO_INCREMENT=200;')
  let a, b, admin, sessionId
  await check('认证与真实数据库初始化', async () => {
    assert.equal((await request('GET', '/api/app/profile')).status, 401)
    a = await ok('POST', '/api/app/auth/wx-login', '', { code: 'local-a-' + suffix }); b = await ok('POST', '/api/app/auth/wx-login', '', { code: 'local-b-' + suffix })
    const captcha = await ok('GET', '/api/common/captcha')
    const code = docker(['exec', redis, 'redis-cli', 'GET', 'auth:cap:' + captcha.captchaId])
    admin = await ok('POST', '/api/admin/auth/login', '', { username: 'acceptance_admin', password: adminPassword, captchaId: captcha.captchaId, captchaCode: code })
  })
  await check('管理员工作台展示全局概览并留审计', async () => {
    const overview = await ok('GET', '/api/admin/dashboard/overview', admin.token)
    const expected = Number(sql("USE glucose_db; SELECT COUNT(*) FROM sys_user WHERE role='PATIENT' AND status=1 AND deleted=0"))
    assert.equal(Number(overview.activePatients), expected)
    assert.ok(Number(overview.recordsLast7Days) >= 0)
    assert.ok(Number(overview.pendingAlerts) >= 0)
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM operation_log WHERE actor_id=${admin.userId} AND action='admin.view.dashboard'`), '1')
    return { activePatients: Number(overview.activePatients), recordsLast7Days: Number(overview.recordsLast7Days), pendingAlerts: Number(overview.pendingAlerts) }
  })
  await check('演示账号隔离、14日CGM覆盖与缺测区间', async () => {
    const demo = await ok('POST', '/api/app/auth/wx-login', '', { code: 'glucose-local-demo' }); assert.equal(demo.dataMode, 'DEMO')
    const summary = await ok('GET', '/api/app/analysis/summary?dataSource=CGM', demo.token); assert.equal(summary.dataKind, 'DEMO'); assert.equal(summary.metricStatus, 'QUALIFIED'); assert.equal(summary.pointCount, 4002)
    const trend = await ok('GET', '/api/app/analysis/trend?dataSource=CGM', demo.token); assert.equal(trend.gaps.length, 1); assert.ok(trend.displayPoints <= 2000)
    const real = await ok('GET', '/api/app/analysis/summary?dataSource=CGM', a.token); assert.equal(real.pointCount, 0)
  })
  await check('医生绑定与解绑后的即时行级授权', async () => {
    const doctor = await ok('POST', '/api/admin/users/doctors', admin.token, { username: 'acceptance_doctor', password: adminPassword, realName: '隔离验收医生' })
    const captcha = await ok('GET', '/api/common/captcha')
    const code = docker(['exec', redis, 'redis-cli', 'GET', 'auth:cap:' + captcha.captchaId])
    const login = await ok('POST', '/api/admin/auth/login', '', { username: 'acceptance_doctor', password: adminPassword, captchaId: captcha.captchaId, captchaCode: code })
    assert.equal((await request('GET', `/api/admin/patients/${a.userId}`, login.token)).status, 403)
    await ok('PUT', `/api/admin/patients/${a.userId}/doctor`, admin.token, { doctorId: doctor.id })
    const bound = await ok('GET', '/api/admin/patients', login.token); assert.equal(Number(bound.total), 1)
    await ok('POST', '/api/app/glucose', a.token, { glucoseMmol: 6.2, measureType: 1 })
    const overview = await ok('GET', '/api/admin/dashboard/overview', login.token)
    assert.equal(Number(overview.activePatients), 1)
    assert.equal(Number(overview.recordsLast7Days), 1)
    assert.equal(Number(overview.pendingAlerts), 0)
    await ok('GET', `/api/admin/analysis/patient/${a.userId}/trend?dataSource=SMBG`, login.token)
    assert.equal((await request('GET', `/api/admin/patients/${b.userId}`, login.token)).status, 403)
    await ok('PUT', `/api/admin/patients/${a.userId}/doctor`, admin.token, { doctorId: 0 })
    assert.equal(sql(`USE glucose_db; SELECT doctor_id IS NULL FROM patient_profile WHERE user_id=${a.userId}`), '1')
    assert.equal((await request('GET', `/api/admin/analysis/patient/${a.userId}/trend?dataSource=SMBG`, login.token)).status, 403)
    const unboundOverview = await ok('GET', '/api/admin/dashboard/overview', login.token)
    assert.equal(Number(unboundOverview.activePatients), 0)
    assert.equal(Number(unboundOverview.recordsLast7Days), 0)
    assert.equal(Number(unboundOverview.pendingAlerts), 0)
  })
  await check('默认时间录入与分钟内20个并发写入去重', async () => {
    const initial = await ok('POST', '/api/app/glucose', a.token, { glucoseMmol: 6.2, measureType: 1 })
    await ok('DELETE', `/api/app/glucose/${initial.id}`, a.token)
    const all = await Promise.all(Array.from({ length: 20 }, (_, i) => request('POST', '/api/app/glucose', a.token, { measuredAt: at('08:00:' + String(i).padStart(2, '0')), glucoseMmol: 6.3, measureType: 1 })))
    assert.equal(all.filter(r => r.code === 0).length, 1); assert.equal(all.filter(r => r.code === 40002).length, 19)
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM glucose_record WHERE patient_id=${a.userId} AND measured_at>='${at('08:00:00')}' AND measured_at<'${at('08:01:00')}' AND deleted=0`), '1')
  })
  await check('长缺口保留两事件、实时严重事件OPEN、处理并发冲突', async () => {
    for (const [measuredAt, glucoseMmol] of [[at('09:00:00'), 3.4], [at('10:00:00'), 3.2], [localTime(2), 2.8]]) await ok('POST', '/api/app/glucose', a.token, { measuredAt, glucoseMmol, measureType: 1 })
    const list = await ok('GET', '/api/app/alerts', a.token); assert.equal(Number(list.total), 3)
    const current = list.records.find(r => r.eventStatus === 'OPEN'); assert.ok(current, JSON.stringify(list.records.map(r => ({ startAt: r.startAt, status: r.eventStatus, historical: r.isHistorical })))); assert.equal(current.level, 3)
    const all = await Promise.all([1, 2].map(n => request('PUT', `/api/admin/alerts/${current.id}/handle`, admin.token, { version: current.version, handleNote: 'isolated-' + n })))
    assert.equal(all.filter(r => r.code === 0).length, 1); assert.equal(all.filter(r => r.code === 40901).length, 1)
    await ok('POST', '/api/app/glucose', a.token, { measuredAt: localTime(1), glucoseMmol: 5.2, measureType: 1 })
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM alert_record WHERE patient_id=${a.userId} AND active_key IS NOT NULL`), '0')
  })
  await check('目标版本并发、隐私单独确认、SMBG目标增删', async () => {
    const invalid = await request('PUT', '/api/app/profile/targets', a.token, { targetRevision: 1, cgmTargetLow: 3.94, cgmTargetHigh: 10 }); assert.notEqual(invalid.code, 0)
    const all = await Promise.all([1, 2].map(() => request('PUT', '/api/app/profile/targets', a.token, { targetRevision: 1, cgmTargetLow: 4, cgmTargetHigh: 10 })))
    assert.equal(all.filter(r => r.code === 0).length, 1); assert.equal(all.filter(r => r.code === 40901).length, 1)
    let t = await ok('GET', '/api/app/profile/targets', a.token)
    t = await ok('PUT', '/api/app/profile/targets', a.token, { targetRevision: t.targetRevision, privacyVersion: 'privacy-v1', rules: [{ measureType: 1, targetLow: 4, targetHigh: 7, basis: 'isolated test' }] }); assert.equal(t.rules.length, 1)
    t = await ok('PUT', '/api/app/profile/targets', a.token, { targetRevision: t.targetRevision, rules: [{ measureType: 1, remove: true }] }); assert.equal(t.rules.length, 0)
    const p = await ok('GET', '/api/app/profile', a.token); assert.equal(p.privacyVersion, 'privacy-v1')
    await ok('PUT', '/api/app/profile', a.token, { realName: '隔离验收患者', birthDate: '1990-01-01' })
    const cleared = await ok('PUT', '/api/app/profile', a.token, { birthDate: '' }); assert.equal(cleared.birthDate, '')
  })
  const preview = async (csv, req) => { const form = new FormData(); form.set('file', new Blob([csv], { type: 'text/csv' }), 'test.csv'); form.set('requestId', req); form.set('metaConfirmed', 'true'); return ok('POST', '/api/app/glucose/import/preview', a.token, form) }
  await check('CSV真实预检、重复并发确认、重复撤销', async () => {
    const bad = await preview(`measured_at,glucose_mmol\n${at('12:00:00')},100\n2099-01-01 12:00:00,6.0\n`, 'bad'); assert.equal(bad.errorRows, 2)
    const p = await preview(`measured_at,glucose_mmol\n${at('12:00:00')},3.4\n${at('12:05:00')},3.3\n`, 'good')
    const confirmed = await Promise.all([1, 2].map(() => ok('POST', `/api/app/glucose/import/batches/${p.batchId}/confirm`, a.token))); assert.ok(confirmed.every(r => r.insertedRows === 2))
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM glucose_record WHERE batch_id=${p.batchId} AND deleted=0`), '2')
    const revoked = await Promise.all([1, 2].map(() => ok('POST', `/api/app/glucose/import/batches/${p.batchId}/revoke`, a.token))); assert.ok(revoked.every(r => r.status === 'REVOKED'))
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM glucose_record WHERE batch_id=${p.batchId} AND deleted=0`), '0')
  })
  await check('问答NO_COVERAGE及重复请求不重复写入', async () => {
    sessionId = (await ok('POST', '/api/app/qa/sessions', a.token, { title: '隔离验收' })).sessionId
    const payload = { sessionId, requestId: 'none', question: '未覆盖的测试问题' }
    const answer = await ok('POST', '/api/app/qa/ask', a.token, payload); assert.equal(answer.answerMode, 'NO_COVERAGE')
    const replay = await ok('POST', '/api/app/qa/ask', a.token, payload); assert.equal(replay.messageId, answer.messageId)
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM qa_message WHERE session_id=${sessionId} AND request_id='none'`), '2')
  })
  await check('问答旧半写恢复、失败重试与会话归属', async () => {
    sql(`USE glucose_db; INSERT INTO qa_message(session_id,request_id,role,content,status) VALUES (${sessionId},'legacy','user','旧请求','COMPLETED')`)
    const payload = { sessionId, requestId: 'legacy', question: '旧请求' }
    const repaired = await ok('POST', '/api/app/qa/ask', a.token, payload); assert.equal(repaired.status, 'COMPLETED')
    sql(`USE glucose_db; UPDATE qa_message SET status='FAILED',failure_reason='INTERRUPTED' WHERE id=${repaired.messageId}`)
    const retried = await ok('POST', '/api/app/qa/ask', a.token, payload); assert.equal(retried.messageId, repaired.messageId); assert.equal(retried.status, 'COMPLETED')
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM qa_message WHERE session_id=${sessionId} AND request_id='legacy'`), '2')
    assert.equal((await request('POST', '/api/app/qa/ask', a.token, { ...payload, question: '不同问题' })).code, 40901)
    assert.equal((await request('GET', `/api/app/qa/sessions/${sessionId}/messages`, b.token)).status, 403)
  })
  await check('索引建立、RAG、模型失败降级与缺失引用降级', async () => {
    const doc = await ok('POST', '/api/admin/kb/documents', admin.token, { title: '隔离验收资料', category: 'test', content: '血糖记录是健康管理的参考。此文档仅供自动化验收，不是临床建议。', sourceTitle: '本地验收夹具', sourceUrl: 'https://example.org/test', sourceVersion: 'fixture-v1' })
    await ok('POST', `/api/admin/kb/documents/${doc.id}/reindex`, admin.token); await ok('PUT', `/api/admin/kb/documents/${doc.id}/status`, admin.token, { status: 1 })
    const ask = requestId => ok('POST', '/api/app/qa/ask', a.token, { sessionId, requestId, question: '血糖' })
    const rag = await ask('rag'); assert.equal(rag.answerMode, 'RAG'); assert.equal(rag.sources.length, 1); assert.ok(rag.sources[0].excerpt); assert.equal(rag.sources[0].sourceUrl, 'https://example.org/test')
    const before = modelCalls; await ask('rag'); assert.equal(modelCalls, before)
    providerMode = 'fail'; const fallback = await ask('fallback'); assert.equal(fallback.answerMode, 'RETRIEVAL_ONLY')
    providerMode = 'uncited'; const uncited = await ask('uncited'); assert.equal(uncited.answerMode, 'RETRIEVAL_ONLY'); providerMode = 'ok'
  })
  await check('问答同请求并发只调用一次模型与会话删除清理', async () => {
    providerMode = 'slow'; const before = modelCalls
    const payload = { sessionId, requestId: 'concurrent', question: '血糖' }
    const first = ok('POST', '/api/app/qa/ask', a.token, payload)
    await until(() => sql(`USE glucose_db; SELECT COUNT(*) FROM qa_message WHERE session_id=${sessionId} AND request_id='concurrent' AND role='assistant'`) === '1', 'QA reservation')
    const second = await ok('POST', '/api/app/qa/ask', a.token, payload); const completed = await first
    assert.equal(second.messageId, completed.messageId); assert.equal(completed.status, 'COMPLETED'); assert.equal(modelCalls - before, 1); providerMode = 'ok'
    const disposable = (await ok('POST', '/api/app/qa/sessions', a.token, { title: '待删除' })).sessionId
    await ok('POST', '/api/app/qa/ask', a.token, { sessionId: disposable, requestId: 'delete', question: '血糖' })
    await ok('DELETE', `/api/app/qa/sessions/${disposable}`, a.token)
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM qa_message WHERE session_id=${disposable}`), '0')
  })
  await check('20并发分析与版本快照', async () => {
    const results = await Promise.all(Array.from({ length: 20 }, async () => { const start = performance.now(); const result = await ok('GET', '/api/app/analysis/summary?dataSource=SMBG', a.token); assert.ok(Number(result.dataRevision) >= 1); return performance.now() - start }))
    results.sort((a, b) => a - b); return { p50Ms: Math.round(results[9]), p95Ms: Math.round(results[18]), maxMs: Math.round(results[19]) }
  })
  await check('注销清理本人数据并立即使旧凭证失效', async () => {
    await ok('POST', '/api/app/glucose', b.token, { glucoseMmol: 6, measureType: 1 })
    await ok('POST', '/api/app/qa/sessions', b.token, { title: '待清理会话' })
    await ok('DELETE', '/api/app/account', b.token)
    assert.equal((await request('GET', '/api/app/profile', b.token)).status, 401)
    for (const table of ['glucose_record', 'glucose_import_batch', 'alert_record', 'glucose_target_rule', 'qa_session']) assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM ${table} WHERE patient_id=${b.userId}`), '0')
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM patient_profile WHERE user_id=${b.userId}`), '0')
    assert.equal(sql(`USE glucose_db; SELECT COUNT(*) FROM sys_user WHERE id=${b.userId} AND deleted=1 AND openid IS NULL AND real_name IS NULL`), '1')
  })
  await fs.writeFile(path.join(out, 'results.json'), JSON.stringify({ passed: true, environment: { mysql: '8.4', redis: '7', java: '21', app: 'local jar', wechat: 'dev mock login', model: 'controlled local HTTP fixture' }, checks }, null, 2))
  console.log(`Completed ${checks.length} real-database acceptance scenarios.`)
} catch (error) {
  await fs.writeFile(path.join(out, 'results.json'), JSON.stringify({ passed: false, checks, error: error.message }, null, 2)); console.error(error.message); process.exitCode = 1
} finally {
  if (backend) { backend.kill(); await pause(300) }
  provider.closeAllConnections(); provider.close()
  for (const name of created.reverse()) { try { docker(['rm', '-f', name]) } catch {} }
  const redacted = backendLog.replaceAll(secret, '[redacted]').replaceAll(adminPassword, '[redacted]').replaceAll(jwt, '[redacted]').replace(/Using generated security password: .*/g, 'Using generated security password: [redacted]')
  await fs.writeFile(path.join(out, 'backend.log'), redacted)
  if (path.dirname(path.resolve(importDir)) !== path.resolve(out) || !path.basename(importDir).startsWith('.imports-')) throw new Error('Refused unsafe cleanup path')
  await fs.rm(importDir, { recursive: true, force: true })
}
