const { ensureSession } = require('../../utils/session')
const api = require('../../api/index')
const { drawTrend } = require('../../utils/trendCanvas')
const { drawAgp } = require('../../utils/agpCanvas')

const STATUS_MAP = {
  QUALIFIED: ['tag-success', '达到展示门槛'],
  DESCRIPTIVE_ONLY: ['tag-real', '仅描述性统计'],
  INSUFFICIENT_CGM: ['tag-warn', 'CGM 数据不足'],
  NO_DATA: ['tag-warn', '没有记录']
}
const REASON_TEXT = {
  SMBG_NOT_TIME_BASED: 'SMBG 为稀疏测量点,不适用时间占比',
  TARGET_NOT_CONFIGURED: '部分测量类型未配置目标',
  SHORT_WINDOW: '窗口不足 14 个完整日',
  LOW_COVERAGE: '覆盖率不足 70%',
  INSUFFICIENT_SAMPLES: '样本量不足',
  INSUFFICIENT_SLOT_DAYS: '部分半小时时段覆盖不足 7 个不同日期，保留为空白'
}

Page({
  data: {
    days: 7,
    dataSource: 'SMBG',
    loading: true,
    errorMsg: '',
    summary: null,
    daily: [],
    trend: null,
    statusTag: [],
    reasons: [],
    adequacyPct: null,
    validHours: null,
    showAgpDetails: false,
    agp: null, agpLoading: false, agpError: '', agpTimes: [], selectedAgp: null, selectedAgpIndex: 0
  },

  onShow() { return ensureSession().then(ready => { if (ready) return this.load() }).catch(e => this.setData({ errorMsg: e.message, loading: false })) },
  goRecord() { wx.navigateTo({ url: '/pages/glucose/record' }) },
  goImport() { wx.navigateTo({ url: '/subpackages/import/pages/preview' }) },

  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, errorMsg: '', agp: null, agpLoading: false, agpError: '', selectedAgp: null })
    const end = new Date()
    const start = new Date(end.getFullYear(), end.getMonth(), end.getDate())
    start.setDate(start.getDate() - this.data.days)
    const fmt = d => d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0') + '-' + String(d.getDate()).padStart(2, '0')
    const params = { startDate: fmt(start), endDate: fmt(end), dataSource: this.data.dataSource }
    return Promise.all([api.summary(params), api.daily(params), api.trend(params)]).then(([summary, daily, trend]) => {
      if (version !== this._loadVersion) return
      this.setData({
        summary,
        daily,
        trend,
        loading: false,
        statusTag: STATUS_MAP[summary.metricStatus] || ['', summary.metricStatus],
        reasons: (summary.reasonCodes || []).map(r => REASON_TEXT[r] || r),
        adequacyPct: summary.dataAdequacy != null ? Math.round(summary.dataAdequacy * 1000) / 10 : null,
        validHours: summary.validMonitoringMinutes != null ? Math.round(summary.validMonitoringMinutes / 60) : null
      }, () => this.drawChart())
      if (params.dataSource === 'CGM') return this.loadAgp(params, version)
    }).catch(e => {
      if (version !== this._loadVersion) return
      this.setData({ loading: false, errorMsg: e.message })
    })
  },

  loadAgp(params, version) {
    this.setData({ agpLoading: true, agpError: '' })
    return api.agp(params).then(agp => {
      if (version !== this._loadVersion) return
      const index = Math.max(0, agp.slots.findIndex(s => s.p50 != null))
      this.setData({ agp, agpTimes: agp.slots.map(s => s.time), selectedAgpIndex: index, selectedAgp: agp.slots[index], agpLoading: false }, () => this.drawAgpChart())
    }).catch(e => {
      if (version === this._loadVersion) this.setData({ agpLoading: false, agpError: e.message })
    })
  },
  retryAgp() { return this.load() },
  toggleAgpDetails() { this.setData({ showAgpDetails: !this.data.showAgpDetails }, () => this.drawAgpChart()) },
  drawAgpChart() {
    if (!this.data.agp || !this.data.agp.qualifiedSlotCount) return
    const version = this._loadVersion
    wx.createSelectorQuery().select('#agp-chart').fields({ node: true, size: true, rect: true }).exec(result => {
      if (version !== this._loadVersion || !result || !result[0] || !result[0].node) return
      const { node, width, height, left } = result[0]
      const ratio = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).pixelRatio
      this._agpBounds = { left, width }
      node.width = width * ratio; node.height = height * ratio
      const context = node.getContext('2d'); context.scale(ratio, ratio)
      drawAgp(context, width, height, this.data.agp, this.data.selectedAgpIndex)
    })
  },
  selectAgp(e) {
    const index = Number(e.detail.value)
    if (!this.data.agp || !this.data.agp.slots[index]) return
    this.setData({ selectedAgpIndex: index, selectedAgp: this.data.agp.slots[index] }, () => this.drawAgpChart())
  },
  touchAgp(e) {
    const touch = (e.changedTouches || [])[0], bounds = this._agpBounds
    if (!touch || !bounds || !Number.isFinite(touch.clientX) || !Number.isFinite(bounds.left)) return
    const index = Math.max(0, Math.min(47, Math.floor((touch.clientX - bounds.left - 36) / (bounds.width - 48) * 48)))
    this.selectAgp({ detail: { value: index } })
  },
  onResize() { if (!this.data.loading) { this.drawChart(); this.drawAgpChart() } },
  onUnload() { this._loadVersion = (this._loadVersion || 0) + 1 },

  drawChart() {
    if (!this.data.trend?.points?.length) return
    wx.createSelectorQuery().select('#glucose-trend').fields({ node: true, size: true }).exec(result => {
      if (!result?.[0]?.node) return
      const { node, width, height } = result[0]
      const ratio = (wx.getWindowInfo ? wx.getWindowInfo() : wx.getSystemInfoSync()).pixelRatio
      node.width = width * ratio; node.height = height * ratio
      const context = node.getContext('2d'); context.scale(ratio, ratio)
      drawTrend(context, width, height, this.data.trend)
    })
  },

  setDays(e) { this.setData({ days: Number(e.currentTarget.dataset.days) }); this.load() },
  setSource(e) { this.setData({ dataSource: e.currentTarget.dataset.source }); this.load() }
})
