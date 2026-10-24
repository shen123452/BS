import assert from 'node:assert/strict'
import { randomBytes } from 'node:crypto'
import { mkdir, readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'

// Only local API calls; no direct SQL, paid model calls or stored credentials.
const args = process.argv.slice(2)
const option = name => args.includes(name) ? args[args.indexOf(name) + 1] : undefined
const browserSession = option('--browser-session')
const dryRun = args.includes('--dry-run'), verifyOnly = args.includes('--verify-only')
const base = new URL(process.env.DEMO_API_BASE || 'http://127.0.0.1:8082/api')
assert.ok(['127.0.0.1', 'localhost'].includes(base.hostname) && base.protocol === 'http:', '仅允许本机 HTTP 服务')
assert.ok(!browserSession || base.href === 'http://127.0.0.1:8082/api', '浏览器方式固定使用 5173 代理的 8082 服务')
const manifestUrl = new URL('../../.cache/demo-data/manifest.json', import.meta.url)
const batch = 'BS_DEMO_V1'
let manifest
try { manifest = JSON.parse(await readFile(manifestUrl, 'utf8')) }
catch (error) { if (error.code !== 'ENOENT') throw error }
manifest ||= { batch, apiBase: base.href, entities: {}, createdAt: new Date().toISOString() }
assert.equal(manifest.batch, batch)
assert.equal(manifest.apiBase, base.href, '清单属于不同的本机服务')
const created = {}, entities = manifest.entities
const chinaNow = () => new Intl.DateTimeFormat('sv-SE', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium', hour12: false }).format(new Date())
const today = chinaNow().slice(0, 10)
const dateAt = offset => new Date(Date.parse(`${today}T12:00:00+08:00`) + offset * 86400000).toISOString().slice(0, 10)
const dates = Array.from({ length: 6 }, (_, i) => dateAt(i + 1))

const departments = [
  ['RESP', '呼吸内科', '二层 201', '咳嗽、鼻塞等呼吸系统不适的普通门诊场景'],
  ['GASTRO', '消化内科', '二层 203', '腹胀、反酸等消化系统不适的普通门诊场景'],
  ['NEURO', '神经内科', '三层 301', '头痛、头晕等普通门诊场景'],
  ['CARDIO', '心血管内科', '三层 303', '血压随访等普通门诊场景'],
  ['ORTHO', '骨科', '一层 105', '关节、肌肉不适等普通门诊场景'],
  ['DERM', '皮肤科', '一层 107', '皮肤瘙痒等普通门诊场景'],
].map(([key, name, room, scope]) => ({ code: `DEMO_BS_${key}`, name: `${name}（演示）`, location: `演示门诊楼 ${room}`, description: `虚构演示科室，仅用于系统功能展示。${scope}；不代表实际医院、服务或医疗建议。`, isEmergency: false, status: 'ENABLED' }))

const doctors = [
  ['resp_1', '林清和', 0, '主任医师', '呼吸系统普通门诊'],
  ['resp_2', '陈知远', 0, '主治医师', '呼吸系统随访'],
  ['gastro_1', '周予安', 1, '副主任医师', '消化系统普通门诊'],
  ['gastro_2', '许明舒', 1, '主治医师', '消化系统随访'],
  ['neuro', '沈若宁', 2, '副主任医师', '头痛、头晕普通门诊'],
  ['cardio', '顾景川', 3, '主任医师', '血压随访普通门诊'],
  ['ortho', '唐亦舟', 4, '主治医师', '关节、肌肉普通门诊'],
  ['derm', '苏念初', 5, '副主任医师', '皮肤普通门诊'],
].map(([key, name, departmentIndex, title, specialty]) => ({ username: `demo_doc_${key}`, name: `${name}（虚构）`, departmentIndex, title, specialty: `虚构演示资料：${specialty}，不代表真实医生执业资质或诊疗能力。` }))

const patients = [
  ['01', '陈小禾', 'FEMALE', 26, '咳嗽、鼻塞', 0],
  ['02', '李明远', 'MALE', 34, '喉咙不适', 1],
  ['03', '王予宁', 'FEMALE', 42, '饭后腹胀', 2],
  ['04', '赵安然', 'MALE', 29, '间歇性头痛', 4],
  ['05', '孙知夏', 'FEMALE', 51, '膝关节不适', 6],
  ['06', '吴亦晨', 'MALE', 23, '手臂皮肤瘙痒', 7],
].map(([key, name, gender, age, complaint, doctorIndex]) => ({ username: `demo_patient_${key}`, name: `${name}（虚构）`, gender, age, complaint, doctorIndex }))

async function save() {
  if (dryRun || verifyOnly) return
  await mkdir(new URL('.', manifestUrl), { recursive: true })
  manifest.updatedAt = new Date().toISOString()
  await writeFile(manifestUrl, JSON.stringify(manifest, null, 2) + '\n')
}
async function remember(kind, key, value, isNew = false) {
  entities[kind] ||= {}
  entities[kind][key] = value
  if (isNew) created[kind] = (created[kind] || 0) + 1
  await save()
  return value
}
async function bridge(code) {
  const response = await fetch('http://127.0.0.1:10086/command', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ action: 'evaluate', args: { code }, session: browserSession }), signal: AbortSignal.timeout(25000) })
  const envelope = await response.json()
  if (!response.ok || !envelope.ok || envelope.data?.type !== 'object') throw new Error('浏览器连接不可用，请检查本机 WebBridge 和管理员登录')
  return envelope.data.value
}
let adminToken
async function api(method, path, body, token, asAdmin = false) {
  let result
  if (asAdmin && browserSession) {
    // The token stays inside the logged-in page; return only the API response.
    result = await bridge(`(async () => { if (location.origin !== 'http://127.0.0.1:5173' && location.origin !== 'http://localhost:5173') return {status:400,body:{message:'请在本机医院页面登录'}}; const t = sessionStorage.getItem('hospital.token'); const r = await fetch(${JSON.stringify('/api' + path)}, {method:${JSON.stringify(method)}, headers:{'Content-Type':'application/json',Authorization:'Bearer '+(t || '')}, body:${body === undefined ? 'undefined' : JSON.stringify(JSON.stringify(body))}}); return {status:r.status,body:await r.json()}; })()`)
  } else {
    const response = await fetch(`${base.href}${path}`, { method, headers: { 'Content-Type': 'application/json', ...((token || (asAdmin && adminToken)) ? { Authorization: `Bearer ${token || adminToken}` } : {}) }, body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(20000) })
    result = { status: response.status, body: await response.json() }
  }
  if (result.status >= 400 || result.body.code !== 'OK') throw new Error(`${method} ${path.split('?')[0]}: ${result.body.code || result.status} ${result.body.message || '请求失败'}`)
  return result.body.data
}
const admin = (method, path, body) => api(method, path, body, undefined, true)
async function list(path, token, asAdmin = false) {
  const items = []
  for (let page = 1; ; page++) {
    const data = await api('GET', `${path}${path.includes('?') ? '&' : '?'}page=${page}&pageSize=100`, undefined, token, asAdmin)
    items.push(...data.items)
    if (items.length >= data.total) return items
    assert.ok(data.items.length, '分页未返回预期数据')
  }
}
const login = (username, password) => api('POST', '/auth/login', { username, password })
function requireOwned(kind, key, value) {
  const tracked = entities[kind]?.[key]
  assert.ok(tracked && tracked.id === value.id, `已有 ${key} 不属于本机演示清单，停止以避免修改原数据`)
}

