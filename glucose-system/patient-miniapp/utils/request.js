// 网络层:BASE_URL 是可公开的接口地址,不是密钥
// 开发者工具同机调试用 127.0.0.1;真机预览改为电脑局域网 IP,如 http://192.168.x.x:8083
const BASE_URL = 'http://127.0.0.1:8083'
let directingToLogin = false

function isLocalDevelopment() {
  try {
    const host = BASE_URL.replace(/^https?:\/\//i, '').split('/')[0].split(':')[0].toLowerCase()
    return host === '127.0.0.1' || host === 'localhost'
  } catch (e) {
    return false
  }
}

function handleUnauthorized() {
  for (const key of ['accessToken', 'userId', 'dataMode', 'profileCompleted']) wx.removeStorageSync(key)
  if (!directingToLogin) {
    directingToLogin = true
    wx.reLaunch({
      url: '/pages/login/index',
      complete: () => { directingToLogin = false }
    })
  }
}

function createUtf8Decoder() {
  let pending = new Uint8Array(0)
  return {
    decode(chunk) {
      let incoming
      if (chunk instanceof Uint8Array) incoming = chunk
      else if (chunk instanceof ArrayBuffer) incoming = new Uint8Array(chunk)
      else if (typeof chunk === 'string') return chunk
      else if (chunk && chunk.buffer instanceof ArrayBuffer) incoming = new Uint8Array(chunk.buffer, chunk.byteOffset || 0, chunk.byteLength || chunk.length || 0)
      else return ''

      const bytes = new Uint8Array(pending.length + incoming.length)
      bytes.set(pending, 0)
      bytes.set(incoming, pending.length)
      pending = new Uint8Array(0)

      let out = ''
      let i = 0
      const len = bytes.length
      while (i < len) {
        const b0 = bytes[i]
        if (b0 < 0x80) {
          out += String.fromCharCode(b0)
          i += 1
        } else if ((b0 & 0xe0) === 0xc0) {
          if (i + 2 > len) { pending = bytes.slice(i); break }
          const cp = ((b0 & 0x1f) << 6) | (bytes[i + 1] & 0x3f)
          out += String.fromCharCode(cp)
          i += 2
        } else if ((b0 & 0xf0) === 0xe0) {
          if (i + 3 > len) { pending = bytes.slice(i); break }
          const cp = ((b0 & 0x0f) << 12) | ((bytes[i + 1] & 0x3f) << 6) | (bytes[i + 2] & 0x3f)
          out += String.fromCharCode(cp)
          i += 3
        } else if ((b0 & 0xf8) === 0xf0) {
          if (i + 4 > len) { pending = bytes.slice(i); break }
          const cp = ((b0 & 0x07) << 18) | ((bytes[i + 1] & 0x3f) << 12) | ((bytes[i + 2] & 0x3f) << 6) | (bytes[i + 3] & 0x3f)
          out += String.fromCodePoint(cp)
          i += 4
        } else {
          i += 1
        }
      }
      return out
    },
    flush() {
      pending = new Uint8Array(0)
      return ''
    }
  }
}

function createSseParser(onEvent) {
  let buffer = ''
  function dispatchBlock(block) {
    if (!block) return
    let eventName = 'message'
    const dataLines = []
    for (const rawLine of block.split('\n')) {
      if (!rawLine || rawLine.startsWith(':')) continue
      if (rawLine.startsWith('event:')) {
        eventName = rawLine.slice(6).trim() || 'message'
      } else if (rawLine.startsWith('data:')) {
        const val = rawLine.slice(5)
        dataLines.push(val.startsWith(' ') ? val.slice(1) : val)
      }
    }
    if (!dataLines.length) return
    const rawData = dataLines.join('\n')
    let parsed = rawData
    try { parsed = JSON.parse(rawData) } catch (e) { /* keep raw string */ }
    if (onEvent) onEvent(eventName, parsed)
  }
  return {
    feed(text) {
      if (!text) return
      buffer += text.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
      let boundary
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const block = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        dispatchBlock(block)
      }
    },
    flush() {
      if (buffer.trim()) {
        dispatchBlock(buffer)
      }
      buffer = ''
    }
  }
}

