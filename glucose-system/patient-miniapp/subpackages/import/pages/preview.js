const { uploadFile } = require('../../../utils/request')
const api = require('../../../api/index')
const { ensureSession } = require('../../../utils/session')
const STATUS_MAP = { READY: ['tag-warn', '待确认'], CONFIRMED: ['tag-success', '已导入'], NO_CHANGE: ['tag-info', '无新增'], EXPIRED: ['tag-muted', '已过期'], REVOKED: ['tag-muted', '已撤销'] }
function decorate(batch) {
  const status = STATUS_MAP[batch.status] || ['tag-muted', batch.status || '状态未知']
  return { ...batch, statusClass: status[0], statusText: status[1], errors: batch.errors || [], revocable: batch.status === 'CONFIRMED' }
}
Page({
  data: { step: 1, metaConfirmed: false, uploading: false, busyId: '', errorMsg: '', feedback: '', preview: null, batches: [], batchesLoading: false, batchesError: '' },
  onLoad() { return ensureSession().then(ready => { if (ready) return this.loadBatches() }).catch(e => this.setData({ errorMsg: e.message })) },
  onUnload() { this._batchVersion = (this._batchVersion || 0) + 1 },
  toggleMeta() { if (!this.data.uploading) this.setData({ metaConfirmed: !this.data.metaConfirmed }) },
  copyTemplate() { wx.setClipboardData({ data: 'measured_at,glucose_mmol\n2026-10-18 00:00:00,8.1\n2026-10-18 00:05:00,8.3', success: () => wx.showToast({ title: '模板已复制', icon: 'success' }) }) },
  chooseAndUpload() {
    if (this.data.uploading || this.data.busyId) return
    if (!this.data.metaConfirmed) { this.setData({ errorMsg: '请先确认数据单位、时区与采样间隔。' }); return }
    this.setData({ uploading: true, errorMsg: '', feedback: '' })
    wx.chooseMessageFile({ count: 1, type: 'file', extension: ['csv'], success: res => {
      const file = res.tempFiles[0]
      if (!file || file.size > 5 * 1024 * 1024) { this.setData({ uploading: false, errorMsg: '请选择不超过 5 MB 的 CSV 文件。' }); return }
      const requestId = 'imp-' + Date.now() + '-' + Math.floor(Math.random() * 10000)
      uploadFile({ url: '/api/app/glucose/import/preview?requestId=' + requestId + '&metaConfirmed=true', filePath: file.path })
        .then(preview => { this.setData({ preview: decorate(preview), step: 3 }); return this.loadBatches() })
        .catch(e => this.setData({ errorMsg: e.message })).finally(() => this.setData({ uploading: false }))
    }, fail: e => this.setData({ uploading: false, errorMsg: /cancel/.test(e.errMsg || '') ? '' : '未能选择文件，请重试。' }) })
  },
  confirmBatch(e) {
    if (this.data.busyId || this.data.uploading) return
    const batch = e?.currentTarget?.dataset?.batch || this.data.preview
    if (!batch || batch.status !== 'READY' || batch.errorRows > 0 || batch.conflictRows > 0) return
    const id = String(batch.batchId || batch.id)
    this.setData({ busyId: id, errorMsg: '', feedback: '' })
    wx.showModal({ title: '确认导入', content: '将新增 ' + Math.max(0, batch.validRows - batch.duplicateRows - batch.conflictRows) + ' 条有效数据，确认导入？',
      success: async res => {
        if (!res.confirm) { this.setData({ busyId: '' }); return }
        try {
          const result = await api.confirmBatch(id)
          if (this.data.preview && String(this.data.preview.batchId || this.data.preview.id) === id) this.setData({ preview: decorate({ ...this.data.preview, ...result }) })
          this.setData({ feedback: '导入完成，新增 ' + (result.insertedRows || 0) + ' 条数据。' })
          await this.loadBatches()
        } catch (err) { this.setData({ errorMsg: err.message }) }
        finally { this.setData({ busyId: '' }) }
      }, fail: () => this.setData({ busyId: '' }) })
  },
  revokeBatch(e) {
    if (this.data.busyId || this.data.uploading) return
    const id = String(e.currentTarget.dataset.id)
    this.setData({ busyId: id, errorMsg: '', feedback: '' })
    wx.showModal({ title: '撤销批次', content: '将删除本批次导入的数据，其他批次不受影响。确认撤销？', confirmColor: '#B93838',
      success: async res => {
        if (!res.confirm) { this.setData({ busyId: '' }); return }
        try {
          await api.revokeBatch(id)
          if (this.data.preview && String(this.data.preview.batchId || this.data.preview.id) === id) this.setData({ preview: decorate({ ...this.data.preview, status: 'REVOKED' }) })
          this.setData({ feedback: '本批数据已撤销。' }); await this.loadBatches()
        } catch (err) { this.setData({ errorMsg: err.message }) }
        finally { this.setData({ busyId: '' }) }
      }, fail: () => this.setData({ busyId: '' }) })
  },
  backToUpload() { if (!this.data.busyId) this.setData({ step: 1, preview: null, errorMsg: '', feedback: '' }) },
  loadBatches() {
    const version = this._batchVersion = (this._batchVersion || 0) + 1
    this.setData({ batchesLoading: true, batchesError: '' })
    return api.importBatches().then(batches => { if (version === this._batchVersion) this.setData({ batches: (batches || []).map(decorate) }) })
      .catch(e => { if (version === this._batchVersion) this.setData({ batchesError: e.message }) })
      .finally(() => { if (version === this._batchVersion) this.setData({ batchesLoading: false }) })
  }
})
