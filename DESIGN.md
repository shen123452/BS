---
name: "血糖时序监测与智能问答系统"
description: "现代高端医疗 SaaS / 健康科技风工作台与移动健康管理端"
colors:
  ink: "#0F172A"
  ink-muted: "#475569"
  ink-faint: "#64748B"
  surface: "#FFFFFF"
  canvas: "#F8FAFC"
  border: "#E2E8F0"
  border-strong: "#CBD5E1"
  primary: "#2563EB"
  primary-strong: "#1D4ED8"
  accent: "#EF4444"
  info: "#0284C7"
  success: "#059669"
  warning: "#D97706"
  danger: "#DC2626"
  demo: "#7C3AED"
  surface-subtle: "#F1F5F9"
  danger-subtle: "#FEF2F2"
  warning-subtle: "#FFFBEB"
  success-subtle: "#ECFDF5"
  info-subtle: "#F0F9FF"
typography:
  headline:
    fontFamily: "Noto Sans SC, Microsoft YaHei, PingFang SC, Hiragino Sans GB, sans-serif"
    fontSize: "24px"
    fontWeight: 600
    lineHeight: "1.3"
  title:
    fontFamily: "Noto Sans SC, Microsoft YaHei, PingFang SC, Hiragino Sans GB, sans-serif"
    fontSize: "16px"
    fontWeight: 600
    lineHeight: "1.4"
  body:
    fontFamily: "Noto Sans SC, Microsoft YaHei, PingFang SC, Hiragino Sans GB, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: "1.57"
  label:
    fontFamily: "Noto Sans SC, Microsoft YaHei, PingFang SC, Hiragino Sans GB, sans-serif"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: "1.6"
  web-data:
    fontFamily: "Noto Sans SC, Microsoft YaHei, PingFang SC, Hiragino Sans GB, sans-serif"
    fontSize: "30px"
    fontWeight: 600
    lineHeight: "1.1"
  mini-body:
    fontFamily: "Noto Sans SC, PingFang SC, Microsoft YaHei, sans-serif"
    fontSize: "28rpx"
    lineHeight: "1.55"
  mini-title:
    fontSize: "40rpx"
    fontWeight: 600
    lineHeight: "1.3"
rounded:
  sm: "8px"
  md: "12px"
  lg: "16px"
  pill: "999px"
  mini-card: "24rpx"
  mini-button: "16rpx"
spacing:
  "1": "4px"
  "2": "8px"
  "3": "12px"
  "4": "16px"
  "5": "20px"
  "6": "24px"
  "8": "32px"
  mini-page: "24rpx"
  mini-card: "28rpx"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.sm}"
    padding: "0 14px"
  button-primary-hover:
    backgroundColor: "{colors.primary-strong}"
  panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "20px 22px"
  mini-card:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.mini-card}"
    padding: "28rpx"
  mini-button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.mini-button}"
    height: "88rpx"
---
# Design System: 血糖时序监测与智能问答系统

## Overview

**Creative North Star: "临床工作台与日常记录"**

临床管理工作台与患者日常记录共用蓝白视觉语言：浅灰画布、白色面板、清晰细边框、蓝色操作与克制的状态色。页面依靠真实内容、筛选和状态建立层级，产品事实与指标语义以 PRODUCT.md 为准。

本记录依据实际 Vue/CSS 与 WXML/WXSS 提取，刷新已获授权的新原型实现；来源见 `docs/ui-implementation-2026-11-13/实现方向.md`。Figma 原始参考在 `docs/ui-prototypes/全套页面-2026-11-13/页面图片/`，代码是本文件记录值的依据。

**Key Characteristics:**
- 蓝色主操作，白色内容层，浅灰画布。
- 桌面紧凑表格，患者端触控与读数优先。
- 来源、真实/演示和处理状态持续可辨。

## Colors

### Primary

清晰蓝用于主按钮、选中导航、链接与趋势；深蓝用于 Web 主按钮悬停和聚焦。浅蓝是选中和信息背景，不代替状态文案。

### Secondary

成功绿、关注棕、高风险红分别对应状态；信息蓝标记来源或说明，演示紫明确 DEMO。强调红是现有辅助令牌，不替代危险状态。状态浅底与语义文字成对使用。

### Neutral

深蓝灰为主文字，中蓝灰为说明，浅蓝灰为辅助文字；白色承载内容，浅灰承载页面。标准边框与加强边框分离容器和输入。小程序加强边框实际为 `#C7D0DB`，与 Web 的加强边框不同。

**The Explicit Status Rule.** 颜色辅助状态，文字明确来源、真实性和处理结果。

## Typography

Web 使用 frontmatter 的中文无衬线字体栈；Noto Sans SC 未安装时由平台字体回退，不假设字体已随包分发。小程序使用其独立字体栈。

Web 页面标题、分节标题、正文、辅助说明与概览数值分别对应 headline、title、body、label、web-data；表格正文为13px，表头与状态标签为12px。分析指标为28px/600/1.2，窄屏为22px。数据使用 tabular-nums。

小程序页面标题40rpx，卡片标题30rpx，正文28rpx；数值基础64rpx，首页最近值70rpx，分析读数60rpx。登录品牌名54rpx是该页面专用字号，不作为通用标题。现有辅助信息使用22/24rpx，部分标签、单位与问答说明低至20/21rpx，回答正文仍有27rpx；这些是现状差异，不能推导为正文小于28rpx的授权。

