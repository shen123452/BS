import { createRequire } from 'node:module'
import { mkdir, writeFile, readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import { chinaDate, recordFields } from '../src/lib/helpers.js'
const { chromium } = createRequire(import.meta.url)('playwright')
const base = 'http://127.0.0.1:5173'
const output = new URL('../../.impeccable/review/', import.meta.url)
await mkdir(output, { recursive:true })
if (!process.env.QA_ADMIN_PASSWORD) throw new Error('需要本机 QA_ADMIN_PASSWORD 环境变量，不在文件中保存管理员密码')
const browser = await chromium.launch({ executablePath: process.env.QA_BROWSER_PATH || 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe', headless:true })
const context = await browser.newContext({ viewport:{ width:1440, height:1000 }, locale:'zh-CN', timezoneId:'Asia/Shanghai' })
const page = await context.newPage()
page.setDefaultTimeout(8000)
const runtimeErrors = [], checks = [], fixtures = {}, suffix = Date.now().toString(36)
const password = `Qa_${crypto.randomUUID()}`, doctorUsername = `qa_doc_${suffix}`, patientUsername = `qa_pat_${suffix}`
const departmentName = `联调科室（虚构）${suffix}`, doctorName = `联调医生（虚构）${suffix}`
page.on('pageerror', error => runtimeErrors.push(error.message))
const check = message => { checks.push(message); console.log(`PASS ${message}`) }
async function persist() { await writeFile(new URL('qa-fixtures.json', output), JSON.stringify(fixtures,null,2)) }
async function capture(name, width = 1440) { await page.locator('.el-message').last().waitFor({state:'hidden',timeout:10000}); await page.setViewportSize({ width, height: width < 800 ? 900 : 1000 }); await page.evaluate(()=>scrollTo(0,0)); await page.screenshot({ path:new URL(name,output).pathname.replace(/^\/(\w:)/,'$1'), fullPage:true }) }
async function noOverflow() { assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth),true) }
async function login(username, secret) { await page.goto(`${base}/login`); await page.locator('#username').fill(username); await page.locator('#password').fill(secret); await page.locator('button.auth-submit').click(); await page.waitForURL(url => url.pathname !== '/login') }
async function logout() { await page.getByRole('button',{name:'退出登录'}).click(); await page.waitForURL('**/login') }
async function select(id, label) { await page.locator('.el-select').filter({ has:page.locator(`#${id}`) }).locator('.el-select__wrapper').click(); await page.getByRole('option',{ name:label, exact:true }).click() }
async function saved(method, path, button) {
  const response = page.waitForResponse(r => new URL(r.url()).pathname === `/api${path}` && r.request().method() === method)
  await page.getByRole('button',{name:button,exact:true}).click()
  const result = await response, body = await result.json()
  assert.equal(result.ok(),true, `${button}: ${body.message}`)
  return body.data
}
try {
  if (process.argv.includes('--record-only')) {
    Object.assign(fixtures,JSON.parse(await readFile(new URL('qa-fixtures.json',output),'utf8')))
    const previous = JSON.parse(await readFile(new URL('browser-failure.json',output),'utf8'))
    checks.push(...previous.checks)
    await login('admin',process.env.QA_ADMIN_PASSWORD)
    await page.route('**/api/auth/me',route => route.fulfill({json:{code:'OK',message:'成功',data:{id:fixtures.doctor.userId,username:'fixture-doctor',displayName:fixtures.doctor.name,role:'DOCTOR',status:'ACTIVE',doctorId:fixtures.doctor.id,patientId:null}}}))
  } else {
  await page.goto(`${base}/login`)
  await capture('login-desktop.png'); await capture('login-mobile.png',375); await noOverflow(); await page.setViewportSize({width:1440,height:1000})
  await login('admin',process.env.QA_ADMIN_PASSWORD)
  await page.getByRole('heading',{name:'门诊准备'}).waitFor()
  await capture('admin-desktop.png')
  check('管理员登录与真实空状态')
  await page.getByRole('link',{name:'管理科室',exact:true}).click()
  await page.getByRole('button',{name:'新增科室'}).click()
  await page.locator('#deptCode').fill(`QA_${suffix}`)
  await page.locator('#deptName').fill(departmentName)
  await page.locator('#deptLocation').fill('测试楼一层')
  await page.locator('#deptDescription').fill('仅用于页面联调验证的虚构科室')
  fixtures.department = await saved('POST','/admin/departments','保存科室'); await persist()
  await page.getByRole('dialog').waitFor({state:'hidden'})
  check('页面新增科室')
  await page.getByRole('link',{name:'医生管理',exact:true}).click()
  await page.getByRole('button',{name:'新增医生'}).click()
  await page.locator('#doctorUsername').fill(doctorUsername); await page.locator('#doctorPassword').fill(password)
  await page.locator('#doctorName').fill(doctorName)
  await select('doctorDepartment',departmentName)
  await page.locator('#doctorTitle').fill('主治医师')
  fixtures.doctor = await saved('POST','/admin/doctors','保存医生'); await persist()
  await page.getByRole('dialog').waitFor({state:'hidden'})
  check('页面创建医生账号')
  await page.getByRole('link',{name:'排班管理',exact:true}).click()
  await page.getByRole('button',{name:'新增排班'}).click()
  await select('editScheduleDoctor',`${doctorName} · ${departmentName}`)
  fixtures.schedule = await saved('POST','/admin/schedules','保存排班'); await persist()
  await page.getByRole('dialog').waitFor({state:'hidden'})
  await page.getByRole('cell',{name:doctorName,exact:true}).waitFor()
  await capture('schedules-desktop.png')
  check('页面新增排班并显示真实余号')
  await logout()
  await page.goto(`${base}/register`)
  await page.locator('#username').fill(patientUsername); await page.locator('#displayName').fill('联调患者（虚构）')
  await page.locator('#password').fill(password); await page.locator('#confirmPassword').fill(password)
  fixtures.patient = await saved('POST','/auth/register','注册患者账号'); await persist()
  await page.waitForURL('**/login'); await login(patientUsername,password)
  await page.getByRole('heading',{name:'接下来的就诊'}).waitFor()
  await page.getByRole('link',{name:'完善档案',exact:true}).click()
  await page.locator('#patientName').fill('联调患者（虚构）')
  await saved('PUT','/patient/profile','保存档案')
  check('患者注册、登录和本人档案保存')
  await page.getByRole('link',{name:'预约挂号',exact:true}).click()
  await page.getByRole('button',{name:'选择场次'}).waitFor()
  await capture('patient-booking-desktop.png'); await capture('patient-booking-tablet.png',768); await noOverflow(); await capture('patient-booking-mobile.png',375); await noOverflow()
  await page.getByRole('button',{name:'选择场次'}).click()
  await page.getByRole('dialog').waitFor()
  await page.locator('#symptoms').fill('虚构就诊说明，仅验证系统流程')
  fixtures.registration = await saved('POST','/patient/registrations','确认预约'); await persist()
  await page.waitForURL(`**/patient/visits/${fixtures.registration.id}`)
  await page.getByRole('button',{name:'取消预约',exact:true}).waitFor()
  await capture('patient-visit-mobile.png',375); await noOverflow()
  check('手机预约确认与就诊详情')
  await page.getByRole('button',{name:'取消预约',exact:true}).click()
  await page.getByRole('button',{name:'确认取消预约',exact:true}).click()
  await page.locator('.status-badge.cancelled').waitFor()
  check('页面取消预约并更新状态')
  await logout(); await login(doctorUsername,password)
  await page.goto(`${base}/doctor/workbench?date=${chinaDate(1)}`)
  await page.getByRole('link',{name:'查看详情',exact:true}).click()
  await page.waitForURL(`**/doctor/visits/${fixtures.registration.id}`)
  await page.locator('.status-badge.cancelled').waitFor()
  await page.goto(`${base}/doctor/schedules`)
  await page.getByRole('cell',{name:departmentName,exact:true}).waitFor()
  await capture('doctor-schedules-desktop.png')
  assert.equal(await page.getByRole('button',{name:'新增排班'}).count(),0)
  check('医生名下就诊与只读排班')
  }

  // 生产时间已超过当日截止；仅在隔离浏览器中模拟病历接口，不修改服务器时钟或业务数据。
  const mockId='900000001', registration={...fixtures.registration,id:mockId,status:'IN_PROGRESS',visitDate:chinaDate()}
  let draft={id:'900000002',registrationId:mockId,doctorId:fixtures.doctor.id,status:'DRAFT',version:0,...Object.fromEntries(recordFields.map(([key])=>[key,'']))}
  let failNext=true
  await page.route(url => url.pathname.startsWith(`/api/doctor/registrations/${mockId}`),async route => {
    const req=route.request(), path=new URL(req.url()).pathname, method=req.method()
    const respond=(data,status=200,code='OK',message='成功')=>route.fulfill({status,json:{code,message,data,traceId:'browser-ui-fixture'}})
    if(path.endsWith('/medical-record') && method==='PUT') {
      if(failNext) { failNext=false; return respond(null,409,'RECORD_VERSION_CONFLICT','病历已被其他窗口更新') }
      draft={...draft,...req.postDataJSON(),version:draft.version+1}; return respond(draft)
    }
    if(path.endsWith('/complete')) { draft={...draft,status:'SUBMITTED',submittedAt:`${chinaDate()}T18:00:00`}; return respond(draft) }
    if(path.endsWith('/medical-record')) return respond(draft)
    return respond({registration,patient:{name:'联调患者（虚构）',gender:'UNKNOWN',age:null,chronicStatus:'UNKNOWN',allergyStatus:'UNKNOWN'}})
  })
  await page.goto(`${base}/doctor/visits/${mockId}/record`)
  for(const [key,label] of recordFields) await page.locator(`#${key}`).fill(`${label}：虚构文本，仅验证编辑流程。`)
  await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await page.getByText('病历已被其他窗口更新',{exact:true}).waitFor()
  assert.match(await page.locator('#chiefComplaint').inputValue(),/虚构文本/)
  check('病历版本冲突保留当前正文')
  await page.getByRole('button',{name:'重新载入最新病历'}).click()
  await page.getByRole('button',{name:'重新载入',exact:true}).click()
  await page.getByText('当前正文已保存',{exact:true}).waitFor()
  for(const [key,label] of recordFields) await page.locator(`#${key}`).fill(`${label}：虚构文本，仅验证编辑流程。`)
  await page.getByRole('link',{name:'我的排班',exact:true}).click()
  await page.getByRole('button',{name:'继续编辑',exact:true}).click()
  assert.ok(page.url().endsWith('/record'))
  check('未保存病历离开确认')
  await page.getByRole('button',{name:'保存草稿',exact:true}).click()
  await page.getByText('当前正文已保存',{exact:true}).waitFor()
  await page.evaluate(()=>scrollTo(0,0)); await capture('doctor-record-desktop.png'); await capture('doctor-record-mobile.png',375); await noOverflow()
  await page.getByRole('button',{name:'预览并提交完成'}).click()
  await page.getByRole('button',{name:'确认提交并完成就诊'}).click()
  await page.getByText('已提交',{exact:true}).waitFor()
  assert.equal(await page.locator('textarea').count(),0)
  check('七段病历草稿、预览提交和只读正文（隔离接口测试）')
  assert.deepEqual(runtimeErrors,[])
  check('页面无未处理运行错误')
  await writeFile(new URL('browser-result.json',output),JSON.stringify({checks,runtimeErrors,fixtures,recordApi:'isolated-browser-fixture',capturedAt:new Date().toISOString()},null,2))
} catch(error) {
  console.error(error.message)
  console.error((await page.locator('body').innerText()).slice(0,6000))
  await writeFile(new URL('browser-failure.json',output),JSON.stringify({checks,message:error.message,runtimeErrors,fixtures},null,2))
  process.exitCode=1
} finally { await browser.close() }
