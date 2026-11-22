// Verify newly seeded knowledge through the patient RAG endpoint.
// Runs only against the local backend and deletes the synthetic patient account.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { randomUUID } from 'node:crypto'

const base = process.env.RUNTIME_BASE || 'http://127.0.0.1:8083'
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname), '此脚本只允许本机测试环境')
const checks = []
let patient, cleanupPassed = false, report = { passed: false, checks }
const out = new URL('./.artifacts/knowledge-acceptance-2026-10-25/', import.meta.url)
const fixture = async name => JSON.parse(await fs.readFile(new URL('./fixtures/knowledge-' + name + '.json', import.meta.url), 'utf8'))

async function api(method, route, token, body) {
  const response = await fetch(base + route, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
    signal: AbortSignal.timeout(30000)
  })
  const result = await response.json()
  assert.equal(result.code, 0, `${method} ${route}: ${result.message}`)
  return result.data
}

try {
  patient = await api('POST', '/api/app/auth/wx-login', '', { code: 'kb-acceptance-' + randomUUID() })
  const targets = await api('GET', '/api/app/profile/targets', patient.token)
  await api('PUT', '/api/app/profile/targets', patient.token, {
    targetRevision: targets.targetRevision,
    privacyVersion: 'privacy-v1'
  })
  const availability = await api('GET', '/api/app/qa/availability', patient.token)
  assert.equal(availability.modelReady, true)
  assert.equal(availability.knowledgeReady, true)
  const session = await api('POST', '/api/app/qa/sessions', patient.token, { title: '新增知识库验收（自动清理）' })
  const cases = [
    { id: 'low', question: '清醒且能吞咽的糖尿病患者，低血糖时一般如何补糖和复测？',
      document: await fixture('low-blood-sugar'), facts: [/15/, /复测|测量|检测|监测/] },
    { id: 'testing', question: '非孕期糖化血红蛋白达到多少可能达到糖尿病诊断界限？需要复查吗？',
      document: await fixture('diabetes-testing'), facts: [/6\.5/, /确认|复查|第二次|再次/] },
    { id: 'daily', question: '糖尿病日常管理通常包括哪些方面？',
      document: await fixture('daily-management'), facts: [/饮食/, /活动|运动/, /用药|药物|处方/] },
    { id: 'cgm', question: '连续血糖监测和指尖血糖记录有什么区别？',
      document: await fixture('cgm-smbg'), facts: [/间液|细胞之间|细胞间|细胞间隙/, /时点|某个时间|某一时间|瞬时|瞬间|即时|某一时刻|当时/] },
    { id: 'ogtt-boundary', question: '可以用普通饭后两小时的指尖血糖代替口服葡萄糖耐量试验来确诊糖尿病吗？',
      document: await fixture('diabetes-testing'), facts: [/不能|不可|不可以|不应|无法/, /实验室|饮糖|葡萄糖耐量/] },
    { id: 'emergency', question: '低血糖患者意识不清时能喂糖水吗？',
      document: await fixture('low-blood-sugar'), facts: [/不能|不可|不应|不要|严禁/, /急救|急诊|120|紧急医疗/] }
  ]
  for (const { id, question, document, facts } of cases) {
    const answer = await api('POST', '/api/app/qa/ask', patient.token, {
      sessionId: session.sessionId,
      requestId: 'kb-' + id,
      question
    })
    const check = { id, passed: false, question, answer: answer.answer, answerMode: answer.answerMode, latencyMs: answer.latencyMs,
      sources: answer.sources.map(source => ({ title: source.title, sourceUrl: source.sourceUrl, documentVersion: source.documentVersion })) }
    checks.push(check)
    assert.equal(answer.status, 'COMPLETED', `${id}: 回答未完成`)
    assert.equal(answer.answerMode, 'RAG', `${id}: 未走有据回答`)
    const cited = answer.sources.find(source => source.title === document.title && source.sourceUrl === document.sourceUrl)
    assert.ok(cited, `${id}: 没有引用预期文档及其原文链接`)
    assert.equal(cited.excerpt, document.content, `${id}: 引用必须来自当前中文摘要版本`)
    for (const fact of facts) assert.match(answer.answer, fact, `${id}: 缺少关键事实或适用边界 ${fact}`)
    const savedSources = await api('GET', `/api/app/qa/messages/${answer.messageId}/sources`, patient.token)
    assert.deepEqual(savedSources, answer.sources, `${id}: 保存的来源快照不一致`)
    check.passed = true
    console.log('PASS ' + id)
  }
  const uncovered = await api('POST', '/api/app/qa/ask', patient.token, {
    sessionId: session.sessionId, requestId: 'kb-uncovered', question: '如何维修汽车变速箱？'
  })
  assert.equal(uncovered.answerMode, 'NO_COVERAGE')
  assert.equal(uncovered.sources.length, 0)
  checks.push({ id: 'uncovered', answerMode: uncovered.answerMode, sources: [] })
  report = { passed: true, generatedAt: new Date().toISOString(), scope: '本地测试环境；真实百炼；合成问题', checks }
  console.log('PASS uncovered')
} catch (error) {
  report = { passed: false, checks, error: error.message }
  console.error(error.message)
  process.exitCode = 1
} finally {
  if (patient) {
    try {
      await api('DELETE', '/api/app/account', patient.token)
      cleanupPassed = true
      console.log('temporary patient account deleted')
    } catch (error) {
      console.error('temporary patient cleanup failed: ' + error.message)
      report.passed = false
      report.cleanupError = '临时患者账号清理失败'
      process.exitCode = 1
    }
  }
  await fs.mkdir(out, { recursive: true })
  await fs.writeFile(new URL('results.json', out), JSON.stringify({ ...report, testAccountDeleted: cleanupPassed }, null, 2))
}
