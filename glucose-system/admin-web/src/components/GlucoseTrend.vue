<script setup>
import { computed, ref } from 'vue'
import { trendGeometry } from '../utils/trendGeometry'

const props = defineProps({ trend: Object, low: Number, high: Number })
const geometry = computed(() => trendGeometry(props.trend, props.low, props.high))
const hoveredPoint = ref(null)

const isOut = value => Number.isFinite(Number(value)) && (
  (props.low != null && Number(value) < Number(props.low)) ||
  (props.high != null && Number(value) > Number(props.high))
)
</script>

<template>
  <section class="trend-section" aria-label="血糖趋势">
    <div class="trend-heading">
      <div class="trend-title-group">
        <h2 class="section-title">血糖时序趋势</h2>
        <span class="trend-badge" :class="trend?.dataSource === 'SMBG' ? 'badge-smbg' : 'badge-cgm'">
          {{ trend?.dataSource === 'SMBG' ? '指尖血糖 (SMBG) · 离散点' : '动态监测 (CGM) · 连续轨迹' }}
        </span>
      </div>
      <div class="trend-meta">
        <span v-if="trend?.dataSource === 'SMBG'" class="meta-note">手工离散测量点，不插值连线</span>
        <span v-else class="meta-note">14日完整窗口 · 缺测断线</span>
        <span class="trend-unit-tag">单位: mmol/L</span>
      </div>
    </div>

    <div v-if="geometry.points.length" class="trend-plot">
      <svg viewBox="0 0 970 280" role="img" aria-label="血糖随测量时间的变化" class="trend-svg">
        <defs>
          <!-- 面积渐变 -->
          <linearGradient id="trendAreaGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#2563EB" stop-opacity="0.25" />
            <stop offset="65%" stop-color="#2563EB" stop-opacity="0.05" />
            <stop offset="100%" stop-color="#2563EB" stop-opacity="0" />
          </linearGradient>
          <!-- 目标区域渐变 -->
          <linearGradient id="targetBandGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stop-color="#10B981" stop-opacity="0.10" />
            <stop offset="100%" stop-color="#10B981" stop-opacity="0.03" />
          </linearGradient>
          <!-- 缺测条纹 -->
          <pattern id="gapStripe" width="8" height="8" patternTransform="rotate(45 0 0)" patternUnits="userSpaceOnUse">
            <line x1="0" y1="0" x2="0" y2="8" stroke="#E2E8F0" stroke-width="1.5" />
          </pattern>
        </defs>

        <!-- 目标范围带 -->
        <g v-if="geometry.band">
          <rect x="56" width="884" :y="geometry.band.y" :height="geometry.band.height" class="target-band" fill="url(#targetBandGrad)" rx="4" />
          <line x1="56" x2="940" :y1="geometry.band.y" :y2="geometry.band.y" class="target-limit-line" />
          <line x1="56" x2="940" :y1="geometry.band.y + geometry.band.height" :y2="geometry.band.y + geometry.band.height" class="target-limit-line" />
          <text x="935" :y="geometry.band.y - 4" text-anchor="end" class="target-limit-text">目标上限 {{ high }}</text>
          <text x="935" :y="geometry.band.y + geometry.band.height + 12" text-anchor="end" class="target-limit-text">目标下限 {{ low }}</text>
        </g>

        <!-- 水平标尺网格 -->
        <g v-for="tick in geometry.ticks" :key="tick.value">
          <line x1="56" x2="940" :y1="tick.y" :y2="tick.y" class="grid-line" />
          <text x="44" :y="tick.y + 4" text-anchor="end" class="axis-text">{{ tick.value }}</text>
        </g>

        <!-- 垂直时间格线 -->
        <g class="time-grid">
          <line x1="56" y1="22" x2="56" y2="230" />
          <line x1="277" y1="22" x2="277" y2="230" />
          <line x1="498" y1="22" x2="498" y2="230" />
          <line x1="719" y1="22" x2="719" y2="230" />
          <line x1="940" y1="22" x2="940" y2="230" />
        </g>

        <!-- 缺测区间阴影 -->
        <g v-for="(gap, i) in geometry.gaps" :key="'gap-'+i">
          <rect :x="gap.x" y="22" :width="gap.width" height="208" class="gap-band" fill="url(#gapStripe)" />
          <text :x="gap.x + gap.width / 2" y="125" text-anchor="middle" class="gap-text">缺测</text>
        </g>

        <!-- CGM 面积填充与折线 -->
        <template v-if="trend?.dataSource === 'CGM'">
          <polygon v-for="(line, i) in geometry.lines" :key="'area-'+i"
                   :points="`${line.map(p => `${p.x},${p.y}`).join(' ')} ${line[line.length - 1].x},230 ${line[0].x},230`"
                   fill="url(#trendAreaGrad)" />
          <polyline v-for="(line, i) in geometry.lines" :key="'line-'+i"
                    :points="line.map(p => `${p.x},${p.y}`).join(' ')"
                    class="trend-line" />
        </template>

        <!-- 测量数据点 -->
        <g v-for="(p, i) in geometry.points" :key="'point-'+i"
           class="point-group"
           @mouseenter="hoveredPoint = p"
           @mouseleave="hoveredPoint = null">
          <circle :cx="p.x" :cy="p.y"
                  :r="trend?.dataSource === 'SMBG' ? 5 : (isOut(p.value) ? 3.5 : 2.5)"
                  class="trend-point"
                  :class="{ 'point-out': isOut(p.value), 'point-smbg': trend?.dataSource === 'SMBG' }">
            <title>{{ p.time }} · {{ p.value }} mmol/L</title>
          </circle>
          <!-- 异常读数外发光圈 -->
          <circle v-if="isOut(p.value)" :cx="p.x" :cy="p.y" r="7" class="point-halo" />
        </g>

        <!-- 时间刻度文字 -->
        <text x="56" y="260" class="time-axis-text">{{ geometry.first }}</text>
        <text x="940" y="260" text-anchor="end" class="time-axis-text">{{ geometry.last }}</text>

        <!-- 悬停高亮提示浮层 -->
        <g v-if="hoveredPoint" class="hover-tooltip" :transform="`translate(${hoveredPoint.x}, ${Math.max(30, hoveredPoint.y - 25)})`">
          <rect x="-45" y="-18" width="90" height="24" rx="4" class="tooltip-bg" />
          <text x="0" y="-3" text-anchor="middle" class="tooltip-text">{{ hoveredPoint.value }} mmol/L</text>
        </g>
      </svg>

      <div class="trend-footer">
        <div class="legend-row">
          <span class="legend-item"><span class="legend-swatch band-swatch"></span>目标范围 ({{ low || 3.9 }}–{{ high || 10.0 }})</span>
          <span class="legend-item"><span class="legend-swatch line-swatch"></span>{{ trend?.dataSource === 'SMBG' ? '离散实测点' : '连续监测曲线' }}</span>
          <span class="legend-item"><span class="legend-swatch out-swatch"></span>异常读数 (偏高/偏低)</span>
          <span v-if="geometry.gaps.length" class="legend-item"><span class="legend-swatch gap-swatch"></span>无信号缺测断线</span>
        </div>
        <div class="stat-note">
          展示 <strong class="mono-num">{{ trend.displayPoints ?? geometry.points.length }}</strong> / {{ trend.totalPoints ?? geometry.points.length }} 个采样点；区间全部记录已纳入有效计算。
        </div>
      </div>
    </div>
    <el-empty v-else description="所选日期区间暂无有效血糖测量点" :image-size="68" />
  </section>
