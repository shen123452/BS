<script setup>
import { computed } from 'vue'
const props = defineProps({ profile: Object, loading: Boolean, error: String })
const emit = defineEmits(['retry'])
const geometry = computed(() => {
  const slots = props.profile?.slots || [], runs = []; let run = []
  slots.forEach((s, i) => {
    const values = [s.p5, s.p25, s.p50, s.p75, s.p95]
    if (values.some(v => v == null || !Number.isFinite(Number(v))) || values.some((v, j) => j > 0 && Number(v) < Number(values[j - 1]))) {
      if (run.length) runs.push(run); run = []; return
    }
    run.push({ ...s, i })
  }); if (run.length) runs.push(run)
  const max = Math.ceil(Math.max(12, Number(props.profile?.targetHigh) || 0, ...runs.flat().map(s => Number(s.p95))) / 2) * 2
  const x = i => 48 + (i + .5) / 48 * 704, y = v => 212 - Number(v) / max * 190
  const band = (r, lo, hi) => r.length === 1 ? `${x(r[0].i) - 5},${y(r[0][hi])} ${x(r[0].i) + 5},${y(r[0][hi])} ${x(r[0].i) + 5},${y(r[0][lo])} ${x(r[0].i) - 5},${y(r[0][lo])}` : [...r.map(s => `${x(s.i)},${y(s[hi])}`), ...r.slice().reverse().map(s => `${x(s.i)},${y(s[lo])}`)].join(' ')
  return {
    runs: runs.map(r => ({ outer: band(r, 'p5', 'p95'), inner: band(r, 'p25', 'p75'), median: r.map(s => `${x(s.i)},${y(s.p50)}`).join(' ') })),
    ticks: Array.from({ length: 5 }, (_, i) => ({ value: Number((max * i / 4).toFixed(1)), y: y(max * i / 4) }))
  }
})
</script>

<template>
  <section class="panel agp-panel">
    <div class="agp-header">
      <div class="agp-title-group">
        <h2>日间血糖谱 (AGP)</h2>
        <span class="agp-pill">24小时叠加 · 30分钟槽位</span>
      </div>
      <span class="agp-unit-tag">单位: mmol/L</span>
    </div>

    <div v-if="loading" class="loading-state">
      <div class="loading-spinner-sm"></div>
      <p class="muted">正在计算并叠加 48 个时段的分位分布…</p>
    </div>

    <div v-else-if="error" role="alert" class="agp-error">
      <p>{{ error }}</p>
      <el-button type="primary" size="small" @click="emit('retry')">重新加载分布</el-button>
    </div>

    <template v-else-if="profile?.qualifiedSlotCount">
      <div class="agp-scroll" tabindex="0" aria-label="日间分布图，可横向滚动查看坐标">
        <svg viewBox="0 0 780 250" role="img" aria-label="日间血糖分位分布；空白时段为数据不足；单位 mmol/L" class="agp-svg">
          <defs>
            <linearGradient id="agpOuterGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#3B82F6" stop-opacity="0.25" />
              <stop offset="100%" stop-color="#3B82F6" stop-opacity="0.10" />
            </linearGradient>
            <linearGradient id="agpInnerGrad" x1="0" y1="0" x2="0" y2="1">
              <stop offset="0%" stop-color="#2563EB" stop-opacity="0.55" />
              <stop offset="100%" stop-color="#1D4ED8" stop-opacity="0.35" />
            </linearGradient>
          </defs>

          <!-- 刻度线 -->
          <g v-for="t in geometry.ticks" :key="t.value">
            <line x1="48" x2="752" :y1="t.y" :y2="t.y" class="grid" />
            <text x="38" :y="t.y + 4" text-anchor="end" class="tick-text">{{ t.value }}</text>
          </g>

          <!-- 分位带多边形 -->
          <g v-for="(r, i) in geometry.runs" :key="i">
            <polygon :points="r.outer" class="outer" fill="url(#agpOuterGrad)" />
            <polygon :points="r.inner" class="inner" fill="url(#agpInnerGrad)" />
            <polyline :points="r.median" class="median" />
          </g>

          <!-- 24 小时时间轴 -->
          <g class="time-axis">
            <text x="48" y="240">00:00</text>
            <text x="224" y="240">06:00 (晨起)</text>
            <text x="400" y="240">12:00 (午间)</text>
            <text x="576" y="240">18:00 (傍晚)</text>
            <text x="752" y="240" text-anchor="end">24:00</text>
          </g>
        </svg>
      </div>

      <div class="legend-box">
        <div class="legend-items">
          <span class="legend-item"><span class="legend-swatch sw-outer"></span>P5–P95 轻度波动带 (90%区间)</span>
          <span class="legend-item"><span class="legend-swatch sw-inner"></span>P25–P75 核心四分位带 (50%区间)</span>
          <span class="legend-item"><span class="legend-swatch sw-median"></span>中位数曲线 (P50)</span>
        </div>
        <p class="agp-note">
          <strong class="mono-num">{{ profile.qualifiedSlotCount }}</strong> / 48 个半小时槽位达到门槛；空白时段保留缺口不插值。
        </p>
      </div>
    </template>

    <div v-else class="agp-empty-box">
      <p class="agp-empty-title">数据充分性未达标，暂不绘制日间分位谱带</p>
      <p class="agp-note">临床基准要求：连续监测 ≥ 14 个完整日、整体有效覆盖率 ≥ 70%，且对应半小时时段覆盖 ≥ 7 个不同日期。</p>
    </div>
  </section>
