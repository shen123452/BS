const api = require('../../api/index')
const { ensureSession } = require('../../utils/session')

Page({
  data: {
    dataMode: 'REAL',
    todayLabel: '',
    loading: true,
    errorMsg: '',
    overview: null,
    sources: [],
    selectedSource: 'SMBG',
    medication: null, medicationError: '', medicationLoading: true
  },

  onShow() {
    const now = new Date()
    const week = ['日', '一', '二', '三', '四', '五', '六']
    this.setData({
      dataMode: wx.getStorageSync('dataMode') || 'REAL',
      todayLabel: `${now.getMonth() + 1}月${now.getDate()}日 · 周${week[now.getDay()]}`,
      sources: [
        { key: 'SMBG', label: '指尖血糖', empty: '还没有手工记录' },
        { key: 'CGM', label: '连续监测', empty: '尚未导入监测数据' }
      ]
    })
    return ensureSession().then(ready => { if (ready) return this.load() })
      .catch(e => this.setData({ loading: false, errorMsg: e.message }))
  },

  load() {
    this.loadMedication()
    this.setData({ loading: true, errorMsg: '' })
    return api.latest().then(overview => {
      // 组装渲染数据:每个来源的展示状态
      const sources = this.data.sources.map(s => {
        const item = overview[s.key] || {}
        const latest = item.latest || null
        return {
          ...s,
          latest,
          todayCount: item.todayCount || 0,
          todayAvg: item.todayAvg,
          todayMin: item.todayMin,
          todayMax: item.todayMax,
          sparseTip: s.key === 'SMBG' && item.todayCount > 0 && item.todayCount < 4
        }
      })
      this.setData({ overview, sources, todayCount: sources.reduce((total, item) => total + item.todayCount, 0), loading: false })
    }).catch(e => {
      this.setData({ loading: false, errorMsg: e.message })
    })
  },

  loadMedication() {
    this.setData({ medicationLoading: true, medicationError: '' })
    return api.medicationDay().then(medication => this.setData({ medication }))
      .catch(e => this.setData({ medicationError: e.message }))
      .finally(() => this.setData({ medicationLoading: false }))
  },
  goMedication() { wx.navigateTo({ url: '/subpackages/medication/pages/index' }) },

  goRecord() { wx.navigateTo({ url: '/pages/glucose/record' }) },
  pickSource(e) { this.setData({ selectedSource: e.currentTarget.dataset.source }) },
  goList(e) {
    getApp().globalData.recordSource = e.currentTarget.dataset.source
    wx.switchTab({ url: '/pages/glucose/list' })
  },
  goAlerts() { wx.navigateTo({ url: '/subpackages/alert/pages/list' }) },
  goQa() { wx.switchTab({ url: '/pages/qa/index' }) },
  goAnalysis() { wx.switchTab({ url: '/pages/analysis/index' }) },
  goImport() { wx.navigateTo({ url: '/subpackages/import/pages/preview' }) },

  fmtTime(ts) { return ts ? ts.slice(5, 16) : '' }
})
