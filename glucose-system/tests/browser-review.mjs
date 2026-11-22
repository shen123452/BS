// Isolated visual fixtures: intercepts API calls; never writes business data.
import fs from 'node:fs/promises'
const phase = process.argv[2] || 'before'
const base = process.env.REVIEW_BASE || 'http://127.0.0.1:5174'
const out = new URL('./.artifacts/ui-review-2026-10-24/', import.meta.url)
await fs.mkdir(out, { recursive: true })
const target = await (await fetch('http://127.0.0.1:9223/json/new?about:blank', { method: 'PUT' })).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }))
let seq = 0
const pending = new Map()
const errors = []
const warnings = []
const requestPaths = []
const fixtures = [{ userId: 201, username: 'demo_201', realName: '演示患者甲', dataMode: 'DEMO', doctorName: '演示医生', doctorId: 101, status: 1, createdAt: '2026-10-20 09:00:00' }, { userId: 202, username: 'demo_202', realName: '演示患者乙', dataMode: 'DEMO', status: 1, createdAt: '2026-10-21 10:20:00' }]
let mode = 'filled'
const summary = { metricStatus: 'DESCRIPTIVE_ONLY', reasonCodes: ['LOW_COVERAGE'], pointCount: 60, avgGlucose: 6.4, minGlucose: 4.2, maxGlucose: 9.8, sd: 1.1, cv: 17.2, dataAdequacy: .42 }
ws.addEventListener('message', async event => {
  const msg = JSON.parse(event.data)
  if (msg.id) { const p = pending.get(msg.id); if (p) { pending.delete(msg.id); msg.error ? p.reject(msg.error) : p.resolve(msg.result) } }
  if (msg.method === 'Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.text)
  if (msg.method === 'Runtime.consoleAPICalled' && ['error','warning'].includes(msg.params.type)) warnings.push(msg.params.args.map(a => a.value || a.description || '').join(' '))
  if (msg.method === 'Fetch.requestPaused') {
    const { requestId, request } = msg.params
    const url = new URL(request.url)
    requestPaths.push(url.pathname)
    if (mode === 'error' && (url.pathname.includes('/analysis/') || url.pathname === '/api/admin/glucose')) {
      await send('Fetch.fulfillRequest', { requestId, responseCode: 503, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify({ code: 50301, message: '隔离测试：服务暂时不可用' })).toString('base64') }); return
    }
    let data = { records: [], total: 0 }
    if (url.pathname === '/api/admin/patients') data = { records: mode === 'empty' ? [] : fixtures, total: mode === 'empty' ? 0 : 2 }
    else if (url.pathname.startsWith('/api/admin/patients/') && url.pathname !== '/api/admin/patients/') data = { ...fixtures[0], cgmTargetLow: 3.9, cgmTargetHigh: 10, targetRevision: 1, dataRevision: 1 }
    else if (url.pathname === '/api/admin/users/doctors') data = { records: [{ id: '101', username: 'demo_doctor', realName: '演示医生', status: 1 }], total: 1 }
    else if (url.pathname.includes('/analysis/')) {
      if (url.pathname.endsWith('/daily')) data = []
      else if (url.pathname.endsWith('/trend')) data = { points: [{ time: '2026-10-20 08:00:00', value: 5.6 }, { time: '2026-10-20 08:05:00', value: 6.4 }, { time: '2026-10-20 10:00:00', value: 9.8 }], gaps: [{ start: '2026-10-20 08:05:00', end: '2026-10-20 10:00:00' }], totalPoints: 3, displayPoints: 3, dataSource: 'CGM' }
      else data = summary
    }
    else if (url.pathname.endsWith('/captcha')) data = { captchaId: 'visual-only', image: 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="104" height="40"><rect width="104" height="40" fill="#eef3f1"/><text x="14" y="27" font-size="23" fill="#24565a">DEMO</text></svg>').toString('base64') }
    await send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify({ code: 0, data })).toString('base64') })
  }
})
function send(method, params = {}) { return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })) }) }
const pause = () => new Promise(r => setTimeout(r, 900))
const evaluate = async expression => (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result.value
await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: base + '/api/*' }] })
await send('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('accessToken','visual-only');localStorage.setItem('username','演示管理员');localStorage.setItem('role','ADMIN')" })
async function capture(name, path, width = 1440) {
  await send('Emulation.setDeviceMetricsOverride', { width, height: 960, deviceScaleFactor: 1, mobile: false })
  await send('Page.navigate', { url: base + path })
  for (let i = 0; i < 12; i++) { await pause(); if (await evaluate('document.body.innerText.length > 20 && !document.querySelector(".el-loading-mask")')) break }
  const layout = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,text:document.body.innerText.slice(0,140),links:[...document.querySelectorAll("nav a")].map(a=>({label:a.getAttribute("aria-label")||a.textContent.trim(),height:a.getBoundingClientRect().height}))})')
  const shot = await send('Page.captureScreenshot', { format: 'png' })
  await fs.writeFile(new URL(`${phase}-${name}.png`, out), Buffer.from(shot.data, 'base64'))
  return layout
}
const results = {}
results.patients = await capture('patients', '/patients')
if (phase === 'confirm') {
  const rowVisible = await evaluate('document.querySelector(".el-table__body")?.innerText.includes("演示患者甲")')
  if (!rowVisible) errors.push('患者表格未渲染夹具行')
  await evaluate('[...document.querySelectorAll("button")].find(b=>b.textContent.trim()==="绑定医生")?.click()'); await pause()
  await evaluate('document.querySelector(".el-dialog .el-select__wrapper")?.click()'); await pause()
  const optionVisible = await evaluate('[...document.querySelectorAll(".el-select-dropdown__item")].some(e=>e.innerText.includes("演示医生"))')
  if (!optionVisible) errors.push('绑定医生选择器未显示选项')
  await evaluate('[...document.querySelectorAll(".el-select-dropdown__item")].find(e=>e.innerText.includes("演示医生"))?.click()')
  await evaluate('[...document.querySelectorAll(".el-dialog button")].find(b=>b.textContent.trim()==="保存绑定")?.click()'); await pause()
  if (await evaluate('Boolean(document.querySelector(".el-dialog")?.getBoundingClientRect().height)')) errors.push('保存绑定后对话框未关闭')
  await evaluate('[...document.querySelectorAll("button")].find(b=>b.textContent.trim()==="查看")?.click()')
  let detailReady = false
  for (let i = 0; i < 10; i++) { await new Promise(r => setTimeout(r, 300)); detailReady = await evaluate('Boolean([...document.querySelectorAll(".el-drawer")].find(e=>e.innerText.includes("CGM 目标范围")))'); if (detailReady) break }
  if (!detailReady) errors.push('患者详情抽屉未正确加载: ' + await evaluate(`[...document.querySelectorAll('.el-drawer')].map(e=>e.innerText.slice(0,180)).join(' | ')`) + '; requests=' + requestPaths.join(','))
}
results.mobile = await capture('mobile', '/patients', 390)
if (phase !== 'before') {
  results.glucose = await capture('glucose', '/glucose')
  if (phase === 'confirm') {
    await evaluate('[...document.querySelectorAll(".el-radio-button")].find(e=>e.textContent.trim()==="SMBG")?.click()'); await pause()
    if (!(await evaluate('Boolean(document.querySelector(".el-date-editor"))'))) errors.push('日期筛选控件未渲染')
  }
  results.glucoseMobile = await capture('glucose-mobile', '/glucose', 390)
  mode = 'empty'; results.empty = await capture('empty', '/patients')
  mode = 'error'; results.error = await capture('error', '/glucose')
  if (phase === 'confirm') {
    if (!(await evaluate('document.body.innerText.includes("隔离测试：服务暂时不可用")'))) errors.push('分析失败状态未显示错误与重试入口')
    mode = 'filled'; await evaluate('[...document.querySelectorAll("button")].find(b=>b.textContent.includes("重新加载"))?.click()'); await pause()
    if (!(await evaluate('document.body.innerText.includes("数据充分性")'))) errors.push('错误状态重试后未恢复图表与指标')
    results.errorRecovery = await evaluate('({hasMetrics:document.body.innerText.includes("数据充分性"), width:document.documentElement.scrollWidth})')
  }
  mode = 'filled'
  await send('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('role','DOCTOR')" })
  results.doctor = await capture('doctor', '/patients')
  await send('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.removeItem('accessToken')" })
  results.login = await capture('login', '/login')
  results.loginMobile = await capture('login-mobile', '/login', 390)
}
await fs.writeFile(new URL(`${phase}-layout.json`, out), JSON.stringify({ results, errors, warnings, requestPaths, fixtureNote: '仅浏览器隔离演示数据，不代表数据库/微信验收' }, null, 2))
await send('Page.close'); ws.close()
console.log(JSON.stringify({ results, errors, warnings }))
if (errors.length) process.exitCode = 1
