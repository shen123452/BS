const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const root = path.resolve(__dirname, '../patient-miniapp')
const flush = () => new Promise(resolve => setImmediate(resolve))

test('AGP两层分位带保留空槽，不把null当零或连接缺口', () => {
  const { agpGeometry, drawAgp } = require('../patient-miniapp/utils/agpCanvas')
  const slot = { p5: 3, p25: 5, p50: 6, p75: 7, p95: 10 }
  const profile = { slots: [slot, slot, { p5: null, p25: null, p50: null, p75: null, p95: null }, slot], targetLow: 3.9, targetHigh: 10 }
  const g = agpGeometry(profile)
  assert.deepEqual(g.runs.map(r => r.map(s => s.index)), [[0, 1], [3]])
  const calls = []
  const ctx = new Proxy({}, { get: (_, name) => (...args) => calls.push([name, ...args]), set: () => true })
  drawAgp(ctx, 320, 220, profile, 1)
  assert.equal(calls.filter(c => c[0] === 'fill').length, 3) // two polygons plus isolated median dot
  assert.equal(calls.filter(c => c[0] === 'fillRect').length, 2) // both independent bands for one isolated slot
  assert.ok(calls.filter(c => c[0] === 'fillRect').every(c => c[3] > 0 && c[4] >= 0))
})

test('AGP失败保留基础分析，后续切换来源丢弃迟到分布', async () => {
  let rejectAgp, resolveAgp
  const analysis = page('pages/analysis/index.js', { require: () => ({ agp: () => new Promise((resolve, reject) => { resolveAgp = resolve; rejectAgp = reject }) }) })
  analysis._loadVersion = 1
  analysis.setData({ summary: { pointCount: 8 } })
  const first = analysis.loadAgp({}, 1); rejectAgp(Error('分布暂不可用')); await first
  assert.equal(analysis.data.summary.pointCount, 8); assert.equal(analysis.data.agpError, '分布暂不可用')
  const second = analysis.loadAgp({}, 1); analysis._loadVersion = 2; analysis.setData({ agp: null, agpLoading: false })
  resolveAgp({ slots: [{ time: '00:00', p50: 6 }], qualifiedSlotCount: 1 }); await second
  assert.equal(analysis.data.agp, null)
})

test('问答人工回复在患者端标记为医生并不显示引用入口', async () => {
  const qa = page('pages/qa/index.js', {
    require: () => ({ messages: async () => [{ messageId: '9', role: 'assistant', answerMode: 'HUMAN', status: 'COMPLETED', hasSources: false, content: '请按已有医嘱记录。' }] })
  })
  qa.setData({ sessionId: '11' }); await qa.loadMessages()
  assert.equal(qa.data.messages[0].isHuman, true); assert.equal(qa.data.messages[0].modeText, '医生回复'); assert.equal(qa.data.messages[0].hasSources, false)
})

test('用药表单阻止重复点击；网络失败后重试复用提交编号', async () => {
  const sent = []; let rejectFirst
  const form = page('subpackages/medication/pages/form.js', {
    wx: { navigateBack() {} },
    require: () => ({ addMedicationPlan: data => { sent.push(data); return sent.length === 1 ? new Promise((_, reject) => { rejectFirst = reject }) : Promise.resolve({}) } })
  })
  await form.onShow()
  form.setData({ form: { drugName: '登记药名', dosage: '原处方用量', scheduledTime: '08:30', startDate: form.data.today, endDate: '', instruction: '' } })
  const saving = form.save(); form.save(); assert.equal(sent.length, 1)
  rejectFirst(new Error('网络异常')); await saving
  await form.save(); assert.equal(sent.length, 2); assert.equal(sent[0].requestId, sent[1].requestId)
})

