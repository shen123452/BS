// The API uses local calendar timestamps. Avoid browser-specific string parsing.
export function localTimestamp(value) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/.exec(value || '')
  if (!m) return NaN
  const [, y, month, d, h, min, s] = m.map(Number)
  const date = new Date(y, month - 1, d, h, min, s)
  return date.getFullYear() === y && date.getMonth() === month - 1 && date.getDate() === d && date.getHours() === h && date.getMinutes() === min && date.getSeconds() === s ? date.getTime() : NaN
}

export function trendGeometry(trend, low, high) {
  const points = (trend?.points || []).map(p => ({ ...p, t: localTimestamp(p.time), v: Number(p.value) }))
    .filter(p => Number.isFinite(p.t) && p.value != null && Number.isFinite(p.v)).sort((a, b) => a.t - b.t)
  if (!points.length) return { points: [], lines: [], ticks: [], gaps: [] }
  const left = 56, right = 940, top = 22, bottom = 230
  const first = points[0].t, last = points.at(-1).t
  const min = Math.floor(Math.min(0, ...points.map(p => p.v)))
  const max = Math.ceil(Math.max(12, ...points.map(p => p.v), Number(high) || 0) / 2) * 2
  const x = t => first === last ? (left + right) / 2 : left + (t - first) / (last - first) * (right - left)
  const y = v => bottom - (v - min) / (max - min) * (bottom - top)
  const gaps = (trend.gaps || []).map(g => ({ start: localTimestamp(g.start), end: localTimestamp(g.end) })).filter(g => Number.isFinite(g.start) && g.end > g.start)
  const lines = []
  let current = []
  const mapped = points.map((p, i) => {
    const dot = { ...p, x: x(p.t), y: y(p.v) }
    const prev = points[i - 1]
    const crossesGap = prev && (gaps.some(g => prev.t < g.end && p.t > g.start) || ((!trend.totalPoints || trend.totalPoints <= points.length) && p.t - prev.t > 15 * 60_000))
    if (trend.dataSource === 'CGM') {
      if (crossesGap && current.length) { lines.push(current); current = [] }
      current.push(dot)
    }
    return dot
  })
  if (current.length) lines.push(current)
  const validBand = low != null && high != null && Number.isFinite(Number(low)) && Number.isFinite(Number(high)) && Number(high) > Number(low)
  return {
    points: mapped, lines, first: points[0].time, last: points.at(-1).time,
    ticks: Array.from({ length: 5 }, (_, i) => { const value = min + (max - min) * i / 4; return { value: Number(value.toFixed(1)), y: y(value) } }),
    gaps: gaps.filter(g => g.end > first && g.start < last).map(g => ({ x: x(Math.max(first, g.start)), width: x(Math.min(last, g.end)) - x(Math.max(first, g.start)) })),
    band: validBand ? { y: y(Number(high)), height: y(Number(low)) - y(Number(high)) } : null
  }
}
