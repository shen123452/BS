# Surface brief — 管理端 Web

Scope: 医生与管理员共用的桌面优先管理端，覆盖登录、工作台、患者管理、血糖与分析、预警中心、问答记录、知识库、医生账号；390px 窄屏仍可完成核心任务。Mode: Operate.

Audience/job: 医生在门诊间隙快速定位绑定患者和待处理事件；管理员维护账号、绑定与知识资料。信息密度、状态可扫描性和表格效率优先。

Chosen direction: 真实医院信息系统的管理工作台（category standard，按生产系统的方式直接做）。浅色背景、白色内容面板、医院蓝主操作、紧凑表格、标准表单；不采用概念化数字、装饰刻度、英文小标签或夸张排版。

## Direction contract

THESIS: 一套能直接用于日常工作的医院管理软件，而不是设计概念稿；页面靠表格、筛选器、状态标签和清晰的面板层级工作。

OWN-WORLD: 背景 #F3F5F7、白色面板、#DDE3E9 细边框、医院蓝 #0B5C8E；6px 圆角、浅阴影、系统中文 sans；状态用轻量色块和文字双重表达；表格密度优先。

STORY: 医生打开工作台，先看四项概览，再通过顶部菜单进入患者、血糖或预警；管理员在同一套导航里维护账号和知识库。

FIRST VIEWPORT: 顶部白色应用导航（品牌、七个中文菜单、当前角色与退出）；下方页面标题与一行说明；工作台首屏是四张紧凑概览卡（启用患者、近 7 日记录、待处理预警、数据更新时间），下方左侧为工作入口列表、右侧为数据说明面板。

FORM: 医院信息系统管理台 / 标准企业组件；seed key 不适用（用户否决前一概念方向后按生产系统直接实现）。

FINISH: unreviewed and undocumented is unfinished; this build ends with the finish review, the verdict, DESIGN.md, and every shipping raster carrying its provenance.
