// Read-only check against the running local backend; does not log in or write records.
import assert from 'node:assert/strict'
const base = process.env.RUNTIME_BASE || 'http://127.0.0.1:8083'
async function check() {
const response = await fetch(new URL('/v3/api-docs', base), { signal: AbortSignal.timeout(10000) })
assert.equal(response.status, 200, '无法读取运行中后端的接口定义，请检查后端地址及服务状态')
const schema = await response.json()
const add = schema.components?.schemas?.AddReq
const update = schema.components?.schemas?.UpdateReq
assert.ok(add?.properties?.measuredAt, '运行服务缺少新增血糖接口定义')
assert.ok(!add.required?.includes('measuredAt'), '运行中的后端仍强制填写 measuredAt，请重启本项目后端以加载最新代码')
assert.ok(update?.required?.includes('measuredAt'), '编辑血糖记录仍须明确测量时间')
console.log('PASS 运行后端已支持当前时间录入，编辑记录仍要求测量时间（只读检查）')
}
check().catch(error => { console.error('FAIL ' + error.message); process.exitCode = 1 })
