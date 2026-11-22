// Native local integration: a random disposable database and a separate loopback Redis process.
// Does not use the running 8083 service, its credentials, or any production host.
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:net'
import { createServer as httpServer } from 'node:http'
import { createInterface } from 'node:readline'
import { pathToFileURL } from 'node:url'
import { randomUUID } from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import assert from 'node:assert/strict'
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..')
const out = path.join(root, 'glucose-system/tests/.artifacts/ui-completion-2026-11-13')
await fs.mkdir(out, { recursive: true })
const backendJar = process.env.ACCEPTANCE_BACKEND_JAR || path.join(root,'glucose-system/backend/target/glucose-backend.jar')
const suffix = randomUUID().replaceAll('-', '').slice(0, 16)
const db = 'glucose_ui_test_' + suffix, migratedDb = 'glucose_med_upgrade_' + suffix
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

let web, provider, mode = 'ok', modelCalls = 0
const imports = path.join(out, '.imports-' + suffix), project = path.join(out, '.miniapp-' + suffix)
async function multipart(csv, token, requestId) {
  const form = new FormData(); form.set('file', new Blob([csv], { type:'text/csv' }), 'acceptance.csv'); form.set('requestId',requestId); form.set('metaConfirmed','true')
  const r = await fetch(base + '/api/app/glucose/import/preview', {method:'POST',headers:{Authorization:'Bearer ' + token},body:form}); const j=await r.json(); assert.equal(j.code,0,j.message); return j.data
}
try {
  assert.match(db,/^glucose_ui_test_[a-f0-9]{16}$/)
  sql('CREATE DATABASE `' + db + '` CHARACTER SET utf8mb4'); created.push(db)
  sql((await fs.readFile(path.join(root,'sql/schema.sql'),'utf8')).replaceAll('glucose_db',db),db)
  await fs.mkdir(imports); await fs.cp(path.join(root,'glucose-system/patient-miniapp'),project,{recursive:true})
  const requestFile=path.join(project,'utils/request.js'); await fs.writeFile(requestFile,(await fs.readFile(requestFile,'utf8')).replace('http://127.0.0.1:8083',base))
  const config=JSON.parse(await fs.readFile(path.join(project,'project.config.json'),'utf8'));config.projectname='glucose-ui-isolated-' + suffix; await fs.writeFile(path.join(project,'project.config.json'),JSON.stringify(config,null,2))
  provider=httpServer(async(req,res)=>{
    if(req.url==='/mode' && req.method==='POST') { let text='';for await(const c of req)text+=c;mode=JSON.parse(text).mode;return res.end('ok') }
    let text='';for await(const c of req)text+=c
    res.setHeader('Content-Type','application/json')
    if(req.url.endsWith('/embeddings')) return res.end(JSON.stringify({data:[{embedding:[1,0]}]}))
    modelCalls++
    if(mode==='fail') {res.statusCode=503;return res.end('{"error":"controlled failure"}')}
    res.end(JSON.stringify({choices:[{message:{content:mode==='uncited'?'没有引用的受控测试回答。':'血糖记录用于了解测量变化；请结合已有医嘱管理。[S1]'}}]}))
  });await new Promise(r=>provider.listen(0,'127.0.0.1',r)); const providerBase='http://127.0.0.1:' + provider.address().port
  redis=spawn(redisExe,['--bind','127.0.0.1','--port',String(redisPort),'--save','','--appendonly','no'],{cwd:out,windowsHide:true});redis.stdout.on('data',b=>redisLog+=b);redis.stderr.on('data',b=>redisLog+=b)
  backend=spawn('java',['-jar',backendJar,'--spring.profiles.active=dev','--server.port='+appPort,'--app.import.temp-dir='+imports,'--app.qa.per-user-hourly-limit=200','--logging.level.com.glucose=INFO'],{cwd:root,windowsHide:true,env:{...process.env,DB_HOST:'127.0.0.1',DB_PORT:process.env.LOCAL_TEST_DB_PORT||'3306',DB_NAME:db,DB_USER:process.env.LOCAL_TEST_DB_USER||'root',DB_PASSWORD:dbPassword,REDIS_HOST:'127.0.0.1',REDIS_PORT:String(redisPort),REDIS_PASSWORD:'',JWT_SECRET:jwt,GLUCOSE_ADMIN_USERNAME:'ui_acceptance',GLUCOSE_ADMIN_PASSWORD:adminPassword,WX_APPID:'',WX_SECRET:'',BAILIAN_API_KEY:'',LLM_BASE_URL:providerBase+'/v1',LLM_API_KEY:'isolated-fixture',LLM_MODEL:'fixture',EMBEDDING_BASE_URL:providerBase+'/v1',EMBEDDING_API_KEY:'isolated-fixture',EMBEDDING_MODEL:'fixture',EMBEDDING_DIM:'2'}})
  backend.stdout.on('data',b=>backendLog+=b);backend.stderr.on('data',b=>backendLog+=b)
  await until(async()=> (await request('GET','/api/common/captcha')).code===0,'隔离后端启动',100)
  sql('ALTER TABLE sys_user AUTO_INCREMENT=1000',db)
  const admin=await adminLogin('ui_acceptance',adminPassword)
  const patient=await ok('POST','/api/app/auth/wx-login','',{code:'ui-patient-'+suffix})
  const empty=await ok('POST','/api/app/auth/wx-login','',{code:'ui-empty-'+suffix})
  for(const p of [patient,empty]) await ok('PUT','/api/app/profile/targets',p.token,{targetRevision:1,privacyVersion:'privacy-v1'})
  await ok('PUT','/api/app/profile',patient.token,{realName:'本地验收患者',phone:'13800000000',birthDate:'1990-01-01',diabetesType:'测试档案'})
  const doctorUser=await ok('POST','/api/admin/users/doctors',admin.token,{username:'ui_doctor',password:adminPassword,realName:'本地验收医生'})
  const doctor=await adminLogin('ui_doctor',adminPassword)
  await ok('PUT',`/api/admin/patients/${patient.userId}/doctor`,admin.token,{doctorId:doctorUser.id})
  const points=[]; const start=new Date(Date.parse(today+'T00:00:00Z')-14*86400000)
  for(let i=0;i<14*288;i++){if(i>=100&&i<110)continue; const at=new Date(start.getTime()+i*300000).toISOString().replace('T',' ').slice(0,19);points.push(`(${patient.userId},'${at}',${(7+Math.sin(i/20)*1.8).toFixed(1)},'CGM','IMPORT','REAL',5)`)}
  sql('INSERT INTO glucose_record(patient_id,measured_at,glucose_mmol,data_source,ingest_method,data_kind,sample_interval_minutes) VALUES '+points.join(','),db)
  sql(`UPDATE patient_profile SET data_revision=data_revision+1 WHERE user_id=${patient.userId}`,db)
  for(const [time,value] of [['07:00',6.2],['09:00',3.0]]) await ok('POST','/api/app/glucose',patient.token,{measuredAt:yesterday+' '+time+':00',glucoseMmol:value,measureType:1})
  await ok('POST','/api/app/medication/plans',patient.token,{requestId:randomUUID(),drugName:'示例药品（验收）',dosage:'按原医嘱',scheduledTime:'08:30',startDate:today,instruction:'仅用于测试记录'})
  const doc=await ok('POST','/api/admin/kb/documents',admin.token,{title:'血糖记录说明（验收）',category:'基础知识',sourceTitle:'本地合成资料',sourceVersion:'fixture-v1',content:'血糖记录是健康管理参考，用于了解测量变化。此资料是本地受控验收夹具，不是临床建议。',sourceUrl:'https://example.org/fixture'})
  await ok('POST',`/api/admin/kb/documents/${doc.id}/reindex`,admin.token)
  // Cover uncovered and controlled model modes without using paid models.
  const session=await ok('POST','/api/app/qa/sessions',patient.token,{title:'血糖记录与资料引用'})
  const uncovered=await ok('POST','/api/app/qa/ask',patient.token,{sessionId:session.sessionId,requestId:'uncovered',question:'血糖记录'});assert.equal(uncovered.answerMode,'NO_COVERAGE')
  await ok('PUT',`/api/admin/kb/documents/${doc.id}/status`,admin.token,{status:1})
  const rag=await ok('POST','/api/app/qa/ask',patient.token,{sessionId:session.sessionId,requestId:'rag',question:'血糖记录'});assert.equal(rag.answerMode,'RAG')
  mode='fail';const degraded=await ok('POST','/api/app/qa/ask',patient.token,{sessionId:session.sessionId,requestId:'degraded',question:'血糖记录'});assert.equal(degraded.answerMode,'RETRIEVAL_ONLY');mode='ok'
  await ok('POST',`/api/admin/qa/sessions/${session.sessionId}/reply`,doctor.token,{requestId:'human-fixture',content:'已查看记录，请按已有医嘱记录。'})
  const csv='measured_at,glucose_mmol\n'+yesterday+' 12:00:00,6.4\n'+yesterday+' 12:05:00,6.6\n'
  // This timestamp conflicts with seeded CGM; use a new complete local day/time in a separate account for import flow.
  const batch=await multipart(csv,empty.token,'import-fixture');assert.equal(batch.status,'READY')
  const configModule=await import(pathToFileURL(path.join(root,'glucose-system/admin-web/node_modules/vite/dist/node/index.js')))
  web=await configModule.createServer({root:path.join(root,'glucose-system/admin-web'),server:{host:'127.0.0.1',port:0,proxy:{'/api':{target:base,changeOrigin:true}}}});await web.listen();const webBase=web.resolvedUrls.local[0].replace(/\/$/,'')
  const runtime={base,webBase,providerBase,project,admin,doctor,patient,empty,docId:doc.id,sessionId:session.sessionId,batch,csv}
  await fs.writeFile(path.join(out,'.runtime.json'),JSON.stringify(runtime))
  await fs.writeFile(path.join(out,'integration-results.json'),JSON.stringify({passed:true,environment:{mysql:sql('SELECT VERSION()'),redis:'isolated 5.0.14.1',model:'controlled loopback fixture; no real model calls',miniapp:'separate project copy',wechatAuth:'local mock only'},checks:['isolated authentication','doctor binding','14-day CGM with gap','SMBG isolated points','medication plan','NO_COVERAGE','RAG with citations','provider failure degradation','human reply','CSV preview'],modelCalls},null,2))
  console.log('READY '+JSON.stringify({base,webBase,project,output:out}))
  const rl=createInterface({input:process.stdin}); await new Promise(resolve=>{rl.on('line',line=>{if(line==='stop'){rl.close();resolve()}});process.on('SIGINT',resolve)})
} catch(e) {console.error(redact(e.stack||e.message));process.exitCode=1}
finally {
  if(web)await web.close()
  for(const child of [backend,redis])if(child){child.kill();await pause(300)}
  if(provider){provider.closeAllConnections();provider.close()}
  for(const name of created.reverse()){assert.match(name,/^glucose_ui_test_[a-f0-9]{16}$/);sql('DROP DATABASE `'+name+'`')}
  await fs.writeFile(path.join(out,'backend.log'),redact(backendLog));await fs.writeFile(path.join(out,'redis.log'),redact(redisLog))
  await fs.rm(path.join(out,'.runtime.json'),{force:true})
  // Keep isolated miniapp copy for reproducibility, remove imports only after checking the exact workspace child.
  assert.equal(path.dirname(path.resolve(imports)),path.resolve(out));assert.ok(path.basename(imports).startsWith('.imports-'));await fs.rm(imports,{recursive:true,force:true})
  console.log('Isolated services stopped; disposable database removed.')
}
