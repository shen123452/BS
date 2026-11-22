"""Local, isolated UI review. API responses are synthetic; no business data is written."""
from pathlib import Path
import json
import re
import shutil
from urllib.parse import urlparse
from io import BytesIO
from playwright.sync_api import sync_playwright
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[2]
MINI=ROOT/'glucose-system/patient-miniapp'
OUT=Path(__file__).resolve().parent/'.artifacts/ui-implementation-2026-11-13'
OUT.mkdir(parents=True,exist_ok=True)
BASE='http://127.0.0.1:5176'
patients=[{'userId':201,'username':'demo_201','realName':'演示患者01','dataMode':'DEMO','status':1}]
alerts=[{'id':str(i),'version':'1','patientId':201,'alertType':'LOW' if i%2 else 'HIGH','level':3 if i<3 else 2,'dataSource':'CGM','startAt':'2026-11-12 08:00:00','endAt':'2026-11-12 08:25:00','minGlucose':3.2,'maxGlucose':12.6,'sampleCount':6,'handleStatus':'PENDING','message':'请核对异常读数与近期情况。'} for i in range(1,6)]
summary={'metricStatus':'QUALIFIED','reasonCodes':[],'pointCount':4002,'avgGlucose':7.1,'minGlucose':3.2,'maxGlucose':12.6,'sd':1.7,'cv':23.94,'dataAdequacy':.993,'tir':90,'tar':8,'tbr':2,'gmi':6.37,'targetLow':3.9,'targetHigh':10,'measurementInRangeRate':None,'startDate':'2026-10-30','validMonitoringMinutes':20010,'maxAt':'2026-11-09 13:20','minAt':'2026-11-06 08:10'}
trend={'dataSource':'CGM','totalPoints':4002,'displayPoints':120,'gaps':[],'points':[{'time':f'2026-11-12 {i//12:02d}:{(i%12)*5:02d}:00','value':round(7+__import__('math').sin(i/5)*1.8,1)} for i in range(120)]}
agp={'qualifiedSlotCount':47,'targetLow':3.9,'targetHigh':10,'slots':[{'time':f'{i//2:02d}:{i%2*30:02d}','p5':4.2 if i!=20 else None,'p25':5.6 if i!=20 else None,'p50':7+__import__('math').sin(i/6) if i!=20 else None,'p75':8.2 if i!=20 else None,'p95':10 if i!=20 else None,'coveredDays':14,'sampleCount':80} for i in range(48)]}
records=[{'id':str(i),'glucoseMmol':v,'measuredAt':f'2026-11-13 {h}','dataSource':'SMBG','ingestMethod':'MANUAL','isSmbg':True,'measureType':1,'measureTypeLabel':'空腹','riskFlag':'NONE','note':''} for i,v,h in [(1,7.1,'09:35:00'),(2,7.4,'08:15:00'),(3,6.8,'07:42:00'),(4,6.2,'06:50:00')]]
scenario='filled'

def api(route):
    if not urlparse(route.request.url).path.startswith('/api/'):
        route.continue_(); return
    url=route.request.url.split('?')[0]
    if scenario=='error' and (url.endswith('/dashboard/overview') or url.endswith('/alerts')):
        route.fulfill(status=503,content_type='application/json',body=json.dumps({'code':50300,'message':'服务暂时不可用，请稍后重试'})); return
    data={'records':[],'total':0}
    if url.endswith('/dashboard/overview'):data={'activePatients':128,'recordsLast7Days':3842,'pendingAlerts':7,'updatedAt':'2026-11-13 10:30:00'}
    elif url.endswith('/patients'):data={'records':patients,'total':1}
    elif url.endswith('/alerts'):data={'records':alerts,'total':len(alerts)}
    elif '/handle' in url:data={}
    elif url.endswith('/agp'):data=agp
    elif url.endswith('/trend'):data=trend
    elif url.endswith('/daily'):data=[{'date':'2026-11-12','count':286,'avg':7.1,'min':4.2,'max':10.1,'coverage':99.3}]
    elif '/analysis/' in url:data=summary
    elif url.endswith('/glucose'):data={'records':records,'total':4}
    elif url.endswith('/captcha'):data={'captchaId':'visual','image':'data:image/svg+xml,<svg xmlns="http://www.w3.org/2000/svg" width="104" height="36"><text y="26" x="10" font-size="22">DEMO</text></svg>'}
    if scenario=='empty' and url.endswith('/alerts'):data={'records':[],'total':0}
    if url.endswith('/alerts') and scenario!='empty':data={'records':[{**item,'dataKind':'DEMO','eventStatus':'ENDED'} for item in alerts],'total':len(alerts)}
    if url.endswith('/glucose'):
        data={'records':[{**item,'measuredAt':item['measuredAt'].replace('2026-11-13','2026-11-12'),'dataSource':'CGM','measureTypeLabel':None} for item in records],'total':4}
    route.fulfill(status=200,content_type='application/json',body=json.dumps({'code':0,'data':data}))

