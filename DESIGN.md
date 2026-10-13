---
name: 医院智能导诊系统
description: 蓝白医疗管理界面，支持患者预约、医生接诊与管理员维护的连续门诊工作流。
colors:
  primary: "#1D4ED8"
  primary-soft: "#EFF6FF"
  background: "#F5F7FB"
  surface: "#FFFFFF"
  text: "#0F172A"
  muted: "#475569"
  border: "#D7DFEA"
  input-border: "#7C8DA5"
  success: "#15803D"
  warning: "#92400E"
  danger: "#B91C1C"
typography:
  display:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "42px"
    fontWeight: 650
    lineHeight: 1.45
  headline:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "26px"
    fontWeight: 650
    lineHeight: 1.35
  title:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "19px"
    fontWeight: 650
    lineHeight: 1.45
  body:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.65
  patient-body:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "16px"
    fontWeight: 400
    lineHeight: 1.65
  label:
    fontFamily: "-apple-system, BlinkMacSystemFont, Segoe UI, Microsoft YaHei, PingFang SC, sans-serif"
    fontSize: "14px"
    fontWeight: 500
    lineHeight: 1.65
rounded:
  control: "8px"
  card: "12px"
  badge: "6px"
spacing:
  xs: "4px"
  sm: "8px"
  md: "12px"
  lg: "16px"
  xl: "24px"
  xxl: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.control}"
    height: "44px"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.muted}"
    rounded: "{rounded.control}"
    height: "40px"
  panel:
    backgroundColor: "{colors.surface}"
    rounded: "{rounded.card}"
    padding: "24px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    rounded: "{rounded.control}"
    height: "44px"
  status-badge:
    rounded: "{rounded.badge}"
    padding: "3px 9px"

# Design System: 医院智能导诊系统

## Overview

**Creative North Star: "清晰的门诊工作台"**

这是一个以连续操作为中心的蓝白医疗管理系统。浅灰蓝页面底色、白色工作区和单一深蓝主色让预约、接诊、排班与病历内容保持清楚的层级；中文系统字体和 Element Plus 原生控件承担熟悉、可核对的工作感。登录、管理、医生和患者截图都使用同一套边界、按钮和状态表达。

桌面管理端采用固定侧栏和顶栏，患者端保持顶部导航并在 375px 手机宽度下换行。表单标签可见，错误保留恢复入口，状态同时使用文字和颜色。截图中的患者预约确认、档案保护、医生工作台真实摘要和病历身份条是实际交付行为，不是装饰性概念。

**Key Characteristics:**
- 蓝白、低装饰、内容优先的医疗工作区
- 真实接口状态、空状态与可恢复错误并列呈现
- 患者大字号与 44px 高频操作区，管理端紧凑表格密度
- Element Plus 控件与 SVG 图标保持一致

## Colors

主色只承担操作与当前导航，背景和表面用冷静的近白层次分开工作内容。

### Primary
- **医疗深蓝** (`#1D4ED8`): 主按钮、当前导航、品牌图标和焦点提示。
- **浅蓝选择面** (`#EFF6FF`): 当前侧栏/导航项与日期选择状态的低饱和背景。

### Neutral
- **冷灰页面底** (`#F5F7FB`): 管理与患者主区背景。
- **白色工作面** (`#FFFFFF`): 面板、顶栏、侧栏、输入与对话框表面。
- **墨色正文** (`#0F172A`): 标题、正文和重要身份信息。
- **石板次文** (`#475569`): 辅助说明、表头、未选导航。
- **分隔线** (`#D7DFEA`): 面板、表格行、顶栏和表单层次边界。
- **输入边界** (`#7C8DA5`): 必要输入控件的可见边界。

### Named Rules
**The One Action Blue Rule.** 深蓝用于主要下一步和当前上下文；次要动作留在白色描边层。

## Typography

**Display Font:** 系统中文字体栈（`-apple-system`, `BlinkMacSystemFont`, `Segoe UI`, `Microsoft YaHei`, `PingFang SC`, `sans-serif`）

**Body Font:** 同一系统字体栈，不加载在线字体。

**Character:** 清楚、克制、适合中文表单与长病历阅读。患者容器整体为 16px，管理与医生工作区为 14px。

### Hierarchy
- **Display** (650, 42px, 1.45): 登录页主张，桌面首屏建立产品语境；移动端收缩为 27px。
- **Headline** (650, 26px, 1.35): 页面标题；700px 以下为 24px。
- **Title** (650, 19px, 1.45): 面板标题、患者卡片和章节标题。
- **Body** (400, 14px, 1.65): 管理端正文、表格与说明。
- **Patient body** (400, 16px, 1.65): 患者端页面和手机输入正文。
- **Label** (500, 14px, 1.65): 可见表单标签与字段标题。

