export const states = { WAITING: '待接诊', IN_PROGRESS: '接诊中', COMPLETED: '已完成', CANCELLED: '已取消', ACTIVE: '正常', ENABLED: '启用', DISABLED: '停用', OPEN: '开放', CLOSED: '关闭', DRAFT: '草稿', SUBMITTED: '已提交' }
export const slots = { AM: '上午 · 08:00', PM: '下午 · 14:00' }
export const histories = { UNKNOWN: '未提供', NONE: '明确无', KNOWN: '有相关病史' }
export const recordFields = [['chiefComplaint','主诉'],['presentIllness','现病史'],['pastHistory','既往史'],['physicalExam','体格检查'],['auxiliaryExam','辅助检查'],['preliminaryDiagnosis','初步诊断'],['treatmentPlan','处理计划']]
export function chinaDate(offset = 0) {
  const parts = new Intl.DateTimeFormat('en-CA', { timeZone: 'Asia/Shanghai', year: 'numeric', month: '2-digit', day: '2-digit' }).formatToParts(new Date())
  const p = Object.fromEntries(parts.map(v => [v.type,v.value]))
  const day = new Date(Date.UTC(Number(p.year), Number(p.month) - 1, Number(p.day) + offset))
  return day.toISOString().slice(0,10)
}
export const money = value => `¥${Number(value || 0).toFixed(2)}`
export const dateTime = value => value ? value.replace('T', ' ').slice(0,16) : '—'
export function safeReturnTo(path, role) {
  const prefix = `/${role?.toLowerCase()}/`
  return typeof path === 'string' && path.startsWith(prefix) && !path.includes('\\') ? path : null
}
export function recordPayload(form) { return Object.fromEntries(['version', ...recordFields.map(([key]) => key)].map(key => [key, form[key]])) }
