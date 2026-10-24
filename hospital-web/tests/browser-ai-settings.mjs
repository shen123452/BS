import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

// Fictional credentials and isolated API fixtures: no database or provider calls.
const base = process.env.QA_FRONTEND_URL || 'http://127.0.0.1:5173'
const output = new URL('../../.impeccable/review/', import.meta.url)
await mkdir(output, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.QA_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless: true })
const context = await browser.newContext({ viewport: { width: 1440, height: 1000 }, locale: 'zh-CN', timezoneId: 'Asia/Shanghai' })
await context.addInitScript(() => sessionStorage.setItem('hospital.token', 'isolated-ai-settings-fixture'))
const page = await context.newPage(), errors = [], checks = []
page.on('pageerror', error => errors.push(error.message))
let role = 'ADMIN', failLoad = true, failSave = true, saves = 0, releaseSave, releaseLoad
let config = { configured: false, credentialSource: 'NONE', baseUrl: 'https://ws-8kbz2v3i6i8sr0hm.cn-beijing.maas.aliyuncs.com/compatible-mode/v1', chatModel: 'qwen-plus', embeddingModel: 'text-embedding-v4', embeddingDimension: 1024 }
await context.route('**/api/**', async route => {
  const request = route.request(), path = new URL(request.url()).pathname
  const respond = data => route.fulfill({ json: { code: 'OK', data, message: '成功' } })
  if (path === '/api/auth/me') return respond({ id: 'fixture', role, displayName: '工程测试（虚构）', status: 'ACTIVE' })
  if (path === '/api/admin/ai/config') {
    if (releaseLoad !== undefined) await new Promise(resolve => { releaseLoad = resolve })
    if (failLoad) return route.fulfill({ status: 503, json: { code: 'TEST_UNAVAILABLE', message: '虚构配置读取失败，请重试' } })
    return respond(config)
  }
  if (path === '/api/admin/ai/api-key') {
    assert.equal(request.method(), 'PUT')
    assert.match(request.postDataJSON().apiKey, /^test-only-/)
    saves++
    if (failSave) return route.fulfill({ status: 503, json: { code: 'TEST_UNAVAILABLE', message: '虚构保存失败，请重试' } })
    await new Promise(resolve => { releaseSave = resolve })
    config = { ...config, configured: true, credentialSource: 'DATABASE' }
    return respond(config)
  }
  if (path === '/api/patient/profile') return respond({ name: '虚构患者' })
  if (path === '/api/patient/registrations') return respond({ items: [], total: 0 })
  throw new Error(`Unexpected isolated request: ${path}`)
})
try {
  releaseLoad = null
  await page.goto(`${base}/admin/ai-settings`)
  await page.getByRole('heading', { name: '正在读取 AI 配置', exact: true }).waitFor()
  assert.equal(await page.locator('.ai-loading').getAttribute('aria-busy'), 'true')
  for (const [name, width] of [['desktop', 1440], ['mobile', 375]]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: fileURLToPath(new URL(`ai-settings-loading-${name}.png`, output)), fullPage: true, animations: 'disabled' })
  }
  await page.setViewportSize({ width: 1440, height: 1000 })
  releaseLoad(); releaseLoad = undefined
  await page.getByRole('alert').getByText('虚构配置读取失败，请重试', { exact: true }).waitFor()
  failLoad = false
  await page.getByRole('button', { name: '重新加载', exact: true }).click()
  await page.getByRole('heading', { name: '尚未配置 API Key', exact: true }).waitFor()
  assert.equal(await page.getByRole('button', { name: '保存 API Key', exact: true }).isDisabled(), true)
  checks.push('读取失败提供重试，未配置状态明确，空输入不可保存')

  const input = page.locator('#ai-api-key')
  await input.fill('short')
  await page.getByRole('button', { name: '保存 API Key', exact: true }).click()
  await page.getByRole('alert').getByText('请填写完整的 API Key，检查是否混入了空格或换行。', { exact: true }).waitFor()
  assert.equal(saves, 0)
  assert.equal(await input.evaluate(el => el === document.activeElement), true)
  await input.fill('test-only-browser-key')
  await page.getByRole('button', { name: '保存 API Key', exact: true }).click()
  await page.getByRole('alert').getByText('虚构保存失败，请重试', { exact: true }).waitFor()
  assert.equal(await input.inputValue(), 'test-only-browser-key')
  checks.push('无效输入不请求接口并聚焦，服务失败保留输入')

  failSave = false
  await page.getByRole('button', { name: '保存 API Key', exact: true }).click()
  await page.waitForFunction(() => document.querySelector('#ai-api-key').disabled)
  assert.equal(await page.getByRole('button', { name: '刷新状态', exact: true }).isDisabled(), true)
  assert.equal(saves, 2)
  releaseSave()
  await page.getByRole('status').getByText('API Key 已保存，新请求将使用此配置。', { exact: true }).waitFor()
  assert.equal(await input.inputValue(), '')
  assert.equal(await input.getAttribute('type'), 'password')
  assert.equal(await page.getByRole('button', { name: '保存新的 API Key', exact: true }).isDisabled(), true)
  assert.ok(!(await page.content()).includes('test-only-browser-key'))
  assert.equal(await page.evaluate(() => JSON.stringify({ ...localStorage, ...sessionStorage }).includes('test-only-browser-key')), false)
  await page.reload()
  await page.getByRole('heading', { name: 'API Key 已配置', exact: true }).waitFor()
  assert.equal(await input.inputValue(), '')
  checks.push('保存中禁止重复操作，成功清空输入，刷新不回填，浏览器存储不缓存密钥')

  for (const [name, width] of [['desktop', 1440], ['mobile', 375]]) {
    await page.setViewportSize({ width, height: 1000 })
    await page.evaluate(() => window.scrollTo(0, 0))
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true, `${name} overflow`)
    await page.screenshot({ path: fileURLToPath(new URL(`ai-settings-${name}.png`, output)), fullPage: true, animations: 'disabled' })
  }
  checks.push('1440px 桌面与 375px 手机无横向溢出')
  role = 'PATIENT'
  await page.goto(`${base}/admin/ai-settings`)
  await page.waitForURL('**/patient/home')
  assert.equal(await page.locator('#ai-api-key').count(), 0)
  checks.push('患者访问管理员配置页回到本人首页')
  assert.deepEqual(errors, [])
  console.log(JSON.stringify({ checks, saves, errors }, null, 2))
} finally { await browser.close() }