## Layout

管理与医生工作区使用 220px 固定侧栏、64px 顶栏，内容区从左侧偏移 220px；桌面主内容最大宽度 1600px、内边距 32px。患者端没有侧栏，主内容最大宽度 1250px，桌面内边距 36px 24px，顶部导航高度 64px。常用间距是 4/8/12/16/24/32px；网格、筛选和面板之间通常以 16px 或 24px 呼吸。

响应式在 1000px 收缩患者导航与预约筛选，在 700px 将管理侧栏改为横向可滚动导航、主区内边距改为 24px 16px、双栏表单变单栏。患者已验证 375px、768px、1440px；管理表格保持自身横向滚动，不强行压缩列。

## Elevation & Depth

系统使用平面白色表面加边界线表达深度，没有自定义阴影词汇。`#F5F7FB` 页面底、白色面板、顶栏/侧栏和对话框的分层足够区分工作区；当前导航通过浅蓝面和深蓝文字区分，模态层由 Element Plus 遮罩提供。

### Named Rules
**The Tonal Layer Rule.** 默认依靠背景、白色表面和 `#D7DFEA` 边界建立层级；不要为普通面板添加额外投影。

## Shapes

控件和导航项采用一致的温和 8px 圆角，面板与卡片采用 12px 圆角，状态徽章采用 6px。面板使用 1px 边界和 24px 内边距，移动端面板改为 20px 16px。日期按钮有 54px 最小高度；主按钮、患者入口、病历手机操作和记录章节入口达到 44px。

## Components

组件是低装饰、可扫描的工作控件；每个状态都保留文本标签。

### Buttons
- **Shape:** 8px 圆角；普通按钮最小高度 40px，主按钮 44px。
- **Primary:** `#1D4ED8` 背景、白字；用于登录、预约、查询排班、保存与提交。
- **Secondary / Ghost:** 白色表面与边界，次文色文字；文本退出按钮保留图标和可见标签。
- **Hover / Focus:** 当前导航与日期选择使用浅蓝背景；全局 `:focus-visible` 为 3px 深蓝轮廓、3px 偏移。

### Cards / Containers
- **Corner Style:** 12px 面板/预约卡片；8px 身份条、错误面板和筛选控件。
- **Background:** 白色表面置于 `#F5F7FB` 页面底。
- **Border:** 1px `#D7DFEA`，输入和日期按钮使用更深的 `#7C8DA5` 边界。
- **Internal Padding:** 桌面 24px；移动端普通面板 20px 16px。

### Inputs / Fields
- **Style:** Element Plus 输入、选择、日期与文本域；白底、8px 圆角、44px 控件高度，文本域内边距 12px、行高 1.8。
- **Focus:** 深蓝可见焦点轮廓；输入插入光标使用主色。
- **Error / Disabled:** 错误面板使用浅红底和深红文字；保存/提交期间字段禁用并保留正文。

### Navigation
- **Management:** 220px 侧栏、SVG 图标、13px 纵向间距；当前项为 `#EFF6FF` 背景、深蓝文字和半粗字重。
- **Patient:** 顶部导航，当前项深蓝字并用底部 2px 线标记；移动端导航换行并保持可点击高度。
- **Accessibility:** 路由导航写入 `aria-current="page"`，日期选择写入 `aria-pressed`。

### Status Badge

`StatusBadge.vue` 将 WAITING、IN_PROGRESS、COMPLETED、CANCELLED、DRAFT、SUBMITTED 等状态以文字加浅色背景呈现：等待/草稿为琥珀，接诊中为浅蓝，完成/开放为浅绿，停用为浅红。

### Medical Record Workspace

`hospital-web/src/pages/RecordPage.vue` 的病历编辑器使用章节锚点、持续患者/挂号身份条和白色内容面板；桌面左侧章节导航，移动端改为可换行入口。底部保存草稿与预览提交操作在桌面吸附，手机恢复自然流布局。

## Do's and Don'ts

### Do:
- **Do** 使用 `#1D4ED8` 作为主要下一步、当前导航和品牌识别。
- **Do** 保留清楚的中文标签、真实空状态、失败重试和输入保护。
- **Do** 在患者端保持 16px 正文与 44px 高频操作区，在管理端保持 14px 表格密度。
- **Do** 使用 Element Plus 组件和仓库已有 SVG 图标，状态同时显示文字。
- **Do** 让长表格在容器内滚动，让病历身份在长页面可见。

### Don't:
- **Don't** 引入未经实现证据支持的渐变、插画、在线字体或大动效。
- **Don't** 用颜色单独表达预约、接诊或病历状态。
- **Don't** 为普通面板增加阴影或超过 12px 的圆角。
- **Don't** 把患者 375px 手机布局压缩成桌面表格，或移除输入失败后的恢复入口。
