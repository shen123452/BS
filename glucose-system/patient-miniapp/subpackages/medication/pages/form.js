const api = require('../../../api/index')
const { ensureSession } = require('../../../utils/session')
const { shanghaiDate } = require('../../../utils/medication')

Page({
  data: { form: { drugName: '', dosage: '', scheduledTime: '', startDate: '', endDate: '', instruction: '' }, today: '', saving: false, ready: false, errorMsg: '' },
  onShow() {
    const today = shanghaiDate()
    this.setData({ today, form: { ...this.data.form, startDate: this.data.form.startDate || today } })
    return ensureSession().then(ready => this.setData({ ready: !!ready })).catch(e => this.setData({ errorMsg: e.message }))
  },
  input(e) {
    if (this.data.saving) return
    this._requestId = null
    this.setData({ form: { ...this.data.form, [e.currentTarget.dataset.field]: e.detail.value }, errorMsg: '' })
  },
  clearEnd() { if (!this.data.saving) { this._requestId = null; this.setData({ form: { ...this.data.form, endDate: '' } }) } },
  save() {
    if (this.data.saving || !this.data.ready) return
    const f = this.data.form
    if (!f.drugName.trim() || !f.dosage.trim() || !f.scheduledTime || !f.startDate || (f.endDate && f.endDate < f.startDate)) {
      this.setData({ errorMsg: '请填写药名、单次用量与每日时间，结束日期不能早于开始日期。' }); return
    }
    this._requestId = this._requestId || 'med-' + Date.now() + '-' + Math.random().toString(36).slice(2, 12)
    this.setData({ saving: true, errorMsg: '' })
    return api.addMedicationPlan({ ...f, drugName: f.drugName.trim(), dosage: f.dosage.trim(), instruction: f.instruction.trim(), endDate: f.endDate || null, requestId: this._requestId })
      .then(() => { this._completed = true; wx.navigateBack({ fail: () => wx.redirectTo({ url: '/subpackages/medication/pages/index?tab=plans' }) }) })
      .catch(e => this.setData({ errorMsg: e.message + '。可重试本次提交，或返回查看计划。' }))
      .finally(() => this.setData({ saving: !!this._completed }))
  }
})