test('用药操作取消时不保存；确认时只提交当前记录和版本', async () => {
  const sent = []; let confirmed = false
  const record = page('subpackages/medication/pages/index.js', {
    wx: { showModal: o => o.success({ confirm: confirmed }) },
    require: () => ({ setMedicationStatus: async (...args) => sent.push(args) })
  })
  record.setData({ loading: false, day: { editable: true }, records: [{ id: '401', status: 'PENDING', version: '7', drugName: '登记药名' }] })
  record.load = async () => {}
  const event = { currentTarget: { dataset: { id: '401', status: 'TAKEN' } } }
  await record.changeStatus(event); assert.equal(sent.length, 0)
  confirmed = true
  const first = record.changeStatus(event); record.changeStatus(event); await first
  assert.equal(sent.length, 1); assert.equal(sent[0][0], '401'); assert.equal(sent[0][1].version, '7'); assert.equal(sent[0][1].status, 'TAKEN')
})

test('历史用药不能打卡；切换日期后迟到请求不覆盖新日期', async () => {
  const pending = []
  const record = page('subpackages/medication/pages/index.js', {
    require: () => ({ medicationDay: date => new Promise(resolve => pending.push({ date, resolve })), setMedicationStatus: () => assert.fail('历史不允许修改') })
  })
  record.setData({ date: '2026-10-24', loading: false, day: { editable: false } })
  await record.changeStatus({ currentTarget: { dataset: { id: '1', status: 'TAKEN' } } })
  const older = record.load(); const newer = record.changeDate({ detail: { value: '2026-10-25' } })
  pending[1].resolve({ date: '2026-10-25', records: [] }); await newer
  pending[0].resolve({ date: '2026-10-24', records: [] }); await older
  assert.equal(record.data.date, '2026-10-25'); assert.equal(record.data.day.date, '2026-10-25')
})

test('用药入口遵守登录守卫，首页用药失败不影响血糖概览', async () => {
  const record = page('subpackages/medication/pages/index.js', {
    session: { ensureSession: async () => false }, require: () => ({ medicationDay: () => assert.fail('不应读取') })
  })
  await record.onShow()
  const home = page('pages/home/index.js', {
    require: () => ({ medicationDay: async () => { throw Error('用药暂不可用') }, latest: async () => ({ SMBG: { todayCount: 1 } }) })
  })
  home.setData({ sources: [{ key: 'SMBG' }] })
  await home.load(); await flush()
  assert.equal(home.data.todayCount, 1); assert.equal(home.data.errorMsg, ''); assert.equal(home.data.medicationError, '用药暂不可用')
})

function page(file, extra = {}) {
  let result
  const api = extra.require ? extra.require() : {}
  const session = extra.session || { ensureSession: async () => true, rememberSession() {}, clearSession() {} }
  function load(filename) {
    const module = { exports: {} }
    vm.runInNewContext(fs.readFileSync(filename, 'utf8'), {
      ...extra, module, exports: module.exports, Page: value => { result = value },
      require: spec => {
        if (spec.endsWith('api/index')) return api
        if (spec.endsWith('/session')) return session
        if (spec.endsWith('/request') && extra.request) return extra.request
        return load(path.resolve(path.dirname(filename), spec + '.js'))
      }
    })
    return module.exports
  }
  load(path.join(root, file))
  result.setData = updates => Object.assign(result.data, updates)
  return result
}

test('概览通过 switchTab 打开记录页并保留一次性来源筛选', async () => {
  const app = { globalData: {} }
  let target
  const home = page('pages/home/index.js', {
    getApp: () => app, wx: { switchTab: options => { target = options.url } }
  })
  home.goList({ currentTarget: { dataset: { source: 'CGM' } } })
  assert.equal(target, '/pages/glucose/list')
  assert.equal(app.globalData.recordSource, 'CGM')
  const list = page('pages/glucose/list.js', { getApp: () => app })
  list.load = () => {}
  await list.onShow()
  assert.equal(list.data.dataSource, 'CGM')
  assert.equal(app.globalData.recordSource, undefined)
  list.data.dataSource = 'SMBG'
  await list.onShow()
  assert.equal(list.data.dataSource, 'SMBG')
})

