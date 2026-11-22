import test from 'node:test'
import assert from 'node:assert/strict'
import { localTimestamp, trendGeometry } from '../src/utils/trendGeometry.js'
const p = (time, value) => ({ time: '2026-10-20 ' + time, value })
test('CGM 缺测不跨区间连线', () => {
  const result = trendGeometry({ dataSource: 'CGM', points: [p('08:00:00', 5), p('08:05:00', 6), p('09:00:00', 7)] })
  assert.deepEqual(result.lines.map(l => l.length), [2, 1])
})
test('降采样后仍以原始缺口断开，不把正常降采样间隔判作缺测', () => {
  const points = [p('08:00:00', 5), p('09:00:00', 6), p('10:00:00', 7)]
  const result = trendGeometry({ dataSource: 'CGM', totalPoints: 100, points, gaps: [{ start: '2026-10-20 08:30:00', end: '2026-10-20 08:50:00' }] })
  assert.deepEqual(result.lines.map(l => l.length), [1, 2])
  assert.equal(result.gaps.length, 1)
})
test('SMBG 只显示点，单点也能定位', () => {
  const result = trendGeometry({ dataSource: 'SMBG', points: [p('08:00:00', 5)] })
  assert.equal(result.lines.length, 0)
  assert.equal(result.points[0].x, 498)
  assert.ok(Number.isFinite(result.points[0].y))
})
test('空值、非法数值和非法日期不会产生虚假零值或错误路径', () => {
  assert.ok(Number.isNaN(localTimestamp('2026-02-30 08:00:00')))
  const result = trendGeometry({ points: [p('08:00:00', null), p('09:00:00', 'bad')] })
  assert.equal(result.points.length, 0)
})
