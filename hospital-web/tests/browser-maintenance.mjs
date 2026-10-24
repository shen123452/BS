import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
import { mkdir } from 'node:fs/promises'

// Isolated, explicitly fictional API fixtures: no database writes or provider calls.
const base = process.env.QA_FRONTEND_URL || 'http://127.0.0.1:5173'
const output = new URL('../../.impeccable/review/', import.meta.url)
await mkdir(output, { recursive:true })
const browser = await chromium.launch({ executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true })
const context = await browser.newContext({ viewport:{width:1440,height:1000}, locale:'zh-CN', timezoneId:'Asia/Shanghai' })
await context.addInitScript(() => sessionStorage.setItem('hospital.token','isolated-maintenance-fixture'))
const page = await context.newPage()
const errors = [], checks = []
page.on('pageerror', error => errors.push(error.message))
let role = 'PATIENT', deletes = 0, newRuns = 0, busyDeleted = false, rebuilds = 0, retries = 0
const sessions = [
  {id:'20',title:'历史问诊（虚构工程样例）',summaryVersion:2,summaryConfirmed:true,updatedAt:'2026-10-14T09:00:00'},
  {id:'21',title:'活动问诊（虚构工程样例）',summaryVersion:1,summaryConfirmed:true,activeOperationId:'run-live',activeOperationType:'TRIAGE',updatedAt:'2026-10-14T08:00:00'},
]
const run = id => ({ id,sessionId:id === '201' ? '21' : '20',status:id === '201' ? 'RUNNING' : 'SUCCEEDED',resultId:id === '201' ? null : `result-${id}`,createdAt:'2026-10-14T08:00:00',steps:[] })
const docs = [
  {id:'1',title:'就医资料（虚构工程样例）',sourceDescription:'仅用于隔离验证',documentVersion:'test-v1',status:'READY',enabled:true,totalChunks:1,embeddedChunks:1,taskKind:'INGEST',targetSpaceKey:'test-space',updatedAt:'2026-10-14T09:00:00'},
  {id:'2',title:'旧模型资料（虚构）',sourceDescription:'仅用于隔离验证',documentVersion:'test-v1',status:'READY',enabled:false,totalChunks:1,embeddedChunks:1,taskKind:'INGEST',targetSpaceKey:'old-space',updatedAt:'2026-10-14T09:00:00'},
]
let kbStatus = {status:'AVAILABLE',spaceKey:'test-space',candidateCount:1,rebuildTotal:0,rebuildReady:0,rebuildFailed:0}
await context.route('**/api/**', async route => {
  const request = route.request(), url = new URL(request.url()), path = url.pathname.slice(4), method = request.method()
  const respond = (data,status=200,message='成功') => route.fulfill({status,json:{code:status<400?'OK':'TEST_ERROR',message,data,traceId:'isolated-maintenance'}})
  if (path === '/auth/me') return respond({id:'fixture',role,displayName:'工程测试（虚构）',status:'ACTIVE'})
  if (path === '/patient/consultations') {
    if (method === 'POST') { const created = {id:'22',title:'新问诊',summaryVersion:0};sessions.unshift(created);return respond(created) }
    return respond({items:sessions,total:sessions.length,page:1,pageSize:100})
  }
  const consultation = path.match(/^\/patient\/consultations\/(20|21|22)(?:\/(messages|triage-runs))?$/)
  if (consultation) {
    const [,id,resource] = consultation, session = sessions.find(s => s.id === id)
    if (method === 'DELETE') { if (id === '21') busyDeleted = true; deletes++; sessions.splice(sessions.findIndex(s => s.id === id),1); return respond({deleted:true}) }
    if (!session) return respond(null,404,'问诊已删除')
    if (resource === 'messages') return respond([{id:'message-1',role:'USER',content:'仅工程测试的虚构症状描述',status:'COMPLETED'}])
    if (resource === 'triage-runs') return respond({items:id === '21' ? [run('201')] : [run('101'),run('100')],total:id === '21'?1:2,page:1,pageSize:10})
    return respond(session)
  }
  if (path === '/patient/triage-runs' && method === 'POST') { newRuns++; return respond(null,500,'历史恢复不应创建新任务') }
  if (path.match(/^\/patient\/triage-runs\/(100|101|201)$/)) return respond(run(path.split('/').at(-1)))
  if (path.startsWith('/patient/triage-results/')) return respond({id:path.split('/').at(-1),sessionId:'20',riskLevel:'ROUTINE',departmentName:'虚构工程科室',reason:'仅用于验证历史结果恢复，不可用于医疗决策。',evidenceRefs:[{chunkId:'5',title:'原始来源（虚构）',excerpt:'历史引用正文保留。',sourceDescription:'隔离验证',documentVersion:'test-v1'}],doctorOptions:[],missingInformation:[],expiresAt:'2026-10-13T08:00:00',isCurrent:false,canBook:false,unavailableReason:'此为历史结果，请重新确认症状并导诊'})
  if (path === '/admin/knowledge/documents') return respond({items:docs,total:docs.length,page:1,pageSize:20})
  if (path === '/admin/knowledge/status') return respond(kbStatus)
  if (path === '/admin/knowledge/rebuild') {
    rebuilds++; docs[0].taskKind = 'REBUILD'; docs[1].taskKind = 'REBUILD'; docs[1].status = 'FAILED'; docs[1].errorMessage = '虚构向量失败，等待重试'; docs[1].targetSpaceKey = 'test-space'
    kbStatus = {status:'MAINTENANCE',spaceKey:'test-space',candidateCount:0,rebuildTotal:2,rebuildReady:1,rebuildFailed:1}
    return respond(kbStatus,202)
  }
  if (path === '/admin/knowledge/documents/2/retry') {
    retries++; docs[1].status = 'READY'; docs[1].errorMessage = null; docs.forEach(d => d.taskKind = 'INGEST')
    kbStatus = {status:'AVAILABLE',spaceKey:'test-space',candidateCount:1,rebuildTotal:0,rebuildReady:0,rebuildFailed:0}
    return respond(docs[1],202)
  }
  if (path === '/admin/knowledge/documents/2/rebuild') { rebuilds++; docs[1].targetSpaceKey = 'test-space'; return respond(docs[1],202) }
  return respond(null,404,'隔离验证未配置此接口')
})
async function go(path) { await page.goto(base+path); await page.locator('main h1').waitFor() }
async function captures(name) {
  await page.locator('.el-message-box__wrapper').waitFor({state:'hidden'})
  for (const width of [1440,375]) {
    await page.setViewportSize({width,height:width===375?900:1000})
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true,`${name} overflow at ${width}`)
    await page.screenshot({path:fileURLToPath(new URL(`${name}-${width}.png`,output)),fullPage:true,animations:'disabled'})
  }
}
try {
  await go('/patient/consultations'); await page.getByRole('heading',{name:'历史导诊记录',exact:true}).waitFor()
  await page.getByRole('link',{name:'查看导诊记录',exact:true}).first().waitFor()
  await captures('consultation-history')
  await page.getByRole('button',{name:'新建问诊',exact:true}).click()
  await page.getByText('该问诊还没有导诊记录。',{exact:true}).waitFor()
  assert.equal(await page.getByRole('link',{name:'查看导诊记录',exact:true}).count(),0)
  assert.equal(await page.locator('#messageInput').evaluate(el => el === document.activeElement),true)
  await captures('consultation-new')
  sessions.splice(sessions.findIndex(s => s.id === '22'),1)
  await go('/patient/consultations');await page.getByRole('link',{name:'查看导诊记录',exact:true}).first().waitFor()
  checks.push('新建问诊立即清空上一会话历史，并聚焦输入')
  await page.getByRole('link',{name:'查看导诊记录',exact:true}).last().click()
  await page.getByText('历史引用正文保留。',{exact:true}).waitFor()
  assert.equal(await page.getByRole('link',{name:'查看推荐科室号源'}).count(),0)
  assert.equal(newRuns,0); await page.reload(); await page.getByText('历史引用正文保留。',{exact:true}).waitFor(); assert.equal(newRuns,0)
  checks.push('历史导诊恢复与刷新不创建新任务，旧结果禁止关联预约')
  await go('/patient/consultations'); await page.getByRole('button',{name:'删除问诊',exact:true}).click()
  await page.getByRole('button',{name:'保留记录',exact:true}).click(); assert.equal(deletes,0)
  await page.getByRole('button',{name:'删除问诊',exact:true}).click(); await page.getByRole('button',{name:'确认删除',exact:true}).click()
  await page.getByRole('link',{name:'继续查看导诊',exact:true}).waitFor(); assert.equal(deletes,1)
  assert.equal(await page.getByRole('button',{name:'删除问诊',exact:true}).isDisabled(),true)
  assert.equal(await page.locator('#messageInput').isDisabled(),true); assert.equal(busyDeleted,false)
  checks.push('删除确认可取消、成功切换会话、活动导诊禁止删除或发送消息')
  role = 'ADMIN'; await go('/admin/knowledge'); await page.getByText('知识库可用',{exact:true}).waitFor()
  await page.getByRole('button',{name:'重建知识库',exact:true}).click(); await page.getByRole('button',{name:'确认重建',exact:true}).click()
  await page.getByText('知识库重建中',{exact:true}).waitFor(); assert.equal(rebuilds,1)
  assert.equal(await page.locator('#knowledge-file').isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'停用',exact:true}).isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'上传并处理',exact:true}).isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'检索',exact:true}).isDisabled(),true)
  assert.equal(await page.getByRole('button',{name:'重试',exact:true}).isDisabled(),false)
  await page.getByText('检索暂时关闭，所有重建资料完成后恢复。',{exact:true}).waitFor()
  assert.equal(await page.getByText('请启用已就绪资料',{exact:false}).count(),0)
  await captures('knowledge-maintenance')
  await page.getByRole('button',{name:'重试',exact:true}).click(); await page.getByText('知识库可用',{exact:true}).waitFor()
  assert.equal(retries,1); assert.equal(docs[0].enabled,true)
  await page.getByRole('button',{name:'重建此资料',exact:true}).click(); await page.getByRole('button',{name:'确认重建',exact:true}).click()
  assert.equal(rebuilds,2); assert.equal(docs[1].enabled,false)
  checks.push('维护状态与进度、上传启停检索拦截、失败重试恢复、单份停用资料重建')
  assert.deepEqual(errors,[])
  console.log(JSON.stringify({checks,errors,screenshots:6},null,2))
} finally { await browser.close() }