test('当前时间录入省略时间供后端补充，自定义时间按约定传递', async () => {
  const sent = []
  const record = page('pages/glucose/record.js', {
    require: () => ({ addGlucose: async payload => { sent.push(payload); return {} } })
  })
  Object.assign(record.data, { glucose: '6.0', measureType: 1 })
  record.handleSubmit()
  await flush()
  assert.equal(sent[0].measuredAt, undefined)
  Object.assign(record.data, { useCustomTime: true, customDate: '2026-10-24', customTime: '10:15' })
  record.handleSubmit()
  await flush()
  assert.equal(sent[1].measuredAt, '2026-10-24 10:15:00')
})

for (const days of [7, 14, 30]) {
  test(`${days}日分析只查询已结束的完整本地日`, async () => {
    const fixed = new Date(2026, 9, 24, 18, 0).getTime()
    class FixedDate extends Date {
      constructor(...args) { super(...(args.length ? args : [fixed])) }
      static now() { return fixed }
    }
    const sent = []
    const trend = page('pages/analysis/index.js', {
      Date: FixedDate,
      require: () => ({
        summary: async params => { sent.push(params); return { metricStatus: 'NO_DATA', reasonCodes: [] } },
        daily: async () => [], trend: async () => ({ points: [], totalPoints: 0, displayPoints: 0 })
      })
    })
    trend.data.days = days
    trend.load()
    await flush()
    const { startDate, endDate } = sent[0]
    assert.equal(endDate, '2026-10-24')
    assert.equal((Date.parse(endDate) - Date.parse(startDate)) / 86400000, days)
    assert.equal(trend.data.loading, false)
  })
}

test('记录列表分页追加，不重复首屏并保留筛选', async () => {
  const sent = []
  const list = page('pages/glucose/list.js', { require: () => ({ listGlucose: async params => {
    sent.push(params); return { records: [{ id: params.pageNum, dataSource: 'CGM' }], total: 2 }
  } }) })
  list.data.dataSource = 'CGM'
  await list.load(); assert.equal(list.data.hasMore, true)
  await list.loadMore(); assert.equal(list.data.records.length, 2); assert.equal(list.data.hasMore, false)
  assert.equal(sent[1].pageNum, 2); assert.equal(sent[1].dataSource, 'CGM')
})

test('未勾选隐私说明不能调用微信登录', () => {
  let called = false
  const login = page('pages/login/index.js', { wx: { getStorageSync: () => '', login: () => { called = true } } })
  login.handleLogin(); assert.equal(called, false); assert.match(login.data.errorMsg, /勾选/)
  login.onConsent({ detail: { value: ['accept'] } }); login.handleLogin(); assert.equal(called, true)
})

test('本地微信登录失败自动使用固定开发账号', async () => {
  const store = new Map(), codes = [], entered = []
  const login = page('pages/login/index.js', {
    request: { isLocalDevelopment: () => true },
    require: () => ({
      wxLogin: async code => { codes.push(code); return { token: 'local-token', userId: 'local-user', dataMode: 'REAL' } },
      getTargets: async () => ({ targetRevision: '1' }),
      updateTargets: async () => ({})
    }),
    wx: {
      getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, value),
      login: options => options.fail({ errMsg: 'login:fail invalid appid' }), switchTab: options => entered.push(options.url)
    }
  })
  login.data.consent = true; login.handleLogin(); await flush(); await flush()
  assert.deepEqual(codes, ['glucose-local-dev']); assert.deepEqual(entered, ['/pages/home/index'])
  assert.equal(store.get('accessToken'), 'local-token'); assert.equal(login.data.loading, false)
})

test('非本地环境微信登录失败不允许回退开发账号', () => {
  let called = false
  const login = page('pages/login/index.js', {
    request: { isLocalDevelopment: () => false },
    require: () => ({ wxLogin: async () => { called = true; return {} } }),
    wx: { getStorageSync: () => '', login: options => options.fail({ errMsg: 'login:fail cancel' }) }
  })
  login.data.consent = true; login.handleLogin()
  assert.equal(called, false); assert.match(login.data.errorMsg, /微信登录失败：login:fail cancel/)
})

test('本地开发账号后端不可用时保留后端错误', async () => {
  const login = page('pages/login/index.js', {
    request: { isLocalDevelopment: () => true },
    require: () => ({ wxLogin: async () => { throw Error('网络异常,请检查连接') } }),
    wx: { getStorageSync: () => '', login: options => options.fail({ errMsg: 'login:fail invalid appid' }) }
  })
  login.data.consent = true; login.handleLogin(); await flush()
  assert.equal(login.data.loading, false); assert.equal(login.data.errorMsg, '网络异常,请检查连接')
})

