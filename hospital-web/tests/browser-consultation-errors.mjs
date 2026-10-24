import { chromium } from 'playwright'
import assert from 'node:assert/strict'

// Isolated API fixtures: no database changes or provider calls.
const browser = await chromium.launch({ executablePath: process.env.QA_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true })
const context = await browser.newContext({ locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
await context.addInitScript(() => sessionStorage.setItem('hospital.token', 'isolated-consultation-error-test'))
const page = await context.newPage(), errors = [], checks = []
page.on('pageerror', error => errors.push(error.message))
let status = 503
await context.route('**/api/**', async route => {
  const request = route.request(), path = new URL(request.url()).pathname
  const respond = data => route.fulfill({ json: { code: 'OK', data, message: '成功' } })
  if (path === '/api/auth/me') return respond({ id: 'test-patient', role: 'PATIENT', displayName: '错误处理测试（虚构）', status: 'ACTIVE' })
  if (path === '/api/patient/consultations') return respond({ items: [{ id: '20', title: '虚构错误处理会话', summaryVersion: 0 }], total: 1 })
  if (path === '/api/patient/consultations/20') return respond({ id: '20', title: '虚构错误处理会话', summaryVersion: 0 })
  if (path.endsWith('/triage-runs')) return respond({ items: [], total: 0 })
  if (path.endsWith('/messages')) {
    if (request.method() === 'GET') return respond([])
    assert.ok(request.headers().accept.includes('text/event-stream'))
    assert.ok(request.headers().accept.includes('application/json'))
    return route.fulfill({ status, json: { code: status === 401 ? 'INVALID_TOKEN' : 'AI_NOT_CONFIGURED', message: status === 401 ? '登录凭证无效或已过期' : '请先配置百炼 DASHSCOPE_API_KEY', traceId: 'isolated-error-test' } })
  }
  throw new Error(`未预期的隔离请求：${path}`)
})
try {
  await page.goto(`${process.env.QA_FRONTEND_URL || 'http://127.0.0.1:5173'}/patient/consultations`)
  await page.getByText('虚构错误处理会话', { exact: true }).first().waitFor()
  const draft = '虚构工程测试，不作就医建议。'
  await page.locator('#messageInput').fill(draft)
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.getByRole('alert').getByText('请先配置百炼 DASHSCOPE_API_KEY', { exact: true }).waitFor()
  await page.waitForFunction(value => document.querySelector('#messageInput')?.value === value, draft)
  assert.equal(await page.locator('.chat-message').count(), 0)
  assert.ok(await page.evaluate(() => sessionStorage.getItem('hospital.token')))
  assert.equal(new URL(page.url()).pathname, '/patient/consultations')
  checks.push('AI配置错误显示原始原因，保留登录和未接受的输入，不残留虚假聊天记录')
  status = 401
  await page.getByRole('button', { name: '发送', exact: true }).click()
  await page.waitForURL('**/login?expired=1')
  assert.equal(await page.evaluate(() => sessionStorage.getItem('hospital.token')), null)
  checks.push('真实401清除登录并返回登录页')
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ checks, errors }, null, 2))
} finally { await browser.close() }
