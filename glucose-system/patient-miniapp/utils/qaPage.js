const api = require('../api/index')
const { ensureSession } = require('./session')
const MODE_TEXT = { RAG: '有据回答', RETRIEVAL_ONLY: '资料摘录', NO_COVERAGE: '暂未找到依据', FIXED_NOTICE: '提示', HUMAN: '医生回复' }

function createQaPage(isTab) {
  return {
    data: {
      isTab, sessionId: '', messages: [], input: '', generating: false, loading: false,
      errorMsg: '', scrollInto: '', pendingRequest: null, visibleSources: null,
      sourceLoading: false, knowledgeHint: '',
      suggestions: [
        '血糖是什么',
        '发生低血糖时应该怎么处理',
        '连续监测 CGM 和指尖血糖有什么区别',
        '糖尿病日常饮食与运动有哪些注意事项'
      ]
    },
    onLoad(options = {}) { this.setData({ sessionId: options.sessionId || '' }) },
    onShow() {
      return ensureSession().then(ready => {
        if (!ready) return
        this.loadAvailability()
        if (this.data.sessionId && !this.data.generating) return this.loadMessages()
      }).catch(e => this.setData({ errorMsg: e.message }))
    },
    loadAvailability() {
      return api.qaAvailability().then(status => this.setData({
        knowledgeHint: !status.knowledgeReady ? '知识资料准备中。你仍可使用记录和分析功能，资料补齐后即可在这里提问。'
          : !status.modelReady ? '当前提供资料摘录，可查看原文和来源。' : ''
      })).catch(() => {})
    },
    onInput(e) { this.setData({ input: e.detail.value }) },
    chooseQuestion(e) {
      if (this.data.generating) return
      this.setData({ input: e.currentTarget.dataset.question })
    },
    history() { wx.navigateTo({ url: '/subpackages/qa/pages/sessions' }) },
    newConversation() {
      if (this.data.generating || this.data.pendingRequest) {
        this.setData({ errorMsg: '请先查看上一条问题的结果，再开始新对话' }); return
      }
      this.setData({ sessionId: '', messages: [], input: '', errorMsg: '', visibleSources: null, loading: false })
    },
    loadMessages() {
      if (!this.data.sessionId) return Promise.resolve()
      this.setData({ loading: true })
      return api.messages(this.data.sessionId).then(messages => {
        this.setData({
          messages: messages.map(m => ({
            ...m, isUser: m.role === 'user', isHuman: m.answerMode === 'HUMAN',
            hasSources: m.role === 'assistant' && m.status === 'COMPLETED' && m.hasSources === true && m.answerMode !== 'NO_COVERAGE',
            modeText: MODE_TEXT[m.answerMode] || '',
            isProcessing: m.status === 'PROCESSING',
            isStreaming: false
          })),
          scrollInto: 'msg-bottom', loading: false, errorMsg: ''
        })
      }).catch(e => this.setData({ errorMsg: e.message, loading: false }))
    },
    send() {
      const question = (this.data.input || '').trim()
      if (!question || this.data.generating || this.data.loading) return
      if (this.data.pendingRequest) { this.setData({ errorMsg: '请先查看上一条问题的结果' }); return }
      if (question.length > 500) { this.setData({ errorMsg: '问题不能超过 500 字' }); return }
      const payload = { sessionId: this.data.sessionId, requestId: 'ask-' + Date.now() + '-' + Math.floor(Math.random() * 100000), question }
      this.setData({
        input: '', errorMsg: '', generating: true, pendingRequest: payload,
        messages: this.data.messages.concat([
          { messageId: 'local-user', role: 'user', isUser: true, content: question, status: 'COMPLETED' },
          { messageId: 'local-processing', role: 'assistant', isUser: false, status: 'PROCESSING', isProcessing: true, isStreaming: false, content: '' }
        ]), scrollInto: 'msg-bottom'
      })
      return this.submitRequest(payload)
    },
    retry() {
      if (this.data.generating || !this.data.pendingRequest) return
      const hasPlaceholder = this.data.messages.some(m => m.messageId === 'local-processing')
      const nextMessages = hasPlaceholder ? this.data.messages : this.data.messages.concat([
        { messageId: 'local-processing', role: 'assistant', isUser: false, status: 'PROCESSING', isProcessing: true, isStreaming: false, content: '' }
      ])
      this.setData({ generating: true, errorMsg: '', messages: nextMessages, scrollInto: 'msg-bottom' })
      return this.submitRequest(this.data.pendingRequest)
    },
    updateStreamMessage(updater) {
      const messages = this.data.messages.map(m => {
        if (m.messageId !== 'local-processing') return m
        return updater(m)
      })
      this.setData({ messages, scrollInto: 'msg-bottom' })
    },
    async submitRequest(payload) {
      try {
        if (!payload.sessionId) {
          const session = await api.createSession(payload.question.slice(0, 30))
          payload = { ...payload, sessionId: String(session.sessionId) }
          this.setData({ sessionId: payload.sessionId, pendingRequest: payload })
        }
        const result = typeof api.askStream === 'function'
          ? await api.askStream(payload, (eventName, eventData) => {
            if (eventName === 'delta' && eventData && typeof eventData.text === 'string' && eventData.text) {
              this.updateStreamMessage(m => ({
                ...m,
                isProcessing: false,
                isStreaming: true,
                content: (m.content || '') + eventData.text
              }))
            } else if (eventName === 'replace' && eventData && typeof eventData.text === 'string') {
              this.updateStreamMessage(m => ({
                ...m,
                isProcessing: false,
                isStreaming: true,
                content: eventData.text
              }))
            }
          })
          : await api.ask(payload)
        if (result && result.status === 'COMPLETED') this.setData({ pendingRequest: null })
        await this.loadMessages()
      } catch (e) {
        this.setData({
          messages: this.data.messages.filter(m => m.messageId !== 'local-processing'),
          errorMsg: e.message + '，可重试查看结果'
        })
      } finally { this.setData({ generating: false }) }
    },
    viewSources(e) {
      const message = this.data.messages.find(m => String(m.messageId) === String(e.currentTarget.dataset.id))
      if (!message?.hasSources || this.data.sourceLoading) return
      const version = this._sourceVersion = (this._sourceVersion || 0) + 1
      this.setData({ sourceLoading: true, visibleSources: [] })
      return api.sources(e.currentTarget.dataset.id).then(visibleSources => {
        if (version === this._sourceVersion) this.setData({ visibleSources })
      }).catch(e => {
        if (version === this._sourceVersion) this.setData({ visibleSources: null, errorMsg: e.message })
      }).finally(() => { if (version === this._sourceVersion) this.setData({ sourceLoading: false }) })
    },
    closeSources() { this._sourceVersion = (this._sourceVersion || 0) + 1; this.setData({ visibleSources: null, sourceLoading: false }) },
    stopTap() {},
    copySource(e) {
      const source = (this.data.visibleSources || [])[Number(e.currentTarget.dataset.index)]
      if (source?.sourceUrl) wx.setClipboardData({ data: source.sourceUrl })
    }
  }
}
module.exports = { createQaPage }