## Layout

Web：224px固定宽度的黏性左侧栏、64px黏性顶部栏；内容最大1600px，常规内边距30px 32px 44px。1100px以下侧栏196px、内容18px；767px以下转为顶部品牌与横向滚动菜单，顶部栏52px，内容16px 12px 32px，面板内边距14px。页面内部网格按内容收拢，不把桌面画布缩小塞进手机。

工作台与分析页的特定首屏和布局见 `.impeccable/surfaces/`。桌面工作台使用20px间隔，三列概览在1000px以下转两列、620px以下单列；分析在1100px以下重排，767px以下指标两列。

小程序：常规页面24rpx外边距，底部48rpx加安全区，卡片内边距28rpx、卡片间距20rpx。五个原生 tabBar 入口为首页、记录、分析、问答、我的。白色原生导航栏与白色底栏使用统一蓝色选中态。登录页使用独立白底、安全区与62rpx水平留白；问答页采用固定高度容器、内部滚动与底部输入区。

## Elevation & Depth

普通 Web 面板实际为无阴影、1px边框；小程序卡片无阴影，部分首页/个人中心卡片隐藏边框，靠白底与画布区分。不要把旧文档的“所有面板浅阴影”继续继承。Web 声明了低层与浮层阴影令牌，其值见 sidecar；令牌存在不表示普通面板启用。问答资料底片使用半透明遮罩和顶角建立浮层。

工作台加载骨架使用1.5s ease循环 shimmer；该组件自身含 reduced-motion 规则。未观察到全局统一150–200ms动效规范，不将旧的动效时长保留为系统承诺。

## Shapes

Web常规输入与按钮8px、面板与对话框12px；16px和pill是已有令牌，不强制应用到所有组件。状态标签单独使用4px小圆角与5px圆点。小程序卡片24rpx、主按钮16rpx、输入8rpx；分析指标卡20rpx、问答回答卡24rpx和引用摘录16rpx均为已实现变体。登录按钮28rpx圆角、104rpx高是专用变体。

## Components

### Buttons

Web Element Plus主按钮使用蓝底白字，最小高度34px，横向14px内边距，悬停/聚焦为深蓝；全局键盘可见焦点为2px蓝描边、偏移2px。小程序主按钮最小88rpx，白底描边次按钮84rpx；文本按钮和通用chip现有最小68rpx，不能一概声称所有控件都达到44 CSS px。录入页chip与新版问答输入/发送为88rpx。禁用态保留文字与现有透明度；登录禁用态使用专门浅蓝而非通用透明度。

### Inputs / Fields

Web输入最小36px，用1px内描边；悬停加强边框、聚焦蓝边框。小程序表单输入最小88rpx，18rpx 22rpx内边距；录入值用62rpx数字、独立白色表单组。错误邻近字段，成功状态保留数值、后续入口与提示。

### Chips

Web状态为文字、圆点和浅底边框；小程序为文字与浅底描边，筛选选中态用浅蓝底和蓝字。真实/演示标签与业务状态分开，不能由“已处理”推断风险等级。

### Cards / Containers

Web面板为1px标准边框、12px圆角、20px 22px内边距；概览卡24px内边距。小程序通用白卡按frontmatter；来源切换是在浅灰轨道中的白色选中段，首页不再使用旧蓝色双列读数头。旧读数头样式仍留在app.wxss，不是当前首页组成。

### Navigation

Web侧栏条目最小44px、8px圆角，选中浅蓝底、蓝字、600字重，悬停浅底；窄屏转横向滚动。品牌使用本轮血滴矢量标志。小程序tabBar在app.json声明，五对PNG来自统一图标源且内嵌来源信息；不要用字体字符替代其图标。登录同意状态默认为false，链接指向已有隐私与使用说明。

### Analysis and citations

分析保留独立来源/日期工具栏、数据充分性、指标、趋势、TIR/GMI及AGP的条件显示；图表本身不能补写后端未提供的统计。问答引用在独立底片中展示资料来源与原文摘录，回答状态和重试入口保留。

## Do's and Don'ts

### Do:
- **Do** 使用现有颜色令牌，状态同时给出文字与颜色。
- **Do** 保留 SMBG/CGM、真实/演示、未配置目标与数据不足的明确区别。
- **Do** 在表格和读数中使用等宽数字，保留单位、时间与来源。
- **Do** 为加载、空、失败和部分数据保留说明；错误靠近操作并保留输入。

### Don't:
- **Don't** 将本次 HTML 转译预览称为小程序原生验收。
- **Don't** 用颜色或正常色替代数据充分性和目标配置判断。
- **Don't** 把旧顶部桌面导航、旧蓝色或首页蓝色双读数头重新作为本轮主界面规范。

验证边界：`docs/ui-implementation-2026-11-13/` 的 Web 截图来自实际Vue应用加隔离合成API，只证明该响应下的显示，不证明真实后端联调。miniapp-*.png来自WXML/WXSS转HTML的布局预览；官方编译通过和行为测试不等于原生视觉通过。`界面复核.md` disposition为recapture，原生开发者工具/真机复核仍待补；本文件不替代最终复核结论。

未纳入规范或修复：小程序辅助字号与部分点击区域未达到PRODUCT的正文/触控目标，保留为待验收差异；旧readout-head和部分旧圆角样式作为残留代码说明，不提升为新界面规则。文档任务不修改源代码。