test('目标保存保留字符串版本，未启用SMBG类型明确移除', async () => {
  let payload
  const targets = page('pages/my/targets.js', { require: () => ({ updateTargets: async body => { payload = body } }), wx: { showToast() {} } })
  targets.load = async () => {}
  targets.data.targets = { targetRevision: '9007199254740993' }
  targets.data.targetForm = { low: '3.9', high: '10' }
  targets.data.targetRows = [{ measureType: 1, enabled: true, low: '4', high: '7', basis: 'test' }, { measureType: 2, enabled: false }]
  await targets.saveTargets()
  assert.equal(payload.targetRevision, '9007199254740993'); assert.equal(payload.rules[1].remove, true)
  assert.equal(payload.rules[0].targetLow, 4)
})

test('问答网络失败后重试沿用原requestId和字符串会话ID', async () => {
  const requests = []
  const chat = page('subpackages/qa/pages/chat.js', { require: () => ({ ask: async payload => { requests.push(payload); if (requests.length === 1) throw new Error('network'); return { status: 'COMPLETED' } } }) })
  chat.loadMessages = () => {}
  chat.data.sessionId = '9007199254740993'; chat.data.input = '测试问题'
  chat.send(); await flush(); assert.ok(chat.data.pendingRequest)
  chat.retry(); await flush()
  assert.equal(requests[0].requestId, requests[1].requestId); assert.equal(requests[1].sessionId, '9007199254740993'); assert.equal(chat.data.pendingRequest, null)
})

test('原生Canvas趋势算法保留缺口，SMBG不连线', () => {
  const { trendGeometry } = require('../patient-miniapp/utils/trendCanvas')
  const points = [{ time: '2026-10-20 08:00:00', value: 5 }, { time: '2026-10-20 09:00:00', value: 6 }]
  assert.equal(trendGeometry({ dataSource: 'CGM', points }).lines.length, 2)
  assert.equal(trendGeometry({ dataSource: 'SMBG', points }).lines.length, 0)
})

test('问答无依据及处理中消息不显示引用入口，也不发起空引用查询', async () => {
  let sourceCalls = 0
  const chat = page('subpackages/qa/pages/chat.js', { require: () => ({
    messages: async () => [
      { messageId: '1', role: 'assistant', status: 'COMPLETED', answerMode: 'NO_COVERAGE', hasSources: true },
      { messageId: '2', role: 'assistant', status: 'COMPLETED', answerMode: 'RAG', hasSources: true },
      { messageId: '3', role: 'assistant', status: 'PROCESSING', answerMode: 'RAG', hasSources: true }
    ], sources: async () => { sourceCalls++; return [] }
  }) })
  chat.data.sessionId = 'test'
  await chat.loadMessages()
  assert.equal(chat.data.messages[0].hasSources, false); assert.equal(chat.data.messages[1].hasSources, true)
  assert.equal(chat.data.messages[2].hasSources, false)
  chat.viewSources({ currentTarget: { dataset: { id: '1' } } }); await flush(); assert.equal(sourceCalls, 0)
  chat.viewSources({ currentTarget: { dataset: { id: '2' } } }); await flush(); assert.equal(sourceCalls, 1)
})

test('问答在没有已启用资料时明确提示准备状态', async () => {
  const chat = page('subpackages/qa/pages/chat.js', { require: () => ({ qaAvailability: async () => ({ knowledgeReady: false, modelReady: true }) }) })
  await chat.loadAvailability(); assert.match(chat.data.knowledgeHint, /知识资料准备中/)
})