def wxml_to_vue(text):
    text=text.replace('<block','<template').replace('</block>','</template>')
    text=re.sub(r'<(input|image|checkbox|switch|canvas)\b([^>]*?)/>',lambda m:f'<{m[1]}{m[2]}></{m[1]}>',text)
    text=re.sub(r'wx:for="{{(.*?)}}"',r'v-for="(item,index) in \1"',text)
    text=re.sub(r'wx:key="[^"]*"','',text)
    text=re.sub(r'wx:if="{{(.*?)}}"',r'v-if="\1"',text)
    text=re.sub(r'wx:elif="{{(.*?)}}"',r'v-else-if="\1"',text)
    text=text.replace('wx:else','v-else')
    text=re.sub(r'([\w-]+)="([^"]*{{.*?}}[^"]*)"',lambda m:':'+m[1]+'="'+ ('(' + m[2][2:-2] + ')' if m[2].startswith('{{') and m[2].endswith('}}') and m[2].count('{{')==1 else "`"+m[2].replace('{{','${').replace('}}','}')+"`") +'"',text)
    text=re.sub(r'\b(?:bind|catch)[\w:-]+="[^"]*"','',text)
    text=text.replace('<image','<img').replace('</image>','')
    text=text.replace('src="/assets/','src="assets/')
    text=text.replace('<scroll-view','<section').replace('</scroll-view>','</section>')
    text=text.replace('<checkbox-group','<section').replace('</checkbox-group>','</section>')
    text=text.replace('<checkbox','<input type="checkbox"').replace('</checkbox>','')
    return text

def mini_preview(name,wxml,css,data,with_tab=True):
    app_css=(MINI/'app.wxss').read_text(encoding='utf-8-sig')
    style=re.sub(r'([\d.]+)rpx',lambda m:str(float(m[1])*390/750)+'px',app_css+'\n'+css)
    style=re.sub(r'(?m)^page(?=\s*\{)','body',style)
    system='<div class="system"><strong>9:41</strong><span>5G　100%</span></div><div class="native-nav"><b>'+name+'</b><span>•••　◉</span></div>'
    tabs='<div class="native-tabs">首页　　记录　　分析　　问答　　我的</div>' if with_tab else ''
    return '''<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><style>'''+style+'''
html,body{margin:0;padding:0;width:390px;min-height:844px}view{display:block}text{display:inline}button{font-family:inherit;border:0;line-height:1.4}input{font-family:inherit}img{display:block}button{cursor:pointer}input[type=checkbox]{accent-color:#2463EB;width:20px;height:20px}picker{display:block}canvas{background:#f5f7fa}.system{height:44px;background:white;padding:12px 24px;display:flex;justify-content:space-between;box-sizing:border-box;font-size:14px}.native-nav{height:44px;background:white;display:flex;justify-content:center;align-items:center;position:relative;font-size:15px}.native-nav span{position:absolute;right:12px;border:1px solid #e4e9f0;padding:3px 10px;border-radius:20px;font-size:13px}.native-tabs{position:fixed;bottom:0;left:0;right:0;background:white;border-top:1px solid #e4e9f0;height:64px;text-align:center;padding-top:16px;font-size:12px;color:#52637a;z-index:10}body:has(.qa-page){height:844px}.qa-page{height:692px!important}.login-page{min-height:756px!important}.login-hero{min-height:362px!important}.login-agreements{margin-top:62px!important}.page{padding-bottom:90px}
</style><script src="vue.global.prod.js"></script></head><body>'''+system+'<div id="app">'+wxml_to_vue(wxml)+'</div>'+tabs+'<script>Vue.createApp({data(){return '+json.dumps(data,ensure_ascii=False)+'}}).mount("#app")</script></body></html>'

