---
version: 1
slug: "tem-patient-miniapp-pages-home-index-wxml-1dd5904c"
primary_target: "glucose-system/patient-miniapp/pages/home/index.wxml"
related_targets:
  - "glucose-system/patient-miniapp/pages/login/index.wxml"
  - "glucose-system/patient-miniapp/pages/glucose/record.wxml"
  - "glucose-system/patient-miniapp/pages/analysis/index.wxml"
  - "glucose-system/patient-miniapp/pages/qa/index.wxml"
  - "glucose-system/patient-miniapp/pages/my/index.wxml"
---

# Surface brief — 患者微信小程序

Scope: 微信原生患者端，覆盖登录、首页、SMBG录入/列表、CGM导入、分析、问答、个人中心及已有子包。Mode: Operate。用户已授权按新原型实现，本记录刷新旧蓝色双列读数首页方向。

Audience/job: 患者快速完成一次记录，区分来源与数据状态，查看趋势、用药及有引用的问答；产品事实与指标口径见根PRODUCT.md。

## Direction contract

THESIS: 实现已确认的蓝白患者端原型，保留记录、数据充分性、来源、同意和引用的真实行为。

OWN-WORLD: #2463EB主色、#F5F7FA画布、#172B4D主文字、#52637A辅助文字；白色24rpx卡片、28rpx内边距、24rpx页面留白，正文基础28rpx/1.55；中文无衬线，数值显著放大且使用等宽数字。小程序加强边框#C7D0DB，与Web存在平台差异。

STORY: 登录页阅读协议并主动勾选，微信登录后选择SMBG/CGM查看最近值；记录一次血糖后查看成功状态；从首页用药/汇总或五个原生tab进入后续任务。

FIRST VIEWPORT: 登录为纯白底血滴标志、产品名、登录按钮、默认未勾选同意控件、协议入口与健康参考说明；首页为来源切换、白卡最近测量、蓝色记录按钮、今日用药与记录汇总。旧蓝色双列readout-head样式虽留在公共WXSS，当前首页不使用。

FORM: 现有原型的直接实现；原始参考在docs/ui-prototypes/全套页面-2026-11-13/页面图片/，实际样式以app.wxss及各页WXSS为准；不把rpx预览换算值写成原生token。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Implemented extensions

五个原生tab为首页、记录、分析、问答、我的，由app.json声明；五对PNG使用统一图标源，嵌入来源记录。登录与品牌使用本轮血滴SVG。录入以独立白色表单组呈现数值、测量类型、时间、备注；成功态保留记录值与后续入口。分析分开来源/日期、资格状态、趋势、指标与AGP；问答有白色回答卡、引用资料底片、引用原文与底部输入；个人中心保留资料、目标、用药、隐私及账号操作。

正文基础28rpx，辅助22/24rpx及部分20/21rpx标签仍存在，部分问答正文27rpx；通用chip/文本操作68rpx，主按钮88rpx，登录按钮104rpx，不能宣称所有区域已满足PRODUCT的44px等效触控目标。记录页chip和新版问答输入/发送为88rpx。保留差异等待原生验收，不把偏差扩散为通用规范。

## Evidence boundary

证据docs/ui-implementation-2026-11-13/miniapp-*.png为WXML/WXSS转HTML/Vue布局预览，非微信开发者工具或真机。官方编译通过与行为测试不替代原生画面验收。界面复核.md当前disposition为recapture：需原生七页画面，特别确认分析滚动、问答底部说明、键盘、原生底栏、安全区和点击区域；不得写为原生通过。
