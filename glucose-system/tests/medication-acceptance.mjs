// Native local integration: a random disposable database and a separate loopback Redis process.
// Does not use the running 8083 service, its credentials, or any production host.
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:net'
import { randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'glucose-system/tests/.artifacts/medication-acceptance-2026-10-25')
await fs.mkdir(out, { recursive: true })
const backendJar = process.env.ACCEPTANCE_BACKEND_JAR || path.join(root,'glucose-system/backend/target/glucose-next.jar')
const suffix = randomUUID().replaceAll('-', '').slice(0, 16)
const db = 'glucose_med_test_' + suffix, migratedDb = 'glucose_med_upgrade_' + suffix
const mysql = process.env.MYSQL_CLIENT || 'C:/Program Files/MySQL/MySQL Server 5.7/bin/mysql.exe'
const redisExe = process.env.REDIS_SERVER || 'D:/Redis-x64-5.0.14.1/redis-server.exe'
const dev = await fs.readFile(path.join(root, 'glucose-system/backend/src/main/resources/application-dev.yml'), 'utf8')
const dbPassword = process.env.LOCAL_TEST_DB_PASSWORD || /DB_PASSWORD:([^}]+)\}/.exec(dev)?.[1]
assert.ok(dbPassword, '需要本机测试数据库凭据')
const adminPassword = randomUUID(), jwt = randomUUID() + randomUUID()
const env = { ...process.env, MYSQL_PWD: dbPassword }
const checks = []; let backend, redis, backendLog = '', redisLog = '', created = []
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const redact = value => value.replaceAll(dbPassword, '[redacted]').replaceAll(adminPassword, '[redacted]').replaceAll(jwt, '[redacted]').replace(/Using generated security password: .*/g, 'Using generated security password: [redacted]')
function sql(query, database) {
  const args = ['--host=127.0.0.1', '--port=' + (process.env.LOCAL_TEST_DB_PORT || '3306'), '--user=' + (process.env.LOCAL_TEST_DB_USER || 'root'), '--default-character-set=utf8mb4', '--batch', '--skip-column-names', ...(database ? ['--database=' + database] : [])]
  const r = spawnSync(mysql, args, { env, input: query, encoding: 'utf8', windowsHide: true, timeout: 20000 })
  if (r.status !== 0) throw Error('本机测试库操作失败：' + redact(r.stderr || r.error?.message || 'unknown'))
  return r.stdout.trim()
}
async function port() {
  const s = createServer(); await new Promise(r => s.listen(0, '127.0.0.1', r))
  const p = s.address().port; await new Promise(r => s.close(r)); return p
}
async function until(check, name, attempts = 60) {
  for (let i = 0; i < attempts; i++) { try { if (await check()) return } catch {} await pause(500) }
  throw Error('等待超时：' + name)
}
const appPort = await port(), redisPort = await port(), base = 'http://127.0.0.1:' + appPort
async function request(method, url, token, body) {
  const r = await fetch(base + url, { method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), 'Content-Type': 'application/json' }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(15000) })
  return { status: r.status, ...await r.json() }
}
async function ok(method, url, token, body) {
  const r = await request(method, url, token, body); assert.equal(r.code, 0, `${method} ${url}: ${r.code} ${r.message}`); return r.data
}
async function adminLogin(username, password) {
  const cap = await ok('GET','/api/common/captcha')
  const redisCli = path.join(path.dirname(redisExe), 'redis-cli.exe')
  const r = spawnSync(redisCli, ['-h','127.0.0.1','-p',String(redisPort),'GET','auth:cap:' + cap.captchaId], { encoding:'utf8', windowsHide:true })
  assert.equal(r.status, 0)
  return ok('POST','/api/admin/auth/login','',{ username, password, captchaId:cap.captchaId, captchaCode:r.stdout.trim() })
}
async function check(name, fn) { const start = performance.now(); const detail = await fn(); checks.push({ name, passed: true, ms: Math.round(performance.now() - start), ...(detail ? { detail } : {}) }); console.log('PASS ' + name) }
const date = new Date(Date.now() + 8 * 3600000), today = date.toISOString().slice(0,10)
const yesterday = new Date(date.getTime() - 86400000).toISOString().slice(0,10)
const tomorrow = new Date(date.getTime() + 86400000).toISOString().slice(0,10)
try {
  const version = sql('SELECT VERSION()');
  for (const name of [db, migratedDb]) { assert.match(name, /^glucose_med_(test|upgrade)_[a-f0-9]{16}$/); sql('CREATE DATABASE `' + name + '` CHARACTER SET utf8mb4'); created.push(name) }
  const schema = await fs.readFile(path.join(root, 'sql/schema.sql'), 'utf8')
  sql(schema.replaceAll('glucose_db', db), db)
  await check('增量迁移保留原有用药记录及taken状态', async () => {
    sql(`CREATE TABLE medication_log (id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,patient_id BIGINT UNSIGNED NOT NULL,record_date DATE NOT NULL,drug_name VARCHAR(100) NOT NULL,dosage VARCHAR(50) NULL,taken_at DATETIME NULL,taken TINYINT NOT NULL DEFAULT 0,created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,deleted TINYINT NOT NULL DEFAULT 0,PRIMARY KEY(id),KEY idx_patient_date(patient_id,record_date)) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 ROW_FORMAT=DYNAMIC;
      INSERT INTO medication_log(patient_id,record_date,drug_name,dosage,taken) VALUES (201,'${yesterday}','原有记录一','原处方',1),(202,'${yesterday}','原有记录二','原处方',0);`, migratedDb)
    sql(await fs.readFile(path.join(root, 'sql/upgrade-medication-2026-10-25.sql'), 'utf8'), migratedDb)
    assert.equal(sql('SELECT GROUP_CONCAT(CONCAT(taken,\':\',status) ORDER BY id) FROM medication_log', migratedDb), '1:TAKEN,0:PENDING')
    assert.equal(sql('SELECT COUNT(*) FROM medication_log WHERE plan_id IS NULL', migratedDb), '2')
    return { mysql: version, legacyRowsPreserved: 2 }
  })
  redis = spawn(redisExe, ['--bind','127.0.0.1','--port',String(redisPort),'--save','','--appendonly','no'], { cwd: out, windowsHide: true })
  redis.stdout.on('data', b => { redisLog += b }); redis.stderr.on('data', b => { redisLog += b })
  backend = spawn('java', ['-jar', backendJar, '--spring.profiles.active=dev','--server.port=' + appPort,'--logging.level.com.glucose=INFO'], {
    cwd: root, windowsHide: true, env: { ...process.env, DB_HOST: '127.0.0.1', DB_PORT: process.env.LOCAL_TEST_DB_PORT || '3306', DB_NAME: db, DB_USER: process.env.LOCAL_TEST_DB_USER || 'root', DB_PASSWORD: dbPassword,
      REDIS_HOST: '127.0.0.1', REDIS_PORT: String(redisPort), REDIS_PASSWORD: '', JWT_SECRET: jwt, GLUCOSE_ADMIN_USERNAME: 'med_acceptance', GLUCOSE_ADMIN_PASSWORD: adminPassword,
      WX_APPID: '', WX_SECRET: '', LLM_API_KEY: '', EMBEDDING_API_KEY: '', BAILIAN_API_KEY: '' }
  })
  backend.stdout.on('data', b => { backendLog += b }); backend.stderr.on('data', b => { backendLog += b })
  await until(async () => (await request('GET','/api/common/captcha')).code === 0, '后端启动')
  sql('ALTER TABLE sys_user AUTO_INCREMENT=1000', db)
  const a = await ok('POST','/api/app/auth/wx-login','',{ code:'med-a-' + suffix })
  const b = await ok('POST','/api/app/auth/wx-login','',{ code:'med-b-' + suffix })
  const route = '/api/app/medication', payload = { requestId:'test-request-' + suffix, drugName:'隔离验收药名', dosage:'原处方用量（测试）', scheduledTime:'08:30', startDate:today, endDate:null, instruction:'仅测试记录功能' }
  let plan, item
  await check('未登录拒绝与新账号空列表', async () => {
    assert.equal((await request('GET',route)).status,401)
    assert.deepEqual(await ok('GET',route + '/plans',a.token),[])
    assert.equal((await ok('GET',route,a.token)).records.length,0)
  })
  await check('20次并发创建同计划只落一行且返回同ID', async () => {
    const results = await Promise.all(Array.from({length:20},() => ok('POST',route + '/plans',a.token,payload)))
    plan = results[0]; assert.ok(results.every(r => r.id === plan.id))
    assert.equal(sql(`SELECT COUNT(*) FROM medication_plan WHERE patient_id=${a.userId}`, db),'1')
    assert.equal((await request('POST',route + '/plans',a.token,{...payload,drugName:'变更药名'})).code,40901)
    return { requests:20, rows:1 }
  })
  await check('20次并发读取当天记录不重复，并保留医嘱快照', async () => {
    const results = await Promise.all(Array.from({length:20},() => ok('GET',route,a.token)))
    item = results[0].records[0]; assert.ok(results.every(r => r.records.length === 1 && r.records[0].id === item.id))
    assert.equal(item.drugName,payload.drugName); assert.equal(item.dosage,payload.dosage); assert.equal(item.scheduledTime,'08:30'); assert.equal(item.status,'PENDING')
  })
  await check('20次并发确认服用幂等，时间与版本保持不变', async () => {
    const results = await Promise.all(Array.from({length:20},() => ok('PUT',route + '/' + item.id + '/taken',a.token,{status:'TAKEN',version:item.version})))
    assert.ok(results.every(r => Number(r.version) === 2 && r.takenAt === results[0].takenAt)); assert.ok(results[0].takenAt)
    assert.equal(sql(`SELECT taken FROM medication_log WHERE id=${item.id}`,db),'1')
    assert.equal((await request('PUT',route + '/' + item.id + '/taken',a.token,{status:'SKIPPED',version:1})).code,40901)
  })
  await check('撤销清空服用时间；相反状态并发只成功一个', async () => {
    const reset = await ok('PUT',route + '/' + item.id + '/taken',a.token,{status:'PENDING',version:2}); assert.equal(reset.takenAt,'')
    assert.equal(sql(`SELECT taken_at IS NULL AND taken=0 FROM medication_log WHERE id=${item.id}`,db),'1')
    const results = await Promise.all(['TAKEN','SKIPPED'].map(status => request('PUT',route + '/' + item.id + '/taken',a.token,{status,version:reset.version})))
    assert.equal(results.filter(r => r.code === 0).length,1); assert.equal(results.filter(r => r.code === 40901).length,1)
  })
  await check('跨账号不可查看或修改他人的计划和服药记录', async () => {
    assert.equal((await ok('GET',route,b.token)).records.length,0)
    assert.equal((await request('PUT',route + '/' + item.id + '/taken',b.token,{status:'TAKEN',version:1})).status,404)
    assert.equal((await request('PUT',route + '/plans/' + plan.id + '/stop',b.token,{version:plan.version})).status,404)
    assert.equal((await ok('GET',route + '/plans',b.token)).length,0)
  })
  await check('边界参数拒绝，不凭客户端patientId选择归属', async () => {
    for (const patch of [{scheduledTime:'24:00'},{drugName:' '},{dosage:''},{startDate:yesterday},{endDate:yesterday},{drugName:'a'.repeat(101)}]) {
      assert.equal((await request('POST',route + '/plans',a.token,{...payload,requestId:randomUUID(),...patch})).status,400)
    }
    assert.equal((await request('GET',route + '?date=' + tomorrow,a.token)).status,400)
    assert.equal((await request('GET',route + '?date=2026-02-30',a.token)).status,400)
    assert.equal((await request('PUT',route + '/' + item.id + '/taken',a.token,{status:'OTHER',version:1})).status,400)
    const own = await ok('POST',route + '/plans',b.token,{...payload,requestId:randomUUID(),patientId:a.userId,scheduledTime:'20:30'})
    assert.equal(sql(`SELECT patient_id FROM medication_plan WHERE id=${own.id}`,db),String(b.userId))
  })
  await check('停用关闭当天待记录项目，保留已服用记录且重复停用不变', async () => {
    const added = await ok('POST',route + '/plans',a.token,{...payload,requestId:randomUUID(),scheduledTime:'20:30'})
    const before = await ok('GET',route,a.token), pending = before.records.find(r => r.planId === added.id)
    const stopped = await ok('PUT',route + '/plans/' + added.id + '/stop',a.token,{version:added.version})
    const replay = await ok('PUT',route + '/plans/' + added.id + '/stop',a.token,{version:added.version}); assert.equal(replay.version,stopped.version)
    const after = await ok('GET',route,a.token); assert.equal(after.records.find(r => r.id === pending.id).status,'CANCELLED')
    assert.equal(after.records.find(r => r.id === item.id).status,before.records.find(r => r.id === item.id).status)
    assert.equal((await request('PUT',route + '/' + pending.id + '/taken',a.token,{status:'TAKEN',version:2})).status,400)
  })
  await check('历史只读与计划生效区间，不虚构补签', async () => {
    assert.equal((await ok('GET',route + '?date=' + yesterday,a.token)).records.length,0)
    // Move only this disposable fixture's dates to exercise a genuinely historical record.
    sql(`UPDATE medication_plan SET start_date='${yesterday}' WHERE id=${plan.id}`,db)
    const old = await ok('GET',route + '?date=' + yesterday,a.token); assert.equal(old.records.length,1); assert.equal(old.editable,false); assert.equal(old.records[0].status,'PENDING')
    assert.equal((await request('PUT',route + '/' + old.records[0].id + '/taken',a.token,{status:'TAKEN',version:1})).status,400)
    assert.equal((await ok('GET',route + '?date=' + yesterday,a.token)).records.length,1)
  })
  await check('已记录项目在停用后仍可撤销，但不会重新出现待服任务', async () => {
    let day = await ok('GET',route,a.token), current = day.records.find(r => r.id === item.id)
    if (current.status !== 'TAKEN') current = await ok('PUT',route + '/' + item.id + '/taken',a.token,{status:'TAKEN',version:current.version})
    await ok('PUT',route + '/plans/' + plan.id + '/stop',a.token,{version:plan.version})
    assert.equal((await ok('GET',route,a.token)).records.find(r => r.id === item.id).status,'TAKEN')
    const undone = await ok('PUT',route + '/' + item.id + '/taken',a.token,{status:'PENDING',version:current.version})
    assert.equal(undone.status,'CANCELLED'); assert.equal(undone.takenAt,'')
  })
  await check('真实数据库14日CGM产生GMI与48槽分位数，7日和SMBG不显示', async () => {
    const start = new Date(date.getTime() - 14*86400000).toISOString().slice(0,10)
    const values = []
    for(let i=0;i<14*288;i++) {
      const timestamp = new Date(Date.parse(start + 'T00:00:00Z') + i*300000).toISOString().replace('T',' ').slice(0,19)
      values.push(`(${a.userId},'${timestamp}',8.0,'CGM','IMPORT','REAL',5)`)
    }
    sql('INSERT INTO glucose_record(patient_id,measured_at,glucose_mmol,data_source,ingest_method,data_kind,sample_interval_minutes) VALUES ' + values.join(','),db)
    sql(`UPDATE patient_profile SET data_revision=data_revision+1 WHERE user_id=${a.userId}`,db)
    const query = `?startDate=${start}&endDate=${today}&dataSource=CGM`
    const summary = await ok('GET','/api/app/analysis/summary' + query,a.token)
    assert.equal(summary.gmi,6.76); assert.equal(summary.metricStatus,'QUALIFIED'); assert.equal(summary.pointCount,4032)
    const agp = await ok('GET','/api/app/analysis/agp' + query,a.token)
    assert.equal(agp.qualifiedSlotCount,48); assert.ok(agp.slots.every(s => s.p5===8 && s.p50===8 && s.p95===8 && s.coveredDays===14))
    const short = new Date(date.getTime()-7*86400000).toISOString().slice(0,10)
    assert.equal((await ok('GET',`/api/app/analysis/summary?startDate=${short}&endDate=${today}&dataSource=CGM`,a.token)).gmi,null)
    assert.equal((await ok('GET',`/api/app/analysis/agp?startDate=${short}&endDate=${today}&dataSource=CGM`,a.token)).qualifiedSlotCount,0)
    assert.equal((await ok('GET','/api/app/analysis/summary' + query.replace('CGM','SMBG'),a.token)).gmi,null)
    assert.equal((await ok('GET','/api/app/analysis/agp' + query,b.token)).qualifiedSlotCount,0)
    return { points:4032,gmi:6.76,qualifiedSlots:48 }
  })
  await check('医生分布查询限绑定患者，解绑后立即失去权限', async () => {
    const admin = await adminLogin('med_acceptance',adminPassword)
    const doctor = await ok('POST','/api/admin/users/doctors',admin.token,{username:'agp_doctor',password:adminPassword,realName:'隔离验收医生'})
    const login = await adminLogin('agp_doctor',adminPassword)
    const url = `/api/admin/analysis/patient/${a.userId}/agp?dataSource=CGM`
    const unbound = await ok('GET','/api/admin/dashboard/overview',login.token)
    assert.equal(Number(unbound.activePatients),0); assert.equal(Number(unbound.recordsLast7Days),0); assert.equal(Number(unbound.pendingAlerts),0)
    assert.equal((await request('GET',url,login.token)).status,403)
    await ok('PUT',`/api/admin/patients/${a.userId}/doctor`,admin.token,{doctorId:doctor.id})
    assert.equal((await ok('GET',url,login.token)).qualifiedSlotCount,48)
    assert.equal((await request('GET',`/api/admin/analysis/patient/${b.userId}/agp`,login.token)).status,403)
    assert.equal((await request('GET',route,login.token)).status,403)
    await ok('POST','/api/app/glucose',a.token,{glucoseMmol:6.2,measureType:1})
    await ok('POST','/api/app/glucose',b.token,{glucoseMmol:6.3,measureType:1})
    const adminOverview = await ok('GET','/api/admin/dashboard/overview',admin.token)
    const doctorOverview = await ok('GET','/api/admin/dashboard/overview',login.token)
    const scopedRecords = Number(sql(`SELECT COUNT(*) FROM glucose_record WHERE patient_id=${a.userId} AND measured_at>=DATE_SUB(NOW(),INTERVAL 7 DAY) AND measured_at<NOW() AND deleted=0`,db))
    assert.equal(Number(adminOverview.activePatients),2)
    assert.equal(Number(doctorOverview.activePatients),1)
    assert.equal(Number(doctorOverview.recordsLast7Days),scopedRecords)
    assert.equal(Number(adminOverview.recordsLast7Days),scopedRecords + 1)
    assert.equal(Number(doctorOverview.pendingAlerts),0)
    assert.equal(sql(`SELECT COUNT(*) FROM operation_log WHERE actor_id=${admin.userId} AND action='admin.view.dashboard'`,db),'1')
    await ok('PUT',`/api/admin/patients/${a.userId}/doctor`,admin.token,{doctorId:0})
    assert.equal((await request('GET',url,login.token)).status,403)
    const afterUnbind = await ok('GET','/api/admin/dashboard/overview',login.token)
    assert.equal(Number(afterUnbind.activePatients),0); assert.equal(Number(afterUnbind.recordsLast7Days),0); assert.equal(Number(afterUnbind.pendingAlerts),0)
  })
  await check('医生人工回复幂等可见，解绑后立即拒绝', async () => {
    await ok('PUT','/api/app/profile/targets',a.token,{targetRevision:1,privacyVersion:'privacy-v1'})
    const session = await ok('POST','/api/app/qa/sessions',a.token,{title:'人工回复验收'})
    const admin = await adminLogin('med_acceptance',adminPassword)
    const doctor = await ok('POST','/api/admin/users/doctors',admin.token,{username:'reply_doctor',password:adminPassword,realName:'回复医生'})
    const login = await adminLogin('reply_doctor',adminPassword)
    await ok('PUT',`/api/admin/patients/${a.userId}/doctor`,admin.token,{doctorId:doctor.id})
    const body={requestId:'human-reply-' + suffix,content:'请按已有医嘱记录，如有不适请联系医生。'}
    const first=await ok('POST',`/api/admin/qa/sessions/${session.sessionId}/reply`,login.token,body)
    const replay=await ok('POST',`/api/admin/qa/sessions/${session.sessionId}/reply`,login.token,body)
    assert.equal(first.messageId,replay.messageId); assert.equal(first.answerMode,'HUMAN'); assert.equal(first.hasSources,false)
    assert.equal((await request('POST',`/api/admin/qa/sessions/${session.sessionId}/reply`,login.token,{...body,content:'冲突'})).code,40901)
    const patientMessages=await ok('GET',`/api/app/qa/sessions/${session.sessionId}/messages`,a.token)
    assert.ok(patientMessages.some(m=>m.answerMode==='HUMAN' && m.content.includes('已有医嘱')))
    await ok('PUT',`/api/admin/patients/${a.userId}/doctor`,admin.token,{doctorId:0})
    assert.equal((await request('POST',`/api/admin/qa/sessions/${session.sessionId}/reply`,login.token,{requestId:'human-reply-2-' + suffix,content:'再次回复'})).status,403)
  })
  await check('管理端导出按授权范围输出 CSV，并拒绝超范围日期', async () => {
    const admin = await adminLogin('med_acceptance',adminPassword)
    const exportStart = new Date(date.getTime() - 14 * 86400000).toISOString().slice(0,10)
    const response = await fetch(base + `/api/admin/glucose/export?patientId=${a.userId}&startDate=${exportStart}&endDate=${today}&dataSource=CGM`, { headers:{Authorization:'Bearer ' + admin.token} })
    assert.equal(response.status,200); const csv = await response.text(); assert.ok(csv.includes('测量时间,血糖值')); assert.match(csv,/8\.0/); assert.match(csv,/CGM/)
    const tooLong = await request('GET',`/api/admin/glucose/export?patientId=${a.userId}&startDate=2020-01-01&endDate=2022-01-01&dataSource=CGM`,admin.token)
    assert.equal(tooLong.status,400)
  })
  await check('注销清理用药计划与记录，另一个账号仍完整', async () => {
    await ok('DELETE','/api/app/account',b.token)
    for (const table of ['medication_plan','medication_log']) assert.equal(sql(`SELECT COUNT(*) FROM ${table} WHERE patient_id=${b.userId}`,db),'0')
    assert.equal((await request('GET',route,b.token)).status,401)
    assert.equal((await ok('GET',route + '/plans',a.token)).length,2)
  })
  await fs.writeFile(path.join(out,'results.json'),JSON.stringify({ passed:true, environment:{ mysql:version,redis:'5.0.14.1 isolated loopback process',backend:'new jar on disposable local DB',wechat:'dev mock login; no device validation',model:'not called' },checks },null,2))
  console.log('Completed ' + checks.length + ' medication scenarios.')
} catch (e) {
  await fs.writeFile(path.join(out,'results.json'),JSON.stringify({passed:false,checks,error:redact(e.message)},null,2)); console.error(redact(e.message)); process.exitCode=1
} finally {
  for (const child of [backend,redis]) if (child) { child.kill(); await pause(300) }
  for (const name of created.reverse()) { assert.match(name,/^glucose_med_(test|upgrade)_[a-f0-9]{16}$/); sql('DROP DATABASE `' + name + '`') }
  await fs.writeFile(path.join(out,'backend.log'),redact(backendLog))
  await fs.writeFile(path.join(out,'redis.log'),redact(redisLog))
}
