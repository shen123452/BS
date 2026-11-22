// Two independent percentile bands and a median line. A missing slot always breaks all three.
function agpGeometry(profile) {
  const slots = profile && profile.slots || []
  const runs = []; let current = []
  slots.forEach((s, index) => {
    const values = [s.p5, s.p25, s.p50, s.p75, s.p95]
    const valid = values.every(v => v != null && Number.isFinite(Number(v))) && values.every((v, i) => !i || Number(v) >= Number(values[i - 1]))
    if (!valid) { if (current.length) runs.push(current); current = []; return }
    current.push({ ...s, index })
  })
  if (current.length) runs.push(current)
  const max = Math.ceil(Math.max(12, Number(profile && profile.targetHigh) || 0, ...runs.flat().map(s => Number(s.p95))) / 2) * 2
  return { runs, max, slotCount: slots.length }
}

function drawAgp(ctx, width, height, profile, selectedIndex) {
  const g = agpGeometry(profile), left = 36, right = width - 12, top = 16, bottom = height - 30
  const x = index => left + (index + .5) / 48 * (right - left)
  const y = value => bottom - Number(value) / g.max * (bottom - top)
  ctx.clearRect(0, 0, width, height)
  ctx.font = '12px sans-serif'; ctx.lineWidth = 1
  for (let i = 0; i <= 4; i++) {
    const value = g.max * i / 4, py = y(value)
    ctx.strokeStyle = '#DCE3EC'; ctx.beginPath(); ctx.moveTo(left, py); ctx.lineTo(right, py); ctx.stroke()
    ctx.fillStyle = '#52637A'; ctx.textAlign = 'right'; ctx.fillText(String(value), left - 6, py + 4)
  }
  function band(run, low, high, color) {
    ctx.fillStyle = color; ctx.beginPath()
    if (run.length === 1) {
      const s = run[0], half = (right - left) / 48 * .4
      ctx.fillRect(x(s.index) - half, y(s[high]), half * 2, y(s[low]) - y(s[high])); return
    }
    run.forEach((s, i) => i ? ctx.lineTo(x(s.index), y(s[high])) : ctx.moveTo(x(s.index), y(s[high])))
    run.slice().reverse().forEach(s => ctx.lineTo(x(s.index), y(s[low])))
    ctx.closePath(); ctx.fill()
  }
  g.runs.forEach(run => { band(run, 'p5', 'p95', '#DCE7FF'); band(run, 'p25', 'p75', '#AEC7FF') })
  if (profile.targetLow != null && profile.targetHigh != null) {
    ctx.strokeStyle = '#966018'; ctx.setLineDash([4, 4])
    for (const target of [profile.targetLow, profile.targetHigh]) {
      ctx.beginPath(); ctx.moveTo(left, y(target)); ctx.lineTo(right, y(target)); ctx.stroke()
    }
    ctx.setLineDash([])
  }
  ctx.strokeStyle = '#2463EB'; ctx.fillStyle = '#2463EB'; ctx.lineWidth = 2
  g.runs.forEach(run => {
    ctx.beginPath(); run.forEach((s, i) => i ? ctx.lineTo(x(s.index), y(s.p50)) : ctx.moveTo(x(s.index), y(s.p50))); ctx.stroke()
    if (run.length === 1) { ctx.beginPath(); ctx.arc(x(run[0].index), y(run[0].p50), 2, 0, Math.PI * 2); ctx.fill() }
  })
  ctx.fillStyle = '#52637A'
  for (const hour of [0, 6, 12, 18, 24]) {
    ctx.textAlign = hour === 0 ? 'left' : hour === 24 ? 'right' : 'center'
    ctx.fillText(String(hour).padStart(2, '0') + ':00', left + hour / 24 * (right - left), height - 8)
  }
  if (selectedIndex != null && selectedIndex >= 0 && selectedIndex < 48) {
    ctx.strokeStyle = '#172B4D'; ctx.lineWidth = 1; ctx.setLineDash([2, 3])
    ctx.beginPath(); ctx.moveTo(x(selectedIndex), top); ctx.lineTo(x(selectedIndex), bottom); ctx.stroke(); ctx.setLineDash([])
  }
  return g
}
module.exports = { agpGeometry, drawAgp }
