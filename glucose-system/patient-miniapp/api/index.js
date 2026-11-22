const { request, requestStream, qs } = require('../utils/request')

module.exports = {
  // 认证与档案
  wxLogin: code => request({ url: '/api/app/auth/wx-login', method: 'POST', data: { code } }),
  logout: () => request({ url: '/api/common/logout', method: 'POST' }),
  getProfile: () => request({ url: '/api/app/profile' }),
  updateProfile: data => request({ url: '/api/app/profile', method: 'PUT', data }),
  getTargets: () => request({ url: '/api/app/profile/targets' }),
  updateTargets: data => request({ url: '/api/app/profile/targets', method: 'PUT', data }),
  deleteAccount: () => request({ url: '/api/app/account', method: 'DELETE' }),

  // 已有用药方案的记录，不生成处方
  medicationPlans: () => request({ url: '/api/app/medication/plans' }),
  addMedicationPlan: data => request({ url: '/api/app/medication/plans', method: 'POST', data }),
  stopMedicationPlan: (id, version) => request({ url: '/api/app/medication/plans/' + id + '/stop', method: 'PUT', data: { version } }),
  medicationDay: date => request({ url: '/api/app/medication?' + qs({ date }) }),
  setMedicationStatus: (id, data) => request({ url: '/api/app/medication/' + id + '/taken', method: 'PUT', data }),

  // 血糖
  addGlucose: data => request({ url: '/api/app/glucose', method: 'POST', data }),
  listGlucose: params => request({ url: '/api/app/glucose?' + qs(params) }),
  updateGlucose: (id, data) => request({ url: '/api/app/glucose/' + id, method: 'PUT', data }),
  deleteGlucose: id => request({ url: '/api/app/glucose/' + id, method: 'DELETE' }),
  latest: () => request({ url: '/api/app/glucose/latest' }),
  importBatches: () => request({ url: '/api/app/glucose/import/batches' }),
  confirmBatch: id => request({ url: '/api/app/glucose/import/batches/' + id + '/confirm', method: 'POST' }),
  revokeBatch: id => request({ url: '/api/app/glucose/import/batches/' + id + '/revoke', method: 'POST' }),

  // 分析
  summary: params => request({ url: '/api/app/analysis/summary?' + qs(params) }),
  daily: params => request({ url: '/api/app/analysis/daily?' + qs(params) }),
  trend: params => request({ url: '/api/app/analysis/trend?' + qs(params) }),
  agp: params => request({ url: '/api/app/analysis/agp?' + qs(params) }),

  // 预警
  alerts: params => request({ url: '/api/app/alerts?' + qs(params) }),
  readAlert: (id, noticeVersion) =>
    request({ url: '/api/app/alerts/' + id + '/read', method: 'PUT', data: { noticeVersion } }),

  // 问答
  qaAvailability: () => request({ url: '/api/app/qa/availability' }),
  createSession: title => request({ url: '/api/app/qa/sessions', method: 'POST', data: { title } }),
  sessions: () => request({ url: '/api/app/qa/sessions' }),
  messages: sessionId => request({ url: '/api/app/qa/sessions/' + sessionId + '/messages' }),
  sources: messageId => request({ url: '/api/app/qa/messages/' + messageId + '/sources' }),
  ask: data => request({ url: '/api/app/qa/ask', method: 'POST', data, timeout: 18000 }),
  askStream: (data, onEvent) => requestStream({ url: '/api/app/qa/ask/stream', method: 'POST', data, timeout: 30000, onEvent }),
  deleteSession: id => request({ url: '/api/app/qa/sessions/' + id, method: 'DELETE' })
}