</template>

<style scoped>
.agp-panel { min-width: 0; }
.agp-header { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; }
.agp-title-group { display: flex; align-items: center; gap: 10px; }
.agp-header h2 { font-size: 16px; font-weight: 700; margin: 0; color: var(--color-ink); }
.agp-pill {
  font-size: 11.5px; font-weight: 600; background: #EFF6FF; color: var(--color-primary);
  padding: 2px 8px; border-radius: var(--radius-pill); border: 1px solid #DBEAFE;
}
.agp-unit-tag {
  font-size: 11.5px; color: var(--color-ink-muted); background: var(--color-surface-subtle);
  padding: 2px 8px; border-radius: 4px;
}
.loading-state { padding: 30px 0; text-align: center; }
.agp-error {
  padding: 16px 20px; background: var(--color-danger-subtle); border: 1px solid #FECACA;
  border-radius: var(--radius-sm); color: var(--color-danger); display: flex;
  align-items: center; justify-content: space-between; margin-top: 14px;
}

.agp-scroll { overflow-x: auto; margin-top: 12px; }
.agp-svg { display: block; width: 100%; min-width: 620px; }
.tick-text { font: 11px var(--font-data); fill: #94A3B8; font-variant-numeric: tabular-nums; }
.time-axis text { font: 11.5px var(--font-ui); fill: #64748B; font-weight: 500; }
.grid { stroke: #F1F5F9; stroke-width: 1; }

.outer { stroke: #BFDBFE; stroke-width: 0.5; }
.inner { stroke: #60A5FA; stroke-width: 0.8; }
.median {
  fill: none; stroke: var(--color-primary-strong); stroke-width: 2.4;
  stroke-linecap: round; stroke-linejoin: round; vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 2px 4px rgba(29, 78, 216, 0.3));
}

.legend-box {
  margin-top: 16px; padding-top: 14px; border-top: 1px solid var(--color-border);
}
.legend-items { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; margin-bottom: 8px; }
.legend-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-ink-muted); }
.legend-swatch { width: 12px; height: 10px; border-radius: 2px; flex: 0 0 auto; }
.sw-outer { background: rgba(59, 130, 246, 0.25); border: 1px solid #93C5FD; }
.sw-inner { background: rgba(37, 99, 235, 0.55); border: 1px solid #3B82F6; }
.sw-median { background: var(--color-primary-strong); height: 3px; }
.agp-note { font-size: 12px; color: var(--color-ink-faint); margin: 4px 0 0; line-height: 1.6; }

.agp-empty-box {
  margin-top: 18px; padding: 24px 20px; background: #F8FAFC; border-radius: var(--radius-sm);
  border: 1px dashed var(--color-border); text-align: center;
}
.agp-empty-title { font-weight: 600; color: var(--color-ink-muted); margin: 0 0 6px; font-size: 13.5px; }
</style>
