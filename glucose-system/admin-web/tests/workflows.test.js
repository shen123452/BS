import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'
import { analysisWindow, completeDayRange, formatLocalDate } from '../src/utils/analysisDates.js'

// 执行页面自身的处理函数，替换 UI 确认与网络；覆盖取消和确认两个真实分支。
function page(file, expose, extra = {}) {
  const text = fs.readFileSync(new URL(`../src/views/${file}`, import.meta.url), 'utf8')
  const script = text.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const context = vm.createContext({
    ref: value => ({ value }), reactive: value => value, onMounted() {}, watch() {},
    useAuthStore: () => ({ isAdmin: true }),
    ElMessage: { success() {}, error() {} },
    completeDayRange, analysisWindow, formatLocalDate, ...extra
  })
  vm.runInContext(script + `\nglobalThis.exposed = { ${expose} };`, context)
  return context.exposed
}

for (const [file, fn, apiName, method, row] of [
  ['KbView.vue', 'remove', 'kbApi', 'remove', { id: '1', title: 'test' }],
  ['PatientsView.vue', 'handleToggle', 'patientsApi', 'toggleStatus', { userId: '1', username: 'test', status: 1 }],
  ['DoctorsView.vue', 'toggle', 'doctorsApi', 'toggleStatus', { id: '1', username: 'test', status: 1 }]
]) {
  for (const confirmed of [false, true]) {
    test(`${file}: ${confirmed ? '确认只提交一次' : '取消不提交'}`, async () => {
      let calls = 0
      const api = { [method]: async () => { calls++ }, list: async () => ({ records: [], total: 0 }) }
      const exposed = page(file, fn, {
        [apiName]: api,
        ElMessageBox: { confirm: () => confirmed ? Promise.resolve() : Promise.reject('cancel') }
      })
      await exposed[fn](row)
      assert.equal(calls, confirmed ? 1 : 0)
    })
  }
}

test('字符串日期范围转换为包含开始、不包含结束的区间', () => {
  assert.deepEqual(analysisWindow(['2026-10-10', '2026-10-23']), { startDate: '2026-10-10', endDate: '2026-10-24' })
})
test('14 日默认窗口恰好包含十四个已完成日', () => {
  assert.deepEqual(completeDayRange(14, new Date(2026, 9, 24, 18, 0)), ['2026-10-10', '2026-10-23'])
})
test('日期控件清空、逆序、非法日期不会发起错误查询', () => {
  for (const range of [null, [], ['2026-10-24', '2026-10-10'], ['2026-02-30', '2026-03-01']]) {
    assert.equal(analysisWindow(range), null)
  }
})
test('闰年日期和跨年结束边界正确', () => {
  assert.deepEqual(analysisWindow(['2024-02-29', '2024-02-29']), { startDate: '2024-02-29', endDate: '2024-03-01' })
  assert.deepEqual(analysisWindow(['2026-12-31', '2026-12-31']), { startDate: '2026-12-31', endDate: '2027-01-01' })
})
test('管理端使用同一日期窗口加载汇总、每日统计、趋势和记录', async () => {
  const requests = []
  const glucoseApi = {
    summary: async params => { requests.push(params); return {} },
    daily: async params => { requests.push(params); return [] },
    trend: async params => { requests.push(params); return { points: [] } },
    records: async params => { requests.push(params); return { records: [], total: 0 } }
  }
  const exposed = page('GlucoseView.vue', 'filters, patientId, loadAll', { glucoseApi })
  exposed.patientId.value = '1000'
  exposed.filters.dateRange = ['2026-10-10', '2026-10-23']
  await exposed.loadAll()
  assert.equal(requests.length, 4)
  for (const params of requests) {
    assert.equal(params.startDate, '2026-10-10')
    assert.equal(params.endDate, '2026-10-24')
    assert.equal(params.patientId, '1000')
  }
  requests.length = 0
  exposed.filters.dateRange = null
  await exposed.loadAll()
  assert.equal(requests.length, 0)
  assert.equal(exposed.filters.summary, null)
})

test('快速切换患者时，较慢的旧查询不会覆盖新患者结果', async () => {
  let resolveOld
  const glucoseApi = {
    summary: p => p.patientId === 'old' ? new Promise(r => { resolveOld = r }) : Promise.resolve({ patient: 'new' }),
    daily: async () => [], trend: async () => ({ points: [] }), records: async () => ({ records: [], total: 0 })
  }
  const exposed = page('GlucoseView.vue', 'filters, patientId, loadAll, loading', { glucoseApi })
  exposed.patientId.value = 'old'
  const old = exposed.loadAll()
  exposed.patientId.value = 'new'
  await exposed.loadAll()
  resolveOld({ patient: 'old' })
  await old
  assert.equal(exposed.filters.summary.patient, 'new')
  assert.equal(exposed.loading.value, false)
})