test('底部五个入口都在主包，问答与分析可直接到达', () => {
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  assert.deepEqual(app.tabBar.list.map(t => t.text), ['首页', '记录', '分析', '问答', '我的'])
  for (const tab of app.tabBar.list) {
    assert.ok(app.pages.includes(tab.pagePath))
    for (const ext of ['js', 'json', 'wxml', 'wxss']) assert.ok(fs.existsSync(path.join(root, tab.pagePath + '.' + ext)))
    assert.ok(fs.existsSync(path.join(root, tab.iconPath)))
    assert.ok(fs.existsSync(path.join(root, tab.selectedIconPath)))
  }
  const visited = []
  const home = page('pages/home/index.js', { wx: { switchTab: ({ url }) => visited.push(url) } })
  home.goQa(); home.goAnalysis()
  assert.deepEqual(visited, ['/pages/qa/index', '/pages/analysis/index'])
})

test('问答首条发送自动建会话，建会话期间连续点击只提交一次', async () => {
  let resolveSession, creates = 0, asks = 0
  const chat = page('pages/qa/index.js', { require: () => ({
    createSession: title => { creates++; assert.equal(title, '血糖是什么'); return new Promise(resolve => { resolveSession = resolve }) },
    ask: async payload => { asks++; assert.equal(payload.sessionId, '9007199254740993'); return { status: 'COMPLETED' } },
    messages: async () => []
  }) })
  chat.data.input = '血糖是什么'
  const first = chat.send()
  chat.send(); assert.equal(creates, 1); assert.equal(asks, 0)
  resolveSession({ sessionId: '9007199254740993' }); await first
  assert.equal(asks, 1); assert.equal(chat.data.pendingRequest, null); assert.equal(chat.data.isTab, true)
})

test('首条提问失败重试保留已建会话与请求编号，不产生重复对话', async () => {
  let creates = 0
  const requests = []
  const chat = page('pages/qa/index.js', { require: () => ({
    createSession: async () => { creates++; return { sessionId: 'session-1' } },
    ask: async body => { requests.push(body); if (requests.length === 1) throw Error('网络异常'); return { status: 'COMPLETED' } },
    messages: async () => []
  }) })
  chat.data.input = '问题'
  await chat.send(); assert.ok(chat.data.pendingRequest)
  chat.newConversation(); assert.equal(chat.data.sessionId, 'session-1')
  await chat.retry()
  assert.equal(creates, 1); assert.equal(requests[0].requestId, requests[1].requestId)
  assert.equal(chat.data.pendingRequest, null)
  chat.newConversation(); assert.equal(chat.data.sessionId, ''); assert.equal(chat.data.messages.length, 0)
})

test('关闭引用面板后，迟到的来源请求不会重新弹出面板', async () => {
  let finish
  const chat = page('pages/qa/index.js', { require: () => ({ sources: () => new Promise(resolve => { finish = resolve }) }) })
  chat.data.messages = [{ messageId: '1', hasSources: true }]
  const task = chat.viewSources({ currentTarget: { dataset: { id: '1' } } })
  chat.closeSources(); finish([{ title: '资料' }]); await task
  assert.equal(chat.data.visibleSources, null); assert.equal(chat.data.sourceLoading, false)
})

test('登录确认持久化失败时不进入功能，重试沿用同一账号', async () => {
  const store = new Map(), sent = []
  let wxLogins = 0, entered = 0, updates = 0
  const login = page('pages/login/index.js', {
    require: () => ({
      wxLogin: async () => ({ token: 'synthetic-token', userId: '200', dataMode: 'REAL' }),
      getTargets: async () => ({ targetRevision: '9007199254740993' }),
      updateTargets: async body => { sent.push(body); updates++; if (updates === 1) throw Error('保存失败') }
    }), wx: {
      getStorageSync: key => store.get(key), setStorageSync: (key, value) => store.set(key, value),
      login: options => { wxLogins++; options.success({ code: 'test-code' }) }, switchTab: () => { entered++ }
    }
  })
  login.data.consent = true; login.handleLogin(); await flush()
  assert.equal(entered, 0); assert.equal(login.data.loading, false); assert.equal(login.data.hasSession, true)
  await login.handleLogin()
  assert.equal(wxLogins, 1); assert.equal(entered, 1)
  assert.equal(sent[1].privacyVersion, 'privacy-v1'); assert.equal(sent[1].targetRevision, '9007199254740993')
})

