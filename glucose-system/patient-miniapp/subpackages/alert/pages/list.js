const api = require('../../../api/index')
const { ensureSession } = require('../../../utils/session')

const EVENT_STATUS_TEXT = {
  OPEN: '近期仍有异常读数', ENDED: '后续测量回到基础阈值范围', END_UNKNOWN: '后续无数据，结束时间未知', INVALIDATED: '数据依据已变化'
}

Page({
  data: {
    list: [],
    loading: true,
    showHistorical: false, errorMsg: '', readingId: ''
  },

  onShow() { return ensureSession().then(ready => { if (ready) return this.load() }).catch(e => this.setData({ loading: false, errorMsg: e.message })) },

  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, errorMsg: '', list: [] })
    return api.alerts({ pageNum: 1, pageSize: 50, isHistorical: this.data.showHistorical ? 1 : 0 }).then(data => {
      if (version !== this._loadVersion) return
      this.setData({
        list: (data.records || []).map(a => ({
          ...a,
          directionText: (a.alertType === 'LOW' ? '低血糖' : '高血糖') + ' · ' + (a.level === 3 ? '严重' : '警告'),
          unread: Number(a.noticeVersion) > Number(a.patientSeenNoticeVersion),
          eventText: a.endAt ? '恢复于 ' + a.endAt.slice(5, 16) : (EVENT_STATUS_TEXT[a.eventStatus] || a.eventStatus),
          handledText: a.handleStatus === 'HANDLED'
            ? '医生已于 ' + (a.handledAt || '').slice(5, 16) + ' 处理' + (a.handleNote ? ':' + a.handleNote : '')
            : '医生尚未处理'
        })),
        loading: false
      })
    }).catch(e => {
      if (version === this._loadVersion) this.setData({ loading: false, errorMsg: e.message })
    })
  },

  toggleHistorical() {
    this.setData({ showHistorical: !this.data.showHistorical })
    return this.load()
  },

  markRead(e) {
    if (this.data.readingId) return
    const item = this.data.list.find(a => String(a.id) === String(e.currentTarget.dataset.id))
    if (!item) return
    this.setData({ readingId: String(item.id), errorMsg: '' })
    return api.readAlert(item.id, item.noticeVersion).then(() => {
      wx.showToast({ title: '已确认查看' })
      return this.load()
    }).catch(err => this.setData({ errorMsg: err.message })).finally(() => this.setData({ readingId: '' }))
  }
})
