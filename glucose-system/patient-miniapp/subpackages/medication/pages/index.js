const api = require('../../../api/index')
const { ensureSession } = require('../../../utils/session')
const { STATUS_LABELS, shanghaiDate, planState } = require('../../../utils/medication')

Page({
  data: {
    tab: 'records', date: '', today: '', minDate: '', loading: true, errorMsg: '',
    day: null, records: [], plans: [], closedPlans: [], showClosed: false, savingId: null
  },
  onLoad(options) { if (options && options.tab === 'plans') this.setData({ tab: 'plans' }) },
  onShow() {
    const today = shanghaiDate()
    this.setData({ today, minDate: shanghaiDate(-89), date: this.data.date || today })
    return ensureSession().then(ready => { if (ready) return this.load() })
      .catch(e => this.setData({ loading: false, errorMsg: e.message }))
  },
  onUnload() { this._loadVersion = (this._loadVersion || 0) + 1 },
  load() {
    const version = this._loadVersion = (this._loadVersion || 0) + 1
    const tab = this.data.tab
    this.setData({ loading: true, errorMsg: '' })
    const task = tab === 'plans' ? api.medicationPlans() : api.medicationDay(this.data.date)
    return task.then(result => {
      if (version !== this._loadVersion) return
      if (tab === 'plans') {
        const plans = result.map(p => ({ ...p, stateLabel: planState(p, this.data.today), ongoing: p.active && (!p.endDate || p.endDate >= this.data.today) }))
        this.setData({ plans: plans.filter(p => p.ongoing), closedPlans: plans.filter(p => !p.ongoing) })
      } else {
        this.setData({ day: result, date: result.date, records: result.records.map(r => ({ ...r, statusLabel: STATUS_LABELS[r.status] || '未记录', takenTime: (r.takenAt || '').slice(11, 16) })) })
      }
    }).catch(e => { if (version === this._loadVersion) this.setData({ errorMsg: e.message }) })
      .finally(() => { if (version === this._loadVersion) this.setData({ loading: false }) })
  },
  switchTab(e) {
    if (this.data.savingId != null) return
    this.setData({ tab: e.currentTarget.dataset.tab }); return this.load()
  },
  changeDate(e) {
    if (this.data.savingId != null) return
    this.setData({ date: e.detail.value }); return this.load()
  },
  goToday() { if (this.data.savingId == null) { this.setData({ date: shanghaiDate() }); return this.load() } },
  addPlan() { if (this.data.savingId == null) wx.navigateTo({ url: '/subpackages/medication/pages/form' }) },
  toggleClosed() { this.setData({ showClosed: !this.data.showClosed }) },
  confirm(options) {
    return new Promise(resolve => wx.showModal({ ...options, success: r => resolve(!!r.confirm), fail: () => resolve(false) }))
  },
  async changeStatus(e) {
    if (this.data.savingId != null || this.data.loading || this.data.errorMsg || !this.data.day || !this.data.day.editable) return
    const { id, status } = e.currentTarget.dataset
    const record = this.data.records.find(r => String(r.id) === String(id))
    if (!record || record.status === 'CANCELLED' || !STATUS_LABELS[status]) return
    this.setData({ savingId: id })
    try {
      const confirmed = await this.confirm({
        title: status === 'TAKEN' ? '确认已服用？' : status === 'SKIPPED' ? '记录为跳过？' : '撤销这次记录？',
        content: record.drugName + ' · ' + record.dosage + ' · ' + record.scheduledTime + (status === 'SKIPPED' ? '\n此操作仅保存状态。是否补服请咨询医生或药师。' : ''),
        confirmText: status === 'TAKEN' ? '已服用' : status === 'SKIPPED' ? '确认跳过' : '撤销记录'
      })
      if (!confirmed) return
      await api.setMedicationStatus(id, { status, version: record.version })
      await this.load()
    } catch (err) {
      this.setData({ errorMsg: err.message + '，请重新加载确认当前状态后再操作。' })
    } finally { this.setData({ savingId: null }) }
  },
  async stopPlan(e) {
    if (this.data.savingId != null || this.data.loading || this.data.errorMsg) return
    const plan = this.data.plans.find(p => String(p.id) === String(e.currentTarget.dataset.id))
    if (!plan) return
    this.setData({ savingId: plan.id })
    try {
      if (!await this.confirm({ title: '停用此记录计划？', content: plan.drugName + ' · ' + plan.scheduledTime + '\n当天未记录的项目将关闭，历史记录保留。此操作不代表医生建议停药。', confirmText: '停用计划' })) return
      await api.stopMedicationPlan(plan.id, plan.version)
      await this.load()
    } catch (err) { this.setData({ errorMsg: err.message + '，请重新加载确认。' }) }
    finally { this.setData({ savingId: null }) }
  }
})
