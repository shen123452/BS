import { chromium } from 'playwright'
import assert from 'node:assert/strict'
import { fileURLToPath } from 'node:url'
const base = process.env.QA_FRONTEND_URL || 'http://127.0.0.1:5173'
const output = new URL('../../.impeccable/review/', import.meta.url)
const browser = await chromium.launch({ executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true })
const context = await browser.newContext({ viewport:{width:1440,height:1000},locale:'zh-CN',timezoneId:'Asia/Shanghai' })
await context.addInitScript(() => sessionStorage.setItem('hospital.token','isolated-ai-ui-fixture'))
const page = await context.newPage()
const errors = [], checks = []
page.on('pageerror', e => errors.push(e.message))
let role='ADMIN', documents=[
  {id:'1',title:'呼吸科资料（虚构工程样例）',sourceDescription:'仅用于隔离界面验证',documentVersion:'test-v1',status:'READY',enabled:false,totalChunks:21,embeddedChunks:21,updatedAt:'2026-10-13T09:00:00'},
  {id:'2',title:'失败资料（虚构）',sourceDescription:'仅用于验证失败状态',documentVersion:'test-v1',status:'FAILED',enabled:false,totalChunks:0,embeddedChunks:0,errorMessage:'文件没有可提取文本',updatedAt:'2026-10-13T09:00:00'}
], polls=0, cancelled=false, submitted=null, booked=null, uploadCount=0, failSummary=false
const session={id:'20',title:'工程测试咳嗽',summaryVersion:1,summaryJson:null,summaryConfirmed:false}
const schedule={id:'50',doctorId:'8',departmentId:'7',doctorName:'工程测试医生（虚构）',departmentName:'工程测试呼吸科',visitDate:'2026-10-14',timeSlot:'PM',bookingDeadline:'2026-10-14T13:30:00',remainingCount:5,totalCount:10,fee:10,bookable:true}
const result={id:'200',sessionId:'20',riskLevel:'ROUTINE',validationStatus:'VALID',departmentId:'7',departmentName:'工程测试呼吸科',alternativeDepartmentIds:[],reason:'这是隔离验证的虚构导诊结果，不能用于医学结论。',evidenceRefs:[{chunkId:'5',title:'资料来源（虚构）',sourceDescription:'仅工程验证',documentVersion:'test-v1',paragraphStart:1,excerpt:'用于验证引用内容显示。'}],doctorOptions:[schedule],missingInformation:[],expiresAt:'2026-10-14T09:00:00',isCurrent:true,canBook:true,unavailableReason:''}
await context.route('**/api/**',async route => {
  const request=route.request(), url=new URL(request.url()), path=url.pathname.slice(4), method=request.method()
  const respond=(data,status=200,message='成功') => route.fulfill({status,json:{code:status<400?'OK':'TEST_ERROR',message,data,traceId:'isolated-ai-ui'}})
  if(path==='/auth/me')return respond({id:'fixture',role,displayName:'工程测试（虚构）',patientId:'3',status:'ACTIVE'})
  if(path==='/admin/knowledge/documents'){
    if(method==='POST'){assert.ok(request.headers()['content-type'].includes('multipart/form-data'));assert.ok(request.postData().includes('test-document.txt'));uploadCount++;return respond({document:documents[0],duplicate:false},202)}
    return respond({items:documents,total:documents.length,page:1,pageSize:20})
  }
  if(path==='/admin/knowledge/status')return respond({status:documents.some(d=>d.enabled)?'AVAILABLE':'UNAVAILABLE',spaceKey:'isolated-test-space',candidateCount:21})
  if(path.endsWith('/enabled')){documents[0].enabled=request.postDataJSON().enabled;return respond(documents[0])}
  if(path.endsWith('/retry')){documents[1].status='QUEUED';documents[1].errorMessage=null;return respond(documents[1],202)}
  if(path.endsWith('/chunks')){const next=url.searchParams.get('page')==='2';return respond({items:[{id:next?'21':'1',chunkNo:next?21:1,charCount:15,content:next?'最后一段（虚构）':'第一段（虚构）'}],total:21,page:next?2:1,pageSize:20})}
  if(path==='/admin/knowledge/search')return respond({hits:[{chunkId:'5',title:'资料来源（虚构）',sourceDescription:'隔离验证',documentVersion:'test-v1',score:0.82,excerpt:'咳嗽相关的虚构工程片段'}]})
  if(path==='/patient/consultations')return respond({items:[session],total:1,page:1,pageSize:100})
  if(path==='/patient/consultations/20')return respond(session)
  if(path==='/patient/consultations/20/triage-runs')return respond({items:[],total:0,page:1,pageSize:10})
  if(path==='/patient/consultations/20/messages')return respond([{id:'1',role:'USER',content:'工程测试咳嗽',status:'COMPLETED'}])
  if(path==='/patient/consultations/20/summary'){
    submitted=request.postDataJSON()
    if(failSummary)return respond(null,503,'摘要保存暂时失败')
    session.summaryVersion++;session.summaryJson=JSON.stringify(submitted.summary);session.summaryConfirmed=true;return respond(session)
  }
  if(path==='/patient/triage-runs'){polls=0;cancelled=false;return respond({run:{id:'100',status:'QUEUED'},pollAfterMs:2000},202)}
  if(path==='/patient/triage-runs/100'){
    polls++;return respond({id:'100',sessionId:'20',status:cancelled?'CANCELLED':polls>1?'SUCCEEDED':'RUNNING',resultId:!cancelled&&polls>1?'200':null,steps:[{sequenceNo:1,roundNo:1,stepType:'TOOL',toolName:'searchMedicalKnowledge',publicLabel:'正在检索导诊知识',status:'SUCCEEDED',durationMs:100}]})
  }
  if(path==='/patient/triage-runs/100/cancel'){cancelled=true;return respond({cancelled:true})}
  if(path==='/patient/triage-results/200')return respond(result)
  if(path==='/patient/profile')return respond({name:'测试患者（虚构）'})
  if(path==='/departments')return respond([{id:'7',name:'工程测试呼吸科'},{id:'9',name:'其他测试科室'}])
  if(path==='/schedules')return respond({items:[schedule],total:1,page:1,pageSize:12})
  if(path==='/schedules/50')return respond(schedule)
  if(path==='/patient/registrations'&&method==='POST'){booked=request.postDataJSON();return respond({id:'500',registrationNo:'虚构测试号'},201)}
  if(path==='/patient/registrations/500')return respond({...schedule,id:'500',status:'WAITING',registrationNo:'虚构测试号',patientName:'测试患者（虚构）'})
  return respond(null,404,'隔离验证未配置此接口')
})
async function go(path){await page.goto(base+path);await page.locator('main h1').waitFor()}
async function capture(name,width){await page.setViewportSize({width,height:width<800?900:1000});await page.evaluate(()=>scrollTo(0,0));await page.screenshot({path:fileURLToPath(new URL(name,output)),fullPage:true});assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true)}
try{
  await go('/admin/knowledge')
  await page.locator('#knowledge-title').fill('测试上传（虚构）');await page.locator('#knowledge-version').fill('test-v1');await page.locator('#knowledge-source').fill('隔离界面验证')
  await page.locator('#knowledge-file').setInputFiles({name:'test-document.txt',mimeType:'text/plain',buffer:Buffer.from('测试文本')})
  await page.getByRole('button',{name:'上传并处理',exact:true}).click()
  await page.getByText('资料已上传，后台开始处理。',{exact:true}).waitFor();assert.equal(uploadCount,1)
  await page.getByRole('button',{name:'查看片段',exact:true}).first().click()
  await page.getByText('片段 1',{exact:true}).waitFor()
  await page.locator('.el-dialog .btn-next').click()
  await page.getByText('最后一段（虚构）',{exact:true}).waitFor();await page.locator('.el-dialog__headerbtn').click()
  await page.getByRole('button',{name:'启用',exact:true}).click();await page.getByRole('button',{name:'确认启用',exact:true}).click()
  await page.getByText('可检索',{exact:true}).waitFor()
  await page.getByPlaceholder('例如：咳嗽伴发热应该先挂哪个科室？').fill('咳嗽')
  await page.getByRole('button',{name:'检索',exact:true}).click();await page.getByText('咳嗽相关的虚构工程片段',{exact:true}).waitFor()
  await page.getByRole('button',{name:'重试',exact:true}).click();await page.getByText('待处理',{exact:true}).waitFor()
  await capture('knowledge-desktop.png',1440);await capture('knowledge-mobile.png',375)
  checks.push('知识上传、启用、失败重试、检索、片段分页、桌面及手机')
  role='PATIENT';await go('/patient/consultations');await page.getByRole('link',{name:'核对症状并导诊'}).click()
  await page.locator('#triage-duration').fill('三天');await page.locator('#triage-severity').fill('轻微')
  for(const key of ['chestPain','severeBreathing','unconscious','majorBleeding','paralysis'])await page.locator('.risk-row').filter({has:page.locator('#risk-'+key)}).locator('label.el-radio').filter({hasText:/^无$/}).click()
  await capture('triage-summary-desktop.png',1440);await capture('triage-summary-mobile.png',375)
  failSummary=true;await page.getByRole('button',{name:'确认并开始导诊'}).click();await page.getByText('摘要保存暂时失败',{exact:true}).waitFor();assert.equal(await page.locator('#triage-duration').inputValue(),'三天')
  failSummary=false;await page.getByRole('button',{name:'确认并开始导诊'}).click()
  await page.getByRole('heading',{name:'工程测试呼吸科',exact:true}).waitFor()
  assert.equal(submitted.summary.riskAnswers.chestPain,'NO')
  await capture('triage-result-desktop.png',1440);await capture('triage-result-mobile.png',375)
  await page.getByRole('link',{name:'查看推荐科室号源'}).click();await page.getByRole('button',{name:'选择场次'}).click()
  await page.getByRole('button',{name:'确认预约',exact:true}).click();await page.waitForURL('**/patient/visits/500')
  assert.equal(booked.triageResultId,'200');checks.push('摘要确认、失败保留输入、导诊轮询、来源及预约关联')
  polls=0;cancelled=false;await go('/patient/triage/100');await page.getByRole('button',{name:'停止导诊',exact:true}).click();await page.getByRole('heading',{name:'已停止',exact:true}).waitFor()
  checks.push('取消后停止轮询，不显示成功结果')
  assert.deepEqual(errors,[]);console.log(JSON.stringify({checks,errors},null,2))
}finally{await browser.close()}
