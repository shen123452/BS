const STATUS_LABELS = { PENDING: '未记录', TAKEN: '已服用', SKIPPED: '已跳过', CANCELLED: '计划已停用' }

function shanghaiDate(offset = 0) {
  const d = new Date(Date.now() + 8 * 3600000 + offset * 86400000)
  return d.getUTCFullYear() + '-' + String(d.getUTCMonth() + 1).padStart(2, '0') + '-' + String(d.getUTCDate()).padStart(2, '0')
}
function planState(plan, today) {
  if (!plan.active) return '已停用'
  if (plan.endDate && plan.endDate < today) return '已结束'
  return plan.startDate > today ? '尚未开始' : '进行中'
}
module.exports = { STATUS_LABELS, shanghaiDate, planState }