test('血糖导出地址分别保留 CSV 与 Excel 扩展名', async () => {
  const source = fs.readFileSync(new URL('../src/api/index.js', import.meta.url), 'utf8')
  assert.match(source, /exportXlsxUrl: params => '\/api\/admin\/glucose\/export\.xlsx\?'/)
  assert.match(source, /exportUrl: params => '\/api\/admin\/glucose\/export\?'/)
})

test('患者绑定失败保留选择，连续点击只提交一次', async () => {
  let reject, calls = 0
  const p = page('PatientsView.vue', 'binding, saveBinding', { patientsApi: { bindDoctor: () => { calls++; return new Promise((_, r) => { reject = r }) } } })
  p.binding.row = { userId: '9007199254740993' }; p.binding.doctorId = '501'
  const task = p.saveBinding(); await p.saveBinding(); assert.equal(calls, 1)
  reject(Error('绑定失败')); await task
  assert.equal(p.binding.doctorId, '501'); assert.equal(p.binding.error, '绑定失败')
})
test('医生表单校验并在失败后保留密码和姓名', async () => {
  let calls = 0
  const p = page('DoctorsView.vue', 'dialog, submit', { doctorsApi: { create: async () => { calls++; throw Error('暂不可用') } } })
  p.dialog.form = { username: 'doctor', realName: '测试医生', phone: '', password: '123' }
  await p.submit(); assert.equal(calls, 0)
  p.dialog.form.password = 'synthetic-password'; await p.submit()
  assert.equal(calls, 1); assert.equal(p.dialog.form.realName, '测试医生'); assert.equal(p.dialog.form.password, 'synthetic-password'); assert.equal(p.dialog.error, '暂不可用')
})
test('知识库编辑失败保留正文和来源', async () => {
  const p = page('KbView.vue', 'dialog, submit', { kbApi: { update: async () => { throw Error('保存失败') } } })
  p.dialog.mode = 'edit'; p.dialog.id = '500'; p.dialog.form = { title: '资料', sourceTitle: '来源', content: '完整正文', sourceUrl: '' }
  await p.submit(); assert.equal(p.dialog.form.content, '完整正文'); assert.equal(p.dialog.form.sourceTitle, '来源'); assert.equal(p.dialog.error, '保存失败')
})
test('人工回复失败保留正文及幂等编号，发送成功但刷新失败不误报发送失败', async () => {
  let fail = true, calls = []
  const p = page('QaView.vue', 'drawer, reply, sendReply', { qaApi: { reply: async (id, body) => { calls.push(body); if (fail) throw Error('发送失败') }, messages: async () => { throw Error('刷新失败') } } })
  p.drawer.sessionId = '9007199254740993'; p.reply.content = '请核对记录。'
  await p.sendReply(); assert.equal(p.reply.content, '请核对记录。'); const id = p.reply.requestId; assert.ok(id)
  fail = false; await p.sendReply(); assert.equal(calls[1].requestId, id); assert.equal(p.reply.content, ''); assert.equal(p.reply.error, ''); assert.equal(p.drawer.error, '刷新失败')
})
for (const file of ['PatientsView.vue', 'KbView.vue', 'QaView.vue']) {
  test(`${file} 迟到详情不覆盖新选择并立即打开加载状态`, async () => {
    let finish
    const apiName = file === 'PatientsView.vue' ? 'patientsApi' : file === 'KbView.vue' ? 'kbApi' : 'qaApi'
    const method = file === 'QaView.vue' ? 'messages' : 'detail'
    const state = file === 'PatientsView.vue' ? 'drawer' : file === 'KbView.vue' ? 'dialog' : 'drawer'
    const fn = file === 'PatientsView.vue' ? 'openDetail' : file === 'KbView.vue' ? 'openEdit' : 'openSession'
    const p = page(file, `${state}, ${fn}`, { [apiName]: { [method]: id => id === 'old' ? new Promise(r => { finish = r }) : Promise.resolve(file === 'QaView.vue' ? [{ content: 'new' }] : { title: 'new', username: 'new' }) } })
    const row = id => ({ userId: id, id, sessionId: id })
    const old = p[fn](row('old')); assert.equal(p[state].visible, true); assert.equal(p[state].loading, true)
    await p[fn](row('new')); finish(file === 'QaView.vue' ? [{ content: 'old' }] : { title: 'old', username: 'old' }); await old
    const value = file === 'PatientsView.vue' ? p.drawer.data.username : file === 'KbView.vue' ? p.dialog.form.title : p.drawer.messages[0].content
    assert.equal(value, 'new'); assert.equal(p[state].loading, false)
  })
}
