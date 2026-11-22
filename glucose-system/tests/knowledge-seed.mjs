// Seed sourced, local-only knowledge documents and optionally enable them.
// This script never accepts or writes a model API key; it uses the local admin API.
import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import { spawnSync } from 'node:child_process'
import { createHash } from 'node:crypto'

const base = process.env.RUNTIME_BASE || 'http://127.0.0.1:8083'
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname), '此脚本只允许本机测试环境')
const enable = process.argv.includes('--enable')
const update = process.argv.includes('--update')
const out = new URL('./.artifacts/knowledge-acceptance-2026-10-25/', import.meta.url)
const sources = new URL('./fixtures/knowledge-sources/', import.meta.url)
const fixtureNames = [
  'knowledge-low-blood-sugar.json',
  'knowledge-diabetes-testing.json',
  'knowledge-daily-management.json',
  'knowledge-cgm-smbg.json'
]
const previousTitles = { 'knowledge-low-blood-sugar.json': '低血糖：识别与 15-15 处理原则' }

// Check provenance before writing anything. A reachable URL alone is not a content review.
const sourceManifest = JSON.parse(await fs.readFile(new URL('manifest.json', sources), 'utf8'))
const drafts = []
for (const fixtureName of fixtureNames) {
  const draft = JSON.parse(await fs.readFile(new URL('./fixtures/' + fixtureName, import.meta.url), 'utf8'))
  const source = sourceManifest.find(item => item.url === draft.sourceUrl && item.status === 200)
  assert.ok(source, `${draft.title}: 缺少成功读取的官方原文记录`)
  const sourceText = await fs.readFile(new URL(source.id + '.txt', sources))
  assert.equal(createHash('sha256').update(sourceText).digest('hex'), source.textSha256, '原文快照校验失败')
  assert.ok(draft.content.length <= 400, `${draft.title}: 保持适用范围与正文在同一切片内`)
  drafts.push({ fixtureName, draft })
}

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

const captcha = await api('GET', '/api/common/captcha')
const redisPath = process.env.REDIS_CLI || 'D:/Redis-x64-5.0.14.1/redis-cli.exe'
const redisEnv = { ...process.env }
if (process.env.REDIS_PASSWORD) redisEnv.REDISCLI_AUTH = process.env.REDIS_PASSWORD
const redis = spawnSync(redisPath, ['-h', '127.0.0.1', '-p', process.env.REDIS_PORT || '6379', '--raw', 'GET', 'auth:cap:' + captcha.captchaId], {
  env: redisEnv, encoding: 'utf8', windowsHide: true, timeout: 5000
})
assert.equal(redis.status, 0, '无法读取本地验证码；请通过 REDIS_CLI 指定 redis-cli 路径')
const admin = await api('POST', '/api/admin/auth/login', '', {
  username: process.env.GLUCOSE_ADMIN_USERNAME || 'admin',
  password: process.env.GLUCOSE_ADMIN_PASSWORD || 'admin123',
  captchaId: captcha.captchaId,
  captchaCode: redis.stdout.trim()
})

const results = []
for (const { fixtureName, draft } of drafts) {
  const existing = await api('GET', '/api/admin/kb/documents?pageSize=100&keyword=' + encodeURIComponent(draft.title), admin.token)
  let matches = existing.records.filter(item => item.title === draft.title)
  if (matches.length === 0 && previousTitles[fixtureName]) {
    const old = await api('GET', '/api/admin/kb/documents?pageSize=100&keyword=' + encodeURIComponent(previousTitles[fixtureName]), admin.token)
    matches = old.records.filter(item => item.title === previousTitles[fixtureName])
  }
  assert.ok(matches.length <= 1, `${draft.title}: 同名文档不唯一，拒绝自动覆盖`)
  let document = matches[0]
  let action = 'unchanged'
  if (!document) {
    document = await api('POST', '/api/admin/kb/documents', admin.token, draft)
    action = 'created'
  } else {
    const before = await api('GET', '/api/admin/kb/documents/' + document.id, admin.token)
    if (Object.keys(draft).some(key => before[key] !== draft[key])) {
      assert.ok(update, `${draft.title}: 已有内容或来源不同；核对后显式传 --update 才能更新`)
      if (before.status === 1) await api('PUT', `/api/admin/kb/documents/${document.id}/status`, admin.token, { status: 0 })
      await api('PUT', '/api/admin/kb/documents/' + document.id, admin.token, draft)
      action = 'updated'
    }
  }
  const detail = await api('GET', '/api/admin/kb/documents/' + document.id, admin.token)
  assert.equal(detail.content, draft.content, `${draft.title}: 已存在内容不同，拒绝覆盖`)
  assert.equal(detail.sourceUrl, draft.sourceUrl, `${draft.title}: 来源链接不一致`)
  if (detail.indexStatus !== 'READY') await api('POST', `/api/admin/kb/documents/${document.id}/reindex`, admin.token)
  if (enable && detail.status !== 1) await api('PUT', `/api/admin/kb/documents/${document.id}/status`, admin.token, { status: 1 })
  const indexed = await api('GET', '/api/admin/kb/documents/' + document.id, admin.token)
  assert.equal(indexed.indexStatus, 'READY', `${draft.title}: 索引未就绪`)
  if (enable) assert.equal(indexed.status, 1, `${draft.title}: 启用失败`)
  const chunks = await api('GET', `/api/admin/kb/documents/${document.id}/chunks`, admin.token)
  assert.equal(chunks.length, 1, '每篇短摘要应保持为一个完整切片')
  assert.equal(chunks[0].content, draft.content)
  results.push({ action, id: indexed.id, title: indexed.title, status: indexed.status, indexStatus: indexed.indexStatus,
    documentVersion: indexed.documentVersion, contentHash: indexed.contentHash, activeIndexVersion: indexed.activeIndexVersion,
    chunkCount: indexed.chunkCount, embeddingModel: chunks[0].embeddingModel, dimension: chunks[0].dim, sourceUrl: indexed.sourceUrl, sourceVersion: indexed.sourceVersion })
}
const inventory = await api('GET', '/api/admin/kb/documents?pageSize=100', admin.token)
const report = { passed: true, generatedAt: new Date().toISOString(), enableRequested: enable, updateRequested: update,
  clinicalReview: 'not-performed', documents: results, totalDocuments: inventory.total,
  enabledDocuments: inventory.records.filter(item => item.status === 1).length }
await fs.mkdir(out, { recursive: true })
await fs.writeFile(new URL(update ? 'seed-results.json' : 'seed-repeat-results.json', out), JSON.stringify(report, null, 2) + '\n')
console.log(JSON.stringify(report, null, 2))
