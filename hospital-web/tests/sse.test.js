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