test('已同意的已登录用户直接恢复，旧账号只在登录页补确认', async () => {
  let profile = { privacyVersion: 'privacy-v1' }, entered = 0
  const login = page('pages/login/index.js', {
    require: () => ({ getProfile: async () => profile }),
    wx: { getStorageSync: () => 'synthetic-token', switchTab: () => { entered++ } }
  })
  await login.onLoad(); assert.equal(entered, 1)
  profile = { privacyVersion: '' }; await login.restoreSession()
  assert.equal(entered, 1); assert.equal(login.data.consent, false); assert.equal(login.data.hasSession, true)
})

function sessionUtility(api, wx) {
  const module = { exports: {} }
  vm.runInNewContext(fs.readFileSync(path.join(root, 'utils/session.js'), 'utf8'), {
    module, wx, require: spec => spec === './privacy' ? { PRIVACY_VERSION: 'privacy-v1' } : api
  })
  return module.exports
}

test('跨模块共享一次服务端确认检查；未确认的旧登录统一回登录页', async () => {
  let checked = 0, redirect = '', version = 'privacy-v1', token = 'synthetic-1'
  const session = sessionUtility({ getProfile: async () => { checked++; return { privacyVersion: version } } }, {
    getStorageSync: () => token, reLaunch: options => { redirect = options.url; options.complete() }
  })
  assert.deepEqual(await Promise.all([session.ensureSession(), session.ensureSession()]), [true, true])
  assert.equal(checked, 1); await session.ensureSession(); assert.equal(checked, 1)
  token = 'synthetic-2'; version = ''
  assert.equal(await session.ensureSession(), false); assert.equal(redirect, '/pages/login/index')
})

test('记录页可直接新增和导入，退出账号清除全部本地账号状态', () => {
  const visited = [], removed = []
  const list = page('pages/glucose/list.js', { wx: { navigateTo: ({ url }) => visited.push(url) } })
  list.goRecord(); list.goImport()
  assert.deepEqual(visited, ['/pages/glucose/record', '/subpackages/import/pages/preview'])
  const session = sessionUtility({}, { removeStorageSync: key => removed.push(key) })
  session.clearSession()
  assert.deepEqual(removed, ['accessToken', 'userId', 'dataMode', 'profileCompleted'])
})

test('记录、导入、提醒入口同样执行统一登录与隐私检查', async () => {
  let pageChecks = 0, fetches = 0
  const session = { ensureSession: async () => { pageChecks++; return true } }
  const alert = page('subpackages/alert/pages/list.js', {
    session, require: () => ({ alerts: async () => { fetches++; return { records: [] } } })
  })
  await alert.onShow()
  const imported = page('subpackages/import/pages/preview.js', {
    session, require: () => ({ importBatches: async () => { fetches++; return [] } })
  })
  await imported.onLoad()
  const record = page('pages/glucose/record.js', { session })
  await record.onShow()
  assert.equal(pageChecks, 3); assert.equal(fetches, 2)
})

test('两个独立问答入口共享完全一致的对话视图与来源面板', () => {
  for (const ext of ['wxml', 'wxss']) {
    const main = fs.readFileSync(path.join(root, 'pages/qa/index.' + ext), 'utf8')
    const history = fs.readFileSync(path.join(root, 'subpackages/qa/pages/chat.' + ext), 'utf8')
    assert.equal(main, history)
  }
  const app = JSON.parse(fs.readFileSync(path.join(root, 'app.json'), 'utf8'))
  assert.deepEqual(app.tabBar.list.map(tab => tab.text), ['首页', '记录', '分析', '问答', '我的'])
})

