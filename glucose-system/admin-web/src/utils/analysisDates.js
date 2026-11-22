export function formatLocalDate(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
}

export function completeDayRange(days, now = new Date()) {
  const end = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  const start = new Date(end)
  start.setDate(start.getDate() - days)
  const last = new Date(end)
  last.setDate(last.getDate() - 1)
  return [formatLocalDate(start), formatLocalDate(last)]
}

// 日期控件返回本地日期字符串；使用日历加一天，避免 UTC 和夏令时换算。
export function analysisWindow(range) {
  if (!Array.isArray(range) || range.length !== 2) return null
  const parse = value => {
    if (typeof value !== 'string' || !/^\d{4}-\d{2}-\d{2}$/.test(value)) return null
    const [year, month, day] = value.split('-').map(Number)
    const date = new Date(year, month - 1, day)
    return formatLocalDate(date) === value ? date : null
  }
  const start = parse(range[0])
  const last = parse(range[1])
  if (!start || !last || start > last) return null
  last.setDate(last.getDate() + 1)
  return { startDate: range[0], endDate: formatLocalDate(last) }
}
