const api = require('../../api/index')
const { ensureSession, clearSession } = require('../../utils/session')

function maskPhone(phone) {
  const clean = String(phone || '').trim()
  if (!clean) return '未绑定手机号'
  if (/^\d{11}$/.test(clean)) return clean.slice(0, 3) + '****' + clean.slice(7)
  return clean
}

function formatDateOnly(iso) {
  const clean = String(iso || '').trim()
  if (!clean) return '查看说明'
  const datePart = clean.split('T')[0].split(' ')[0]
  return datePart ? '已确认 ' + datePart : '已确认'
}

Page({
  data: {
    profile: null,
    targets: null,
    dataMode: 'REAL',
    displayName: '微信用户',
    avatarInitial: '',
    accountId: '—',
    phoneLabel: '未绑定手机号',
    hasPhone: false,
    profileSummary: '去补充',
    targetSummaryText: '去设置',
    privacyDateText: '查看说明',
    targetDetails: false,
    loading: false,
    errorMsg: '',
    accountBusy: false
  },

  onShow() {
    this.setData({ dataMode: wx.getStorageSync('dataMode') || 'REAL' })
    return ensureSession().then(ready => { if (ready) return this.load() })
      .catch(e => this.setData({ loading: false, errorMsg: e.message }))
  },

  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, errorMsg: '' })
    return Promise.all([api.getProfile(), api.getTargets()]).then(([profile, targets]) => {
      if (version !== this._loadVersion) return
      const realName = String(profile.realName || '').trim()
      const phone = String(profile.phone || '').trim()
      const diabetesType = String(profile.diabetesType || '').trim()
      const targetSummaryText = targets
        ? targets.cgmTargetLow + '–' + targets.cgmTargetHigh + ' mmol/L'
        : '去设置'
      this.setData({
        profile,
        targets,
        displayName: realName || profile.username || '微信用户',
        avatarInitial: realName ? realName.slice(0, 1) : '',
        accountId: profile.userId || wx.getStorageSync('userId') || '—',
        phoneLabel: maskPhone(phone),
        hasPhone: !!phone,
        profileSummary: diabetesType || (realName || phone ? '已完善' : '去补充'),
        targetSummaryText,
        privacyDateText: formatDateOnly(profile.privacyAcceptedAt)
      })
    }).catch(e => {
      if (version === this._loadVersion) this.setData({ errorMsg: e.message })
    }).finally(() => {
      if (version === this._loadVersion) this.setData({ loading: false })
    })
  },

  goProfile() { wx.navigateTo({ url: '/pages/my/profile' }) },
  goTargets() { wx.navigateTo({ url: '/pages/my/targets' }) },
  goMedication() { wx.navigateTo({ url: '/subpackages/medication/pages/index?tab=plans' }) },
  goAlerts() { wx.navigateTo({ url: '/subpackages/alert/pages/list' }) },
  viewPrivacy() { wx.navigateTo({ url: '/pages/privacy/index' }) },

  handleLogout() {
    if (this.data.accountBusy) return
    this.setData({ accountBusy: true })
    wx.showModal({
      title: '退出登录',
      content: '确认退出当前账号?',
      success: res => {
        if (!res.confirm) { this.setData({ accountBusy: false }); return }
        api.logout().catch(() => { }).finally(() => {
          clearSession()
          wx.reLaunch({ url: '/pages/login/index' })
        })
      }, fail: () => this.setData({ accountBusy: false })
    })
  },

  handleDelete() {
    if (this.data.accountBusy) return
    this.setData({ accountBusy: true })
    wx.showModal({
      title: '注销账号',
      content: '注销后账号和登录凭证立即失效，本人的档案、血糖、预警、问答及导入数据将被清理，无法恢复。确认注销？',
      confirmColor: '#B44D4D',
      success: res => {
        if (!res.confirm) { this.setData({ accountBusy: false }); return }
        api.deleteAccount().then(() => {
          clearSession()
          wx.showToast({ title: '已注销' })
          setTimeout(() => wx.reLaunch({ url: '/pages/login/index' }), 800)
        }).catch(e => this.setData({ errorMsg: e.message })).finally(() => this.setData({ accountBusy: false }))
      }, fail: () => this.setData({ accountBusy: false })
    })
  }
})
