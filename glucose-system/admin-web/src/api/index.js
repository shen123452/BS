import { request } from '../utils/request'

// 过滤 undefined/null/空串,避免 URLSearchParams 把 undefined 序列化成 "undefined" 导致后端 400
function qs(params) {
  const search = new URLSearchParams()
  for (const [k, v] of Object.entries(params || {})) {
    if (v !== undefined && v !== null && v !== '') search.append(k, v)
  }
  return search.toString()
}

export const authApi = {
  captcha: () => request('GET', '/api/common/captcha', undefined, { auth: false }),
  login: body => request('POST', '/api/admin/auth/login', body, { auth: false }),
  logout: () => request('POST', '/api/common/logout', {})
}

export const dashboardApi = {
  overview: () => request('GET', '/api/admin/dashboard/overview')
}

export const patientsApi = {
  list: params => request('GET', '/api/admin/patients?' + qs(params)),
  detail: id => request('GET', `/api/admin/patients/${id}`),
  bindDoctor: (id, doctorId) => request('PUT', `/api/admin/patients/${id}/doctor`, { doctorId }),
  toggleStatus: (id, status) => request('PUT', `/api/admin/patients/${id}/status`, { status })
}

export const doctorsApi = {
  list: params => request('GET', '/api/admin/users/doctors?' + qs(params)),
  create: body => request('POST', '/api/admin/users/doctors', body),
  update: (id, body) => request('PUT', `/api/admin/users/doctors/${id}`, body),
  toggleStatus: (id, status) => request('PUT', `/api/admin/users/doctors/${id}/status`, { status })
}

export const glucoseApi = {
  records: params => request('GET', '/api/admin/glucose?' + qs(params)),
  exportUrl: params => '/api/admin/glucose/export?' + qs(params),
  exportXlsxUrl: params => '/api/admin/glucose/export.xlsx?' + qs(params),
  summary: params => request('GET', '/api/admin/analysis/patient/' + params.patientId + '?' + qs(params)),
  daily: params => request('GET', `/api/admin/analysis/patient/${params.patientId}/daily?` + qs(params)),
  trend: params => request('GET', `/api/admin/analysis/patient/${params.patientId}/trend?` + qs(params)),
  agp: params => request('GET', `/api/admin/analysis/patient/${params.patientId}/agp?` + qs(params))
}

export const alertsApi = {
  list: params => request('GET', '/api/admin/alerts?' + qs(params)),
  handle: (id, body) => request('PUT', `/api/admin/alerts/${id}/handle`, body)
}

export const qaApi = {
  sessions: params => request('GET', '/api/admin/qa/sessions?' + qs(params)),
  messages: sessionId => request('GET', `/api/admin/qa/sessions/${sessionId}/messages`),
  reply: (sessionId, body) => request('POST', `/api/admin/qa/sessions/${sessionId}/reply`, body)
}

export const kbApi = {
  list: params => request('GET', '/api/admin/kb/documents?' + qs(params)),
  detail: id => request('GET', `/api/admin/kb/documents/${id}`),
  create: body => request('POST', '/api/admin/kb/documents', body),
  update: (id, body) => request('PUT', `/api/admin/kb/documents/${id}`, body),
  remove: id => request('DELETE', `/api/admin/kb/documents/${id}`),
  toggle: (id, status) => request('PUT', `/api/admin/kb/documents/${id}/status`, { status }),
  reindex: id => request('POST', `/api/admin/kb/documents/${id}/reindex`),
  chunks: id => request('GET', `/api/admin/kb/documents/${id}/chunks`)
}