async function verifyRecorded(tokens, existingDepartments, existingDoctors) {
  const registrations = [], records = []
  for (const item of Object.values(entities.departments || {})) assert.ok(existingDepartments.some(x => x.id === item.id && x.code === item.code), `科室缺失 ${item.code}`)
  for (const item of Object.values(entities.doctors || {})) assert.ok(existingDoctors.some(x => x.id === item.id && x.userId === item.userId), `医生缺失 ${item.id}`)
  const storedSchedules = Object.values(entities.schedules || {})
  const scheduleDates = [...new Set(storedSchedules.map(x => x.visitDate))].sort()
  const schedules = []
  for (const date of scheduleDates) schedules.push(...await list(`/admin/schedules?startDate=${date}&endDate=${date}`, undefined, true))
  const doctorLists = new Map()
  for (const stored of storedSchedules) {
    const current = schedules.find(x => x.id === stored.id)
    assert.ok(current && current.doctorId === stored.doctorId && current.departmentId === stored.departmentId && current.visitDate === stored.visitDate && current.timeSlot === stored.timeSlot, `排班缺失或归属变化 ${stored.id}`)
    const doctor = doctors.find(x => entities.doctors[x.username]?.id === current.doctorId)
    const key = `${doctor.username}:${current.visitDate}`
    if (!doctorLists.has(key)) doctorLists.set(key, await list(`/doctor/registrations?date=${current.visitDate}`, tokens.get(doctor.username)))
    const used = doctorLists.get(key).filter(x => x.scheduleId === current.id && x.status !== 'CANCELLED').length
    assert.equal(current.totalCount - current.remainingCount, used, `号源核对失败 ${current.id}`)
  }
  for (const [key, stored] of Object.entries(entities.registrations || {})) {
    const username = key.split(':')[0]
    const current = await api('GET', `/patient/registrations/${stored.id}`, undefined, tokens.get(username))
    assert.equal(current.patientId, stored.patientId)
    assert.equal(current.scheduleId, stored.scheduleId)
    registrations.push(current)
    if (entities.medicalRecords?.[key]) {
      const doctor = doctors.find(x => entities.doctors[x.username]?.id === current.doctorId)
      const record = await api('GET', `/doctor/registrations/${current.id}/medical-record`, undefined, tokens.get(doctor.username))
      assert.equal(record.id, entities.medicalRecords[key].id)
      if (record.status === 'SUBMITTED') assert.equal((await api('GET', `/patient/registrations/${current.id}/medical-record`, undefined, tokens.get(username))).id, record.id)
      records.push(record)
    }
  }
  for (const [username, stored] of Object.entries(entities.sessions || {})) assert.equal((await api('GET', `/patient/consultations/${stored.id}`, undefined, tokens.get(username))).id, stored.id)
  console.log(JSON.stringify({ verifyOnly: true, at: new Date().toISOString(), created: {}, counts: { departments: Object.keys(entities.departments || {}).length, doctors: Object.keys(entities.doctors || {}).length, patients: Object.keys(entities.patientUsers || {}).length, schedules: storedSchedules.length, registrations: registrations.length, registrationStates: Object.fromEntries(['WAITING','IN_PROGRESS','COMPLETED','CANCELLED'].map(s => [s, registrations.filter(x => x.status === s).length])), medicalRecords: records.length, manualSummaries: Object.keys(entities.sessions || {}).length }, checks: ['已登记对象身份与归属', '历史及当前排班号源', '患者预约读取', '病历与会话读取'], manifest: fileURLToPath(manifestUrl) }, null, 2))
}

