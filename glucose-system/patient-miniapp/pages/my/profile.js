const api = require('../../api/index')
const { ensureSession } = require('../../utils/session')

const DIABETES_PRESETS = ['1型糖尿病', '2型糖尿病', '妊娠期糖尿病', '糖前期']

Page({
  data: {
    profile: null,
    accountId: '—',
    avatarInitial: '',
    loading: false,
    saving: false,
    errorMsg: '',
    profileError: '',
    genders: ['未填写', '男', '女'],
    diabetesPresets: DIABETES_PRESETS,
    profileForm: {
      realName: '',
      phone: '',
      gender: 0,
      birthDate: '',
      diabetesType: ''
    }
  },

  onShow() {
    return ensureSession().then(ready => { if (ready) return this.load() })
      .catch(e => this.setData({ loading: false, errorMsg: e.message }))
  },

  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, errorMsg: '' })
    return api.getProfile().then(profile => {
      if (version !== this._loadVersion) return
      const realName = String(profile.realName || '').trim()
      this.setData({
        profile,
        accountId: profile.userId || wx.getStorageSync('userId') || '—',
        avatarInitial: realName ? realName.slice(0, 1) : '',
        profileForm: {
          realName: profile.realName || '',
          phone: profile.phone || '',
          gender: Number(profile.gender || 0),
          birthDate: profile.birthDate || '',
          diabetesType: profile.diabetesType || ''
        }
      })
    }).catch(e => {
      if (version === this._loadVersion) this.setData({ errorMsg: e.message })
    }).finally(() => {
      if (version === this._loadVersion) this.setData({ loading: false })
    })
  },

  onProfileInput(e) {
    const field = e.currentTarget.dataset.field
    const value = e.detail.value
    const next = { ...this.data.profileForm, [field]: value }
    const patch = { profileForm: next, profileError: '' }
    if (field === 'realName') {
      const trimmed = String(value || '').trim()
      patch.avatarInitial = trimmed ? trimmed.slice(0, 1) : ''
    }
    this.setData(patch)
  },

  onGender(e) {
    this.setData({ profileForm: { ...this.data.profileForm, gender: Number(e.detail.value) }, profileError: '' })
  },

  clearBirthDate() {
    this.setData({ profileForm: { ...this.data.profileForm, birthDate: '' } })
  },

  pickDiabetesPreset(e) {
    const value = e.currentTarget.dataset.value || ''
    const current = this.data.profileForm.diabetesType
    this.setData({
      profileForm: { ...this.data.profileForm, diabetesType: current === value ? '' : value },
      profileError: ''
    })
  },

  saveProfile() {
    if (this.data.saving) return
    const f = this.data.profileForm
    const phone = String(f.phone || '').trim()
    if (phone && !/^1[3-9]\d{9}$/.test(phone)) {
      this.setData({ profileError: '请填写有效的 11 位手机号，或留空。' })
      return
    }
    this.setData({ saving: true, profileError: '' })
    return api.updateProfile({
      realName: String(f.realName || '').trim(),
      phone,
      gender: f.gender,
      birthDate: f.birthDate || '',
      diabetesType: String(f.diabetesType || '').trim()
    }).then(() => {
      wx.showToast({ title: '资料已保存', icon: 'success' })
      setTimeout(() => { wx.navigateBack({ fail: () => {} }) }, 600)
    }).catch(e => {
      this.setData({ profileError: e.message })
    }).finally(() => {
      this.setData({ saving: false })
    })
  }
})
