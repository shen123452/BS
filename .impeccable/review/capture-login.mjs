import fs from 'node:fs/promises'
const base = 'http://127.0.0.1:5174'
const out = new URL('./', import.meta.url)
const target = await (await fetch('http://127.0.0.1:9223/json/new?about:blank', { method: 'PUT' })).json()
const ws = new WebSocket(target.webSocketDebuggerUrl)
await new Promise(resolve => ws.addEventListener('open', resolve, { once: true }))
let seq = 0
const pending = new Map()
ws.addEventListener('message', event => { const msg = JSON.parse(event.data); if (msg.id) { const p = pending.get(msg.id); if (p) { pending.delete(msg.id); msg.error ? p.reject(msg.error) : p.resolve(msg.result) } } })
function send(method, params = {}) { return new Promise((resolve, reject) => { const id = ++seq; pending.set(id, { resolve, reject }); ws.send(JSON.stringify({ id, method, params })) }) }
await send('Page.enable')
await send('Runtime.enable')
await send('Fetch.enable', { patterns: [{ urlPattern: base + '/api/*' }] })
ws.addEventListener('message', async event => {
  const msg = JSON.parse(event.data)
  if (msg.method === 'Fetch.requestPaused') {
    const { requestId } = msg.params
    const data = { captchaId: 'visual-only', image: 'data:image/svg+xml;base64,' + Buffer.from('<svg xmlns="http://www.w3.org/2000/svg" width="104" height="40"><rect width="104" height="40" fill="#E6ECE6"/><text x="14" y="27" font-size="23" fill="#174A44">DEMO</text></svg>').toString('base64') }
    await send('Fetch.fulfillRequest', { requestId, responseCode: 200, responseHeaders: [{ name: 'Content-Type', value: 'application/json' }], body: Buffer.from(JSON.stringify({ code: 0, data })).toString('base64') })
  }
})
await send('Page.addScriptToEvaluateOnNewDocument', { source: "localStorage.removeItem('accessToken')" })
for (const [name, width, height] of [['desktop-login', 1440, 900], ['mobile-login', 390, 844]]) {
  await send('Emulation.setDeviceMetricsOverride', { width, height, deviceScaleFactor: 1, mobile: width < 600 })
  await send('Page.navigate', { url: base + '/login' })
  await new Promise(r => setTimeout(r, 1200))
  const shot = await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })
  await fs.writeFile(new URL(`${name}.png`, out), Buffer.from(shot.data, 'base64'))
}
await send('Page.close'); ws.close()
console.log('login captured')
