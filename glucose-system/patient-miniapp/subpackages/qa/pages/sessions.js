const api = require('../../../api/index')
const { ensureSession } = require('../../../utils/session')

Page({
  data: {
    sessions: [],
    loading: true, errorMsg: '', deletingId: ''
  },

  onShow() { return ensureSession().then(ready => { if (ready) return this.load() }).catch(e => this.setData({ loading: false, errorMsg: e.message })) },

  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, errorMsg: '', deletingId: '' })
    return api.sessions().then(sessions => {
      if (version === this._loadVersion) this.setData({ sessions, loading: false })
    }).catch(e => {
      if (version === this._loadVersion) this.setData({ loading: false, errorMsg: e.message })
    })
  },

  createAndEnter() {
    wx.navigateTo({ url: '/subpackages/qa/pages/chat' })
  },

  enter(e) {
    wx.navigateTo({ url: '/subpackages/qa/pages/chat?sessionId=' + e.currentTarget.dataset.id })
  },

  removeSession(e) {
    if (this.data.deletingId) return
    const item = this.data.sessions.find(s => String(s.sessionId) === String(e.currentTarget.dataset.id))
    if (!item) return
    this.setData({ deletingId: String(item.sessionId), errorMsg: '' })
    wx.showModal({
      title: '删除会话',
      content: '删除「' + item.title + '」及其全部消息?来源引用同步不可访问。',
      confirmColor: '#B44D4D',
      success: res => {
        if (!res.confirm) { this.setData({ deletingId: '' }); return }
        api.deleteSession(item.sessionId).then(() => this.load())
          .catch(err => this.setData({ errorMsg: err.message })).finally(() => this.setData({ deletingId: '' }))
      }, fail: () => this.setData({ deletingId: '' })
    })
  }
})
