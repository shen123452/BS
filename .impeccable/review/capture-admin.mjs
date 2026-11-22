import fs from 'node:fs/promises'

const base = process.env.REVIEW_BASE || 'http://127.0.0.1:5174'
const out = new URL('./', import.meta.url)
await fs.mkdir(out, { recursive: true })
const target = await (await fetch('http://127.0.0.1:9223/json/new?about:blank', { method: 'PUT' })).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }))
let seq = 0
const pending = new Map()
const errors = []
const warnings = []
const requestPaths = []

const patients = [
  { userId: 201, username: 'demo_201', realName: '演示患者甲', dataMode: 'DEMO', doctorName: '演示医生', doctorId: 101, status: 1, createdAt: '2026-10-20 09:00:00' },
  { userId: 202, username: 'demo_202', realName: '演示患者乙', dataMode: 'DEMO', status: 1, createdAt: '2026-10-21 10:20:00' },
  { userId: 203, username: 'demo_203', realName: '演示患者丙', dataMode: 'REAL', doctorName: '演示医生', doctorId: 101, status: 1, createdAt: '2026-10-22 14:05:00' },
  { userId: 204, username: 'demo_204', realName: '演示患者丁', dataMode: 'DEMO', status: 0, createdAt: '2026-10-23 08:40:00' }
]
const summary = {
  metricStatus: 'QUALIFIED', reasonCodes: ['TARGET_NOT_CONFIGURED'], pointCount: 312, avgGlucose: 7.1, minGlucose: 4.2, maxGlucose: 11.8,
  sd: 1.6, cv: 22.5, dataAdequacy: 0.86, eligiblePointCount: 280, unconfiguredTargetCount: 32,
  measurementInRangeRate: 74.6, inTargetCount: 209, targetLow: 3.9, targetHigh: 10, gmi: 6.8, tir: 78.4, tar: 16.2, tbr: 5.4,
  validMonitoringMinutes: 24120, maxAt: '2026-10-24 13:40:00', minAt: '2026-10-26 03:10:00'
}
const trendPoints = Array.from({ length: 48 }, (_, i) => {
  const day = 20 + Math.floor(i / 24)
  const hour = (i % 24) * 60
  return { time: `2026-10-${day} ${String(Math.floor(hour / 60)).padStart(2, '0')}:${String(hour % 60).padStart(2, '0')}:00`, value: Number((5.2 + Math.sin(i / 3) * 1.4 + (i % 7 === 0 ? 2.2 : 0)).toFixed(1)) }
})
const trend = { points: trendPoints, gaps: [{ start: '2026-10-21 02:00:00', end: '2026-10-21 05:00:00' }], totalPoints: 8640, displayPoints: 48, dataSource: 'CGM' }
const alerts = [
  { id: 1, alertType: 'LOW', level: 3, patientId: 201, dataSource: 'CGM', startAt: '2026-10-30 03:12:00', endAt: '', eventStatus: 'OPEN', minGlucose: 3.1, maxGlucose: null, sampleCount: 6, isHistorical: 0, handleStatus: 'PENDING', handleNote: '', version: 2, message: '连续读数低于提醒阈值' },
  { id: 2, alertType: 'HIGH', level: 2, patientId: 202, dataSource: 'CGM', startAt: '2026-10-29 18:20:00', endAt: '2026-10-29 19:05:00', eventStatus: 'ENDED', minGlucose: null, maxGlucose: 13.2, sampleCount: 9, isHistorical: 0, handleStatus: 'PENDING', handleNote: '', version: 1, message: '餐后读数高于提醒阈值' },
  { id: 3, alertType: 'LOW', level: 2, patientId: 203, dataSource: 'SMBG', startAt: '2026-10-28 06:50:00', endAt: '2026-10-28 06:50:00', eventStatus: 'ENDED', minGlucose: 3.6, maxGlucose: null, sampleCount: 1, isHistorical: 1, handleStatus: 'HANDLED', handleNote: '已电话随访', version: 1, message: '指尖血读数低于提醒阈值' }
]
const sessions = [
  { sessionId: '9007199254740993', patientId: 201, title: '餐后血糖偏高怎么办', createdAt: '2026-10-29 19:22:00', updatedAt: '2026-10-29 19:24:00' },
  { sessionId: '9007199254740994', patientId: 202, title: '低血糖时应该吃什么', createdAt: '2026-10-28 08:10:00', updatedAt: '2026-10-28 08:12:00' }
]
const docs = [
  { id: 11, title: '基础血糖概念', category: '基础概念', sourceTitle: 'NIH / NIDDK', documentVersion: 3, status: 1, indexStatus: 'READY', chunkCount: 12 },
  { id: 12, title: '低血糖处理', category: '急救边界', sourceTitle: 'NHS', documentVersion: 2, status: 1, indexStatus: 'READY', chunkCount: 9 },
  { id: 13, title: '项目演示规则', category: '演示规则', sourceTitle: '内部资料', documentVersion: 1, status: 0, indexStatus: 'PENDING', chunkCount: 0 }
]
const doctors = [
  { id: 101, username: 'demo_doctor', realName: '演示医生', phone: '13800000000', status: 1, createdAt: '2026-10-20 09:00:00' },
  { id: 102, username: 'demo_doctor2', realName: '演示医生二', phone: '', status: 0, createdAt: '2026-10-21 09:00:00' }
]

