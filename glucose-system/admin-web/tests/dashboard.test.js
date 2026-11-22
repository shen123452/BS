import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

function dashboard(api) {
  const source = fs.readFileSync(new URL('../src/views/DashboardView.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const context = vm.createContext({
    ref: value => ({ value }),
    onMounted() {},
    dashboardApi: api,
    useAuthStore: () => ({ isAdmin: true, isDoctor: false })
  })
  vm.runInContext(script + '\nglobalThis.exposed = { loadOverview, formatCount, loading, errorMessage, overview };', context)
  return context.exposed
}

test('工作台按服务端统计值展示数字，并处理空统计', async () => {
  const view = dashboard({ overview: async () => ({ activePatients: 1250, recordsLast7Days: 0, pendingAlerts: 3 }) })
  await view.loadOverview()
  assert.equal(view.formatCount(view.overview.value.activePatients), '1,250')
  assert.equal(view.formatCount(view.overview.value.recordsLast7Days), '0')
  assert.equal(view.formatCount(view.overview.value.pendingAlerts), '3')
  assert.equal(view.errorMessage.value, '')
  assert.equal(view.loading.value, false)
})

test('工作台加载失败可提示并重试，重试成功清除错误', async () => {
  let attempts = 0
  const view = dashboard({ overview: async () => {
    attempts++
    if (attempts === 1) throw new Error('网络暂时不可用')
    return { activePatients: 1, recordsLast7Days: 2, pendingAlerts: 0 }
  } })

  await view.loadOverview()
  assert.equal(view.errorMessage.value, '网络暂时不可用')
  assert.equal(view.loading.value, false)

  await view.loadOverview()
  assert.equal(view.errorMessage.value, '')
  assert.equal(view.overview.value.activePatients, 1)
  assert.equal(attempts, 2)
})
