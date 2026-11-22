// 统一请求封装:token、错误码转可理解反馈、401 跳登录(规范 §11.2)
const TOKEN_KEY = 'accessToken'

export function getToken() {
  return localStorage.getItem(TOKEN_KEY) || ''
}

export function setToken(token) {
  localStorage.setItem(TOKEN_KEY, token)
}

export function clearToken() {
  for (const key of [TOKEN_KEY, 'userId', 'username', 'role']) localStorage.removeItem(key)
}

const ERROR_TEXT = {
  40001: '参数或格式错误',
  40002: '记录重复',
  40101: '登录已过期,请重新登录',
  40301: '无权限访问该资源',
  40401: '资源不存在',
  40901: '数据版本已变化,请刷新后重试',
  40902: '确认条件已变化,请重新预检',
  42901: '请求过于频繁,请稍后再试',
  50001: '服务异常,请稍后重试',
  50301: '外部服务暂不可用'
}

export async function request(method, url, body, options = {}) {
  const authenticated = options.auth !== false
  const token = authenticated ? getToken() : ''
  let res
  try {
    res = await fetch(url, {
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: `Bearer ${token}` } : {})
      },
      credentials: 'omit',
      body: body === undefined ? undefined : JSON.stringify(body),
      signal: options.signal
    })
  } catch (e) {
    throw new Error('网络异常,请检查连接')
  }
  let json = {}
  try { json = await res.json() } catch { /* 空响应 */ }
  if (res.status === 401 && authenticated) {
    clearToken()
    if (!location.pathname.startsWith('/login')) {
      location.href = '/login'
    }
    const error = new Error(json.message || ERROR_TEXT[40101])
    error.code = json.code ?? 401
    throw error
  }
  if (res.status >= 400 || json.code !== 0) {
    const text = json.message || ERROR_TEXT[Math.abs(json.code || res.status)] || `请求失败(${res.status})`
    const err = new Error(text)
    err.code = json.code ?? res.status
    err.data = json.data
    throw err
  }
  return json.data
}