test('导入批次状态文字和样式分离，历史失败可重试', async () => {
  let fail = true
  const p = page('subpackages/import/pages/preview.js', { require: () => ({ importBatches: async () => { if (fail) throw Error('历史加载失败'); return [{ id: '901', status: 'CONFIRMED' }] } }) })
  await p.loadBatches(); assert.equal(p.data.batchesError, '历史加载失败'); assert.equal(p.data.batchesLoading, false)
  fail = false; await p.loadBatches(); assert.equal(p.data.batchesError, ''); assert.equal(p.data.batches[0].statusClass, 'tag-success'); assert.equal(p.data.batches[0].statusText, '已导入')
})
for (const action of ['confirmBatch', 'revokeBatch']) {
  test(`导入${action}取消不提交，重复点击只提交一次，失败允许重试`, async () => {
    let confirm = false, calls = 0, reject
    const p = page('subpackages/import/pages/preview.js', { wx: { showModal: o => o.success({ confirm }) }, require: () => ({ [action]: () => { calls++; return new Promise((_, r) => { reject = r }) } }) })
    p.setData({ preview: { batchId: '9007199254740993', status: 'READY', validRows: 3, duplicateRows: 0, conflictRows: 0 } })
    const e = { currentTarget: { dataset: { id: '9007199254740993' } } }
    p[action](e); await flush(); assert.equal(calls, 0); assert.equal(p.data.busyId, '')
    confirm = true; p[action](e); p[action](e); assert.equal(calls, 1)
    reject(Error('操作失败')); await flush(); assert.equal(p.data.errorMsg, '操作失败'); assert.equal(p.data.busyId, '')
    p[action](e); assert.equal(calls, 2); reject(Error('重试失败')); await flush()
  })
}
test('预警失败不是空数据，切换历史后迟到响应不覆盖', async () => {
  let old, calls = 0
  const p = page('subpackages/alert/pages/list.js', { require: () => ({ alerts: () => { calls++; return calls === 1 ? Promise.reject(Error('预警加载失败')) : calls === 2 ? new Promise(r => { old = r }) : Promise.resolve({ records: [{ id: 'new', level: 2, noticeVersion: 1, patientSeenNoticeVersion: 0 }] }) } }) })
  await p.load(); assert.equal(p.data.errorMsg, '预警加载失败')
  const task = p.load(); await p.toggleHistorical(); old({ records: [{ id: 'old' }] }); await task
  assert.equal(p.data.list[0].id, 'new'); assert.equal(p.data.errorMsg, ''); assert.equal(p.data.loading, false)
})

test('历史对话加载失败后可重试并显示会话', async () => {
  const sessions = [{ sessionId: '9007199254740993', title: '历史会话' }]
  let calls = 0
  const history = page('subpackages/qa/pages/sessions.js', {
    require: () => ({ sessions: async () => {
      if (++calls === 1) throw Error('历史对话加载失败')
      return sessions
    } })
  })
  await history.onShow()
  assert.equal(history.data.errorMsg, '历史对话加载失败')
  assert.equal(history.data.loading, false)
  await history.load()
  assert.equal(history.data.errorMsg, '')
  assert.equal(history.data.loading, false)
  assert.deepEqual(history.data.sessions, sessions)
})

test('较早的历史对话响应不能覆盖刷新后的列表', async () => {
  const pending = []
  const history = page('subpackages/qa/pages/sessions.js', {
    require: () => ({ sessions: () => new Promise((resolve, reject) => pending.push({ resolve, reject })) })
  })
  const oldSuccess = history.load()
  const oldFailure = history.load()
  const latest = history.load()
  const sessions = [{ sessionId: '2', title: '最新会话' }]
  pending[2].resolve(sessions)
  await latest
  pending[0].resolve([{ sessionId: '1' }])
  pending[1].reject(Error('旧请求失败'))
  await Promise.all([oldSuccess, oldFailure])
  assert.deepEqual(history.data.sessions, sessions)
  assert.equal(history.data.loading, false)
  assert.equal(history.data.errorMsg, '')
})

