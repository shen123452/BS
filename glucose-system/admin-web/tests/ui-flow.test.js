import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import vm from 'node:vm'

function drawer(api) {
  const source = fs.readFileSync(new URL('../src/components/AlertHandleDrawer.vue', import.meta.url), 'utf8')
  const script = source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  const events = [], messages = []
  const context = vm.createContext({
    ref: value => ({ value }), watch() {}, defineProps: () => ({ modelValue:true, alert:{id:'9',version:'9007199254740993'} }),
    defineEmits: () => (...args) => events.push(args), alertsApi:api,
    ElMessage:{success: x=>messages.push(x)},ElMessageBox:{confirm:()=>Promise.reject('cancel')}
  })
  vm.runInContext(script+'\nglobalThis.view={submit,note,submitting,error};',context)
  return {...context.view,events,messages}
}

test('预警抽屉重复点击只提交一次，保留字符串版本与备注',async()=>{
  const calls=[];let finish
  const view=drawer({handle:(...args)=>{calls.push(args);return new Promise(r=>{finish=r})}})
  view.note.value='已核实，安排随访'
  const pending=view.submit();await view.submit()
  assert.equal(calls.length,1)
  assert.equal(calls[0][1].version,'9007199254740993')
  assert.equal(calls[0][1].handleNote,'已核实，安排随访')
  finish();await pending
  assert.equal(view.submitting.value,false)
  assert.ok(view.events.some(e=>e[0]==='handled'))
})

test('预警保存失败不关闭抽屉、不丢备注，重试成功才刷新',async()=>{
  let attempts=0
  const view=drawer({handle:async()=>{if(++attempts===1)throw new Error('网络不可用')}})
  view.note.value='保留的处理记录'
  await view.submit()
  assert.equal(view.note.value,'保留的处理记录')
  assert.equal(view.error.value,'网络不可用')
  assert.equal(view.events.length,0)
  await view.submit()
  assert.ok(view.events.some(e=>e[0]==='handled'))
})

test('AGP的迟到结果不会覆盖新患者，分布失败不清空基础分析',async()=>{
  const source=fs.readFileSync(new URL('../src/views/GlucoseView.vue',import.meta.url),'utf8')
  const script=source.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm,'')
  let resolveOld
  const context=vm.createContext({ref:v=>({value:v}),reactive:v=>v,onMounted(){},watch(){},completeDayRange:()=>[],glucoseApi:{agp:q=>q.patientId==='old'?new Promise(r=>resolveOld=r):Promise.resolve({patient:'new'})}})
  vm.runInContext(script+'\nglobalThis.view={loadAgp,agp,agpError,filters};',context)
  const v=context.view
  v.filters.summary={pointCount:12}
  const old=v.loadAgp({patientId:'old'});await v.loadAgp({patientId:'new'})
  resolveOld({patient:'old'});await old
  assert.equal(v.agp.value.patient,'new')
  context.glucoseApi.agp=async()=>{throw new Error('分布不可用')}
  await v.loadAgp({patientId:'new'})
  assert.equal(v.filters.summary.pointCount,12)
  assert.equal(v.agpError.value,'分布不可用')
})