fixtures={
'login':('pages/login/index',{'checking':False,'errorMsg':'','loading':False,'consent':False,'hasSession':False},False),
'home':('pages/home/index',{'dataMode':'DEMO','todayLabel':'11月13日 · 周五','overview':{'unreadAlerts':2},'sources':[{'key':'SMBG','label':'指尖血糖','latest':{'glucoseMmol':6.8,'measuredAt':'今天 07:42','measureTypeLabel':'空腹'},'todayCount':3},{'key':'CGM','label':'连续监测','latest':{'glucoseMmol':7.1,'measuredAt':'今天 09:35'},'todayCount':116}],'selectedSource':'SMBG','todayCount':119,'loading':False,'errorMsg':'','medicationLoading':False,'medicationError':'','medication':{'records':[{}],'taken':1,'pending':1,'skipped':0}},True),
'record':('pages/glucose/record',{'types':[{'value':i+1,'label':t} for i,t in enumerate(['空腹','餐前','餐后2小时','睡前','随机','凌晨'])],'measureType':1,'glucose':'6.8','note':'晨起测量','successInfo':None,'fieldError':'','submitting':False,'useCustomTime':False},False),
'records':('pages/glucose/list',{'loading':False,'errorMsg':'','records':records,'total':4,'dataSource':'','date':'','hasMore':False,'loadingMore':False},True),
'analysis':('pages/analysis/index',{'dataSource':'CGM','days':14,'loading':False,'errorMsg':'','summary':summary,'statusTag':['tag-success','达到展示门槛'],'reasons':[],'trend':trend,'daily':[],'adequacyPct':99.3,'validHours':333.5,'showAgpDetails':False,'agp':agp},True),
'qa':('pages/qa/index',{'isTab':True,'sessionId':'1','generating':False,'pendingRequest':False,'loading':False,'messages':[{'messageId':'1','isUser':True,'content':'血糖和葡萄糖有什么关系？'},{'messageId':'2','isUser':False,'content':'血糖通常指血液中的葡萄糖。\n\n葡萄糖是身体能量来源之一。记录中的血糖值，表示测量时血液中葡萄糖的浓度。','modeText':'有据回答','hasSources':True}],'visibleSources':None,'input':'','knowledgeHint':'','errorMsg':''},True),
'my':('pages/my/index',{'profile':{'realName':'演示患者01','diabetesType':'健康记录管理','privacyAcceptedAt':'2026-11-13'},'dataMode':'DEMO','targets':{'cgmTargetLow':3.9,'cgmTargetHigh':10},'ruleTexts':['1','2','3'],'targetDetails':False,'editingProfile':False,'editingTargets':False,'saving':False,'loading':False,'errorMsg':''},True)
}

