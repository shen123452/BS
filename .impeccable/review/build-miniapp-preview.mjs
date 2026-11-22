import fs from 'node:fs'

function wxssToCss(file) {
  return fs.readFileSync(file, 'utf8')
    .replace(/([\d.]+)rpx/g, (_, n) => `${Number(n) / 2}px`)
    .replace(/^page\s*\{/m, 'body {')
}

const root = 'D:/sky-take-out-main/project/BS/glucose-system/patient-miniapp'
const base = wxssToCss(root + '/app.wxss')
const home = base + '\n' + wxssToCss(root + '/pages/home/index.wxss')
const login = base + '\n' + wxssToCss(root + '/pages/login/index.wxss')

const homeBody = `
<div class="page home-page">
  <div class="readout-head">
    <div class="readout-top">
      <div class="readout-title">今日血糖</div>
      <div class="readout-date">10月30日 · 周四<div class="tag tag-demo">演示数据</div></div>
    </div>
    <div class="readout-grid">
      <div class="readout-cell">
        <div class="readout-source">指尖血糖</div>
        <div class="readout-value"><span class="number">6.8</span><span class="unit">mmol/L</span></div>
        <div class="readout-time">2026-10-30 07:42:00</div>
        <div class="readout-foot">今日 3 条</div>
      </div>
      <div class="readout-cell">
        <div class="readout-source">连续监测</div>
        <div class="readout-value"><span class="number">7.1</span><span class="unit">mmol/L</span></div>
        <div class="readout-time">2026-10-30 08:05:00</div>
        <div class="readout-foot">今日 288 条</div>
      </div>
    </div>
  </div>
  <button class="btn-primary record-action">记录一次血糖</button>
  <div class="home-summary-row"><div class="summary-left"><span class="summary-label">今日记录</span><span class="summary-value mono-num">291</span><span class="summary-unit">条</span></div><button class="text-button">查看 2 条提醒</button></div>
  <div class="section-heading">今日用药</div>
  <div class="ledger-list"><div class="ledger-row medication-row"><div class="ledger-main"><div class="ledger-title">服药状态</div><div class="ledger-meta">已服用 1 项 · 未记录 1 项</div></div><button class="text-button">去记录</button></div></div>
  <div class="section-heading">最近测量</div>
  <div class="ledger-list">
    <div class="ledger-row"><div class="ledger-main"><div class="ledger-title">指尖血糖</div><div class="ledger-meta">2026-10-30 07:42:00 · 空腹</div><div class="ledger-meta">今日 3 条 · 均值 6.9 · 最低 6.2 · 最高 7.4</div></div><div class="ledger-value">6.8<small>mmol/L</small></div></div>
    <div class="ledger-row"><div class="ledger-main"><div class="ledger-title">连续监测</div><div class="ledger-meta">2026-10-30 08:05:00 · 连续监测</div><div class="ledger-meta">今日 288 条 · 均值 7.1 · 最低 4.2 · 最高 11.8</div></div><div class="ledger-value">7.1<small>mmol/L</small></div></div>
  </div>
  <div class="home-next"><div><div class="ledger-title">查看长期变化</div><div class="muted">7 / 14 / 30 天趋势与完整日统计</div></div><button class="text-button">查看分析</button></div>
</div>`

const loginBody = `
<div class="login-page">
  <div class="login-card">
    <div class="login-brand"><div class="brand-mark"><div class="brand-h"></div><div class="brand-v"></div></div><div><div class="brand-name">血糖记录</div><div class="brand-sub">患者端 · 健康管理</div></div></div>
    <div class="login-title">欢迎使用血糖记录</div>
    <div class="login-explanation">血糖与档案用于记录、分析和已授权的医生管理。智能问答会向模型服务发送问题和必要的数据摘要。</div>
    <div class="login-explanation">本系统提供健康管理参考，不替代诊断、处方或专业医疗建议。</div>
    <label class="consent-row"><span style="width:16px;height:16px;border:1px solid #C7D0DB;display:inline-block;border-radius:2px"></span><span>我已阅读并同意隐私与使用说明</span></label>
    <button class="text-button privacy-link">查看完整说明</button>
    <button class="btn-primary login-submit">同意并微信登录</button>
    <div class="login-footnote"><div>登录确认后，可直接使用问答和其他功能。</div><div>说明与确认记录可在「我的」中查看。</div></div>
  </div>
  <div class="login-footer">健康管理参考 · 不构成诊断建议</div>
</div>`

const page = (title, css, body) => `<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title}</title><style>${css}\nhtml,body{margin:0;min-height:100%;} body{width:390px;margin:0 auto;}</style></head><body>${body}</body></html>`
fs.writeFileSync('.impeccable/review/miniapp-preview-home.html', page('Home', home, homeBody))
fs.writeFileSync('.impeccable/review/miniapp-preview-login.html', page('Login', login, loginBody))
console.log('preview html written')
