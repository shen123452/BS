import { createRequire } from 'node:module'
import { readFile, writeFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import { chinaDate, recordFields } from '../src/lib/helpers.js'
const { chromium } = createRequire(import.meta.url)('playwright')
const output = new URL('../../.impeccable/review/',import.meta.url)
const fixtures = JSON.parse(await readFile(new URL('qa-fixtures.json',output),'utf8'))
const browser = await chromium.launch({executablePath:'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe',headless:true})
const context = await browser.newContext({viewport:{width:1440,height:1000},locale:'zh-CN',timezoneId:'Asia/Shanghai'})
const page = await context.newPage(); page.setDefaultTimeout(8000)
const checks=[], errors=[]; page.on('pageerror',e=>errors.push(e.message))
let role='ADMIN', profile={name:'联调患者（虚构）',gender:'UNKNOWN',age:null,chronicStatus:'UNKNOWN',chronicHistory:'',allergyStatus:'UNKNOWN',allergyHistory:''}, failProfile=false, failSchedule=false
const visit={...fixtures.registration,visitDate:chinaDate(),status:'IN_PROGRESS'}
let record={id:'900000002',registrationId:visit.id,doctorId:fixtures.doctor.id,status:'DRAFT',version:2,...Object.fromEntries(recordFields.map(([key,label])=>[key,`${label}：虚构文本，仅验证页面交互。`]))}
const list=items=>({items,total:items.length,page:1,pageSize:20})
await context.route('**/api/**',async route=>{
  const req=route.request(), url=new URL(req.url()), path=url.pathname.replace('/api',''), method=req.method()
  const respond=(data,status=200,code='OK',message='成功')=>route.fulfill({status,json:{code,message,data,traceId:'ui-confirm-fixture'}})
  if(path==='/auth/me') return respond({id:'fixture',displayName:role==='ADMIN'?'系统管理员':role==='DOCTOR'?fixtures.doctor.name:profile.name,role,status:'ACTIVE',patientId:fixtures.patient.patientId,doctorId:fixtures.doctor.id})
  if(path==='/patient/profile') { if(method==='PUT') { if(failProfile) return respond(null,503,'TEMPORARY_FAILURE','档案保存暂时失败'); profile=req.postDataJSON() } return respond(profile) }
  if(path==='/departments') return respond([fixtures.department])
  if(path==='/schedules') return respond(list([fixtures.schedule]))
  if(path.startsWith('/schedules/')) return failSchedule?respond(null,503,'TEMPORARY_FAILURE','场次查询暂时失败'):respond(fixtures.schedule)
  if(path==='/admin/departments') return respond(list([fixtures.department]))
  if(path==='/admin/doctors') return respond(list([fixtures.doctor]))
  if(path==='/admin/schedules'||path==='/doctor/schedules') return respond(list([fixtures.schedule]))
  if(path==='/patient/registrations') return respond(list([fixtures.registration]))
  if(path===`/patient/registrations/${fixtures.registration.id}`) return respond(fixtures.registration)
  if(path==='/doctor/registrations') {
    const status=url.searchParams.get('status')
    return respond(list(!status||status==='IN_PROGRESS'?[visit]:[]))
  }
  if(path===`/doctor/registrations/${visit.id}`) return respond({registration:visit,patient:profile})
  if(path.endsWith('/medical-record')) { if(method==='PUT') record={...record,...req.postDataJSON(),version:record.version+1}; return respond(record) }
  return respond(null,404,'NOT_FOUND','隔离验证未配置此接口')
})
async function go(path) { await page.goto(`http://127.0.0.1:5173${path}`); await page.evaluate(()=>sessionStorage.setItem('hospital.token','ui-only-fixture')); await page.goto(`http://127.0.0.1:5173${path}`); await page.locator('main h1').waitFor() }
async function capture(name,width=1440) { await page.setViewportSize({width,height:width<800?900:1000}); await page.evaluate(()=>scrollTo(0,0)); await page.screenshot({path:new URL(name,output).pathname.replace(/^\/(\w:)/,'$1'),fullPage:true}); assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),true) }
const check=msg=>{checks.push(msg);console.log(`PASS ${msg}`)}
try {
  await go('/login'); await capture('login-desktop.png'); await capture('login-mobile.png',375)
  await go('/admin/home'); await page.getByRole('heading',{name:'门诊准备'}).waitFor(); await capture('admin-desktop.png')
  await go('/admin/schedules'); await page.getByRole('cell',{name:fixtures.doctor.name,exact:true}).waitFor(); await capture('schedules-desktop.png')
  role='PATIENT'; await go('/patient/profile'); await page.locator('#patientName').waitFor()
  await page.locator('#patientName').fill('保留输入的虚构患者')
  await page.getByRole('link',{name:'预约挂号',exact:true}).click(); await page.getByRole('button',{name:'继续编辑',exact:true}).click(); assert.ok(page.url().endsWith('/profile'))
  failProfile=true; await page.getByRole('button',{name:'保存档案'}).click(); await page.getByText('档案保存暂时失败',{exact:true}).waitFor()
  await page.getByRole('button',{name:'重新加载',exact:true}).click(); await page.getByRole('button',{name:'保留当前输入',exact:true}).click(); assert.equal(await page.locator('#patientName').inputValue(),'保留输入的虚构患者')
  failProfile=false; await page.getByRole('button',{name:'保存档案'}).click(); await page.getByText('当前档案已保存',{exact:true}).waitFor()
  await page.locator('.el-message').waitFor({state:'hidden'}); await capture('patient-profile-mobile.png',375)
  check('档案未保存离开和失败重载保护')
  await go('/patient/appointments'); await page.getByRole('button',{name:'选择场次'}).waitFor()
  failSchedule=true; await page.getByRole('button',{name:'选择场次'}).click(); await page.getByText('场次查询暂时失败',{exact:true}).waitFor()
  await page.getByRole('button',{name:'查看全部可预约场次'}).count(); assert.equal(await page.getByText('场次查询暂时失败',{exact:true}).isVisible(),true)
  failSchedule=false; await page.getByRole('button',{name:'重新加载',exact:true}).click(); await page.getByRole('dialog').waitFor(); await page.getByRole('button',{name:'返回',exact:true}).click()
  check('场次选择错误保留与原场次重试')
  assert.equal(await page.getByRole('button',{name:'全部日期',exact:true}).getAttribute('aria-pressed'),'true')
  assert.equal(await page.getByRole('link',{name:'预约挂号',exact:true}).getAttribute('aria-current'),'page')
  await capture('patient-booking-desktop.png'); await capture('patient-booking-tablet.png',768); await capture('patient-booking-mobile.png',375)
  await go(`/patient/visits/${fixtures.registration.id}`); await page.getByRole('button',{name:'取消预约',exact:true}).waitFor(); await capture('patient-visit-mobile.png',375)
  check('375/768/1440截图及导航选中辅助标记')
  role='DOCTOR'; await go('/doctor/workbench'); await page.getByText('就诊说明：虚构就诊说明，仅验证系统流程',{exact:true}).waitFor(); assert.match(await page.locator('.queue-summary').innerText(),/接诊中 1/); await capture('doctor-workbench-desktop.png')
  await go('/doctor/schedules'); await page.getByRole('cell',{name:fixtures.department.name,exact:true}).waitFor(); await capture('doctor-schedules-desktop.png')
  check('医生工作台真实状态汇总和就诊说明')
  await go(`/doctor/visits/${visit.id}/record`); await page.locator('#chiefComplaint').waitFor()
  await page.locator('#treatmentPlan').scrollIntoViewIfNeeded(); const box=await page.locator('.record-identity').boundingBox(); assert.ok(box.y>=0 && box.y<50)
  await page.evaluate(()=>scrollTo(0,0)); await capture('doctor-record-desktop.png'); await capture('doctor-record-mobile.png',375)
  const mobileButtons=await page.locator('.record-actions .el-button').evaluateAll(elements=>elements.map(e=>e.getBoundingClientRect().height)); assert.ok(mobileButtons.every(h=>h>=44))
  check('长病历持续身份条、手机按钮和边界对比')
  assert.deepEqual(errors,[])
  await writeFile(new URL('browser-confirm-result.json',output),JSON.stringify({checks,errors,source:'isolated-browser-interface-fixtures',capturedAt:new Date().toISOString()},null,2))
} catch(e) { console.error(e.message);console.error((await page.locator('body').innerText()).slice(0,4000));process.exitCode=1 } finally { await browser.close() }
