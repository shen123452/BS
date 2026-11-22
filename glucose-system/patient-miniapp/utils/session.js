const api = require('../api/index')
const { PRIVACY_VERSION } = require('./privacy')
let acceptedToken = '', checkingToken = '', checking = null, redirecting = false

function goLogin() {
  if (redirecting) return
  redirecting = true
  wx.reLaunch({ url: '/pages/login/index', complete: () => { redirecting = false } })
}
function rememberSession() { acceptedToken = wx.getStorageSync('accessToken') || '' }
function clearSession() {
  acceptedToken = ''; checkingToken = ''; checking = null
  for (const key of ['accessToken', 'userId', 'dataMode', 'profileCompleted']) wx.removeStorageSync(key)
}
// Saved sessions are verified once per token; legacy consent is completed at login.
function ensureSession() {
  const token = wx.getStorageSync('accessToken')
  if (!token) { goLogin(); return Promise.resolve(false) }
  if (acceptedToken === token) return Promise.resolve(true)
  if (checkingToken === token && checking) return checking
  checkingToken = token
  const task = api.getProfile().then(profile => {
    if (wx.getStorageSync('accessToken') !== token) return false
    if (profile.privacyVersion !== PRIVACY_VERSION) { goLogin(); return false }
    acceptedToken = token
    return true
  }).finally(() => { if (checking === task) { checking = null; checkingToken = '' } })
  checking = task
  return task
}
module.exports = { ensureSession, rememberSession, clearSession, goLogin }
