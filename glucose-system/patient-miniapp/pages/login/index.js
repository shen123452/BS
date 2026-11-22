const api = require('../../api/index')
const { PRIVACY_VERSION } = require('../../utils/privacy')
const { rememberSession } = require('../../utils/session')
const { isLocalDevelopment } = require('../../utils/request')

const LOCAL_LOGIN_CODE = 'glucose-local-dev'

function wxLoginErrorMessage(error) {
  const detail = error && (error.errMsg || error.message)
  if (!detail) return '微信登录失败，请重试'
  return '微信登录失败：' + String(detail).replace(/\s+/g, ' ').slice(0, 160)
}

Page({
  data: { loading: false, checking: false, consent: false, hasSession: false, errorMsg: '' },
  onLoad() { return this.restoreSession() },
  restoreSession() {
    if (!wx.getStorageSync('accessToken')) return Promise.resolve()
    this.setData({ checking: true, errorMsg: '' })
    return api.getProfile().then(profile => {
      this.setData({ hasSession: true })
      if (profile.privacyVersion === PRIVACY_VERSION) this.enterApp()
    }).catch(e => this.setData({ errorMsg: e.message }))
      .finally(() => this.setData({ checking: false }))
  },
  onConsent(e) { this.setData({ consent: e.detail.value.includes('accept'), errorMsg: '' }) },
  viewPrivacy() { wx.navigateTo({ url: '/pages/privacy/index' }) },
  enterApp() {
    rememberSession()
    wx.switchTab({ url: '/pages/home/index' })
  },
  exchangeCode(code) {
    return api.wxLogin(code).then(data => {
      if (!data || !data.token || data.userId === undefined || data.userId === null) {
        throw new Error('登录服务返回无效结果，请重试')
      }
      wx.setStorageSync('accessToken', data.token)
      wx.setStorageSync('userId', data.userId)
      wx.setStorageSync('dataMode', data.dataMode)
      wx.setStorageSync('profileCompleted', data.profileCompleted ? '1' : '0')
      this.setData({ hasSession: true })
      return this.finishConsent()
    }).catch(e => {
      this.setData({ loading: false, errorMsg: e && e.message ? e.message : '登录失败，请重试' })
    })
  },
  handleWxLoginFailure(error) {
    if (isLocalDevelopment()) {
      this.setData({ errorMsg: '开发工具未提供微信登录凭证，正在使用本地开发账号' })
      return this.exchangeCode(LOCAL_LOGIN_CODE)
    }
    this.setData({ loading: false, errorMsg: wxLoginErrorMessage(error) })
  },
  handleLogin() {
    if (this.data.loading || this.data.checking) return
    if (!this.data.consent) { this.setData({ errorMsg: '请阅读并勾选同意隐私与使用说明' }); return }
    this.setData({ loading: true, errorMsg: '' })
    // Complete consent for the existing account instead of exchanging a new mock code.
    if (wx.getStorageSync('accessToken')) return this.finishConsent()
    try {
      wx.login({
        success: loginRes => {
          if (!loginRes.code) return this.handleWxLoginFailure({ errMsg: '未取得登录凭证' })
          return this.exchangeCode(loginRes.code)
        },
        fail: error => this.handleWxLoginFailure(error)
      })
    } catch (error) {
      this.handleWxLoginFailure(error)
    }
  },
  finishConsent() {
    return api.getTargets().then(targets => api.updateTargets({ targetRevision: targets.targetRevision, privacyVersion: PRIVACY_VERSION }))
      .then(() => {
        wx.setStorageSync('profileCompleted', '1')
        this.enterApp()
      }).catch(e => {
        // Do not enter features until server-side consent succeeds. Retry keeps the account.
        this.setData({ errorMsg: e.message + '，请重试确认' })
      }).finally(() => this.setData({ loading: false }))
  }
})
