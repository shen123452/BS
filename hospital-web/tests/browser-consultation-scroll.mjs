import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { mkdir } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

// Fictional browser fixtures only: no database writes or paid model requests.
const output = new URL('../../.impeccable/review/', import.meta.url)
await mkdir(output, { recursive:true })
const browser = await chromium.launch({ executablePath:process.env.QA_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true })
const context = await browser.newContext({ viewport:{width:1280,height:793}, locale:'zh-CN', timezoneId:'Asia/Shanghai' })
await context.addInitScript(() => {
  sessionStorage.setItem('hospital.token', 'isolated-consultation-scroll')
  const originalFetch = window.fetch.bind(window)
  window.fetch = (input, options) => {
    const url = new URL(typeof input === 'string' ? input : input.url, location.href)
    if (options?.method === 'POST' && url.pathname.endsWith('/consultations/20/messages')) {
      const body = new ReadableStream({ start(controller) { window.fixtureStream = controller } })
      window.emitFixtureEvent = (name, data) => window.fixtureStream.enqueue(new TextEncoder().encode(`event: ${name}\ndata: ${JSON.stringify(data)}\n\n`))
      window.emitFixtureEvent('meta', { requestId:'fixture-stream', messageId:'fixture-assistant' })
      return Promise.resolve(new Response(body, { headers:{'Content-Type':'text/event-stream'} }))
    }
    return originalFetch(input, options)
  }
})
const sessions = Array.from({length:40}, (_, index) => ({
  id:String(20 + index), title:index === 0 ? '长对话滚动检查（虚构）' : `问诊记录 ${index + 1}（虚构）`,
  updatedAt:'2026-10-14T13:25:00', summaryVersion:0,
}))
const messages = Array.from({length:30}, (_, index) => ({
  id:`fixture-${index}`, role:index % 2 ? 'ASSISTANT' : 'USER', status:'COMPLETED',
  content:`虚构对话第 ${index + 1} 条，仅用于检查滚动。\n这是长消息的第二行，翻看历史时应保留阅读位置。\n这是第三行，用于检查输入框始终位于聊天区域下方。`,
}))
await context.route('**/api/**', async route => {
  const path = new URL(route.request().url()).pathname
  const respond = data => route.fulfill({json:{code:'OK',message:'成功',data}})
  if (path === '/api/auth/me') return respond({id:'fixture',role:'PATIENT',displayName:'滚动检查（虚构）',status:'ACTIVE'})
  if (path === '/api/patient/consultations') return respond({items:sessions,total:sessions.length})
  const match = path.match(/^\/api\/patient\/consultations\/(\d+)(?:\/(messages|triage-runs))?$/)
  if (match) {
    const [, id, resource] = match
    if (resource === 'messages') return respond(id === '20' ? messages : [])
    if (resource === 'triage-runs') return respond({items:id === '20' ? [{id:'history-1',status:'SUCCEEDED',createdAt:'2026-10-14T12:00:00'}] : [],total:id === '20' ? 1 : 0})
    return respond(sessions.find(session => session.id === id))
  }
  throw new Error(`Unexpected isolated request: ${path}`)
})
const page = await context.newPage(), errors = [], checks = [], measurements = []
page.on('pageerror', error => errors.push(error.message))
const box = page.getByRole('region', {name:'问诊对话',exact:true})
const gap = () => box.evaluate(element => element.scrollHeight - element.clientHeight - element.scrollTop)
const atBottom = () => page.waitForFunction(() => {
  const element = document.querySelector('.consultation-messages')
  return element && element.scrollHeight - element.clientHeight - element.scrollTop <= 1
})
const emit = (name, data) => page.evaluate(({name,data}) => window.emitFixtureEvent(name,data), {name,data})
try {
  await page.goto(`${process.env.QA_FRONTEND_URL || 'http://127.0.0.1:5173'}/patient/consultations`)
  await page.locator('.chat-message').nth(29).waitFor()
  await atBottom()
  assert.ok(await gap() <= 1)
  const initial = await page.locator('.consultation-composer').boundingBox()
  assert.ok(initial.y + initial.height <= 793, 'composer visible in the reported viewport')
  await box.focus()
  const before = await box.evaluate(element => ({top:element.scrollTop,pageY:scrollY}))
  await box.evaluate(element => {
    window.fixtureScrollEnd = false
    element.addEventListener('scrollend', () => { window.fixtureScrollEnd = true }, {once:true})
  })
  await page.keyboard.press('PageUp')
  await page.waitForFunction(() => window.fixtureScrollEnd)
  assert.ok(await box.evaluate(element => element.scrollTop) < before.top - 50)
  await page.getByRole('button',{name:'回到最新消息',exact:true}).waitFor()
  assert.equal(await page.evaluate(() => scrollY), before.pageY)
  assert.deepEqual(await page.locator('.consultation-composer').boundingBox(), initial)
  await box.hover()
  const wheelTop = await box.evaluate(element => element.scrollTop)
  await box.evaluate(element => {
    window.fixtureScrollEnd = false
    element.addEventListener('scrollend', () => { window.fixtureScrollEnd = true }, {once:true})
  })
  await page.mouse.wheel(0, -400)
  await page.waitForFunction(() => window.fixtureScrollEnd)
  assert.ok(await box.evaluate(element => element.scrollTop) < wheelTop - 50)
  await page.getByRole('button',{name:'回到最新消息',exact:true}).click()
  await atBottom()
  assert.equal(await box.evaluate(element => element === document.activeElement), true)
  checks.push('键盘、鼠标独立滚动；输入框和操作栏不随消息移动；可返回最新消息')

  for (const width of [1280,1440,768,375]) {
    await page.setViewportSize({width,height:width === 1280 ? 793 : 900})
    await box.evaluate(element => { element.scrollTop = 200; element.dispatchEvent(new Event('scroll')) })
    const measurement = await page.evaluate(() => {
      const rect = selector => { const r = document.querySelector(selector).getBoundingClientRect(); return {top:r.top,bottom:r.bottom,width:r.width,height:r.height} }
      const messages = document.querySelector('.consultation-messages'), history = document.querySelector('.consultation-history')
      return {overflow:document.documentElement.scrollWidth > innerWidth,chat:rect('.consultation-chat'),messages:rect('.consultation-messages'),composer:rect('.consultation-composer'),scrollable:messages.scrollHeight > messages.clientHeight,historyScrollable:history.scrollHeight > history.clientHeight}
    })
    assert.equal(measurement.overflow, false, `horizontal overflow at ${width}`)
    assert.equal(measurement.scrollable, true)
    assert.equal(measurement.historyScrollable, true)
    assert.ok(measurement.messages.height >= 120, `usable message height at ${width}`)
    assert.ok(measurement.messages.bottom <= measurement.composer.top + 1)
    assert.ok(measurement.composer.bottom <= measurement.chat.bottom + 1)
    measurements.push({width,...measurement})
    await page.evaluate(() => scrollTo(0,0))
    await page.screenshot({path:fileURLToPath(new URL(`consultation-scroll-${width}.png`,output)),fullPage:true,animations:'disabled'})
  }
  checks.push('桌面、平板和手机长对话及长记录列表分别滚动，没有横向溢出')

  await page.setViewportSize({width:1280,height:793})
  await page.getByRole('button',{name:'回到最新消息',exact:true}).click()
  await page.locator('#messageInput').fill('虚构测试消息，不作就医建议。')
  await page.getByRole('button',{name:'发送',exact:true}).click()
  await page.waitForFunction(() => !!window.fixtureStream)
  await atBottom()
  await emit('delta',{text:'虚构流式回答。\n'.repeat(20)})
  await page.getByText('虚构流式回答。',{exact:false}).waitFor()
  await atBottom()
  await box.evaluate(element => { element.scrollTop = 200; element.dispatchEvent(new Event('scroll')) })
  await page.getByRole('button',{name:'回到最新消息',exact:true}).waitFor()
  const readingTop = await box.evaluate(element => element.scrollTop)
  await emit('delta',{text:'继续生成的虚构内容。\n'.repeat(20)})
  await page.getByText('继续生成的虚构内容。',{exact:false}).waitFor()
  assert.ok(Math.abs(await box.evaluate(element => element.scrollTop) - readingTop) < 1, 'stream keeps historical reading position')
  await emit('done',{status:'COMPLETED'})
  await page.evaluate(() => window.fixtureStream.close())
  await page.getByRole('button',{name:'发送',exact:true}).waitFor()
  assert.ok(Math.abs(await box.evaluate(element => element.scrollTop) - readingTop) < 1, 'completion keeps historical reading position')
  await page.getByRole('button',{name:'回到最新消息',exact:true}).click()
  await atBottom()
  checks.push('发送后及底部阅读时自动跟随流式回答；向上翻阅后生成和完成均不抢走位置')

  for (const width of [768,375]) {
    await page.setViewportSize({width,height:900})
    await box.evaluate(element => { element.scrollTop = element.scrollHeight; element.dispatchEvent(new Event('scroll')) })
    await page.locator('#messageInput').fill(`虚构 ${width}px 流式检查。`)
    await page.getByRole('button',{name:'发送',exact:true}).click()
    await page.getByRole('button',{name:'停止回答',exact:true}).waitFor()
    await atBottom()
    await emit('delta',{text:`虚构 ${width}px 的新增回答。\n`.repeat(20)})
    await page.getByText(`虚构 ${width}px 的新增回答。`,{exact:false}).waitFor()
    await atBottom()
    await emit('done',{status:'COMPLETED'})
    await page.evaluate(() => window.fixtureStream.close())
    await page.getByRole('button',{name:'发送',exact:true}).waitFor()
    await atBottom()
  }
  checks.push('平板和手机回复生成及完成时仍跟随最新消息')

  await page.setViewportSize({width:1280,height:793})
  await page.getByRole('button',{name:'问诊记录 2（虚构）',exact:false}).click()
  await page.getByText('你可以描述主要不适、持续时间，以及希望了解的就医安排。',{exact:true}).waitFor()
  assert.equal(await page.locator('.chat-message').count(),0)
  assert.equal(await page.getByRole('button',{name:'回到最新消息',exact:true}).count(),0)
  assert.ok(await gap() <= 1, 'empty conversation has no unnecessary scrollbar')
  await page.screenshot({path:fileURLToPath(new URL('consultation-scroll-empty.png',output)),fullPage:true,animations:'disabled'})
  await page.getByRole('button',{name:'长对话滚动检查（虚构）',exact:false}).click()
  await page.locator('.chat-message').nth(29).waitFor()
  await atBottom()
  assert.equal(await page.getByRole('link',{name:'查看导诊记录',exact:true}).count(),1)
  checks.push('空会话、切换问诊和历史导诊链接保持正常')
  assert.deepEqual(errors,[])
  console.log(JSON.stringify({checks,measurements,errors},null,2))
} catch (error) {
  console.error(JSON.stringify({checks,measurements,errors,scroll:await box.evaluate(element => ({top:element.scrollTop,height:element.clientHeight,total:element.scrollHeight})).catch(() => null)}))
  throw error
} finally { await browser.close() }