with sync_playwright() as p:
    browser=p.chromium.launch(executable_path=r'C:\Program Files\Google\Chrome\Application\chrome.exe',headless=True)
    page=browser.new_page(viewport={'width':1440,'height':1000})
    errors=[]
    page.on('pageerror',lambda e:errors.append(str(e)))
    page.route('**/api/**',api)
    page.add_init_script("if(!location.pathname.startsWith('/login')){localStorage.setItem('accessToken','visual-only');localStorage.setItem('username','演示管理员');localStorage.setItem('role','ADMIN')}")
    layouts={}
    for route,name in [('/dashboard','desktop-dashboard'),('/glucose','desktop-analysis'),('/patients','desktop-patients'),('/alerts','desktop-alerts')]:
        page.goto(BASE+route);page.locator('.layout-content').wait_for();page.wait_for_timeout(350)
        page.locator('.el-loading-mask').wait_for(state='hidden')
        page.screenshot(path=str(OUT/(name+'.png')),full_page=True)
        layouts[name]=page.evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})')
    page.goto(BASE+'/dashboard');page.locator('.pending-panel .el-table__row').first.wait_for()
    page.get_by_role('button',name='处理',exact=True).first.click()
    page.get_by_label('处理备注').fill('已核实，记录随访安排。')
    page.wait_for_timeout(400)
    page.screenshot(path=str(OUT/'desktop-handle-drawer.png'))
    page.get_by_role('button',name='确认处理',exact=True).click()
    page.locator('.el-drawer').wait_for(state='hidden')
    for case in ['empty','error']:
        scenario=case
        page.goto(BASE+'/dashboard');page.locator('.layout-content').wait_for()
        page.locator('.el-table__empty-block' if case=='empty' else '.dashboard-error').wait_for()
        page.screenshot(path=str(OUT/('desktop-dashboard-'+case+'.png')),full_page=True)
    page.get_by_role('button',name='重新加载',exact=True).wait_for()
    scenario='filled'
    page.get_by_role('button',name='重新加载',exact=True).click()
    page.locator('.kpi-value').first.wait_for()
    page.set_viewport_size({'width':390,'height':844})
    page.goto(BASE+'/dashboard');page.locator('.kpi-value').first.wait_for();page.wait_for_timeout(200);page.screenshot(path=str(OUT/'mobile-dashboard.png'),full_page=True)
    layouts['mobile-dashboard']=page.evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})')
    assert layouts['mobile-dashboard']['scrollWidth']<=390
    for route,name in [('/glucose','mobile-analysis'),('/patients','mobile-patients'),('/alerts','mobile-alerts')]:
        page.goto(BASE+route);page.locator('.layout-content').wait_for();page.locator('.el-loading-mask').wait_for(state='hidden');page.wait_for_timeout(250)
        page.screenshot(path=str(OUT/(name+'.png')),full_page=True)
        layouts[name]=page.evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth})')
        assert layouts[name]['scrollWidth']<=390, name
    page.get_by_role('button',name='处理',exact=True).first.click()
    page.locator('.el-drawer').wait_for()
    page.get_by_label('处理备注').fill('已核实，保留随访记录。')
    page.wait_for_timeout(400)
    page.screenshot(path=str(OUT/'mobile-handle-drawer.png'))
    page.get_by_role('button',name='取消',exact=True).click()
    page.goto(BASE+'/dashboard');page.locator('.pending-panel .el-table__row').first.wait_for()
    page.locator('.pending-panel .el-scrollbar__wrap').evaluate('(element)=>element.scrollLeft=element.scrollWidth')
    page.get_by_role('button',name='处理',exact=True).first.click()
    page.locator('.el-drawer').wait_for();page.get_by_role('button',name='取消',exact=True).click()
    page.evaluate('localStorage.removeItem("accessToken")');page.goto(BASE+'/login');page.evaluate('localStorage.removeItem("accessToken")');page.goto(BASE+'/login');page.wait_for_timeout(1500);page.screenshot(path=str(OUT/'mobile-admin-login.png'),full_page=True)
    preview=OUT/'miniapp-preview';preview.mkdir(exist_ok=True)
    shutil.copy2(ROOT/'glucose-system/admin-web/node_modules/vue/dist/vue.global.prod.js',preview/'vue.global.prod.js')
    shutil.copytree(MINI/'assets',preview/'assets',dirs_exist_ok=True)
    for name,(file,data,tab) in fixtures.items():
        artifact=preview/(name+'.html')
        artifact.write_text(mini_preview('' if name=='login' else {'home':'首页','record':'记录血糖','records':'血糖记录','analysis':'血糖分析','qa':'智能问答','my':'我的'}[name],(MINI/(file+'.wxml')).read_text(encoding='utf-8-sig'),(MINI/(file+'.wxss')).read_text(encoding='utf-8-sig'),data,tab),encoding='utf-8')
        page.goto(artifact.as_uri());page.wait_for_timeout(180)
        if name=='analysis':
            chart_source=(MINI/'utils/trendCanvas.js').read_text(encoding='utf-8')
            page.evaluate('(trend)=>{window.__miniTrend=trend}',trend)
            page.evaluate('(source)=>{const module={exports:{}};new Function("module",source)(module);const canvas=document.querySelector("#glucose-trend");canvas.width=canvas.clientWidth;canvas.height=canvas.clientHeight;module.exports.drawTrend(canvas.getContext("2d"),canvas.width,canvas.height,window.__miniTrend)}',chart_source)
        page.screenshot(path=str(OUT/('miniapp-'+name+'.png')),full_page=True)
        layouts['miniapp-'+name]=page.evaluate('({width:innerWidth,scrollWidth:document.documentElement.scrollWidth,height:document.documentElement.scrollHeight})')
        assert layouts['miniapp-'+name]['scrollWidth']<=390, name
    browser.close()
    (OUT/'review-results.json').write_text(json.dumps({'layouts':layouts,'page_errors':errors,'method':'Web admin: actual Vue application with isolated API fixtures. Miniapp: WXML/WXSS translated to HTML for layout review only, not native simulator verification.'},ensure_ascii=False,indent=2),encoding='utf-8')
    print(json.dumps({'screenshots':len(list(OUT.glob('*.png'))),'errors':errors,'layouts':layouts},ensure_ascii=False))