function request(options) {
  const { url, method = 'GET', data, timeout = 10000 } = options
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('accessToken')
    wx.request({
      url: BASE_URL + url,
      method,
      data,
      timeout,
      header: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      success(res) {
        if (res.statusCode === 401) {
          handleUnauthorized()
          reject(new Error('登录已过期,请重新登录'))
          return
        }
        const body = res.data
        if (res.statusCode >= 200 && res.statusCode < 300 && body && body.code === 0) {
          resolve(body.data)
          return
        }
        reject(new Error((body && body.message) || '请求失败,请稍后重试'))
      },
      fail: () => reject(new Error('网络异常,请检查连接'))
    })
  })
}

// SSE 流式请求适配器(wx.request enableChunked + onChunkReceived)
function requestStream(options) {
  const { url, method = 'POST', data, timeout = 30000, onEvent } = options
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('accessToken')
    const decoder = createUtf8Decoder()
    let donePayload = null
    let streamError = null
    let receivedAnyChunk = false

    const parser = createSseParser((eventName, payload) => {
      if (eventName === 'done') donePayload = payload
      if (eventName === 'error') streamError = new Error((payload && payload.message) || '回答生成失败,请重试')
      if (onEvent) onEvent(eventName, payload)
    })

    const task = wx.request({
      url: BASE_URL + url,
      method,
      data,
      timeout,
      enableChunked: true,
      responseType: 'text',
      header: {
        'Content-Type': 'application/json',
        Accept: 'text/event-stream',
        ...(token ? { Authorization: 'Bearer ' + token } : {})
      },
      success(res) {
        if (res.statusCode === 401) {
          handleUnauthorized()
          reject(new Error('登录已过期,请重新登录'))
          return
        }
        if (res.statusCode < 200 || res.statusCode >= 300) {
          let body = res.data
          if (typeof body === 'string') {
            try { body = JSON.parse(body) } catch (e) { /* */ }
          }
          reject(new Error((body && body.message) || '请求失败,请稍后重试'))
          return
        }
        if (!receivedAnyChunk && typeof res.data === 'string' && res.data) {
          parser.feed(res.data)
        }
        decoder.flush()
        parser.flush()
        if (streamError) {
          reject(streamError)
          return
        }
        if (donePayload !== null) {
          resolve(donePayload)
          return
        }
        reject(new Error('流式连接提前结束,请重试'))
      },
      fail: () => reject(new Error('网络异常,请检查连接'))
    })

    if (task && typeof task.onChunkReceived === 'function') {
      task.onChunkReceived(res => {
        receivedAnyChunk = true
        const text = decoder.decode(res && res.data)
        if (text) parser.feed(text)
      })
    }
  })
}

// 上传使用单独的 uploadFile 适配器;JSON 封装不能当 multipart 用
function uploadFile(options) {
  const { url, filePath, name = 'file', formData = {}, timeout = 30000 } = options
  return new Promise((resolve, reject) => {
    const token = wx.getStorageSync('accessToken')
    wx.uploadFile({
      url: BASE_URL + url,
      filePath,
      name,
      formData,
      timeout,
      header: token ? { Authorization: 'Bearer ' + token } : {},
      success(res) {
        let body = {}
        try { body = JSON.parse(res.data) } catch (e) { /* */ }
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) {
          resolve(body.data)
        } else {
          reject(new Error((body && body.message) || '上传失败'))
        }
      },
      fail: () => reject(new Error('网络异常,请检查连接'))
    })
  })
}

function qs(params) {
  const parts = []
  for (const k in params || {}) {
    const v = params[k]
    if (v !== undefined && v !== null && v !== '') parts.push(k + '=' + encodeURIComponent(v))
  }
  return parts.length ? parts.join('&') : ''
}

module.exports = {
  request,
  requestStream,
  uploadFile,
  qs,
  BASE_URL,
  isLocalDevelopment,
  createUtf8Decoder,
  createSseParser
}
