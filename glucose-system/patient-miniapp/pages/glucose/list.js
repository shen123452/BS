const api = require('../../api/index')
const { ensureSession } = require('../../utils/session')

Page({
  data: {
    loading: false,
    records: [],
    total: 0,
    date: '',
    dataSource: '',
    emptyTitle: '',
    emptyTip: ''
    , pageNum: 1, hasMore: false, loadingMore: false, errorMsg: ''
  },

  onLoad(options) {
    if (options && options.source) {
      this.setData({ dataSource: options.source })
    }
  },

  onShow() {
    const globalData = getApp().globalData
    if (globalData.recordSource !== undefined) {
      this.setData({ dataSource: globalData.recordSource })
      delete globalData.recordSource
    }
    return ensureSession().then(ready => { if (ready) return this.load() })
      .catch(e => this.setData({ loading: false, errorMsg: e.message }))
  },

  load() {
    this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, loadingMore: false, pageNum: 1, errorMsg: '' })
    return this.fetchPage(1, false)
  },

  onReachBottom() { this.loadMore() },
  loadMore() {
    if (this.data.loading || this.data.loadingMore || !this.data.hasMore) return
    this.setData({ loadingMore: true })
    return this.fetchPage(this.data.pageNum + 1, true)
  },

  fetchPage(pageNum, append) {
    const version = this._loadVersion
    return api.listGlucose({
      pageNum,
      pageSize: 20,
      dataSource: this.data.dataSource || undefined,
      date: this.data.date || undefined
    }).then(data => {
      if (version !== this._loadVersion) return
      const records = (data.records || []).map(r => ({
        ...r,
        isSmbg: r.dataSource === 'SMBG' && r.ingestMethod === 'MANUAL',
        readOnlyText: r.ingestMethod === 'GENERATOR' ? '演示生成' : '批次管理',
        riskText: r.riskFlag && r.riskFlag !== 'NONE'
          ? (r.riskFlag.includes('SEVERE') ? '严重' : '') + (r.riskFlag.indexOf('LOW') === 0 ? '低' : '高') + '提醒'
          : ''
      }))
      const filtered = this.data.date || this.data.dataSource
      const allRecords = append ? this.data.records.concat(records) : records
      this.setData({
        records: allRecords,
        pageNum,
        hasMore: allRecords.length < Number(data.total || 0),
        total: Number(data.total || 0),
        loading: false,
        loadingMore: false,
        emptyTitle: filtered ? '没有符合条件的记录' : '还没有任何记录',
        emptyTip: filtered ? '可以切换来源或清除筛选' : '记下第一次测量，记录会按时间保存在这里'
      })
    }).catch(e => {
      if (version !== this._loadVersion) return
      this.setData({ loading: false, loadingMore: false, errorMsg: e.message })
    })
  },

  setSource(e) {
    const source = e.currentTarget.dataset.source
    this.setData({ dataSource: this.data.dataSource === source ? '' : source })
    this.load()
  },

  onDateChange(e) {
    this.setData({ date: e.detail.value })
    this.load()
  },

  clearDate() {
    this.setData({ date: '' })
    this.load()
  },

  goRecord() { wx.navigateTo({ url: '/pages/glucose/record' }) },
  goImport() { wx.navigateTo({ url: '/subpackages/import/pages/preview' }) },
  clearFilters() { this.setData({ date: '', dataSource: '' }); return this.load() },

  editRecord(e) {
    const r = this.data.records.find(x => String(x.id) === String(e.currentTarget.dataset.id))
    if (!r) return
    if (r.dataSource !== 'SMBG') {
      wx.showToast({ title: 'CGM 记录不支持单条编辑,请通过批次撤销纠正', icon: 'none', duration: 2500 })
      return
    }
    wx.showModal({
      title: '修改记录',
      editable: true,
      placeholderText: '输入新的血糖值(mmol/L,一位小数)',
      success: res => {
        if (!res.confirm || !res.content) return
        const v = Number(res.content)
        if (Number.isNaN(v) || v < 0.5 || v > 40) {
          wx.showToast({ title: '数值须在 0.5 – 40.0 之间', icon: 'none' })
          return
        }
        api.updateGlucose(r.id, {
          measuredAt: r.measuredAt,
          glucoseMmol: v,
          measureType: Number(r.measureType),
          note: r.note || undefined
        }).then(() => {
          wx.showToast({ title: '已修改' })
          this.load()
        }).catch(err => wx.showToast({ title: err.message, icon: 'none' }))
      }
    })
  },

  deleteRecord(e) {
    const r = this.data.records.find(x => String(x.id) === String(e.currentTarget.dataset.id))
    if (!r) return
    wx.showModal({
      title: '删除记录',
      content: '确认删除 ' + r.measuredAt + ' 的 ' + r.glucoseMmol + ' mmol/L 记录?删除后相关预警将重新计算,无法恢复。',
      confirmColor: '#B44D4D',
      success: res => {
        if (!res.confirm) return
        api.deleteGlucose(r.id).then(() => {
          wx.showToast({ title: '已删除' })
          this.load()
        }).catch(err => wx.showToast({ title: err.message, icon: 'none' }))
      }
    })
  }
})
