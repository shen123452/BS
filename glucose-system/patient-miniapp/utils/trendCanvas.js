// The API uses local calendar timestamps. Avoid browser-specific string parsing.
function localTimestamp(value) {
  const m = /^(\d{4})-(\d{2})-(\d{2})[ T](\d{2}):(\d{2}):(\d{2})$/.exec(value || '')
  if (!m) return NaN
  const [, y, month, d, h, min, s] = m.map(Number)
  const date = new Date(y, month - 1, d, h, min, s)
  return date.getFullYear() === y && date.getMonth() === month - 1 && date.getDate() === d && date.getHours() === h && date.getMinutes() === min && date.getSeconds() === s ? date.getTime() : NaN
}

function trendGeometry(trend, low, high) {
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

// Canvas 2D 现代精致绘制
function drawTrend(ctx, width, height, trend) {
  const g = trendGeometry(trend)
  ctx.clearRect(0, 0, width, height)
  if (!g.points.length) return
  const x = value => 36 + (value - 56) / 884 * (width - 48)
  const y = value => 18 + (value - 22) / 208 * (height - 44)
  ctx.font = '11px -apple-system, sans-serif'
  ctx.textAlign = 'right'

  // 标尺网格
  g.ticks.forEach(tick => {
    const py = y(tick.y)
    ctx.strokeStyle = '#F1F5F9'
    ctx.lineWidth = 1
    ctx.beginPath()
    ctx.moveTo(36, py)
    ctx.lineTo(width - 12, py)
    ctx.stroke()
    ctx.fillStyle = '#64748B'
    ctx.fillText(String(tick.value), 28, py + 4)
  })

  // 缺测阴影
  ctx.fillStyle = '#F8FAFC'
  g.gaps.forEach(gap => ctx.fillRect(x(gap.x), 18, Math.max(0, x(gap.x + gap.width) - x(gap.x)), height - 44))

  // CGM 折线
  ctx.strokeStyle = '#2563EB'
  ctx.lineWidth = 2.4
  ctx.lineCap = 'round'
  ctx.lineJoin = 'round'
  g.lines.forEach(line => {
    ctx.beginPath()
    line.forEach((p, i) => i ? ctx.lineTo(x(p.x), y(p.y)) : ctx.moveTo(x(p.x), y(p.y)))
    ctx.stroke()
  })

  // 数据点
  ctx.fillStyle = '#2563EB'
  g.points.forEach(p => {
    ctx.beginPath()
    const r = trend.dataSource === 'SMBG' ? 4 : 2.5
    ctx.arc(x(p.x), y(p.y), r, 0, Math.PI * 2)
    ctx.fill()
  })
}
module.exports = { trendGeometry, drawTrend }
