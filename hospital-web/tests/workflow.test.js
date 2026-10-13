import test from 'node:test'
import assert from 'node:assert/strict'
import { safeReturnTo, recordPayload, recordFields, chinaDate } from '../src/lib/helpers.js'
import { makeAttempt, definiteFailure } from '../src/lib/bookingAttempt.js'
test('登录返回地址只允许当前角色的站内路径', () => {
  assert.equal(safeReturnTo('/patient/visits/123?from=home','PATIENT'),'/patient/visits/123?from=home')
  for (const path of ['https://example.com','//example.com','/admin/users','/patient/\\evil',null]) assert.equal(safeReturnTo(path,'PATIENT'),null)
})
test('预约确认冻结请求正文，重试不会重新生成请求号', () => {
  const attempt = makeAttempt({ id:'9007199254740993' }, '  头痛  ', '测试患者')
  const restored = JSON.parse(JSON.stringify(attempt))
  assert.equal(restored.payload.clientRequestId,attempt.payload.clientRequestId)
  assert.equal(restored.payload.scheduleId,'9007199254740993')
  assert.equal(restored.payload.symptomDescription,'头痛')
  assert.equal(restored.patientName,'测试患者')
})
test('超时、限流、服务错误必须保留预约请求，明确拒绝才清除', () => {
  for (const status of [undefined,408,429,500,502,503]) assert.equal(definiteFailure({ status }),false)
  for (const status of [400,401,403,404,409,422]) assert.equal(definiteFailure({ status }),true)
})
test('病历保存携带版本与七段正文，不提交就诊身份或状态字段', () => {
  const form = { version:3, status:'SUBMITTED', doctorId:'9', ...Object.fromEntries(recordFields.map(([k]) => [k,'实际正文'])) }
  const payload = recordPayload(form)
  assert.equal(payload.version,3); assert.equal(Object.keys(payload).length,8); assert.equal(payload.status,undefined); assert.equal(payload.doctorId,undefined)
})
test('七日日期按自然日递增，并返回可用于接口的绝对日期', () => {
  const today = chinaDate(); assert.match(today,/^\d{4}-\d{2}-\d{2}$/)
  assert.equal((Date.parse(chinaDate(6)) - Date.parse(today))/86400000,6)
})
test('导诊预约保留字符串结果ID，普通预约不附带结果', () => {
  assert.equal(makeAttempt({id:'10'},'咳嗽','虚构患者','9007199254740993').payload.triageResultId,'9007199254740993')
  assert.equal(makeAttempt({id:'10'},'咳嗽','虚构患者').payload.triageResultId,undefined)
})