async function main() {
  if (!browserSession) {
    assert.ok(process.env.DEMO_ADMIN_PASSWORD, '请从环境提供 DEMO_ADMIN_PASSWORD，或使用 --browser-session')
    adminToken = (await login(process.env.DEMO_ADMIN_USERNAME || 'admin', process.env.DEMO_ADMIN_PASSWORD)).token
  }
  assert.equal((await admin('GET', '/auth/me')).role, 'ADMIN', '需要已有管理员会话')
  const existingDepartments = await list('/admin/departments', undefined, true)
  const existingDoctors = await list('/admin/doctors', undefined, true)
  const users = await list('/admin/users', undefined, true)
  const managedNames = new Set([...doctors, ...patients].map(x => x.username))
  const existingAccounts = users.filter(x => managedNames.has(x.username))
  const password = process.env.DEMO_ACCOUNT_PASSWORD || (!existingAccounts.length ? `Demo_${randomBytes(12).toString('base64url')}` : undefined)
  const tokens = new Map()
  for (const spec of departments) {
    const old = existingDepartments.find(x => x.code === spec.code)
    if (old) { requireOwned('departments', spec.code, old); assert.equal(old.status, 'ENABLED'); assert.equal(old.name, spec.name) }
  }
  for (const spec of [...doctors, ...patients]) {
    const old = users.find(x => x.username === spec.username)
    if (!old) continue
    requireOwned(doctors.includes(spec) ? 'doctorUsers' : 'patientUsers', spec.username, old)
    assert.equal(old.role, doctors.includes(spec) ? 'DOCTOR' : 'PATIENT')
    assert.equal(old.displayName, spec.name)
    if (!dryRun) {
      assert.ok(password, '已有演示账号，请从环境提供原 DEMO_ACCOUNT_PASSWORD；不会重置密码')
      tokens.set(spec.username, (await login(spec.username, password)).token)
    }
  }
  if (dryRun) {
    console.log(JSON.stringify({ dryRun: true, date: today, existing: { departments: existingDepartments.length, doctors: existingDoctors.length, users: users.length }, plan: { departments: departments.length, doctors: doctors.length, patients: patients.length, futureSchedules: 72, todaySchedules: chinaNow().slice(11,19) < '13:25:00' ? 8 : 0, registrations: 12, manualSummaries: 6 }, manifest: fileURLToPath(manifestUrl) }, null, 2))
    return
  }
  if (verifyOnly) {
    assert.ok(existingAccounts.length, '本机尚无演示账号，不能只读复核')
    await verifyRecorded(tokens, existingDepartments, existingDoctors)
    return
  }
  if (!verifyOnly && !existingAccounts.length) console.log(`新建虚构演示账号共用密码（仅本次输出，不保存到文件）：${password}`)
  for (const spec of departments) {
    let department = existingDepartments.find(x => x.code === spec.code)
    if (!department) {
      assert.ok(!verifyOnly, `缺少科室 ${spec.code}`)
      department = await admin('POST', '/admin/departments', spec)
      await remember('departments', spec.code, department, true)
    }
    spec.id = department.id
  }
  for (const spec of doctors) {
    let user = users.find(x => x.username === spec.username)
    let doctor = user && existingDoctors.find(x => x.userId === user.id)
    if (!user) {
      assert.ok(!verifyOnly, `缺少医生 ${spec.username}`)
      doctor = await admin('POST', '/admin/doctors', { username: spec.username, password, departmentId: departments[spec.departmentIndex].id, name: spec.name, title: spec.title, specialty: spec.specialty })
      await remember('doctorUsers', spec.username, { id: doctor.userId, username: spec.username }, true)
      await remember('doctors', spec.username, doctor, true)
    }
    assert.ok(doctor, `缺少医生档案 ${spec.username}`)
    assert.equal(doctor.departmentId, departments[spec.departmentIndex].id)
    assert.equal(doctor.status, 'ENABLED')
    spec.id = doctor.id
    if (!tokens.has(spec.username)) tokens.set(spec.username, (await login(spec.username, password)).token)
  }
  const todayPM = chinaNow().slice(11, 19) < '13:25:00'
  const scheduleDates = todayPM ? [today, ...dates] : dates
  const existingSchedules = await list(`/admin/schedules?startDate=${today}&endDate=${dates.at(-1)}`, undefined, true)
  const schedules = []
  for (let index = 0; index < doctors.length; index++) {
    const doctor = doctors[index]
    for (const date of scheduleDates) {
      const slots = date === today ? ['PM'] : index % 2 === 0 ? ['AM', 'PM'] : [index % 4 === 1 ? 'AM' : 'PM']
      for (const slot of slots) {
        const key = `${doctor.username}:${date}:${slot}`
        let schedule = existingSchedules.find(x => x.doctorId === doctor.id && x.visitDate === date && x.timeSlot === slot)
        if (schedule) requireOwned('schedules', key, schedule)
        else {
          assert.ok(!verifyOnly, `缺少排班 ${key}`)
          schedule = await admin('POST', '/admin/schedules', { doctorId: doctor.id, visitDate: date, timeSlot: slot, totalCount: 12 + (index % 4) * 4, fee: [35, 20, 28, 20, 28, 35, 20, 28][index], status: 'OPEN' })
          await remember('schedules', key, schedule, true)
        }
        schedules.push(schedule)
      }
    }
  }
  console.log(`科室与医生准备完成，当前演示排班 ${schedules.length} 场。`)
  for (let index = 0; index < patients.length; index++) {
    const spec = patients[index]
    let user = users.find(x => x.username === spec.username)
    if (!user) {
      assert.ok(!verifyOnly, `缺少患者 ${spec.username}`)
      user = await api('POST', '/auth/register', { username: spec.username, password, displayName: spec.name })
      await remember('patientUsers', spec.username, user, true)
    }
    if (!tokens.has(spec.username)) tokens.set(spec.username, (await login(spec.username, password)).token)
    const token = tokens.get(spec.username)
    let profile = await api('GET', '/patient/profile', undefined, token)
    if (!profile.name && !verifyOnly) profile = await api('PUT', '/patient/profile', { name: spec.name, gender: spec.gender, age: spec.age, chronicStatus: index === 4 ? 'KNOWN' : 'NONE', chronicHistory: index === 4 ? '虚构演示：既往慢性关节不适，仅验证档案显示。' : '', allergyStatus: index === 2 ? 'KNOWN' : 'UNKNOWN', allergyHistory: index === 2 ? '虚构演示：某类食物过敏，仅验证档案显示。' : '' }, token)
    assert.equal(profile.name, spec.name)
    await remember('patients', spec.username, profile)
    const primaryDate = todayPM ? today : dates[0]
    const secondaryDate = todayPM ? dates[0] : dates[1]
    for (const [kind, date, cancel] of [['primary', primaryDate, false], ['secondary', secondaryDate, index < 4]]) {
      const doctor = doctors[spec.doctorIndex]
      const schedule = schedules.find(x => x.doctorId === doctor.id && x.visitDate === date && (date !== today || x.timeSlot === 'PM'))
      assert.ok(schedule)
      const key = `${spec.username}:${date}:${kind}`
      let registration = entities.registrations?.[key]
      if (!registration) {
        assert.ok(!verifyOnly, `缺少预约 ${key}`)
        registration = await api('POST', '/patient/registrations', { scheduleId: schedule.id, clientRequestId: `${batch}:${key}`, symptomDescription: `虚构演示：${spec.complaint}，持续约两天。仅用于系统操作展示，不对应真实患者。` }, token)
        // Persist intended cancellation so a partial run can resume safely.
        await remember('registrations', key, { ...registration, demoCancelPending: cancel }, true)
      }
      registration = await api('GET', `/patient/registrations/${registration.id}`, undefined, token)
      if (entities.registrations[key].demoCancelPending && !verifyOnly) {
        assert.ok(['WAITING', 'CANCELLED'].includes(registration.status), '预约已被人工处理，停止演示取消')
        registration = await api('POST', `/patient/registrations/${registration.id}/cancel`, { reason: '虚构演示：取消后释放号源，用于展示取消流程。' }, token)
        await remember('registrations', key, registration)
      }
      if (cancel) assert.equal(registration.status, 'CANCELLED')
    }
    const title = `演示：${spec.complaint}（人工摘要）`
    const sessions = await list('/patient/consultations', token)
    let session = sessions.find(x => x.title === title)
    if (!session) {
      assert.ok(!verifyOnly, `缺少演示会话 ${spec.username}`)
      session = await api('POST', '/patient/consultations', { title }, token)
      await remember('sessions', spec.username, session, true)
    } else requireOwned('sessions', spec.username, session)
    if (session.summaryVersion === 0 && !verifyOnly) {
      session = await api('PUT', `/patient/consultations/${session.id}/summary`, { expectedSummaryVersion: 0, confirm: true, summary: { chiefComplaint: `虚构演示：${spec.complaint}。此摘要由人工填写，未调用 AI，不对应真实患者。`, duration: '约两天（虚构）', severity: '轻度（虚构）', associatedSymptoms: [], pertinentNegatives: ['以下危险信号答案均为虚构演示选择'], riskAnswers: { chestPain: 'NO', severeBreathing: 'NO', unconscious: 'NO', majorBleeding: 'NO', paralysis: 'NO' } } }, token)
      await remember('sessions', spec.username, session)
    }
    assert.ok(session.summaryConfirmed)
  }
  if (todayPM) {
    for (let index = 0; index < 2; index++) {
      const patient = patients[index], doctor = doctors[patient.doctorIndex]
      const key = `${patient.username}:${today}:primary`
      const tracked = entities.registrations[key]
      const token = tokens.get(doctor.username)
      let registration = (await api('GET', `/doctor/registrations/${tracked.id}`, undefined, token)).registration
      if (registration.status === 'WAITING') {
        assert.ok(!verifyOnly, '演示病历尚未创建')
        registration = await api('POST', `/doctor/registrations/${tracked.id}/start`, {}, token)
        created.medicalRecords = (created.medicalRecords || 0) + 1
      }
      let record = await api('GET', `/doctor/registrations/${tracked.id}/medical-record`, undefined, token)
      if (record.status === 'DRAFT' && record.version === 0 && !verifyOnly) {
        record = await api('PUT', `/doctor/registrations/${tracked.id}/medical-record`, {
          version: record.version,
          chiefComplaint: `虚构演示：${patient.complaint}，约两天。`,
          presentIllness: '虚构演示：症状描述用于展示病历七段填写，没有真实就诊或采集过程。',
          pastHistory: '虚构演示：本人档案选择用于展示，未核实任何真实既往史。',
          physicalExam: '虚构演示：未实施真实体格检查，本字段仅展示文本保存。',
          auxiliaryExam: '虚构演示：未安排或实施检查，无真实检验结果。',
          preliminaryDiagnosis: '虚构演示：不作疾病诊断，本字段仅展示初步诊断栏位。',
          treatmentPlan: '虚构演示：未提供用药、检查或治疗建议，本字段仅展示病历提交流程。',
        }, token)
      }
      if (index === 0 && record.status === 'DRAFT') {
        assert.ok(!verifyOnly, '演示病历尚未提交')
        record = await api('POST', `/doctor/registrations/${tracked.id}/complete`, { version: record.version }, token)
      }
      assert.equal(record.status, index === 0 ? 'SUBMITTED' : 'DRAFT')
      if (index === 0) assert.equal((await api('GET', `/patient/registrations/${tracked.id}/medical-record`, undefined, tokens.get(patient.username))).id, record.id)
      await remember('medicalRecords', key, record)
      registration = (await api('GET', `/doctor/registrations/${tracked.id}`, undefined, token)).registration
      await remember('registrations', key, registration)
    }
  }
  // Real API checks: role ownership, stored data, cancellations and stock accounting.
  const allRegistrations = []
  for (const patient of patients) {
    const records = await list('/patient/registrations', tokens.get(patient.username))
    allRegistrations.push(...records)
    assert.ok(records.length >= 2)
    assert.ok(records.every(x => x.patientName === patient.name))
  }
  const refreshed = await list(`/admin/schedules?startDate=${today}&endDate=${dates.at(-1)}`, undefined, true)
  for (const schedule of schedules) {
    const current = refreshed.find(x => x.id === schedule.id)
    const ownRecords = await list(`/doctor/registrations?date=${schedule.visitDate}`, tokens.get(doctors.find(x => x.id === schedule.doctorId).username))
    const used = ownRecords.filter(x => x.scheduleId === current.id && x.status !== 'CANCELLED').length
    assert.equal(current.totalCount - current.remainingCount, used, `号源核对失败：${current.id}`)
  }
  const available = await list('/schedules', tokens.get(patients[0].username))
  assert.ok(schedules.every(x => available.some(a => a.id === x.id)), '患者端缺少演示场次')
  manifest.lastVerification = { at: new Date().toISOString(), date: today, dateRange: [scheduleDates[0], dates.at(-1)], counts: { departments: departments.length, doctors: doctors.length, patients: patients.length, schedules: schedules.length, registrations: allRegistrations.length, waiting: allRegistrations.filter(x => x.status === 'WAITING').length, inProgress: allRegistrations.filter(x => x.status === 'IN_PROGRESS').length, completed: allRegistrations.filter(x => x.status === 'COMPLETED').length, cancelled: allRegistrations.filter(x => x.status === 'CANCELLED').length, medicalRecords: Object.keys(entities.medicalRecords || {}).length, manualSummaries: patients.length }, created, checks: ['三角色真实接口', '患者归属', '预约取消', '号源库存', '患者可预约场次', '人工摘要确认', ...(todayPM ? ['当天病历草稿', '患者查阅已提交病历'] : [])] }
  await save()
  console.log(JSON.stringify(manifest.lastVerification, null, 2))
  console.log(`无凭据演示清单：${fileURLToPath(manifestUrl)}`)
}
main().catch(error => { console.error(error.message); process.exitCode = 1 })