</template>

<style scoped>
.trend-section { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--color-border); }
.trend-heading { display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px; margin-bottom: 14px; }
.trend-title-group { display: flex; align-items: center; gap: 12px; }
.trend-title-group .section-title { margin: 0; }
.trend-badge {
  font-size: 12px; font-weight: 600; padding: 2px 10px; border-radius: var(--radius-pill);
}
.badge-cgm { background: #EFF6FF; color: var(--color-primary); border: 1px solid #DBEAFE; }
.badge-smbg { background: #F0FDF4; color: #166534; border: 1px solid #BBF7D0; }
.trend-meta { display: flex; align-items: center; gap: 14px; font-size: 12.5px; color: var(--color-ink-muted); }
.meta-note { color: var(--color-ink-faint); }
.trend-unit-tag {
  background: var(--color-surface-subtle); color: var(--color-ink-muted);
  padding: 2px 8px; border-radius: 4px; font-weight: 500; font-size: 11px;
}

.trend-plot { overflow-x: auto; background: #FFFFFF; border-radius: var(--radius-sm); padding: 4px; }
.trend-svg { width: 100%; min-width: 600px; display: block; font: 11px var(--font-data); }

.grid-line { stroke: #F1F5F9; stroke-width: 1; }
.time-grid line { stroke: #F8FAFC; stroke-width: 1; stroke-dasharray: 4 4; }
.axis-text { fill: #94A3B8; font-size: 11px; font-variant-numeric: tabular-nums; }
.time-axis-text { fill: #64748B; font-size: 11.5px; font-weight: 500; }

.target-band { stroke: #D1FAE5; stroke-width: 1; }
.target-limit-line { stroke: #10B981; stroke-width: 1; stroke-dasharray: 3 3; opacity: 0.6; }
.target-limit-text { fill: #059669; font-size: 10px; font-weight: 500; }

.gap-band { opacity: 0.7; }
.gap-text { fill: #94A3B8; font-size: 11px; font-weight: 500; }

.trend-line {
  fill: none; stroke: var(--color-primary); stroke-width: 2.2;
  stroke-linecap: round; stroke-linejoin: round;
  vector-effect: non-scaling-stroke;
  filter: drop-shadow(0 2px 4px rgba(37, 99, 235, 0.2));
}

.trend-point { fill: #2563EB; transition: all 0.15s ease; cursor: pointer; }
.trend-point.point-smbg { fill: #2563EB; stroke: #FFFFFF; stroke-width: 2; }
.trend-point.point-out { fill: #EF4444; }
.point-halo { fill: none; stroke: #EF4444; stroke-width: 1.5; opacity: 0.45; animation: pulse 2s infinite; }
@keyframes pulse { 0% { r: 5; opacity: 0.6; } 50% { r: 9; opacity: 0.2; } 100% { r: 5; opacity: 0.6; } }

.point-group:hover .trend-point { r: 6; }
.tooltip-bg { fill: #0F172A; rx: 4; filter: drop-shadow(0 2px 8px rgba(0,0,0,0.25)); }
.tooltip-text { fill: #FFFFFF; font-size: 11px; font-weight: 600; }

.trend-footer {
  margin-top: 14px; padding-top: 12px; border-top: 1px solid var(--color-border);
  display: flex; align-items: center; justify-content: space-between; flex-wrap: wrap; gap: 12px;
}
.legend-row { display: flex; align-items: center; gap: 16px; flex-wrap: wrap; }
.legend-item { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--color-ink-muted); }
.legend-swatch { width: 10px; height: 10px; border-radius: 2px; flex: 0 0 auto; }
.band-swatch { background: #D1FAE5; border: 1px solid #10B981; }
.line-swatch { background: #2563EB; }
.out-swatch { background: #EF4444; border-radius: 50%; }
.gap-swatch { background: #E2E8F0; }
.stat-note { font-size: 12px; color: var(--color-ink-faint); }
</style>
