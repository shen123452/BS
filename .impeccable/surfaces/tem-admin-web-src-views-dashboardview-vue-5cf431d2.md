---
version: 1
slug: "tem-admin-web-src-views-dashboardview-vue-5cf431d2"
primary_target: "glucose-system/admin-web/src/views/DashboardView.vue"
related_targets:
  - "glucose-system/admin-web/src/views/GlucoseView.vue"
  - "glucose-system/admin-web/src/layouts/WorkbenchLayout.vue"
---

# Surface brief — 管理端 Web

Scope: 医生与管理员的桌面优先工作台及业务页，390px窄屏保留核心操作。Mode: Operate。用户已授权按新生成原型实现，本记录刷新旧方向，不再沿用顶部桌面菜单和四项概览。

Audience/job: 医生定位绑定患者、阅读来源与数据充分性、处理预警；管理员维护授权范围内的账号、绑定与知识资料。产品事实及权限见根PRODUCT.md。

## Direction contract

THESIS: 把已确认的蓝白原型落成可运行的临床工作台，保留真实接口、权限与指标语义。

OWN-WORLD: #2463EB主色、#F5F7FA画布、#172B4D主文字、#52637A辅助文字，白色面板、#DCE3EC细边框、12px面板圆角；中文无衬线、等宽数字、浅底文字状态。普通面板无阴影。完整复用令牌以根DESIGN.md与src/styles/tokens.css为准。

STORY: 进入工作台扫描三项概览，左侧查看待处理预警，右侧进入常用工作；打开预警抽屉确认风险等级、数据类型和处理状态后操作；分析页先选择患者、来源与日期，再读数据充分性、趋势及指标。

FIRST VIEWPORT: 224px左侧栏、64px顶部栏；工作区标题与三张概览（患者、记录、待处理预警），更新时间独立；下方左侧预警列表、右侧工作入口与说明。工作台左右比例2.2:1、间隔20px。1000px以下主区域单列、概览两列，620px以下概览单列。

FORM: 现有原型的直接实现。原始参考在docs/ui-prototypes/全套页面-2026-11-13/页面图片/，实现方向在docs/ui-implementation-2026-11-13/实现方向.md。不是新概念workshop。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.

## Implemented extensions

血糖分析页按独立工具栏、状态条、五列指标、趋势左/TIR与GMI右、AGP与每日统计下排、记录表底部排序。主排比例2.4:1，下排1.3:1；1100px以下两排单列、指标三列，767px以下指标两列。AGP和GMI按后端资格与数据来源条件显示，不能对SMBG推断全天占比。

全局1100px以下侧栏196px；767px以下顶部品牌加横向滚动菜单，内容可纵向滚动，表格保留横向滚动能力。窄屏AGP不能被固定宽度裁切。

## Evidence boundary

证据目录docs/ui-implementation-2026-11-13/；Web截图来自实际Vue应用和隔离合成API，不能称为真实后端联调。复核曾要求调整分析布局、抽屉状态/数据类型、链接与窄屏AGP；代码已修正，最终完成度以最新截图及复核结论为准，本brief不代替验收。
