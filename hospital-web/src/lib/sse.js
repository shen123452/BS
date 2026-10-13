export async function readSse(response, onEvent) {
  if (!response.ok) {
    let body = null
    try { body = await response.json() } catch {}
    const error = new Error(body?.message || `请求失败（${response.status}）`)
    Object.assign(error, { status: response.status, code: body?.code, traceId: body?.traceId })
    throw error
  }
  if (!response.body) throw new Error('浏览器不支持流式读取，请更新浏览器后重试。')
  const reader = response.body.getReader(), decoder = new TextDecoder()
  let buffer = ''
  const dispatch = frame => {
    let name = 'message'; const data = []
    for (const line of frame.split(/\r?\n/)) {
      if (!line || line.startsWith(':')) continue
      const colon = line.indexOf(':')
      const field = colon < 0 ? line : line.slice(0, colon)
      const value = colon < 0 ? '' : line.slice(colon + 1).replace(/^ /, '')
      if (field === 'event') name = value
      else if (field === 'data') data.push(value)
    }
    if (data.length) onEvent({ name, data: data.join('\n') })
  }
  try {
    while (true) {
      const { value, done } = await reader.read()
      buffer += decoder.decode(value || new Uint8Array(), { stream: !done })
      const frames = buffer.split(/\r?\n\r?\n/)
      buffer = frames.pop() || ''
      for (const frame of frames) dispatch(frame)
      if (done) break
    }
    if (buffer.trim()) dispatch(buffer)
  } finally {
    try { await reader.cancel() } catch {}
    reader.releaseLock()
  }
}
