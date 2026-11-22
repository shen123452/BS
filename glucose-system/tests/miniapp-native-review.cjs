// Optional local simulator review. Uses only a synthetic account and restores simulator storage.
const automator = require('miniprogram-automator')
const assert = require('node:assert/strict')
const fs = require('node:fs/promises')
const path = require('node:path')
const { randomUUID } = require('node:crypto')
const base = process.env.RUNTIME_BASE || 'http://127.0.0.1:8083'
assert.ok(['127.0.0.1', 'localhost'].includes(new URL(base).hostname))
const out = path.resolve(process.env.MINIAPP_REVIEW_OUT || path.join(__dirname, '.artifacts/ui-completion-2026-11-13/native'))
const phase = process.argv[2] || 'review'
assert.ok(['review', 'confirm'].includes(phase))
const checks = [], exceptions = []
let mini, patient, snapshot, originalRoute, report = {}, accountDeleted = false, storageRestored = false
async function api(method, route, token, body) {
  const res = await fetch(base + route, { method, headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: 'Bearer ' + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(25000) })
  const result = await res.json()
  assert.equal(result.code, 0, route + ': ' + result.message)
  return result.data
}
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
async function until(condition, label) {
  for (let i = 0; i < 35; i++) { if (await condition()) return; await pause(300) }
  throw Error('等待超时：' + label)
}
function pass(name, detail) { checks.push({ name, passed: true, ...(detail ? { detail } : {}) }); console.log('PASS ' + name) }
// Geometry and native interactions are recorded; WeChat's App.captureScreenshot is unreliable on this IDE build.
async function tap(page, selector) { const element = await page.$(selector); assert.ok(element, '缺少控件：' + selector); await element.tap() }
async function capture(name) {
  try { await mini.screenshot({ path: path.join(out, phase + '-' + name + '.png') }) }
  catch (e) { report.captureErrors = [...(report.captureErrors || []), name + ': ' + e.message] }
}
async function readyPage(route) {
  const page = await mini.switchTab('/' + route)
  assert.equal(page.path, route)
  await until(async () => !(await page.data('loading')), route + '加载')
  assert.equal((await page.data('errorMsg')) || '', '')
  return page
}
;(async () => {
  await fs.mkdir(out, { recursive: true })
  try {
    mini = process.env.MINIAPP_REVIEW_PROJECT
      ? await automator.launch({
          cliPath: process.env.WECHAT_CLI || 'D:/develop-tools/微信web开发者工具/cli.bat',
          projectPath: process.env.MINIAPP_REVIEW_PROJECT,
          port: Number(process.env.MINIAPP_AUTO_PORT || 9420),
          projectConfig: { appid: 'touristappid' }, timeout: 60000, trustProject: true
        })
      : await automator.connect({ wsEndpoint: process.env.MINIAPP_WS || 'ws://127.0.0.1:9420' })
    const send = mini.connection.send.bind(mini.connection)
    mini.connection.send = (method, params) => {
      let timer
      return Promise.race([send(method, params), new Promise((_, reject) => { timer = setTimeout(() => reject(Error('模拟器指令超时：' + method)), 20000) })])
        .finally(() => clearTimeout(timer))
    }
    mini.on('exception', exception => exceptions.push(exception))
    originalRoute = (await mini.currentPage())?.path
    snapshot = await mini.evaluate(() => wx.getStorageInfoSync().keys.map(key => [key, wx.getStorageSync(key)]))
    const info = await mini.systemInfo()
    report.environment = { platform: info.platform, sdk: info.SDKVersion, width: info.windowWidth, height: info.windowHeight, backend: '独立本机测试库 / Redis / 受控本地模型服务' }
    patient = await api('POST', '/api/app/auth/wx-login', '', { code: 'native-ux-' + randomUUID() })
    await mini.evaluate((token, userId) => {
      wx.clearStorageSync(); wx.setStorageSync('accessToken', token); wx.setStorageSync('userId', userId); wx.setStorageSync('dataMode', 'REAL')
    }, patient.token, patient.userId)
    let page = await mini.reLaunch('/pages/login/index')
    await until(async () => !(await page.data('checking')), '恢复旧登录')
    assert.equal(await page.data('hasSession'), true)
    assert.equal(await page.data('consent'), false)
    
    await page.setData({ consent: true })
    assert.equal(await page.data('consent'), true)
    await tap(page, '.login-submit')
    await until(async () => (await mini.currentPage())?.path === 'pages/home/index', '同意后进入首页')
    const profile = await api('GET', '/api/app/profile', patient.token)
    assert.equal(profile.privacyVersion, 'privacy-v1')
    pass('旧账号在登录页统一确认，服务端保存后进入首页')
    await capture('login-complete')
    page = await readyPage('pages/home/index'); await capture('home'); 
    page = await readyPage('pages/glucose/list'); await capture('records'); 
    await tap(page, '.record-toolbar .btn-primary')
    assert.equal((await mini.currentPage()).path, 'pages/glucose/record')
    await mini.navigateBack()
    pass('记录模块直接进入新增，无需返回首页')
    page = await readyPage('pages/analysis/index'); await capture('analysis'); assert.equal(await page.data('dataSource'), 'SMBG'); 
    page = await readyPage('pages/my/index'); await capture('my'); assert.equal(await page.data('targetDetails'), false); 
    assert.equal(await page.$('[bindtap="acceptPrivacy"]'), null)
    pass('分析独立入口与指尖血糖默认来源；我的设置按需展开')
    page = await readyPage('pages/qa/index')
    assert.equal(await page.data('sessionId'), '')
    
    await capture('qa');
    const input = await page.$('.chat-input'), button = await page.$('.chat-send'), composer = await page.$('.chat-composer')
    const inputSize = await input.size(), buttonSize = await button.size(), composerOffset = await composer.offset(), composerSize = await composer.size(), pageSize = await page.size()
    assert.ok(Number(inputSize.width) > Number(buttonSize.width), '输入框应比发送按钮宽')
    assert.ok(Number(buttonSize.height) >= 44, '发送按钮触点至少 44px')
    assert.ok(Number(composerOffset.top) + Number(composerSize.height) <= Number(pageSize.height) + 2, '输入区不可被底部导航遮挡')
    pass('问答直接输入，首屏无需创建会话或再次确认', { inputWidth: inputSize.width, sendWidth: buttonSize.width, sendHeight: buttonSize.height, composerBottom: Number(composerOffset.top) + Number(composerSize.height), pageHeight: pageSize.height })
    await input.input('你好'); await tap(page, '.chat-send')
    await until(async () => !(await page.data('generating')), '问候回答')
    assert.equal(await page.data('errorMsg'), '')
    const sessionId = await page.data('sessionId'); assert.ok(sessionId)
    let messages = await page.data('messages')
    assert.equal(messages.at(-1).hasSources, false)
    await input.input('血糖是什么'); await tap(page, '.chat-send')
    await until(async () => !(await page.data('generating')), '受控资料回答')
    assert.equal(await page.data('errorMsg'), '')
    messages = await page.data('messages')
    assert.equal(messages.at(-1).answerMode, 'RAG'); assert.equal(messages.at(-1).hasSources, true)
    ; await tap(page, '.source-button')
    await until(async () => !(await page.data('sourceLoading')), '引用资料')
    assert.ok((await page.data('visibleSources')).length >= 1)
    ; await tap(page, '.source-sheet-heading .text-button')
    assert.equal(await page.data('visibleSources'), null)
    pass('问候、自动建会话、受控回答及引用面板通过')
    await readyPage('pages/home/index'); page = await readyPage('pages/qa/index')
    assert.equal(await page.data('sessionId'), sessionId)
    await tap(page, '.qa-toolbar .text-button:last-child')
    const history = await mini.currentPage(); assert.equal(history.path, 'subpackages/qa/pages/sessions')
    await until(async () => !(await history.data('loading')), '历史对话')
    assert.ok((await history.data('sessions')).some(session => session.sessionId === sessionId))
    
    pass('切换底部模块保留当前对话，历史对话可查看')
    page = await mini.reLaunch('/pages/login/index')
    await until(async () => (await mini.currentPage())?.path === 'pages/home/index', '已确认用户直接恢复')
    pass('再次进入登录页自动恢复，无需重复勾选')
    for (const route of ['subpackages/medication/pages/index','subpackages/medication/pages/form','subpackages/import/pages/preview','subpackages/alert/pages/list','subpackages/qa/pages/sessions','pages/privacy/index']) {
      page = await mini.navigateTo('/' + route); await until(async () => !(await page.data('loading')) && !(await page.data('batchesLoading')), route); await pause(500); await capture(route.replaceAll('/', '-')); pass('原生页面可达：' + route); await mini.navigateBack()
    }
    assert.equal(exceptions.length, 0, '微信运行时不应有异常')
    report.passed = true
  } catch (error) {
    report.passed = false; report.error = String(error.message).slice(0, 600); console.error(report.error); process.exitCode = 1
  } finally {
    if (patient) {
      try { await api('DELETE', '/api/app/account', patient.token); accountDeleted = true }
      catch { report.passed = false; report.cleanupError = '临时测试账号清理失败'; process.exitCode = 1 }
    }
    if (mini) {
      if (snapshot) {
        try {
          await mini.evaluate(items => { wx.clearStorageSync(); items.forEach(([key, value]) => wx.setStorageSync(key, value)) }, snapshot)
          storageRestored = true
          await mini.reLaunch('/' + (originalRoute || 'pages/login/index'))
        } catch { report.passed = false; report.restoreError = '原模拟器状态恢复失败'; process.exitCode = 1 }
      }
      await mini.disconnect()
    }
    await fs.writeFile(path.join(out, phase + '-results.json'), JSON.stringify({ ...report, checks, exceptionCount: exceptions.length, testAccountDeleted: accountDeleted, originalStorageRestored: storageRestored }, null, 2))
  }
})().catch(error => { console.error(error.message); process.exitCode = 1 })