test('个人中心展示账号摘要、手机号脱敏、隐私确认日期并跳转到独立子页', async () => {
  const visited = []
  const my = page('pages/my/index.js', {
    require: () => ({
      getProfile: async () => ({ userId: '105970', username: 'wx_demo', realName: '张三', phone: '13812348888', diabetesType: '2型糖尿病', privacyAcceptedAt: '2026-11-18T15:28:36' }),
      getTargets: async () => ({ cgmTargetLow: 3.9, cgmTargetHigh: 10.0, rules: [] })
    }),
    wx: { getStorageSync: () => '', navigateTo: ({ url }) => visited.push(url) }
  })
  await my.load()
  assert.equal(my.data.displayName, '张三')
  assert.equal(my.data.avatarInitial, '张')
  assert.equal(my.data.accountId, '105970')
  assert.equal(my.data.phoneLabel, '138****8888')
  assert.equal(my.data.hasPhone, true)
  assert.equal(my.data.privacyDateText, '已确认 2026-11-18')
  my.goProfile(); my.goTargets(); my.goAlerts()
  assert.deepEqual(visited, ['/pages/my/profile', '/pages/my/targets', '/subpackages/alert/pages/list'])
})

test('个人资料独立页校验手机号并保存返回', async () => {
  let saved = null
  const p = page('pages/my/profile.js', {
    require: () => ({ updateProfile: async body => { saved = body } }),
    wx: { showToast() {}, navigateBack() {} }
  })
  p.data.profileForm = { realName: ' 李四 ', phone: '123', gender: 1, birthDate: '', diabetesType: '2型糖尿病' }
  await p.saveProfile()
  assert.equal(saved, null); assert.match(p.data.profileError, /11 位手机号/)
  p.data.profileForm.phone = '13900001111'
  await p.saveProfile()
  assert.equal(saved.realName, '李四'); assert.equal(saved.phone, '13900001111')
})

test('SSE 流式解码正确拼接跨分片 UTF-8 中文并解析 delta/replace/done 事件', () => {
  const { createUtf8Decoder, createSseParser } = require('../patient-miniapp/utils/request')
  const decoder = createUtf8Decoder()
  const events = []
  const parser = createSseParser((name, data) => events.push({ name, data }))
  const full = Buffer.from(
    'event: delta\ndata: {"text":"血糖是"}\n\n' +
    'event: delta\ndata: {"text":"血液中的葡萄糖"}\n\n' +
    'event: replace\ndata: {"text":"审核资料摘录"}\n\n' +
    'event: done\ndata: {"status":"COMPLETED","messageId":"88"}\n\n',
    'utf8'
  )
  // Cut deliberately mid-Chinese character (every 5 bytes)
  for (let i = 0; i < full.length; i += 5) {
    const slice = full.subarray(i, Math.min(full.length, i + 5))
    const ab = slice.buffer.slice(slice.byteOffset, slice.byteOffset + slice.byteLength)
    parser.feed(decoder.decode(ab))
  }
  parser.flush()
  assert.deepEqual(events, [
    { name: 'delta', data: { text: '血糖是' } },
    { name: 'delta', data: { text: '血液中的葡萄糖' } },
    { name: 'replace', data: { text: '审核资料摘录' } },
    { name: 'done', data: { status: 'COMPLETED', messageId: '88' } }
  ])
})

test('问答页流式接收 delta 和 replace 实时更新气泡并在完成后拉取最终消息', async () => {
  const snapshots = []
  let loaded = 0
  const chat = page('pages/qa/index.js', {
    require: () => ({
      createSession: async () => ({ sessionId: '777' }),
      askStream: async (payload, onEvent) => {
        onEvent('delta', { text: '血糖是' })
        snapshots.push(chat.data.messages.at(-1).content)
        onEvent('delta', { text: '葡萄糖。' })
        snapshots.push(chat.data.messages.at(-1).content)
        onEvent('replace', { text: '已替换为审核摘录' })
        snapshots.push(chat.data.messages.at(-1).content)
        return { status: 'COMPLETED', messageId: '88' }
      },
      messages: async () => {
        loaded++
        return [{ messageId: '88', role: 'assistant', status: 'COMPLETED', answerMode: 'RETRIEVAL_ONLY', content: '已替换为审核摘录', hasSources: true }]
      }
    })
  })
  chat.data.input = '血糖是什么'
  await chat.send()
  assert.deepEqual(snapshots, ['血糖是', '血糖是葡萄糖。', '已替换为审核摘录'])
  assert.equal(loaded, 1)
  assert.equal(chat.data.pendingRequest, null)
  assert.equal(chat.data.generating, false)
  assert.equal(chat.data.messages[0].hasSources, true)
})
