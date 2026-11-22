import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

function client(status = 200, result = { code: 0, data: {} }) {
  const storage = new Map([['accessToken', 'expired-token'], ['userId', '1'], ['username', 'old-user'], ['role', 'ADMIN'], ['unrelated', 'keep']])
  const calls = []
  const context = vm.createContext({
    localStorage: { getItem: key => storage.get(key), removeItem: key => storage.delete(key), setItem: (key, value) => storage.set(key, value) },
    location: { pathname: '/dashboard', href: '' },
    fetch: async (url, options) => { calls.push({ url, ...options }); return { status, json: async () => result } }
  })
  const source = fs.readFileSync(new URL('../src/utils/request.js', import.meta.url), 'utf8').replace(/^export /gm, '')
  const api = fs.readFileSync(new URL('../src/api/index.js', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, '')
  vm.runInContext(source + '\n' + api + '\nglobalThis.client = { request, authApi };', context)
  return { ...context.client, storage, calls, location: context.location }
}

test('验证码和账密登录不携带旧令牌或浏览器Cookie', async () => {
  const view = client()
  await view.authApi.captcha()
  await view.authApi.login({ username: 'doctor', password: 'test', captchaId: 'id', captchaCode: '1234' })
  assert.equal(view.calls.length, 2)
  for (const call of view.calls) {
    assert.equal(call.headers.Authorization, undefined)
    assert.equal(call.credentials, 'omit')
  }
})

test('登录接口401保留账号密码错误原文，不当作会话过期跳转', async () => {
  const view = client(401, { code: 40101, message: '用户名或密码错误' })
  await assert.rejects(view.authApi.login({}), error => error.message === '用户名或密码错误' && error.code === 40101)
  assert.equal(view.location.href, '')
})

test('受保护请求仍携带令牌，过期后只清理本系统会话并返回登录', async () => {
  const view = client(401, { code: 40101, message: '登录已失效' })
  await assert.rejects(view.request('GET', '/api/admin/patients'), /登录已失效/)
  assert.equal(view.calls[0].headers.Authorization, 'Bearer expired-token')
  assert.equal(view.location.href, '/login')
  for (const key of ['accessToken', 'userId', 'username', 'role']) assert.equal(view.storage.has(key), false)
  assert.equal(view.storage.get('unrelated'), 'keep')
})

test('进入登录页清除旧会话，不因本地残留令牌再次跳回工作台', () => {
  const text = fs.readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
  const script = text.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  let cleared = 0, captchas = 0
  const redirects = []
  const context = vm.createContext({
    ref: value => ({ value }), reactive: value => value,
    useRouter: () => ({ replace: path => redirects.push(path) }),
    useAuthStore: () => ({ logout: () => cleared++ }),
    authApi: { captcha: async () => { captchas++; return { captchaId: 'new', image: 'new' } } },
    onMounted: callback => callback()
  })
  vm.runInContext(script, context)
  assert.equal(cleared, 1)
  assert.equal(captchas, 1)
  assert.equal(redirects.length, 0)
})

test('退出登录同时清空内存状态，刷新不会重新读入旧账号', () => {
  const storage = new Map([['accessToken', 'old'], ['userId', '1'], ['username', 'old'], ['role', 'ADMIN']])
  let store
  const context = vm.createContext({
    localStorage: { getItem: key => storage.get(key), removeItem: key => storage.delete(key) },
    defineStore: (_, definition) => { store = definition; return definition }
  })
  const source = fs.readFileSync(new URL('../src/stores/auth.js', import.meta.url), 'utf8').replace(/^import .*$/gm, '').replace(/^export /gm, '')
  vm.runInContext(source, context)
  const state = { ...store.state(), $reset() { Object.assign(this, store.state()) } }
  store.actions.logout.call(state)
  assert.equal(state.token, '')
  assert.equal(state.username, '')
  assert.equal(state.role, '')
  assert.equal(storage.size, 0)
})