ws.addEventListener('message', async event => {
  const msg = JSON.parse(event.data)
  if (msg.id) { const p = pending.get(msg.id); if (p) { pending.delete(msg.id); msg.error ? p.reject(msg.error) : p.resolve(msg.result) } }
  if (msg.method === 'Runtime.exceptionThrown') errors.push(msg.params.exceptionDetails.text)
  if (msg.method === 'Runtime.consoleAPICalled' && ['error', 'warning'].includes(msg.params.type)) warnings.push(msg.params.args.map(a => a.value || a.description || '').join(' '))
  if (msg.method === 'Fetch.requestPaused') {
    const { requestId, request } = msg.params
    const url = new URL(request.url)
    requestPaths.push(url.pathname)
    let data = { records: [], total: 0 }
    if (url.pathname === '/api/admin/dashboard/overview') data = { activePatients: 128, recordsLast7Days: 3842, pendingAlerts: 7, updatedAt: '2026-10-30 15:20' }
    else if (url.pathname === '/api/admin/patients') data = { records: patients, total: patients.length }
    else if (url.pathname.startsWith('/api/admin/patients/') && url.pathname !== '/api/admin/patients/') data = { ...patients[0], cgmTargetLow: 3.9, cgmTargetHigh: 10, targetRevision: 1, dataRevision: 1 }
    else if (url.pathname === '/api/admin/users/doctors') data = { records: doctors, total: doctors.length }
    else if (url.pathname.includes('/analysis/') && url.pathname.endsWith('/daily')) data = [
      { date: '2026-10-24', count: 288, avg: 7.2, min: 4.4, max: 10.9, coverage: 96 },
      { date: '2026-10-25', count: 276, avg: 6.9, min: 4.1, max: 11.6, coverage: 92 },
      { date: '2026-10-26', count: 280, avg: 7.4, min: 4.2, max: 11.8, coverage: 93 }
    ]
    else if (url.pathname.includes('/analysis/') && url.pathname.endsWith('/trend')) data = trend
    else if (url.pathname.includes('/analysis/')) data = summary
    else if (url.pathname === '/api/admin/glucose') data = { records: [
      { id: 1, measuredAt: '2026-10-30 07:42:00', glucoseMmol: 6.4, dataSource: 'CGM', measureTypeLabel: '连续监测', riskFlag: 'NONE', note: '晨起' },
      { id: 2, measuredAt: '2026-10-30 06:10:00', glucoseMmol: 3.8, dataSource: 'CGM', measureTypeLabel: '连续监测', riskFlag: 'LOW', note: '夜间低值' },
      { id: 3, measuredAt: '2026-10-29 21:05:00', glucoseMmol: 9.6, dataSource: 'SMBG', measureTypeLabel: '餐后2小时', riskFlag: 'NONE', note: '' }
    ], total: 3 }
    else if (url.pathname === '/api/admin/alerts') data = { records: alerts, total: alerts.length }
    else if (url.pathname === '/api/admin/qa/sessions') data = { records: sessions, total: sessions.length }
    else if (url.pathname === '/api/admin/kb/documents') data = { records: docs, total: docs.length }
    else if (url.pathname.endsWith('/captcha')) data = { captchaId: 'visual-only', image: 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="104" height="40"><rect width="104" height="40" fill="#E6ECE6"/><text x="14" y="27" font-size="23" fill="#174A44">DEMO</text></svg>').toString('base64') }
    await send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify({ code: 0, data })).toString('base64') })
  }
})
function send(method, params = {}) { return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })) }) }
const pause = () => new Promise(r => setTimeout(r, 700))
const evaluate = async expression => (await send('Runtime.evaluate', { expression, returnByValue: true, awaitPromise: true })).result.value
await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: base + '/api/*' }] })
await send('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.setItem('accessToken','visual-only');localStorage.setItem('username','演示管理员');localStorage.setItem('role','ADMIN')" })
async function capture(name, path, width = 1440, height = 1000) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 })
  await send('Page.navigate', { url: base + path })
  for (let i = 0; i < 14; i++) { await pause(); if (await evaluate('document.body.innerText.length > 30 && !document.querySelector(".el-loading-mask")')) break }
  const layout = await evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,scrollHeight:document.documentElement.scrollHeight,text:document.body.innerText.slice(0,180)})')
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  await fs.writeFile(new URL(`${name}.png`, out), Buffer.from(shot.data, 'base64'))
  return layout
}
const results = {}
for (const [name, path, width, height] of [
  ['desktop-dashboard', '/dashboard', 1440, 1000],
  ['desktop-patients', '/patients', 1440, 1000],
  ['desktop-glucose', '/glucose', 1440, 1000],
  ['desktop-alerts', '/alerts', 1440, 1000],
  ['desktop-qa', '/qa', 1440, 1000],
  ['desktop-kb', '/kb', 1440, 1000],
  ['desktop-doctors', '/doctors', 1440, 1000],
  ['desktop-login', '/login', 1440, 900],
  ['mobile-dashboard', '/dashboard', 390, 844],
  ['mobile-patients', '/patients', 390, 844],
  ['mobile-glucose', '/glucose', 390, 844],
  ['mobile-login', '/login', 390, 844]
]) results[name] = await capture(name, path, width, height)
await fs.writeFile(new URL('layout.json', out), JSON.stringify({ results, errors, warnings, requestPaths, fixtureNote: '仅隔离合成演示数据，不代表数据库或真实患者' }, null, 2))
await send('Page.close'); ws.close()
console.log(JSON.stringify({ results, errors, warnings }, null, 2))
if (errors.length) process.exitCode = 1
