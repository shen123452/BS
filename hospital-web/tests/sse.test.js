import test from 'node:test'
import assert from 'node:assert/strict'
import { readSse } from '../src/lib/sse.js'

test('SSE解析支持CRLF、任意分块和多行data', async () => {
  const bytes = new TextEncoder().encode('event: meta\r\ndata: {"ok":true}\r\n\r\nevent: delta\r\ndata: 第一行\r\ndata: 第二行\r\n\r\nevent: done\ndata: [DONE]')
  const response = new Response(new ReadableStream({
    start(controller) {
      controller.enqueue(bytes.slice(0, 4))
      controller.enqueue(bytes.slice(4, 17))
      controller.enqueue(bytes.slice(17, 38))
      controller.enqueue(bytes.slice(38))
      controller.close()
    },
  }))
  const events = []
  await readSse(response, event => events.push(event))
  assert.deepEqual(events, [
    { name:'meta', data:'{"ok":true}' },
    { name:'delta', data:'第一行\n第二行' },
    { name:'done', data:'[DONE]' },
  ])
})

test('SSE错误响应保留服务端错误码和问题编号', async () => {
  const response = Response.json({ code:'SESSION_BUSY', message:'请稍候', traceId:'trace-test' }, { status:409 })
  await assert.rejects(() => readSse(response, () => {}), error => {
    assert.equal(error.status,409)
    assert.equal(error.code,'SESSION_BUSY')
    assert.equal(error.traceId,'trace-test')
    return true
  })
})

test('AI未配置的503保留原始错误，不触发登录过期', async t => {
  const storage = globalThis.sessionStorage, browser = globalThis.window
  let expired = false
  globalThis.sessionStorage = { removeItem() { expired = true } }
  globalThis.window = new EventTarget()
  t.after(() => { globalThis.sessionStorage = storage; globalThis.window = browser })
  const response = Response.json({ code:'AI_NOT_CONFIGURED', message:'请先配置百炼 DASHSCOPE_API_KEY', traceId:'trace-ai' }, { status:503 })
  await assert.rejects(() => readSse(response, () => {}), error => error.status === 503 && error.code === 'AI_NOT_CONFIGURED')
  assert.equal(expired,false)
})

test('流式请求收到401时清理登录并通知页面跳转', async t => {
  const storage = globalThis.sessionStorage, browser = globalThis.window
  const removed = []
  let notified = false
  globalThis.sessionStorage = { removeItem(key) { removed.push(key) } }
  globalThis.window = new EventTarget()
  window.addEventListener('hospital-session-expired', () => { notified = true })
  t.after(() => { globalThis.sessionStorage = storage; globalThis.window = browser })
  await assert.rejects(() => readSse(Response.json({code:'INVALID_TOKEN',message:'登录凭证无效或已过期'}, {status:401}), () => {}), error => error.status === 401)
  assert.deepEqual(removed,['hospital.token'])
  assert.equal(notified,true)
})
