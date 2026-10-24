import axios from 'axios'
import { expireSession } from './session.js'
const client = axios.create({ baseURL: '/api', timeout: 20000 })
client.interceptors.request.use(config => {
  const token = sessionStorage.getItem('hospital.token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})
client.interceptors.response.use(response => response.data.data, cause => {
  const body = cause.response?.data
  const error = new Error(body?.message || (cause.code === 'ECONNABORTED' ? '请求超时，请重试。' : '暂时无法连接服务，请确认后端已启动后重试。'))
  Object.assign(error, { code: body?.code, traceId: body?.traceId, status: cause.response?.status })
  if (error.status === 401 && !cause.config?.url?.startsWith('/auth/')) {
    expireSession()
  }
  return Promise.reject(error)
})
export const get = (url, params) => client.get(url, { params: Object.fromEntries(Object.entries(params || {}).filter(([,v]) => v !== '' && v !== null && v !== undefined)) })
export const post = (url, data) => client.post(url, data)
export const put = (url, data) => client.put(url, data)
export const patch = (url, data) => client.patch(url, data)
export const remove = url => client.delete(url)
export async function allPages(url) {
  const first = await get(url, { pageSize: 100 })
  const items = [...first.items]
  for (let page = 2; page <= Math.ceil(first.total / 100); page++) items.push(...(await get(url, { page, pageSize: 100 })).items)
  return items
}
