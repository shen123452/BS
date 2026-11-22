const api = require('../../api/index')
const { ensureSession } = require('../../utils/session')

const TYPES = [
  { value: 1, label: '空腹' }, { value: 2, label: '餐前' }, { value: 3, label: '餐后2小时' },
  { value: 4, label: '睡前' }, { value: 5, label: '随机' }, { value: 6, label: '凌晨' }
]

Page({
  data: {
    types: TYPES,
    glucose: '',
    measureType: null,
    useCustomTime: false,
    customDate: '',
    customTime: '',
    note: '',
    submitting: false,
    fieldError: '',
    successInfo: null
  },

  onShow() { return ensureSession().catch(e => this.setData({ fieldError: e.message })) },

  onInputGlucose(e) { this.setData({ glucose: e.detail.value }) },
  onInputNote(e) { this.setData({ note: e.detail.value }) },
  pickType(e) { this.setData({ measureType: Number(e.currentTarget.dataset.value) }) },
  pickNow() { this.setData({ useCustomTime: false }) },
  pickCustom() {
    const now = new Date()
    const pad = n => String(n).padStart(2, '0')
    this.setData({
      useCustomTime: true,
      customDate: this.data.customDate || now.getFullYear() + '-' + pad(now.getMonth() + 1) + '-' + pad(now.getDate()),
      customTime: this.data.customTime || pad(now.getHours()) + ':' + pad(now.getMinutes())
    })
  },
  onDateChange(e) { this.setData({ customDate: e.detail.value }) },
  onTimeChange(e) { this.setData({ customTime: e.detail.value }) },

  validate() {
    const { glucose, measureType } = this.data
    const v = Number(glucose)
    if (glucose === '' || Number.isNaN(v)) return '请输入血糖值'
    if (!/^\d+(\.\d?)?$/.test(String(glucose))) return '血糖值最多一位小数'
    if (v < 0.5 || v > 40.0) return '血糖值须在 0.5 – 40.0 mmol/L 之间'
    if (measureType == null) return '请选择测量类型(不能由系统猜测)'
    return ''
  },

  handleSubmit() {
    if (this.data.submitting) return
    const fieldError = this.validate()
    if (fieldError) { this.setData({ fieldError }); return }
    this.setData({ submitting: true, fieldError: '' })
    const d = this.data
    let measuredAt
    if (d.useCustomTime) {
      measuredAt = (d.customDate || '') + ' ' + (d.customTime || '') + ':00'
    }
    api.addGlucose({
      measuredAt,
      glucoseMmol: Number(d.glucose),
      measureType: d.measureType,
      note: d.note || undefined
    }).then(record => {
      this.setData({ successInfo: record })
    }).catch(e => {
      this.setData({ fieldError: e.message })
    }).finally(() => {
      this.setData({ submitting: false })
    })
  },

  continueRecord() {
    this.setData({ successInfo: null, glucose: '', note: '', fieldError: '' })
  },

  viewToday() { wx.switchTab({ url: '/pages/glucose/list' }) },
  goHome() { wx.switchTab({ url: '/pages/home/index' }) }
})
