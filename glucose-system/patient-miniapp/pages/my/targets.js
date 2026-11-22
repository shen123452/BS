const api = require('../../api/index')
const { ensureSession } = require('../../utils/session')

const TYPE_LABELS = ['空腹', '餐前', '餐后2小时', '睡前', '随机', '凌晨']

Page({
  data: {
    targets: null,
    loading: false,
    saving: false,
    errorMsg: '',
    targetError: '',
    enabledCount: 0,
    targetForm: { low: '', high: '' },
    targetRows: []
  },

  onShow() {
    return ensureSession().then(ready => { if (ready) return this.load() })
      .catch(e => this.setData({ loading: false, errorMsg: e.message }))
  },

  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    this.setData({ loading: true, errorMsg: '' })
    return api.getTargets().then(targets => {
      if (version !== this._loadVersion) return
      const targetRows = TYPE_LABELS.map((label, i) => {
        const r = (targets.rules || []).find(item => Number(item.measureType) === i + 1)
        return {
          label,
          measureType: i + 1,
          enabled: !!r,
          low: r ? String(r.targetLow) : '',
          high: r ? String(r.targetHigh) : '',
          basis: r?.basis || ''
        }
      })
      this.setData({
        targets,
        targetForm: { low: String(targets.cgmTargetLow), high: String(targets.cgmTargetHigh) },
        targetRows,
        enabledCount: targetRows.filter(r => r.enabled).length,
        targetError: ''
      })
    }).catch(e => {
      if (version === this._loadVersion) this.setData({ errorMsg: e.message })
    }).finally(() => {
      if (version === this._loadVersion) this.setData({ loading: false })
    })
  },

  onCgmInput(e) {
    this.setData({
      targetForm: { ...this.data.targetForm, [e.currentTarget.dataset.field]: e.detail.value },
      targetError: ''
    })
  },

  onRuleInput(e) {
    const { index, field } = e.currentTarget.dataset
    const targetRows = this.data.targetRows.map((r, i) => i === Number(index) ? { ...r, [field]: e.detail.value } : r)
    this.setData({
      targetRows,
      enabledCount: targetRows.filter(r => r.enabled).length,
      targetError: ''
    })
  },

  saveTargets() {
    if (this.data.saving) return
    const valid = (low, high) =>
      low !== '' && high !== '' &&
      Number.isFinite(Number(low)) && Number.isFinite(Number(high)) &&
      Number(low) >= 0.5 && Number(high) <= 40 && Number(low) < Number(high)
    const f = this.data.targetForm
    if (!valid(f.low, f.high) || this.data.targetRows.some(r => r.enabled && !valid(r.low, r.high))) {
      this.setData({ targetError: '目标须满足 0.5 ≤ 下限 < 上限 ≤ 40 mmol/L。' })
      return
    }
    this.setData({ saving: true, targetError: '' })
    return api.updateTargets({
      targetRevision: this.data.targets.targetRevision,
      cgmTargetLow: Number(f.low),
      cgmTargetHigh: Number(f.high),
      rules: this.data.targetRows.map(r => r.enabled
        ? { measureType: r.measureType, targetLow: Number(r.low), targetHigh: Number(r.high), basis: r.basis }
        : { measureType: r.measureType, remove: true })
    }).then(() => {
      wx.showToast({ title: '目标已更新', icon: 'success' })
      return this.load()
    }).catch(e => {
      this.setData({ targetError: e.message + '。输入已保留；如提示版本冲突，请下拉或重新进入核对。' })
    }).finally(() => {
      this.setData({ saving: false })
    })
  }
})
